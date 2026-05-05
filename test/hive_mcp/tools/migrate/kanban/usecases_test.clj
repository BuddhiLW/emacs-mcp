(ns hive-mcp.tools.migrate.kanban.usecases-test
  "Use-case tests for the kanban migrator. All adapters are in-memory
   stubs so no milvus/qdrant/disk IO leaks into the test process.

   Stub design:
     `mem-state`    — atom-backed IState
     `mem-lister`   — fixed list of ids
     `mem-reader`   — `id => entry` map lookup
     `mem-writer`   — appends each written entry into a sink atom"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-mcp.tools.migrate.kanban.events :as mig-events]
            [hive-mcp.tools.migrate.kanban.ports :as ports]
            [hive-mcp.tools.migrate.kanban.pure :as pure]
            [hive-mcp.tools.migrate.kanban.state :as state]
            [hive-mcp.tools.migrate.kanban.usecases :as uc]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Stub adapters
;; =============================================================================

(defrecord MemState [atm]
  ports/IState
  (load-state [_]    (r/ok @atm))
  (save-state! [_ s] (reset! atm s) (r/ok s))
  (reset-state! [_]  (reset! atm state/initial-state) (r/ok state/initial-state)))

(defn mem-state [] (->MemState (atom state/initial-state)))

(defrecord MemLister [ids per-coll]
  ports/IIdLister
  (list-ids [_] (r/ok {:ids ids :per-collection per-coll})))

(defn mem-lister
  ([ids] (mem-lister ids {"stub" (count ids)}))
  ([ids per-coll] (->MemLister ids per-coll)))

(defrecord MemReader [entries]
  ports/IEntryReader
  (read-by-ids [_ ids]
    (r/ok (select-keys entries ids))))

(defn mem-reader [entries] (->MemReader entries))

(defrecord MemWriter [sink fail-ids]
  ports/IEntryWriter
  (write-entries [_ entries]
    (let [results (mapv (fn [{:keys [id] :as e}]
                          (if (contains? fail-ids id)
                            {:id id :ok? false :error "stub-fail"}
                            (do (swap! sink conj e)
                                {:id id :ok? true})))
                        entries)]
      (r/ok results))))

(defn mem-writer
  ([] (mem-writer #{}))
  ([fail-ids] (->MemWriter (atom []) fail-ids)))

(defn- writer-sink [writer] @(:sink writer))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- kanban-entry [id title]
  {:id id
   :type :note
   :tags ["kanban" "todo"]
   :content {:task-type "kanban" :title title :status "todo"}})

(defn- non-task-entry [id]
  {:id id :type :note :tags ["kanban" "swarm"]
   :content {:task-type "swarm"}})

(defn- stub-target-entry
  "Target entry with empty content — should be classified as ready-to-write."
  [id]
  {:id id})

(defn- full-target-entry [id title]
  (kanban-entry id title))

(defn- new-deps
  [{:keys [ids source-entries target-entries fail-write-ids]
    :or   {ids [] source-entries {} target-entries {} fail-write-ids #{}}}]
  (mig-events/init!)
  {:source-lister (mem-lister ids)
   :source-reader (mem-reader source-entries)
   :target-reader (mem-reader target-entries)
   :writer        (mem-writer fail-write-ids)
   :state         (mem-state)})

;; =============================================================================
;; init-ids!
;; =============================================================================

(deftest init-ids-persists-list-and-bumps-phase
  (let [deps (new-deps {:ids ["a" "b" "c"]})
        result (uc/init-ids! deps)
        loaded (ports/load-state (:state deps))]
    (is (r/ok? result))
    (is (= 3 (-> result :ok :total)))
    (is (= ["a" "b" "c"] (-> loaded :ok :all-ids)))
    (is (= :ids-listed (-> loaded :ok :phase)))
    (is (zero? (-> loaded :ok :cursor)))))

;; =============================================================================
;; migrate-batch! — happy path with mixed outcomes
;; =============================================================================

(deftest migrate-batch-classifies-and-writes-mixed-outcomes
  (let [ids        ["task-1" "task-2" "swarm-1" "missing-1"]
        sources    {"task-1"   (kanban-entry "task-1" "first")
                    "task-2"   (kanban-entry "task-2" "second")
                    "swarm-1"  (non-task-entry "swarm-1")}
        targets    {"task-1"   (full-target-entry "task-1" "first")
                    "task-2"   (stub-target-entry "task-2")}
        deps       (new-deps {:ids ids :source-entries sources :target-entries targets})
        _          (uc/init-ids! deps)
        result     (uc/migrate-batch! deps {:batch-size 50})
        outcomes   (-> result :ok :outcomes)
        tally      (-> result :ok :tally)
        sink       (writer-sink (:writer deps))]
    (is (r/ok? result))
    (testing "classification"
      (is (= :already-full        (:outcome (first  (filter #(= "task-1"   (:id %)) outcomes)))))
      (is (= :written             (:outcome (first  (filter #(= "task-2"   (:id %)) outcomes)))))
      (is (= :not-task            (:outcome (first  (filter #(= "swarm-1"  (:id %)) outcomes)))))
      (is (= :missing-from-source (:outcome (first  (filter #(= "missing-1"(:id %)) outcomes))))))
    (testing "tally counts"
      (is (= 1 (:already-full        tally)))
      (is (= 1 (:written             tally)))
      (is (= 1 (:not-task            tally)))
      (is (= 1 (:missing-from-source tally))))
    (testing "writer received only the ready entry"
      (is (= 1 (count sink)))
      (is (= "task-2" (:id (first sink)))))))

;; =============================================================================
;; migrate-batch! — dry-run skips writes, marks :would-write
;; =============================================================================

(deftest migrate-batch-dry-run-emits-would-write
  (let [deps (new-deps {:ids            ["k"]
                        :source-entries {"k" (kanban-entry "k" "title")}
                        :target-entries {}})
        _    (uc/init-ids! deps)
        result (uc/migrate-batch! deps {:batch-size 10 :dry-run? true})
        sink   (writer-sink (:writer deps))]
    (is (= :would-write (-> result :ok :outcomes first :outcome)))
    (is (zero? (count sink)))))

;; =============================================================================
;; migrate-batch! — failed writer produces :failed outcome + error string
;; =============================================================================

(deftest migrate-batch-records-failed-writes
  (let [deps (new-deps {:ids            ["k"]
                        :source-entries {"k" (kanban-entry "k" "t")}
                        :target-entries {}
                        :fail-write-ids #{"k"}})
        _    (uc/init-ids! deps)
        result (uc/migrate-batch! deps {:batch-size 10})
        outc   (-> result :ok :outcomes first)]
    (is (= :failed (:outcome outc)))
    (is (= "stub-fail" (:error outc)))))

;; =============================================================================
;; step! folds tally + bumps cursor + clears done? when more remain
;; =============================================================================

(deftest step-folds-state
  (let [deps (new-deps {:ids            ["a" "b" "c"]
                        :source-entries {"a" (kanban-entry "a" "A")
                                         "b" (kanban-entry "b" "B")
                                         "c" (kanban-entry "c" "C")}})
        _    (uc/init-ids! deps)
        r1   (uc/step! deps {:batch-size 2})
        s1   (-> r1 :ok :state)]
    (is (= 2  (:cursor s1)))
    (is (= 2  (-> s1 :stats :scanned)))
    (is (= 2  (-> s1 :stats :written)))
    (is (= :running (:phase s1)))))

;; =============================================================================
;; run! loops until done
;; =============================================================================

(deftest run-iterates-until-done
  (let [n        7
        ids      (mapv #(str "id-" %) (range n))
        sources  (into {} (map (fn [id] [id (kanban-entry id id)]) ids))
        deps     (new-deps {:ids ids :source-entries sources})
        _        (uc/init-ids! deps)
        result   (uc/run! deps {:batch-size 3 :max-steps 10})]
    (is (r/ok? result))
    (is (= :done (-> result :ok :stopped)))
    (is (= n (count (writer-sink (:writer deps)))))))

;; =============================================================================
;; status reflects the saved snapshot
;; =============================================================================

(deftest status-reads-current-progress
  (let [deps (new-deps {:ids ["x" "y" "z"]})
        _    (uc/init-ids! deps)
        st   (uc/status deps)]
    (is (r/ok? st))
    (is (= 3 (-> st :ok :total)))
    (is (= 0 (-> st :ok :cursor)))
    (is (= 3 (-> st :ok :remaining)))
    (is (= :ids-listed (-> st :ok :phase)))))
