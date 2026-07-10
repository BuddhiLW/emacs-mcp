(ns hive-mcp.vectordb.kanban-facade-trifecta-test
  "Trifecta + integration tests for hive-mcp.vectordb.kanban-facade.

   Pure routing (`active-key`) gets the deftrifecta treatment: golden
   cases on the mode→slot table, totality property, mutation oracle.

   Mode-aware CRUD (`get-entry-by-id`, `query-entries`, `update-entry!`,
   `delete-entry!`) is stateful — exercised via stub IMemoryStores and
   `with-redefs` of the mode accessor in plain deftest blocks. These
   verify dual-read fallback, write fan-out, and best-effort mirror
   semantics."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [hive-mcp.config.core :as cfg-core]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.vectordb.kanban-facade :as kf]
            [hive-test.trifecta :refer [deftrifecta]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Stub stores — minimal IMemoryStore impls keyed on a tag so assertions
;; can prove which slot served a call.
;; =============================================================================

(defn- make-stub
  "Build a stub IMemoryStore tagged with `slot-name` so calls report
   their origin in the response payload. `query-fn` returns an entries
   vector (one per stub), letting tests prove the merge logic."
  [slot-name query-fn]
  (reify proto/IMemoryStore
    (connect!       [_ _] {:success? true})
    (disconnect!    [_] nil)
    (connected?     [_] true)
    (health-check   [_] {:healthy? true})
    (add-entry!     [_ e] {:success? true :id (:id e) :slot slot-name})
    (get-entry      [_ id] {:id id :slot slot-name})
    (update-entry!  [_ id _] {:success? true :id id :slot slot-name})
    (delete-entry!  [_ id] {:success? true :id id :slot slot-name})
    (query-entries  [_ _] (query-fn))
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] {:cleaned 0})
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status   [_] {:slot slot-name})
    (reset-store!   [_] nil)))

;; =============================================================================
;; Fixture — clean registry between tests, both slots
;; =============================================================================

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
;; Trifecta — pure routing (active-key)
;; =============================================================================

(defn- active-key-for [m]
  (with-redefs [cfg-core/get-kanban-store-mode (constantly m)]
    (kf/active-key)))

(deftrifecta active-key-routing
  active-key-for
  {:cases       {:legacy    :default
                 :read-only :kanban
                 :mirror    :dual-read}
   :xf          identity
   :apply?      false
   :gen         (gen/elements [:default :kanban :dual-read])
   :pred        #{:default :kanban}
   :num-tests   60
   :mutations   [["always-default"     (fn [_] :default)]
                 ["swap-kanban-default" (fn [m] (case m
                                                   :default :kanban
                                                   :kanban  :default
                                                   :dual-read :default))]]
   :golden-path "test/golden/kanban-facade-active-key.edn"})

;; =============================================================================
;; Integration — get-entry-by-id mode dispatch
;; =============================================================================

(defn- register-stubs!
  "Register both slots with deterministic query payloads.
   :default returns [{:id 'default-only'} {:id 'in-both'}]
   :kanban  returns [{:id 'kanban-only'}  {:id 'in-both'}]"
  []
  (proto/register-store! :default
    (make-stub :stub-default
               (fn [] [{:id "default-only" :slot :stub-default}
                       {:id "in-both"      :slot :stub-default}])))
  (proto/register-store! :kanban
    (make-stub :stub-kanban
               (fn [] [{:id "kanban-only" :slot :stub-kanban}
                       {:id "in-both"     :slot :stub-kanban}]))))

(deftest get-entry-by-id--default-mode-uses-default-slot
  (register-stubs!)
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :default)]
    (is (= :stub-default (:slot (kf/get-entry-by-id "x"))))))

(deftest get-entry-by-id--kanban-mode-uses-kanban-slot
  (register-stubs!)
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :kanban)]
    (is (= :stub-kanban (:slot (kf/get-entry-by-id "x"))))))

(deftest get-entry-by-id--dual-read-prefers-kanban-falls-back-to-default
  (register-stubs!)
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
    (is (= :stub-kanban (:slot (kf/get-entry-by-id "x")))))
  (testing "with :kanban absent, dual-read falls back to :default"
    (proto/unregister-store! :kanban)
    (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
      (is (= :stub-default (:slot (kf/get-entry-by-id "x")))))))

;; =============================================================================
;; Integration — query-entries merge semantics in :dual-read
;; =============================================================================

(deftest query-entries--dual-read-merges-and-dedupes-by-id
  (register-stubs!)
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
    (let [ids (mapv :id (kf/query-entries))]
      (is (= ["kanban-only" "in-both" "default-only"] ids)
          "kanban-first ordering, dedupe by :id keeps the kanban version"))))

(deftest query-entries--default-mode-only-default-slot
  (register-stubs!)
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :default)]
    (let [ids (set (map :id (kf/query-entries)))]
      (is (contains? ids "default-only"))
      (is (not (contains? ids "kanban-only"))))))

(deftest query-entries--kanban-mode-only-kanban-slot
  (register-stubs!)
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :kanban)]
    (let [ids (set (map :id (kf/query-entries)))]
      (is (contains? ids "kanban-only"))
      (is (not (contains? ids "default-only"))))))

;; =============================================================================
;; Integration — write fan-out in :dual-read
;; =============================================================================

(defn- recording-stub
  "Stub that appends every write op to `recorder` atom keyed by slot."
  [slot-name recorder]
  (reify proto/IMemoryStore
    (connect!       [_ _] {:success? true})
    (disconnect!    [_] nil)
    (connected?     [_] true)
    (health-check   [_] {:healthy? true})
    (add-entry!     [_ e]
      (swap! recorder update slot-name (fnil conj []) [:add (:id e)])
      {:success? true :id (:id e) :slot slot-name})
    (get-entry      [_ id] {:id id :slot slot-name})
    (update-entry!  [_ id u]
      (swap! recorder update slot-name (fnil conj []) [:update id u])
      {:success? true :id id :slot slot-name})
    (delete-entry!  [_ id]
      (swap! recorder update slot-name (fnil conj []) [:delete id])
      {:success? true :id id :slot slot-name})
    (query-entries  [_ _] [])
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] {:cleaned 0})
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status   [_] {:slot slot-name})
    (reset-store!   [_] nil)))

(deftest update-entry--dual-read-fans-out-to-both-slots
  (let [recorder (atom {})]
    (proto/register-store! :default (recording-stub :stub-default recorder))
    (proto/register-store! :kanban  (recording-stub :stub-kanban  recorder))
    (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
      (let [r (kf/update-entry! "task-1" {:tags ["kanban" "done"]})]
        (is (= :stub-kanban (:slot r)) "primary write returns :kanban")))
    (is (= [[:update "task-1" {:tags ["kanban" "done"]}]]
           (get @recorder :stub-kanban)))
    (is (= [[:update "task-1" {:tags ["kanban" "done"]}]]
           (get @recorder :stub-default))
        "mirror best-effort wrote to :default too")))

(deftest delete-entry--dual-read-fans-out
  (let [recorder (atom {})]
    (proto/register-store! :default (recording-stub :stub-default recorder))
    (proto/register-store! :kanban  (recording-stub :stub-kanban  recorder))
    (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
      (kf/delete-entry! "task-1"))
    (is (= [[:delete "task-1"]] (get @recorder :stub-kanban)))
    (is (= [[:delete "task-1"]] (get @recorder :stub-default)))))

(deftest update--dual-read-mirror-failure-does-not-break-primary
  (let [recorder (atom {})
        flaky-default (reify proto/IMemoryStore
                        (connect!       [_ _] {:success? true})
                        (disconnect!    [_] nil)
                        (connected?     [_] true)
                        (health-check   [_] {:healthy? false})
                        (add-entry!     [_ _] (throw (ex-info "milvus tailnet hop" {})))
                        (get-entry      [_ _] (throw (ex-info "milvus tailnet hop" {})))
                        (update-entry!  [_ _ _] (throw (ex-info "milvus tailnet hop" {})))
                        (delete-entry!  [_ _] (throw (ex-info "milvus tailnet hop" {})))
                        (query-entries  [_ _] [])
                        (search-similar [_ _ _] [])
                        (supports-semantic-search? [_] false)
                        (cleanup-expired! [_] {})
                        (entries-expiring-soon [_ _ _] [])
                        (find-duplicate [_ _ _ _] nil)
                        (store-status   [_] {})
                        (reset-store!   [_] nil))]
    (proto/register-store! :default flaky-default)
    (proto/register-store! :kanban  (recording-stub :stub-kanban recorder))
    (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
      (let [r (kf/update-entry! "task-1" {:tags ["kanban"]})]
        (is (= :stub-kanban (:slot r))
            "primary write succeeds even though :default mirror throws")))))

;; =============================================================================
;; Integration — boot validation
;; =============================================================================

(deftest validate-mode--kanban-without-slot-flags-not-ok
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :kanban)]
    (let [v (kf/validate-mode!)]
      (is (false? (:ok? v)))
      (is (string? (:warning v))))))

(deftest validate-mode--dual-read-without-slot-warns-but-ok
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :dual-read)]
    (let [v (kf/validate-mode!)]
      (is (true? (:ok? v)))
      (is (string? (:warning v))))))

(deftest validate-mode--default-mode-never-warns
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :default)]
    (let [v (kf/validate-mode!)]
      (is (true? (:ok? v)))
      (is (nil? (:warning v))))))

(deftest validate-mode--kanban-with-slot-registered-is-ok
  (proto/register-store! :kanban (make-stub :stub-kanban (constantly [])))
  (with-redefs [cfg-core/get-kanban-store-mode (constantly :kanban)]
    (let [v (kf/validate-mode!)]
      (is (true? (:ok? v)))
      (is (nil? (:warning v))))))
