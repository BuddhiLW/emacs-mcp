(ns hive-mcp.crystal.fanout-test
  "Step-5 + Step-6 fan-out tests. Mocks the synthesiser via `with-redefs`
   of `hive-mcp.crystal.core/summarize-session-progress` so the test runs
   without addons / LLM."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.crystal.core :as core]
            [hive-mcp.crystal.fanout :as fan]
            [hive-mcp.crystal.harvest.by-scope :as bs]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Mock synthesiser — captures input + returns deterministic stub
;; =============================================================================

(defn- mock-synth-fn
  "Return a synth fn that records each call into `calls-atom` and emits
   a deterministic stub entry. Mirrors the real public signature
   `[notes git-commits harvested]`."
  [calls-atom]
  (fn [notes git-commits harvested]
    (swap! calls-atom conj
           {:project-id (:project-id harvested)
            :scope      (:scope harvested)
            :note-count (count notes)
            :commit-count (count git-commits)
            :kg-edge-count (get-in harvested [:summary :kg-edge-count] 0)})
    {:type :note
     :content (str "synth for " (:project-id harvested))
     :tags ["session-summary" "wrap-generated"]
     :duration :short}))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- multi-scope-hbs
  "A HarvestByScope with two real scopes + an umbrella with cross-pid edge."
  []
  (-> (bs/empty-by-scope {:session "20260504" :directory "/tmp" :agent-id "ag"})
      (bs/assoc-scope "hive"
                       (assoc bs/empty-scope-slice
                              :progress-notes [{:title "fix"}]
                              :git-commits   ["sha1 fix"]))
      (bs/assoc-scope "funeraria"
                       (assoc bs/empty-scope-slice
                              :progress-notes [{:title "wip"}
                                                {:title "more"}]))
      (bs/merge-umbrella {:cross-pid-edges        [{:edge :a}]
                          :cross-cutting-decisions [{:dec :x}]
                          :session-timing          {:t 100}})))

;; =============================================================================
;; Step-5 — fan-out shape and per-scope isolation
;; =============================================================================

(deftest synthesize-wraps--emits-one-entry-per-scope
  (let [calls (atom [])]
    (with-redefs [core/summarize-session-progress (mock-synth-fn calls)]
      (let [hbs (multi-scope-hbs)
            results (fan/synthesize-wraps hbs)]
        (is (= 3 (count results)) "two scopes + one umbrella = 3 entries")
        (is (= [#{"funeraria" "hive" :umbrella}]
               [(set (map :pid results))])
            "all three pids represented (sorted strings + umbrella sentinel)")
        (is (every? :entry results) "every result has an entry")
        (is (every? #(= :note (:type (:entry %))) results))))))

(deftest synthesize-wraps--per-scope-content-is-isolated
  (let [calls (atom [])]
    (with-redefs [core/summarize-session-progress (mock-synth-fn calls)]
      (fan/synthesize-wraps (multi-scope-hbs))
      (let [by-pid (group-by :project-id @calls)]
        (testing "hive slice synthesised in isolation"
          (let [c (first (get by-pid "hive"))]
            (is (= 1 (:note-count c)))
            (is (= 1 (:commit-count c)))))
        (testing "funeraria slice synthesised in isolation"
          (let [c (first (get by-pid "funeraria"))]
            (is (= 2 (:note-count c)))
            (is (= 0 (:commit-count c)))))
        (testing "umbrella synthesised with cross-cutting payload"
          (let [c (first (get by-pid "multi-project"))]
            (is (= 1 (:note-count c)) "cross-cutting-decision becomes a note")
            (is (= 1 (:kg-edge-count c)) "cross-pid-edge surfaces in summary")
            (is (= :umbrella (:scope c)))))))))

(deftest synthesize-wraps--skips-empty-slices
  (let [calls (atom [])]
    (with-redefs [core/summarize-session-progress (mock-synth-fn calls)]
      (let [hbs (-> (bs/empty-by-scope)
                    (bs/assoc-scope "hive" bs/empty-scope-slice))
            results (fan/synthesize-wraps hbs)]
        (is (= 0 (count results)) "an empty scope slice + empty umbrella = no wraps")
        (is (= 0 (count @calls)) "synthesiser never called")))))

(deftest synthesize-wraps--skips-umbrella-when-empty
  (let [calls (atom [])]
    (with-redefs [core/summarize-session-progress (mock-synth-fn calls)]
      (let [hbs (-> (bs/empty-by-scope)
                    (bs/assoc-scope "hive"
                                     (assoc bs/empty-scope-slice
                                            :progress-notes [{:title "x"}])))
            results (fan/synthesize-wraps hbs)]
        (is (= 1 (count results)) "only the hive entry; no umbrella")
        (is (= "hive" (:pid (first results))))))))

(deftest synthesize-wraps--drops-nil-synth-result
  (let [calls (atom [])]
    (with-redefs [core/summarize-session-progress
                  (fn [_ _ _] (swap! calls conj :called) nil)]
      (let [hbs (-> (bs/empty-by-scope)
                    (bs/assoc-scope "hive"
                                     (assoc bs/empty-scope-slice
                                            :progress-notes [{:title "x"}])))
            results (fan/synthesize-wraps hbs)]
        (is (= 0 (count results)) "nil synth result drops the entry")
        (is (= 1 (count @calls)) "synthesiser was still called")))))

(deftest slice->harvested--shape-mirrors-legacy-result
  (let [slice (assoc bs/empty-scope-slice
                     :progress-notes [{:n 1}]
                     :git-commits ["sha1"]
                     :kg-edges-created [{:edge :a}])
        h (fan/slice->harvested "hive" slice
                                 {:session "20260504"
                                  :directory "/tmp"
                                  :agent-id "ag"
                                  :session-timing {:t 1}})]
    (testing "all legacy harvest-all keys present"
      (is (every? #(contains? h %)
                  [:progress-notes :completed-tasks :git-commits :recalls
                   :hivemind-messages :kanban-activity :kg-edges-created
                   :kanban-movements :memory-ids-created :memory-ids-accessed
                   :session :directory :agent-id :project-id :summary])))
    (testing "pid threaded through"
      (is (= "hive" (:project-id h))))
    (testing "summary counts match slice"
      (is (= 1 (get-in h [:summary :progress-count])))
      (is (= 1 (get-in h [:summary :commit-count])))
      (is (= 1 (get-in h [:summary :kg-edge-count]))))
    (testing "kg-edges wrapped in legacy {:edges :count} shape"
      (is (= [{:edge :a}] (get-in h [:kg-edges-created :edges]))))))

(deftest umbrella->harvested--cross-pid-edges-surface
  (let [hbs (-> (bs/empty-by-scope)
                (bs/merge-umbrella {:cross-pid-edges        [{:edge :a}]
                                    :cross-cutting-decisions [{:dec :x}]
                                    :hivemind-shouts-global  [{:m "y"}]}))
        h   (fan/umbrella->harvested hbs {:session "x"})]
    (is (= [{:edge :a}] (get-in h [:kg-edges-created :edges])))
    (is (= [{:dec :x}] (:progress-notes h)))
    (is (= [{:m "y"}] (:hivemind-messages h)))
    (is (= "multi-project" (:project-id h)))
    (is (= :umbrella (:scope h)))))

;; =============================================================================
;; Step-6 — explicit scope tag injection
;; =============================================================================

(deftest with-scope-tag--prepends-scope-project-tag
  (testing "real pid produces scope:project:<pid> prefix"
    (let [tagged (fan/with-scope-tag {:tags ["session-summary" "wrap-generated"]} "funeraria")]
      (is (= "scope:project:funeraria" (first (:tags tagged))))
      (is (= ["scope:project:funeraria" "session-summary" "wrap-generated"]
             (:tags tagged))))))

(deftest with-scope-tag--umbrella-sentinel-uses-multi-project
  (testing "umbrella sentinel produces scope:multi-project tag"
    (let [tagged (fan/with-scope-tag {:tags ["session-summary"]} bs/umbrella-sentinel)]
      (is (= "scope:multi-project" (first (:tags tagged))))
      (is (= ["scope:multi-project" "session-summary"] (:tags tagged))))))

(deftest with-scope-tag--idempotent
  (testing "applying the same scope-tag twice is a no-op"
    (let [once  (fan/with-scope-tag {:tags ["x"]} "hive")
          twice (fan/with-scope-tag once "hive")]
      (is (= once twice))
      (is (= 1 (count (filter #{"scope:project:hive"} (:tags twice))))))))

(deftest synthesize-wraps--every-entry-carries-scope-tag
  (let [calls (atom [])]
    (with-redefs [core/summarize-session-progress (mock-synth-fn calls)]
      (let [hbs (multi-scope-hbs)
            results (fan/synthesize-wraps hbs)
            tag-of (fn [r] (first (get-in r [:entry :tags])))]
        (testing "real-pid entries get scope:project:<pid>"
          (is (= "scope:project:funeraria"
                 (tag-of (first (filter #(= "funeraria" (:pid %)) results)))))
          (is (= "scope:project:hive"
                 (tag-of (first (filter #(= "hive" (:pid %)) results))))))
        (testing "umbrella entry gets scope:multi-project"
          (is (= "scope:multi-project"
                 (tag-of (first (filter #(= bs/umbrella-sentinel (:pid %))
                                         results))))))))))