(ns hive-mcp.knowledge-graph.store.harness
  "Disposable, prod-safe test-store harness for KG connection tests.

   Two ports and one stratified combinator (SOLID / DDD / Stratified Design):

     StoreFactory      — SRP/OCP port that CREATES an ephemeral store per
                         backend and knows how to DISPOSE it. One impl per
                         backend:
                           DatascriptStoreFactory — in-memory, nothing on disk.
                           TempDirStoreFactory    — Datalevin / Datahike in a
                             private directory under java.io.tmpdir, recursively
                             deleted on dispose. The two persistent backends
                             differ only by data (require-ns / create-sym /
                             dir-prefix), so they share ONE record (OCP).

     IsolationStrategy — port that INSTALLS the ephemeral store so the KG
                         connection layer resolves it for the duration of one
                         test, then ALWAYS uninstalls it. Two impls:
                           ThreadLocalIsolation       — binds
                             connection.store/*test-store* (per-thread override
                             consulted first by ensure-store!) plus optional
                             connection/*sync-writes*. SAFE: the process-global
                             store slot is never touched. Only usable when every
                             read/write happens on the calling thread.
                           GlobalSaveRestoreIsolation — captures the prior
                             global store, set-store!s the ephemeral one, stops
                             the shared coalescing writer, and ALWAYS restores
                             the prior store in a finally. The ONLY option for
                             tests that exercise the async writer go-loop, whose
                             pool thread (and any raw spawned Thread) cannot see
                             a thread-local *test-store*.

     with-disposable-store — stratified combinator that composes factory +
                             strategy + GUARANTEED dispose, and owns the one
                             cross-cutting store-swap hygiene step (edge-stats
                             cache reset around the body).

   Safety invariant. No code path here mutates the process-global store slot
   without first capturing and later restoring the prior value in a finally,
   and every ephemeral store is either in-memory or created under
   java.io.tmpdir and disposed in a finally. Tests therefore can never open
   the production Datahike/Datalevin database, never leave the global store
   slot clobbered, and never let a stale writer go-loop write into a store
   that is being torn down. Honors axiom 20260122235103-7151cc29 (Test
   Isolation Silent Server Death) and convention 20260629150125-3a07e787
   (global-store isolation for async-writer tests)."
  (:require [clojure.java.io :as io]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.connection.store :as cstore]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Ephemeral store value object
;; =============================================================================

(defrecord EphemeralStore [store temp-dir])
;; store    — a connection-ready IKGStore
;; temp-dir — java.io.File backing a persistent store, or nil for in-memory

(defn- delete-tree!
  "Recursively delete a directory tree. Nil / absent dirs are ignored.
   Children are deleted before parents (reverse file-seq)."
  [^java.io.File dir]
  (when (and dir (.exists dir))
    (doseq [^java.io.File f (reverse (file-seq dir))]
      (.delete f))))

;; =============================================================================
;; Port 1 — StoreFactory: create + dispose an ephemeral store (one per backend)
;; =============================================================================

(defprotocol StoreFactory
  "Creates and disposes one ephemeral, throwaway KG store. One impl per
   backend (SRP/OCP). Knows nothing about how the store is INSTALLED — that is
   the IsolationStrategy's concern."
  (create [factory]
    "Acquire a fresh ephemeral store with its connection opened. Returns an
     EphemeralStore. In-memory backends carry a nil :temp-dir; persistent
     backends allocate a private directory under java.io.tmpdir.")
  (dispose! [factory ephemeral]
    "Release everything `create` acquired for `ephemeral`: close the
     connection and, for persistent backends, recursively delete the temp
     directory. Exception-safe; call exactly once per `create`."))

(defrecord DatascriptStoreFactory []
  StoreFactory
  (create [_]
    (let [store (ds-store/create-store)]
      (proto/ensure-conn! store)
      (->EphemeralStore store nil)))
  (dispose! [_ ephemeral]
    ;; DataScript is in-memory; reset-conn! drops the ephemeral conn. close!
    ;; is a no-op for DataScript, so a fresh empty conn is the disposal.
    (proto/reset-conn! (:store ephemeral))))

(defrecord TempDirStoreFactory [require-ns create-sym dir-prefix]
  StoreFactory
  (create [_]
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str dir-prefix (System/nanoTime)))
          db-path (.getAbsolutePath tmp-dir)]
      ;; Load the backend lazily: a machine lacking it should skip the test
      ;; (see skip-if-unavailable), not fail to compile the harness.
      (require require-ns)
      (let [create-fn (resolve create-sym)
            store     (create-fn {:db-path db-path})]
        (proto/ensure-conn! store)
        (->EphemeralStore store tmp-dir))))
  (dispose! [_ ephemeral]
    (let [{:keys [store temp-dir]} ephemeral]
      (when store
        ;; Guard close! so a flush hiccup never masks a real test failure.
        (try (proto/close! store) (catch Exception _ nil)))
      (delete-tree! temp-dir))))

(defn datascript-factory
  "StoreFactory for a fresh in-memory DataScript store."
  []
  (->DatascriptStoreFactory))

(defn datalevin-factory
  "StoreFactory for a fresh Datalevin store in a private temp dir."
  []
  (->TempDirStoreFactory 'hive-mcp.knowledge-graph.store.datalevin
                         'hive-mcp.knowledge-graph.store.datalevin/create-store
                         "hive-kg-test-"))

(defn datahike-factory
  "StoreFactory for a fresh Datahike store in a private temp dir."
  []
  (->TempDirStoreFactory 'hive-mcp.knowledge-graph.store.datahike
                         'hive-mcp.knowledge-graph.store.datahike/create-store
                         "hive-kg-datahike-test-"))

;; =============================================================================
;; Port 2 — IsolationStrategy: install / uninstall the ephemeral store
;; =============================================================================

(defprotocol IsolationStrategy
  "Installs an ephemeral store so the KG connection layer resolves it for the
   duration of one test, then ALWAYS uninstalls it. SRP: knows only about
   install/uninstall mechanics, never about how the store was built."
  (with-installed [strategy store thunk]
    "Install `store`, invoke `(thunk)`, and uninstall in a finally — even if
     `thunk` throws. Returns whatever `thunk` returns."))

(defrecord ThreadLocalIsolation [sync-writes?]
  IsolationStrategy
  (with-installed [_ store thunk]
    ;; Per-thread override consulted first by ensure-store!; the process-global
    ;; store slot is NEVER touched. When sync-writes? is set, transact! runs on
    ;; the calling thread so it honors the override (a writer pool thread would
    ;; not inherit these thread-local bindings). with-bindings* pops on return.
    (with-bindings*
      (cond-> {#'cstore/*test-store* store}
        sync-writes? (assoc #'conn/*sync-writes* true))
      thunk)))

(defrecord GlobalSaveRestoreIsolation [sync-writes?]
  IsolationStrategy
  (with-installed [_ store thunk]
    (let [prior (when (proto/store-set?) (proto/get-store))
          run   (if sync-writes?
                  (fn [] (with-bindings* {#'conn/*sync-writes* true} thunk))
                  thunk)]
      (proto/set-store! store)
      ;; Reset the shared coalescing writer: its go-loop is a process-wide
      ;; defonce that may still reference a prior store. Stopping it here and at
      ;; teardown guarantees no stale go-loop write lands in this store nor in a
      ;; store we are about to close/restore.
      (conn/stop-writer!)
      (try
        (run)
        (finally
          (conn/stop-writer!)
          (if prior
            (proto/set-store! prior)
            (proto/clear-store!)))))))

(defn thread-local-isolation
  "Thread-local IsolationStrategy. Defaults to sync-writes? true (deterministic
   read-after-write on the calling thread — the DataScript backend fixture).
   Pass :sync-writes? false for the bare *test-store* override used by the
   composable :kg-conn isolation."
  [& {:keys [sync-writes?] :or {sync-writes? true}}]
  (->ThreadLocalIsolation sync-writes?))

(defn global-isolation
  "Global save/restore IsolationStrategy. Defaults to sync-writes? false so the
   test exercises the real async coalescing writer (writer-fixture, Datalevin,
   Datahike). Pass :sync-writes? true for sync-on-a-global-store tests that
   read immediately after transact! (isolated-store-fixture)."
  [& {:keys [sync-writes?] :or {sync-writes? false}}]
  (->GlobalSaveRestoreIsolation sync-writes?))

;; =============================================================================
;; Stratified combinator — factory + strategy + guaranteed dispose
;; =============================================================================

(defn with-disposable-store
  "Run `(f)` against a fresh ephemeral store built by `factory`, installed via
   `strategy`, with disposal GUARANTEED in a finally.

   Cross-cutting store-swap hygiene (edge-stats cache reset around the body)
   lives here so neither the factory nor the strategy has to know about it —
   swapping the active store invalidates any memoized edge stats."
  [factory strategy f]
  (let [ephemeral (create factory)]
    (try
      (with-installed strategy (:store ephemeral)
        (fn []
          (edges/reset-stats-cache!)
          (try
            (f)
            (finally
              (edges/reset-stats-cache!)))))
      (finally
        (dispose! factory ephemeral)))))

(defn skip-if-unavailable
  "Run `(thunk)`, but if the OPTIONAL backend named `label` cannot be loaded or
   set up on this machine, print a skip notice and treat the test as a no-op
   instead of failing — preserving the historical Datalevin/Datahike fixture
   policy. clojure.test already catches assertion errors inside the test var,
   so in the common `use-fixtures` path only backend setup/teardown escapes
   here; the guard exists for machines missing the optional dependency."
  [label thunk]
  (try
    (thunk)
    (catch Exception e
      (println (str label " fixture failed, skipping:") (.getMessage e)))))

;; =============================================================================
;; Ready-made per-backend fixtures (thin composition of the pieces above)
;; =============================================================================

(defn datascript-store-fixture
  "Thread-local, synchronous ephemeral DataScript store."
  [f]
  (with-disposable-store (datascript-factory)
                         (thread-local-isolation :sync-writes? true)
                         f))

(defn global-datascript-store-fixture
  "Global save/restore ephemeral in-memory DataScript store: installs a fresh
   store as the process-global store (proto/set-store!) so the async writer
   go-loop and any raw spawned threads resolve the SAME instance — the ONLY
   safe choice for tests that exercise the real coalescing queue or spawn
   threads, which cannot see a thread-local *test-store*. Stops the shared
   writer around the body and always restores the prior store. Pass
   :sync-writes? true for deterministic read-after-write on the calling thread.
   See convention 20260629150125-3a07e787."
  [f & {:keys [sync-writes?] :or {sync-writes? false}}]
  (with-disposable-store (datascript-factory)
                         (global-isolation :sync-writes? sync-writes?)
                         f))

(defn datalevin-store-fixture
  "Global save/restore ephemeral Datalevin store in a temp dir; skipped when
   Datalevin is unavailable."
  [f]
  (skip-if-unavailable
   "Datalevin"
   (fn [] (with-disposable-store (datalevin-factory) (global-isolation) f))))

(defn datahike-store-fixture
  "Global save/restore ephemeral Datahike store in a temp dir; skipped when
   Datahike is unavailable."
  [f]
  (skip-if-unavailable
   "Datahike"
   (fn [] (with-disposable-store (datahike-factory) (global-isolation) f))))
