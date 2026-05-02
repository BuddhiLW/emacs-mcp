(ns hive-mcp.tools.kanban.migration-test
  "Tests for the kanban migration ns. Stub IMemoryStores under :default
   (source) and :kanban (target) round-trip a small fixture; verify
   asserts every source id lands in the target.

   Live qdrant is exercised separately during the cutover step — these
   tests stay protocol-pure so they run in any REPL without external
   deps."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.tools.kanban.migration :as mig]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Stub stores
;; =============================================================================

(defn- source-stub
  "Read-only-ish source: returns the configured fixture vec on
   query-entries, accepts get-entry. Records nothing."
  [entries]
  (let [by-id (into {} (map (juxt :id identity)) entries)]
    (reify proto/IMemoryStore
      (connect!       [_ _] {:success? true})
      (disconnect!    [_]   nil)
      (connected?     [_]   true)
      (health-check   [_]   {:healthy? true})
      (add-entry!     [_ e] {:success? true :id (:id e)})
      (get-entry      [_ id] (get by-id id))
      (update-entry!  [_ id _] {:success? true :id id})
      (delete-entry!  [_ id] {:success? true :id id})
      (query-entries  [_ _] entries)
      (search-similar [_ _ _] [])
      (supports-semantic-search? [_] false)
      (cleanup-expired! [_] {:cleaned 0})
      (entries-expiring-soon [_ _ _] [])
      (find-duplicate [_ _ _ _] nil)
      (store-status   [_] {:slot :source})
      (reset-store!   [_] nil))))

(defn- target-stub
  "Recording target: every add-entry! lands in `state` keyed by id, so
   tests can assert what got migrated and verify can round-trip."
  [state]
  (reify proto/IMemoryStore
    (connect!       [_ _] {:success? true})
    (disconnect!    [_]   nil)
    (connected?     [_]   true)
    (health-check   [_]   {:healthy? true})
    (add-entry!     [_ e]
      (swap! state assoc (:id e) e)
      {:success? true :id (:id e)})
    (get-entry      [_ id] (get @state id))
    (update-entry!  [_ id _] {:success? true :id id})
    (delete-entry!  [_ id] (swap! state dissoc id) {:success? true :id id})
    (query-entries  [_ _] (vec (vals @state)))
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] {:cleaned 0})
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status   [_] {:slot :target})
    (reset-store!   [_] (reset! state {}) nil)))

;; =============================================================================
;; Fixture
;; =============================================================================

(def ^:private fixture-entries
  "Three kanban shapes + one decoy non-kanban entry that shares the
   'kanban' tag — the predicate filter must reject the decoy."
  [{:id "k1" :type "note" :tags ["kanban" "todo"]
    :content {:task-type "kanban" :title "first"  :status "todo"  :priority "high"}}
   {:id "k2" :type "note" :tags ["kanban" "doing"]
    :content {:task-type "kanban" :title "second" :status "doing" :priority "medium"}}
   {:id "k3" :type "note" :tags ["kanban" "done"]
    :content {:task-type "kanban" :title "third"  :status "done"  :priority "low"}}
   ;; Decoy: tag-collision; content shape is NOT a kanban task.
   {:id "decoy" :type "note" :tags ["kanban" "research"]
    :content {:task-type "research" :title "fake" :body "just notes"}}])

(use-fixtures :each
  (fn [f]
    (let [snapshot (proto/registered-stores)]
      (proto/unregister-store! :default)
      (proto/unregister-store! :kanban)
      (try
        (f)
        (finally
          (proto/unregister-store! :default)
          (proto/unregister-store! :kanban)
          (doseq [[k store] snapshot]
            (when (#{:default :kanban} k)
              (proto/register-store! k store))))))))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest extract-rejects-tag-collision-decoys
  (proto/register-store! :default (source-stub fixture-entries))
  (let [extracted (mig/extract-kanban-from-default-store)]
    (is (= ["k1" "k2" "k3"] (mapv :id extracted))
        "predicate filter retains kanban-shape entries, drops decoy")))

(deftest dry-run-extracts-but-does-not-write
  (proto/register-store! :default (source-stub fixture-entries))
  (let [target-state (atom {})]
    (proto/register-store! :kanban (target-stub target-state))
    (let [result (mig/migrate-to-kanban! {:dry-run? true})]
      (is (true? (:dry-run? result)))
      (is (= 3 (:transformed result)) "decoy excluded from extract")
      (is (= 0 (:loaded-ok result)) "dry-run skips writes")
      (is (zero? (count @target-state)) "target untouched"))))

(deftest live-run-loads-every-extracted-entry
  (proto/register-store! :default (source-stub fixture-entries))
  (let [target-state (atom {})]
    (proto/register-store! :kanban (target-stub target-state))
    (let [result (mig/migrate-to-kanban! {:batch-size 2})]
      (is (= 3 (:loaded-ok result)) "every kanban-shape entry written")
      (is (zero? (:loaded-fail result)))
      (is (>= (:batches result) 2) "batched at size 2 with 3 entries"))
    (is (= #{"k1" "k2" "k3"} (set (keys @target-state))))))

(deftest verify-confirms-clean-migration
  (proto/register-store! :default (source-stub fixture-entries))
  (let [target-state (atom {})]
    (proto/register-store! :kanban (target-stub target-state))
    (mig/migrate-to-kanban!)
    (let [v (mig/verify {:sample-size 3})]
      (is (= 3 (:checked v)))
      (is (= 3 (:ok v)))
      (is (empty? (:missing v))))))

(deftest verify-flags-missing-when-target-incomplete
  (proto/register-store! :default (source-stub fixture-entries))
  (let [target-state (atom {"k1" {:id "k1"}})] ;; only k1 made it
    (proto/register-store! :kanban (target-stub target-state))
    (let [v (mig/verify {:sample-size 3})]
      (is (= 3 (:checked v)))
      (is (<= (:ok v) 1) "at most k1 round-trips")
      (is (>= (count (:missing v)) 2)))))

(deftest status-reports-both-slots
  (proto/register-store! :default (source-stub fixture-entries))
  (let [target-state (atom {})]
    (proto/register-store! :kanban (target-stub target-state))
    (let [s (mig/status)]
      (is (true? (:default-registered? s)))
      (is (true? (:kanban-registered?  s)))
      (is (= 3 (:source-count s)))
      (is (= ["k1" "k2" "k3"] (:sample-ids s))))))

(deftest status-reports-missing-target-cleanly
  (proto/register-store! :default (source-stub fixture-entries))
  (let [s (mig/status)]
    (is (true? (:default-registered? s)))
    (is (false? (:kanban-registered? s)))
    (is (= 3 (:source-count s)))))
