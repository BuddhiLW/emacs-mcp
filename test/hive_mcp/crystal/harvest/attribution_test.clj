(ns hive-mcp.crystal.harvest.attribution-test
  "Trifecta + integration tests for the attribution layer (step-2 of plan
   `20260504173159-46dc47f1`).

   Invariants:
   1. Tag-based pid extraction is total (returns nil for malformed input).
   2. Strong-attribution datums (entries with :project-id or scope tags) get
      their own pid; weak-attribution datums (commits, accessed-ids) inherit
      source-pid.
   3. Datums with no signal AND no source-pid land in the umbrella sentinel.
   4. `attribute-harvest` is shape-stable: every known source-key has a
      slice in :by-source even when input is empty."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.crystal.harvest.attribution :as attr]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-test.trifecta :refer [deftrifecta]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Generators
;; =============================================================================

(def ^:private gen-pid
  (gen/elements ["hive" "hive-mcp" "hive-knowledge" "funeraria" "sisf-crm"]))

(def ^:private gen-scope-tag
  (gen/fmap #(str "scope:project:" %) gen-pid))

(def ^:private gen-tag-collection
  (gen/vector (gen/one-of [gen-scope-tag
                           (gen/return "kanban")
                           (gen/return "todo")
                           (gen/return "priority-high")])
              0 6))

;; =============================================================================
;; Trifecta — pid-from-tag (single tag)
;; =============================================================================

(deftrifecta pid-from-tag-extraction
  attr/pid-from-tag
  {:golden-path "test/golden/hive-mcp/crystal/harvest/attribution-pid-from-tag.edn"
   :cases       {:hive          "scope:project:hive"
                 :hive-mcp      "scope:project:hive-mcp"
                 :funeraria     "scope:project:funeraria"
                 :no-prefix     "kanban"
                 :scope-global  "scope:global"
                 :empty-after   "scope:project:"
                 :nil-input     nil
                 :keyword-input :hive
                 :reserved-pid  "scope:project:umbrella"}
   :gen         gen-scope-tag
   :pred        (fn [r] (or (nil? r) (string? r)))
   :mutations   [["always-nil"     (fn [_] nil)]
                 ["echo-tag"       identity]
                 ["strip-no-check" (fn [t] (when (string? t)
                                             (when (.startsWith ^String t "scope:project:")
                                               (subs t (count "scope:project:")))))]]})

;; =============================================================================
;; Trifecta — pid-from-tags (collection)
;; =============================================================================

(deftrifecta pid-from-tags-collection
  attr/pid-from-tags
  {:golden-path "test/golden/hive-mcp/crystal/harvest/attribution-pid-from-tags.edn"
   :cases       {:single        ["scope:project:hive"]
                 :first-wins    ["scope:project:funeraria" "scope:project:hive"]
                 :surrounded    ["kanban" "todo" "scope:project:sisf-crm" "priority-high"]
                 :no-scope      ["kanban" "todo" "priority-medium"]
                 :empty         []
                 :nil-input     nil
                 :set-input     #{"scope:project:hive-knowledge" "todo"}
                 :reserved      ["scope:project:umbrella"]}
   :gen         gen-tag-collection
   :pred        (fn [r] (or (nil? r) (bs/valid-pid? r)))
   :mutations   [["always-nil" (fn [_] nil)]
                 ["always-hive" (fn [_] "hive")]]})

;; =============================================================================
;; Trifecta — attribute-commit (weak attribution → source-pid)
;; =============================================================================

(defn- commit-attribution-roundtrip
  [{:keys [source-pid commit]}]
  (let [r (attr/attribute-commit source-pid commit)]
    {:pid    (:pid r)
     :datum  (:datum r)
     :inherits-source? (= source-pid (:pid r))}))

(deftrifecta attribute-commit-inherits-source-pid
  commit-attribution-roundtrip
  {:golden-path "test/golden/hive-mcp/crystal/harvest/attribution-commit.edn"
   :cases       {:hive      {:source-pid "hive"      :commit "abc1 fix bug"}
                 :funeraria {:source-pid "funeraria" :commit "def2 wip"}
                 :nil-pid   {:source-pid nil         :commit "ghi3 docs"}}
   :gen         (gen/hash-map :source-pid (gen/one-of [gen-pid (gen/return nil)])
                              :commit     gen/string-alphanumeric)
   :pred        (fn [r] (and (or (string? (:datum r)) (nil? (:datum r)))
                              (or (= (:pid r) (:datum r) nil)
                                  (= (:pid r) bs/umbrella-sentinel)
                                  (string? (:pid r)))))
   :mutations   [["wrong-pid"   (fn [args]
                                  (-> (commit-attribution-roundtrip args)
                                      (assoc :pid "wrong-project")))]
                 ["drops-datum" (fn [args]
                                  (-> (commit-attribution-roundtrip args)
                                      (assoc :datum nil)))]]})

;; =============================================================================
;; Trifecta — attribute-progress-note (strong, with source-pid fallback)
;; =============================================================================

(defn- progress-note-roundtrip
  [{:keys [source-pid note]}]
  (:pid (attr/attribute-progress-note source-pid note)))

(deftrifecta attribute-progress-note-precedence
  progress-note-roundtrip
  {:golden-path "test/golden/hive-mcp/crystal/harvest/attribution-progress-note.edn"
   :cases       {:explicit-pid    {:source-pid "hive"
                                   :note {:project-id "funeraria" :tags []}}
                 :tag-derived     {:source-pid "hive"
                                   :note {:tags ["scope:project:sisf-crm"]}}
                 :explicit-wins   {:source-pid "hive"
                                   :note {:project-id "funeraria"
                                          :tags ["scope:project:sisf-crm"]}}
                 :falls-to-source {:source-pid "hive-mcp"
                                   :note {:tags ["kanban"]}}
                 :all-nil         {:source-pid nil
                                   :note {}}}
   :gen         (gen/hash-map
                  :source-pid (gen/one-of [gen-pid (gen/return nil)])
                  :note (gen/hash-map :project-id (gen/one-of [gen-pid (gen/return nil)])
                                      :tags       gen-tag-collection))
   :pred        (fn [r] (or (= bs/umbrella-sentinel r)
                            (string? r)))
   :mutations   [["always-source"   (fn [_] "hive")]
                 ["always-umbrella" (fn [_] bs/umbrella-sentinel)]]})

;; =============================================================================
;; Plain deftests — attribute-harvest end-to-end
;; =============================================================================

(def ^:private multi-scope-fixture
  "A harvest-all-shaped result with deliberately mixed scopes across
   strong-attribution sources, plus weak-attribution sources that should
   inherit the source-pid argument."
  {:progress-notes      [{:project-id "hive-mcp" :tags []}
                         {:project-id nil :tags ["scope:project:funeraria"]}
                         {:tags ["kanban"]}]
   :completed-tasks     [{:completed-task/project-id "hive"}
                         {:project-id "sisf-crm"}]
   :git-commits         ["a1 fix" "b2 wip"]
   :recalls             {"id-a" {:project-id "funeraria"}
                         "id-b" {:tags ["scope:project:hive-mcp"]}}
   :hivemind-messages   [{:project-id "sisf-crm" :m "ping"}
                         {:m "no-pid"}]
   :kanban-activity     {:tasks-completed [{:project-id "hive-mcp"}]}
   :kg-edges-created    {:edges [{:kg-edge/scope "hive"}
                                 {:kg-edge/scope "funeraria"}
                                 {:kg-edge/scope nil}]}
   :kanban-movements    {:movements [{:kanban-movement/project-id "hive"}]}
   :memory-ids-created  [{:id "m1" :project-id "funeraria"}]
   :memory-ids-accessed ["m2" "m3"]
   :session   "20260504"
   :directory "/home/leibniz/PP/hive"
   :agent-id  "coordinator"
   :session-timing {:start "x" :end "y"}})

(deftest attribute-harvest--multi-scope-distribution
  (testing "datums distribute across all touched scopes"
    (let [r (attr/attribute-harvest multi-scope-fixture "hive-source")
          pids-by-source (into {}
                                (for [[k ds] (:by-source r)]
                                  [k (mapv :pid ds)]))]
      (testing "strong-attribution sources carry their own pid"
        (is (= ["funeraria" "hive-mcp"]
               (get pids-by-source :recalls)))
        (is (= ["hive" "funeraria" :umbrella]
               (get pids-by-source :kg-edges-created)))
        (is (= ["sisf-crm" "hive-source"]
               (get pids-by-source :hivemind-messages))))
      (testing "weak-attribution sources inherit source-pid"
        (is (= ["hive-source" "hive-source"]
               (get pids-by-source :git-commits)))
        (is (= ["hive-source" "hive-source"]
               (get pids-by-source :memory-ids-accessed))))
      (testing "umbrella scalars surface session-wide facts"
        (is (= {:session-timing {:start "x" :end "y"}}
               (:umbrella-scalars r))))
      (testing "session metadata threads through"
        (is (= "20260504" (:session r)))
        (is (= "/home/leibniz/PP/hive" (:directory r)))
        (is (= "coordinator" (:agent-id r)))))))

(deftest attribute-harvest--shape-stable-on-empty-input
  (testing "every known slice-key is present even on an empty harvest"
    (let [r (attr/attribute-harvest {} "hive")]
      (is (= #{:progress-notes :completed-tasks :git-commits :recalls
                :hivemind-messages :kg-edges-created :kanban-movements
                :memory-ids-created :memory-ids-accessed}
              (set (keys (:by-source r)))))
      (is (every? empty? (vals (:by-source r))))
      (is (= {} (:umbrella-scalars r))))))

(deftest attribute-source--unknown-key-throws
  (testing "unknown source-key surfaces silent attribution drift early"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown harvest source-key"
                          (attr/attribute-source :bogus-key {} "hive")))))

(deftest source-keys--enumerable
  (testing "source-keys is the canonical set"
    (is (set? (attr/source-keys)))
    (is (contains? (attr/source-keys) :progress))
    (is (contains? (attr/source-keys) :kg-edges))
    (is (contains? (attr/source-keys) :hivemind))
    (is (= 7 (count (attr/source-keys))))))

(deftest attribute-harvest--no-source-pid-defaults-to-umbrella
  (testing "weak-attribution datums with nil source-pid land in umbrella"
    (let [r (attr/attribute-harvest {:git-commits ["x" "y"]} nil)]
      (is (= [bs/umbrella-sentinel bs/umbrella-sentinel]
             (mapv :pid (get-in r [:by-source :git-commits])))))))