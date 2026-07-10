(ns hive-mcp.tools.catchup.native-wrap-fanout-test
  "Step-8 e2e: `handle-native-wrap` runs harvest-all-by-scope →
   synthesize-wraps → persist-wraps! and produces one IMemoryStore
   write per touched scope (plus an umbrella entry when umbrella
   facts are present), each with explicit `:project-id` derived
   from the scope (no pwd derivation).

   Plan: `20260504173159-46dc47f1`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.catchup :as catchup]
            [hive-mcp.crystal.core :as crystal-core]
            [hive-mcp.crystal.harvest.collect :as coll]
            [hive-mcp.protocols.memory :as mem-proto]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private fixture-dir-a "/tmp/proj-a")
(def ^:private fixture-dir-b "/tmp/proj-b")

(defn- mock-legacy
  "Shape mirrors what `coll/harvest-all` returns today (single-scope).
   `pid` drives both the per-datum tagging and the harvest's :directory
   so attribution + pwd-derivation agree."
  [pid dir]
  {:progress-notes      [{:project-id pid :tags []}]
   :completed-tasks     []
   :git-commits         ["abc1 fix"]
   :recalls             {}
   :hivemind-messages   []
   :kanban-activity     {:tasks-completed [{:project-id pid}]}
   :kg-edges-created    {:edges []}
   :kanban-movements    {:movements []}
   :memory-ids-created  []
   :memory-ids-accessed []
   :session             "20260506-test"
   :directory           dir
   :agent-id            "fixture-agent"
   :session-timing      {:t 1}
   :summary             {:progress-count 1 :task-count 1 :commit-count 1
                         :recall-count 0 :hivemind-shout-count 0 :kanban-completed 1
                         :kg-edge-count 0 :kanban-movement-count 0
                         :created-count 0 :accessed-count 0}})

(defn- mock-synth
  "Deterministic synthesiser stub so the fan-out doesn't depend on LLM
   non-determinism. Mirrors the public signature
   `[notes git-commits harvested]`."
  [_notes _commits harvested]
  {:type    :note
   :content (str "synth for " (:project-id harvested))
   :tags    ["session-summary" "wrap-generated"]
   :duration :short})

(defn- run-with-stub
  "Drive `f` with mem-proto stubbed, harvest-all redefined to
   `harvest-fn`, and the synthesiser pinned. Captures every
   add-entry! payload."
  [harvest-fn f]
  (let [calls (atom [])
        stub  (reify Object)]
    (with-redefs [coll/harvest-all                        harvest-fn
                  crystal-core/summarize-session-progress mock-synth
                  mem-proto/store-set?                    (fn [] true)
                  mem-proto/get-store                     (fn ([] stub) ([_] stub))
                  mem-proto/add-entry!                    (fn [_ entry]
                                                            (swap! calls conj entry)
                                                            (str "mem-" (count @calls)))]
      {:result (f)
       :calls  @calls})))

(defn- pids-of [calls] (set (mapv :project-id calls)))
(defn- entry-for-pid [calls pid]
  (first (filter #(= pid (:project-id %)) calls)))

(deftest native-wrap--single-scope-emits-real-pid-entry
  (testing "single-scope harvest produces an entry for the real pid"
    (let [{:keys [result calls]}
          (run-with-stub
            (fn [_] (mock-legacy "proj-a" fixture-dir-a))
            (fn [] (catchup/handle-native-wrap
                     {:directory fixture-dir-a
                      :agent_id  "fixture-agent"})))
          payload (json/read-str (:text result) :key-fn keyword)]
      (is (= "20260506-test" (:session payload)))
      (is (zero? (:failed payload)))
      (is (= (:total payload) (:persisted payload)))
      (is (contains? (pids-of calls) "proj-a"))
      (let [proj-a-entry (entry-for-pid calls "proj-a")]
        (is (= "scope:project:proj-a" (first (:tags proj-a-entry))))
        (is (= "proj-a" (:project-id proj-a-entry)))))))

(deftest native-wrap--multi-scope-fans-out
  (testing "harvest with cross-scope datums produces one entry per real pid"
    (let [{:keys [calls]}
          (run-with-stub
            (fn [_]
              (-> (mock-legacy "proj-a" fixture-dir-a)
                  (update :progress-notes conj
                          {:tags ["scope:project:proj-b"]})))
            (fn [] (catchup/handle-native-wrap {:directory fixture-dir-a})))
          pids (pids-of calls)]
      (is (contains? pids "proj-a"))
      (is (contains? pids "proj-b"))
      (testing "real-pid entries lead with their scope tag"
        (is (= "scope:project:proj-a"
               (first (:tags (entry-for-pid calls "proj-a")))))
        (is (= "scope:project:proj-b"
               (first (:tags (entry-for-pid calls "proj-b")))))))))

(deftest native-wrap--explicit-pid-survives-fanout
  (testing "harvest scope wins at the writer; tag-derived pid lands in own slice"
    ;; Harvest is anchored at proj-a (call passes proj-a, mock pwd → proj-a),
    ;; but a progress-note carries an explicit scope:project:proj-b tag.
    ;; Writer must produce two real-pid entries, each with its own
    ;; explicit :project-id — never collapsed onto pwd-derived proj-a.
    (let [{:keys [calls]}
          (run-with-stub
            (fn [_]
              (-> (mock-legacy "proj-a" fixture-dir-a)
                  (update :progress-notes conj
                          {:tags ["scope:project:proj-b"]})))
            (fn [] (catchup/handle-native-wrap {:directory fixture-dir-a})))
          real-pids (disj (pids-of calls) "multi-project")]
      (is (= #{"proj-a" "proj-b"} real-pids)
          "explicit per-scope pids honored; no pwd-collapse"))))

(deftest native-wrap--umbrella-tagged-multi-project
  (testing "umbrella entry (when emitted) carries scope:multi-project tag"
    (let [{:keys [calls]}
          (run-with-stub
            (fn [_]
              (-> (mock-legacy "proj-a" fixture-dir-a)
                  (assoc-in [:kg-edges-created :edges]
                            [{:kg-edge/scope "proj-b"}])))
            (fn [] (catchup/handle-native-wrap {:directory fixture-dir-a})))]
      (when-let [umbrella (entry-for-pid calls "multi-project")]
        (is (= "scope:multi-project" (first (:tags umbrella))))
        (is (= "multi-project" (:project-id umbrella)))))))

(deftest native-wrap--store-not-set-error
  (testing "graceful error when IMemoryStore not registered"
    (with-redefs [mem-proto/store-set? (fn [] false)]
      (let [resp (catchup/handle-native-wrap {:directory fixture-dir-a})]
        (is (:isError resp))))))
