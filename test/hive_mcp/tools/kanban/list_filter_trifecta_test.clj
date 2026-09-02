(ns hive-mcp.tools.kanban.list-filter-trifecta-test
  "Trifecta tests for kanban list filter predicates.

   Each pure predicate added for token-budget filtering gets a
   golden + property + mutation pinning, so the contract for each
   filter is impossible to silently regress.

   Subject fns live in hive-mcp.tools.kanban.transitions:
     - entry-matches-query?  — case-insensitive substring on title/description
     - entry-tags-match?     — extra-tag AND/OR
     - entry-priority?       — exact priority match
     - entry-after-ts?       — temporal threshold on created/updated
     - paginate              — offset + limit
     - project-fields        — slim-shape projection
     - post-filters?         — bumps store fetch window

   Each subject is wrapped in a thin run-* adapter that takes a single
   case map and returns a boolean / scalar / coll, matching the
   trifecta single-input contract."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as tc-prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.tools.kanban.transitions :as kt]
            [clojure.string]))

;; =============================================================================
;; Adapter helpers — collapse multi-arg subjects into unary case-map fns
;; =============================================================================

(defn run-entry-matches-query [{:keys [entry q]}]
  (kt/entry-matches-query? entry q))

(defn run-entry-tags-match [{:keys [entry tags mode]}]
  (kt/entry-tags-match? entry tags mode))

(defn run-entry-priority [{:keys [entry priority]}]
  (kt/entry-priority? entry priority))

(defn run-entry-after-ts [{:keys [entry kind threshold]}]
  (kt/entry-after-ts? entry kind threshold))

(defn run-paginate [{:keys [coll offset limit]}]
  (vec (kt/paginate coll offset limit)))

(defn run-project-fields [{:keys [task fields]}]
  (kt/project-fields task fields))

(defn run-post-filters [params]
  (kt/post-filters? params))

;; =============================================================================
;; 1. entry-matches-query? — case-insensitive substring on title + description
;; =============================================================================

(def ^:private entry-with-title
  {:content {:title "Refactor Auth Flow" :description "rotate tokens"}})

(deftrifecta entry-matches-query-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-entry-matches-query
  {:golden-path "test/golden/kanban/list-filter/entry-matches-query.edn"
   :cases       {:title-hit       {:entry entry-with-title :q "refactor"}
                 :title-hit-ci    {:entry entry-with-title :q "REFACTOR"}
                 :description-hit {:entry entry-with-title :q "tokens"}
                 :no-match        {:entry entry-with-title :q "zzz"}
                 :nil-query       {:entry entry-with-title :q nil}
                 :blank-query     {:entry entry-with-title :q "   "}
                 :empty-content   {:entry {:content {}} :q "anything"}}
   :gen         (gen/let [q (gen/one-of [(gen/return nil)
                                         (gen/return "")
                                         gen/string-alphanumeric])
                          title gen/string-alphanumeric]
                  {:entry {:content {:title title}} :q q})
   :pred        boolean?
   :num-tests   100
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["case-sensitive"
                  (fn [{:keys [entry q]}]
                    (boolean
                     (and (string? q) (not (clojure.string/blank? q))
                          (let [t (get-in entry [:content :title] "")]
                            (clojure.string/includes? (str t) q)))))]]})

;; =============================================================================
;; 2. entry-tags-match? — extra tags under :all or :any
;; =============================================================================

(def ^:private entry-tags
  {:tags ["kanban" "todo" "priority-high" "scope:project:hive-mcp"]})

(deftrifecta entry-tags-match-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-entry-tags-match
  {:golden-path "test/golden/kanban/list-filter/entry-tags-match.edn"
   :cases       {:no-tags       {:entry entry-tags :tags []          :mode :all}
                 :all-pass      {:entry entry-tags :tags ["kanban" "todo"] :mode :all}
                 :all-partial   {:entry entry-tags :tags ["kanban" "review"] :mode :all}
                 :any-pass      {:entry entry-tags :tags ["nope" "todo"] :mode :any}
                 :any-none      {:entry entry-tags :tags ["nope" "zilch"] :mode :any}
                 :empty-tags-on-entry {:entry {:tags []} :tags ["kanban"] :mode :all}}
   :gen         (gen/let [mode  (gen/elements [:all :any])
                          tags  (gen/vector (gen/elements ["kanban" "todo" "doing" "x"])
                                            0 4)
                          entry-tags-v (gen/vector (gen/elements ["kanban" "todo" "doing" "review"])
                                                   0 5)]
                  {:entry {:tags entry-tags-v} :tags tags :mode mode})
   :pred        boolean?
   :num-tests   100
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["mode-flip"
                  (fn [{:keys [entry tags mode]}]
                    (kt/entry-tags-match? entry tags
                                          (if (= mode :all) :any :all)))]]})

;; =============================================================================
;; 3. entry-priority? — exact match
;; =============================================================================

(def ^:private entry-prio
  {:content {:priority "high"}})

(deftrifecta entry-priority-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-entry-priority
  {:golden-path "test/golden/kanban/list-filter/entry-priority.edn"
   :cases       {:exact-high  {:entry entry-prio :priority "high"}
                 :wrong       {:entry entry-prio :priority "low"}
                 :nil-filter  {:entry entry-prio :priority nil}
                 :missing     {:entry {:content {}} :priority "medium"}}
   :gen         (gen/let [p   (gen/one-of [(gen/return nil)
                                           (gen/elements ["high" "medium" "low"])])
                          ep  (gen/elements ["high" "medium" "low"])]
                  {:entry {:content {:priority ep}} :priority p})
   :pred        boolean?
   :num-tests   60
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["substring-match"
                  (fn [{:keys [entry priority]}]
                    (boolean
                     (and priority
                          (clojure.string/includes?
                           (str (get-in entry [:content :priority] ""))
                           priority))))]]})

;; =============================================================================
;; 4. entry-after-ts? — temporal threshold on created/updated
;; =============================================================================

(def ^:private fixed-now      "2026-04-28T12:00:00+0000")
(def ^:private older-than-now "2026-04-27T12:00:00+0000")
(def ^:private newer-than-now "2026-04-29T12:00:00+0000")

(deftrifecta entry-after-ts-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-entry-after-ts
  {:golden-path "test/golden/kanban/list-filter/entry-after-ts.edn"
   :cases       {:created-newer
                 {:entry {:content {:created newer-than-now}}
                  :kind :created :threshold fixed-now}
                 :created-equal
                 {:entry {:content {:created fixed-now}}
                  :kind :created :threshold fixed-now}
                 :created-older
                 {:entry {:content {:created older-than-now}}
                  :kind :created :threshold fixed-now}
                 :nil-threshold
                 {:entry {:content {:created older-than-now}}
                  :kind :created :threshold nil}
                 :missing-ts
                 {:entry {:content {}} :kind :created :threshold fixed-now}
                 :updated-via-completed
                 {:entry {:content {:completed newer-than-now}}
                  :kind :updated :threshold fixed-now}}
   :gen         (gen/let [kind (gen/elements [:created :updated])
                          ts   (gen/elements [older-than-now fixed-now newer-than-now])]
                  {:entry {:content {:created ts :updated ts}}
                   :kind kind :threshold fixed-now})
   :pred        boolean?
   :num-tests   80
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["inclusive"
                  (fn [{:keys [entry kind threshold]}]
                    (or (nil? threshold)
                        (when-let [ts (case kind
                                        :created (get-in entry [:content :created])
                                        :updated (or (get-in entry [:content :updated])
                                                     (get-in entry [:content :completed])))]
                          (>= (compare (str ts) (str threshold)) 0))))]]})

;; =============================================================================
;; 5. paginate — offset + limit slicing
;; =============================================================================

(deftrifecta paginate-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-paginate
  {:golden-path "test/golden/kanban/list-filter/paginate.edn"
   :cases       {:no-args      {:coll [:a :b :c :d :e] :offset nil :limit nil}
                 :limit-only   {:coll [:a :b :c :d :e] :offset nil :limit 2}
                 :offset-only  {:coll [:a :b :c :d :e] :offset 2   :limit nil}
                 :both         {:coll [:a :b :c :d :e] :offset 1   :limit 2}
                 :over-offset  {:coll [:a :b :c :d :e] :offset 10  :limit 3}
                 :over-limit   {:coll [:a :b :c :d :e] :offset nil :limit 100}
                 :empty-coll   {:coll []                :offset 1   :limit 1}}
   :gen         (gen/let [n      (gen/choose 0 20)
                          off    (gen/one-of [(gen/return nil) (gen/choose 0 25)])
                          lim    (gen/one-of [(gen/return nil) (gen/choose 0 25)])]
                  {:coll (vec (range n)) :offset off :limit lim})
   :pred        vector?
   :num-tests   80
   :mutations   [["drop-everything" (fn [_] [])]
                 ["return-input"    (fn [{:keys [coll]}] (vec coll))]
                 ["off-by-one-offset"
                  (fn [{:keys [coll offset limit]}]
                    (vec (kt/paginate coll
                                      (when (number? offset) (max 0 (dec offset)))
                                      limit)))]]})

;; =============================================================================
;; 6. project-fields — subset projection
;; =============================================================================

(def ^:private slim-task
  {:id "x" :title "T" :status "todo" :priority "high"})

(deftrifecta project-fields-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-project-fields
  {:golden-path "test/golden/kanban/list-filter/project-fields.edn"
   :cases       {:nil-fields   {:task slim-task :fields nil}
                 :empty-fields {:task slim-task :fields []}
                 :id-only      {:task slim-task :fields ["id"]}
                 :id-title     {:task slim-task :fields ["id" "title"]}
                 :keyword-form {:task slim-task :fields [:id :status]}
                 :missing-key  {:task slim-task :fields ["id" "nope"]}}
   :gen         (gen/let [keys-v (gen/vector (gen/elements [:id :title :status :priority])
                                             0 4)]
                  {:task slim-task :fields keys-v})
   :pred        map?
   :num-tests   60
   :mutations   [["always-empty"  (fn [_] {})]
                 ["always-input"  (fn [{:keys [task]}] task)]
                 ["string-keys"
                  (fn [{:keys [task fields]}]
                    (if (or (nil? fields) (empty? fields))
                      task
                      (select-keys task (mapv str fields))))]]})

;; =============================================================================
;; 7. post-filters? — bumps store fetch window
;; =============================================================================

(deftrifecta post-filters-contract
  hive-mcp.tools.kanban.list-filter-trifecta-test/run-post-filters
  {:golden-path "test/golden/kanban/list-filter/post-filters.edn"
   :cases       {:empty         {}
                 :only-status   {:status "todo"}
                 :query-set     {:query "auth"}
                 :priority-set  {:priority "high"}
                 :created-set   {:created_after "2026-01-01T00:00:00+0000"}
                 :updated-set   {:updated_after "2026-01-01T00:00:00+0000"}
                 :tags-any      {:tags ["x"] :tag_match "any"}
                 :tags-all      {:tags ["x"] :tag_match "all"}
                 :limit-set     {:limit 50}
                 :offset-set    {:offset 10}
                 :fields-set    {:fields ["id"]}
                 :blank-query   {:query "   "}}
   :gen         (gen/hash-map
                  :query    (gen/one-of [(gen/return nil) gen/string-alphanumeric])
                  :priority (gen/one-of [(gen/return nil) (gen/return "high")])
                  :limit    (gen/one-of [(gen/return nil) (gen/choose 0 100)])
                  :offset   (gen/one-of [(gen/return nil) (gen/choose 0 100)]))
   :pred        boolean?
   :num-tests   80
   :mutations   [["always-true"  (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["status-counts"
                  (fn [params] (boolean (or (kt/post-filters? params)
                                            (:status params))))]]})

;; =============================================================================
;; Property: paginate is a subsequence of the input
;; =============================================================================

(defspec prop-paginate-is-subseq 100
  (tc-prop/for-all
    [n   (gen/choose 0 30)
     off (gen/choose 0 30)
     lim (gen/choose 0 30)]
    (let [coll (vec (range n))
          out  (vec (kt/paginate coll off lim))]
      (and (every? (set coll) out)
           (<= (count out) n)
           ;; lim=0 means "no cap" (consistent with the impl gating on
           ;; `(pos? limit)`); only assert the lim ceiling when positive.
           (or (not (pos? lim))
               (<= (count out) lim))))))

;; =============================================================================
;; Property: project-fields output keys ⊆ requested fields ∩ task keys
;; =============================================================================

(defspec prop-project-fields-key-subset 100
  (tc-prop/for-all
    [task   (gen/hash-map :id gen/string-alphanumeric
                          :title gen/string-alphanumeric
                          :status (gen/elements ["todo" "doing"])
                          :priority (gen/elements ["high" "low"]))
     fields (gen/vector (gen/elements [:id :title :status :priority :missing])
                        0 5)]
    (let [out      (kt/project-fields task fields)
          ks       (set (keys out))
          asked    (set (mapv #(if (keyword? %) % (keyword (name %))) fields))
          original (set (keys task))]
      (if (empty? fields)
        (= out task)
        (every? #(and (contains? asked %) (contains? original %)) ks)))))
