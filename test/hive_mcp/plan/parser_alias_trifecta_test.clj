(ns hive-mcp.plan.parser-alias-trifecta-test
  "Trifecta tests for plan parser alias normalization + unknown-key warnings.
   Pins the regression where :blockedBy was silently dropped, plus the
   warn-on-unknown-keys safety net for footgun keys like :wave."
  (:require [clojure.test.check.generators :as gen]
            [clojure.tools.logging :as ctl]
            [hive-mcp.plan.parser.edn :as edn-parser]
            [hive-test.trifecta :refer [deftrifecta]]))

;; -----------------------------------------------------------------------------
;; alias-dependencies — :dependencies / :blockedBy → :depends-on
;; -----------------------------------------------------------------------------

(defn run-alias-dependencies
  "Public wrapper around the private fn for trifecta var-sym binding."
  [step]
  (#'edn-parser/alias-dependencies step))

(defn alias-result-shape?
  "Total-function predicate: result is always a map, never carries
   :blockedBy, and gains :depends-on iff input had any dep-shaped key."
  [step]
  (let [result (run-alias-dependencies step)
        had-dep-key? (some #(contains? step %)
                           [:depends-on :dependencies :blockedBy])]
    (and (map? result)
         (not (contains? result :blockedBy))
         (= (boolean had-dep-key?)
            (contains? result :depends-on)))))

(def ^:private gen-step-with-deps
  (gen/let [id      gen/string-alphanumeric
            title   gen/string-alphanumeric
            deps    (gen/one-of [(gen/return ::absent)
                                 (gen/vector gen/string-alphanumeric 0 3)])
            depson  (gen/one-of [(gen/return ::absent)
                                 (gen/vector gen/string-alphanumeric 0 3)])
            blocked (gen/one-of [(gen/return ::absent)
                                 (gen/vector gen/string-alphanumeric 0 3)])]
    (cond-> {:id id :title title}
      (not= deps    ::absent) (assoc :dependencies deps)
      (not= depson  ::absent) (assoc :depends-on depson)
      (not= blocked ::absent) (assoc :blockedBy blocked))))

(deftrifecta alias-dependencies-contract
  hive-mcp.plan.parser-alias-trifecta-test/run-alias-dependencies
  {:golden-path "test/golden/hive-mcp/plan/alias-dependencies.edn"
   :cases {:bare              {:id "s1" :title "x"}
           :dependencies-only {:id "s1" :title "x" :dependencies ["a"]}
           :blockedBy-only    {:id "s1" :title "x" :blockedBy ["b"]}
           :depends-on-wins   {:id "s1" :title "x"
                               :depends-on ["c"] :blockedBy ["dropped"]}
           :deps-over-blocked {:id "s1" :title "x"
                               :dependencies ["d"] :blockedBy ["e"]}}
   :gen gen-step-with-deps
   :pred (fn [_] true)
   :num-tests 200
   :mutations [["never-aliases"   (fn [step] step)]
               ["drops-everything" (fn [step]
                                     (dissoc step :dependencies
                                                  :blockedBy
                                                  :depends-on))]
               ["aliases-blockedBy-wrong"
                (fn [step]
                  (-> step
                      (assoc :depends-on (or (:blockedBy step)
                                             (:dependencies step)
                                             (:depends-on step)))
                      (dissoc :dependencies :blockedBy)))]]})

;; -----------------------------------------------------------------------------
;; warn-unknown-keys — surfaces silently-dropped keys (e.g. :wave)
;; -----------------------------------------------------------------------------

(defn run-warn-capture
  "Apply private warn-unknown-keys, returning {:result step :warned? bool
   :unknown <vec>} so trifecta can pin the warn behavior as data."
  [step]
  (let [warned (atom nil)]
    (with-redefs [ctl/log* (fn [_logger _level _t msg]
                             (reset! warned msg))]
      (let [result (#'edn-parser/warn-unknown-keys step)]
        {:result   result
         :warned?  (some? @warned)
         :message  @warned}))))

(defn warn-shape?
  "Predicate: pass-through (result identical to input), warn iff unknown
   keys present (set-difference of step keys against known whitelist)."
  [step]
  (let [{:keys [result warned?]} (run-warn-capture step)
        known #{:id :title :description :depends-on :priority :files :estimate
                :tags :dependencies :blockedBy :file
                :why :validation :details :deliverable :est-tokens
                :files-read :files-write}
        has-unknown? (boolean (some (complement known) (keys step)))]
    (and (= result step)
         (= warned? has-unknown?))))

(def ^:private gen-step-mixed-keys
  (gen/let [id        gen/string-alphanumeric
            extra-key (gen/one-of [(gen/return ::absent)
                                   (gen/return :wave)
                                   (gen/return :why)
                                   (gen/return :rogue-key)
                                   (gen/return :validation)])
            extra-val gen/string-alphanumeric]
    (cond-> {:id id :title "t"}
      (not= extra-key ::absent) (assoc extra-key extra-val))))

(deftrifecta warn-unknown-keys-contract
  hive-mcp.plan.parser-alias-trifecta-test/run-warn-capture
  {:golden-path "test/golden/hive-mcp/plan/warn-unknown-keys.edn"
   :cases {:no-extras       {:id "s1" :title "x"}
           :wave-warns      {:id "s1" :title "x" :wave :A}
           :why-tolerated   {:id "s1" :title "x" :why "reason"}
           :rogue-warns     {:id "s1" :title "x" :rogue 42}
           :many-unknown    {:id "s1" :title "x" :wave :A :rogue 1 :rogue2 2}}
   :xf (fn [{:keys [warned? message] :as out}]
         ;; Drop volatile fields from snapshot — keep stable signal only
         (cond-> (select-keys out [:result :warned?])
           message (assoc :warns? true)))
   :gen gen-step-mixed-keys
   :pred (fn [out] (and (map? out) (contains? out :result)))
   :num-tests 100
   :mutations [["never-warns"
                (fn [step] {:result step :warned? false :message nil})]
               ["always-warns"
                (fn [step] {:result step :warned? true
                            :message "noise"})]]})
