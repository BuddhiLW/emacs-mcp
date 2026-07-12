(ns hive-mcp.knowledge-graph.store.fixtures
  "Shared test fixtures for dual-backend KG testing.

   Thin, backward-compatible facade over the disposable test-store harness
   (hive-mcp.knowledge-graph.store.harness). Every fixture here delegates to a
   `harness/with-disposable-store` composition of a StoreFactory (creates +
   disposes an ephemeral store) and an IsolationStrategy (thread-local for the
   in-memory DataScript backend, global save/restore for the persistent
   Datalevin/Datahike backends). The public names, arities, and dynamic-var
   contract are preserved:

     - datascript-fixture / datalevin-fixture / datahike-fixture  [f]
     - backend-fixture                                            [backend]
     - dual-backend-fixture                                       [f]
     - *current-backend*                                          dynamic var

   Usage in test ns:
     (use-fixtures :each (fixtures/backend-fixture :datascript))
     ;; or for dual-backend:
     (use-fixtures :each fixtures/dual-backend-fixture)"
  (:require [hive-mcp.knowledge-graph.store.harness :as harness]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Backend-Specific Fixtures (thin delegations to the harness)
;; =============================================================================

(defn datascript-fixture
  "Fixture that runs test f against a per-thread fresh DataScript store.

   Thread-local isolation: binds `connection.store/*test-store*` so the
   override-aware ensure-store! returns the test store, leaving the global
   proto store untouched. Also binds `connection/*sync-writes*` true so
   transact! writes synchronously on the calling thread instead of routing
   through the async coalescing writer (a pool thread that does not inherit
   *test-store*), guaranteeing deterministic read-after-write."
  [f]
  (harness/datascript-store-fixture f))

(defn datalevin-fixture
  "Fixture that runs test f with a fresh Datalevin store in a temp dir.
   Global save/restore isolation; cleans up the temp directory after the test.
   Skipped when Datalevin is unavailable."
  [f]
  (harness/datalevin-store-fixture f))

(defn datahike-fixture
  "Fixture that runs test f with a fresh Datahike store in a temp dir.
   Global save/restore isolation; cleans up the temp directory after the test.
   Skipped when Datahike is unavailable."
  [f]
  (harness/datahike-store-fixture f))

(defn global-datascript-fixture
  "Fixture that runs test f against a fresh in-memory DataScript store installed
   as the GLOBAL store (proto/set-store!) with the shared writer reset around
   the body and the prior store restored on teardown. Use for tests that
   exercise the async coalescing writer or spawn threads (which cannot see a
   thread-local *test-store*). Pass :sync-writes? true for deterministic
   read-after-write on the calling thread. See convention 20260629150125-3a07e787."
  [f & {:keys [sync-writes?] :or {sync-writes? false}}]
  (harness/global-datascript-store-fixture f :sync-writes? sync-writes?))

;; =============================================================================
;; Dual-Backend Fixture
;; =============================================================================

(def ^:dynamic *current-backend*
  "Currently active backend for test reporting.
   Bound during dual-backend test execution."
  :datascript)

(defn backend-fixture
  "Returns a fixture for a specific backend.
   backend - :datascript, :datalevin, or :datahike"
  [backend]
  (case backend
    :datascript datascript-fixture
    :datalevin datalevin-fixture
    :datahike datahike-fixture))

(defn dual-backend-fixture
  "Fixture that runs each test against BOTH backends.
   DataScript runs first (fast, in-memory), then Datalevin (temp dir).
   Test failures report which backend failed."
  [f]
  ;; Run with DataScript
  (binding [*current-backend* :datascript]
    (datascript-fixture f))
  ;; Run with Datalevin
  (binding [*current-backend* :datalevin]
    (datalevin-fixture f)))
