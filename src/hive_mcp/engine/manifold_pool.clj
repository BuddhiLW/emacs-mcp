(ns hive-mcp.engine.manifold-pool
  "Bounded `manifold.executor/wait-pool` (ENGINE-L0.5).

   Defense-in-depth layer 0: cap the JVM-wide manifold wait pool so a
   retry storm can't grow it without bound and exhaust the heap. The
   2026-05-11 OOM cascade ended in `manifold-pool-3-{35,36,...}`
   threads — those are dirigiste-backed wait-pool workers, default
   ceiling is `Integer/MAX_VALUE`.

   With L1.1 (KG-nil circuit breaker) and L1.2 (txlog self-heal) in
   place the storm can no longer originate from the slot factory, but
   the wait-pool is still unbounded for every other manifold consumer
   (websocket transports, a2a gateway, olympus stream). This module is
   the last-line cap.

   Boot flow:
   - `server.core/start!` calls `(boot!)` before `ig/init` (same slot
     as `hprof/boot!`).
   - `boot!` reads `HIVE_MANIFOLD_WAIT_POOL_MAX` (env) /
     `hive.manifold.wait-pool-max` (sys prop); falls back to
     `default-max-threads`.
   - It `alter-var-root`'s `manifold.executor/wait-pool` to return a
     bounded `utilization-executor`. Must run before any deferred
     blocks on `wait-pool` (the original is delay-cached behind a
     private promise, so once realized the manifold-internal reference
     is locked).

   This is intentionally a *fn-level swap*, not a registry rewrite.
   In-flight deferreds holding the original pool keep using it; new
   `(wait-pool)` callers — which is how every fresh deferred lookup
   gets the executor — see the bounded one.

   Safe to call once; idempotent guard via `*installed*`."
  (:require [manifold.executor :as ex]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Policy
;; -----------------------------------------------------------------------------

(def ^:const default-target-utilization
  "Mirror manifold's stock value so dirigiste's adaptive sizing keeps
   behaving as upstream documented; we only change the ceiling."
  0.95)

(defn default-max-threads
  "Compute a reasonable thread ceiling from `availableProcessors`.
   `max(64, 4×CPU)` keeps small workstations from being starved while
   capping busy boxes well short of the original `Integer/MAX_VALUE`."
  []
  (let [cpus (.availableProcessors (Runtime/getRuntime))]
    (max 64 (* 4 cpus))))

(defn- read-int [s]
  (try (when s (Long/parseLong (str s))) (catch Throwable _ nil)))

(defn resolve-max-threads
  "Resolution order:
     1. explicit `:max-threads` in opts
     2. `HIVE_MANIFOLD_WAIT_POOL_MAX` env
     3. `hive.manifold.wait-pool-max` system property
     4. `default-max-threads`
   Non-positive values are ignored (caller meant 'leave it alone' or
   typo'd) — we fall through to the next source."
  [{:keys [max-threads]}]
  (let [candidates [max-threads
                    (read-int (System/getenv "HIVE_MANIFOLD_WAIT_POOL_MAX"))
                    (read-int (System/getProperty "hive.manifold.wait-pool-max"))]]
    (or (some #(when (and % (pos? %)) %) candidates)
        (default-max-threads))))

;; -----------------------------------------------------------------------------
;; Install state — idempotent
;; -----------------------------------------------------------------------------

(defonce ^:private *installed
  ;; Holds the bounded pool once installed, or nil if untouched.
  ;; A second `boot!` is a no-op rather than a re-install (re-installing
  ;; would orphan the previous pool's in-flight tasks).
  (atom nil))

(defn installed?
  "Diagnostic: has the bounded wait-pool been installed in this JVM?"
  []
  (some? @*installed))

(defn snapshot
  "Return the currently-installed bounded pool, or nil if `boot!` hasn't
   run yet. Useful for tests and ops drilldowns."
  []
  @*installed)

;; -----------------------------------------------------------------------------
;; Boot
;; -----------------------------------------------------------------------------

(defn- build-bounded-pool [max-threads target-utilization]
  (ex/utilization-executor target-utilization max-threads
                           {:thread-factory
                            (ex/thread-factory
                              (let [cnt (atom 0)]
                                #(str "hive-manifold-wait-" (swap! cnt inc)))
                              (deliver (promise) nil))}))

(defn boot!
  "Install a bounded wait-pool in `manifold.executor`. Returns the
   bounded pool on first call, nil on subsequent calls (idempotent —
   re-installing would orphan in-flight workers).

   `opts`:
     :max-threads         positive int — overrides env/prop/default
     :target-utilization  0<u≤1       — defaults to `default-target-utilization`"
  ([] (boot! {}))
  ([opts]
   (if-let [existing @*installed]
     (do (log/debug "[manifold-pool] boot! no-op — pool already installed")
         nil)
     (let [max-threads        (resolve-max-threads opts)
           target-utilization (or (:target-utilization opts) default-target-utilization)
           bounded            (build-bounded-pool max-threads target-utilization)]
       (alter-var-root #'ex/wait-pool (constantly (fn [] bounded)))
       (reset! *installed bounded)
       (log/info "[manifold-pool] Bounded manifold wait-pool installed"
                 {:max-threads max-threads
                  :target-utilization target-utilization})
       bounded))))
