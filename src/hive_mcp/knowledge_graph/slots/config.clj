;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.config
  "ConfigBackendResolver — Strategy implementation of IBackendResolver.

   Resolves slot → backend via:
     1. config.edn  :services :kg :slots <slot> :backend
     2. Hardcoded default mapping (storage migration plan)
     3. Falls back to :datahike (legacy compatible)

   Pure with respect to the resolver instance — config IO is delegated to
   the injected `path-lookup` fn. Test code passes an in-memory
   `(constantly v)` or `(fn [path] (get-in m path))` to drive the
   resolver from a stub map without touching disk.

   Default mapping encodes decision 20260507133442-017631c2:
     :carto      → Datalevin (LMDB scalar EAV, no rename race, regenerable)
     :memory     → Datahike  (bitemporal — as-of/since/history)
     :sessions   → Datalevin (append-only timestamp idx)
     :default    → Datahike  (legacy global-store callers)

   STORAGE-2 phase 2 (2026-05-07): per-slot vector routing. The two
   new slots host the carto / memory IVecStore handles via the
   Proximum HNSW backend (replaces the qdrant/milvus addons for
   in-process vector search):
     :carto-vec  → Proximum (HNSW, branchable, time-travel)
     :memory-vec → Proximum (HNSW, branchable, time-travel)"
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.knowledge-graph.slots.protocol :as p]))

;; -----------------------------------------------------------------------------
;; Default slot → backend mapping (immutable canonical fact)
;; -----------------------------------------------------------------------------

(def ^:const default-slot->backend
  "Canonical mapping per the storage migration plan
   (decision 20260507133442-017631c2). Vector slots added in
   STORAGE-2 phase 2 — `:proximum` is the IVecStore backend keyword."
  {:carto      :datalevin
   :memory     :datahike
   :sessions   :datalevin
   :default    :datahike
   :carto-vec  :proximum
   :memory-vec :proximum})

(def ^:const fallback-backend
  "Final safety net when slot is unknown to both config + defaults."
  :datahike)

;; -----------------------------------------------------------------------------
;; Path lookup adapter — production reads :services :kg :slots from config.edn
;; -----------------------------------------------------------------------------

(defn- production-path-lookup
  "Read `path` from the live config.edn (mounted via config.core)."
  [path]
  (get-in (config/get-global-config) path))

;; -----------------------------------------------------------------------------
;; Per-slot recovery policy — ENGINE-L1.2b
;; -----------------------------------------------------------------------------
;;
;; Each slot's heal-and-open! policy reflects the data class it owns:
;;   - rebuildable (regenerable from another source) → aggressive heal
;;   - irreplaceable (user-authored, no upstream regen) → audit-only
;;   - append-only (replayable) → truncate-then-quarantine
;;
;; Operators override via config.edn:
;;   :services :kg :slots <slot> :recovery-policy {:strategy ... :max-attempts ...}
;; -----------------------------------------------------------------------------

(def ^:const default-slot->recovery-policy
  "Recovery policy per slot. `nil` means use the global heal-and-open!
   default (`:throw`, preserves pre-L1.2 semantics).

   :carto    — derived index, fully regenerable via `codebase-map scan`.
                Aggressive heal: truncate tail-zeroed corruption in place,
                quarantine on unhealable corruption, finally throw.
   :sessions — append-only timestamp index; truncate handles crash-tail.
   :memory   — user-authored knowledge, no upstream regen path. Audit
                only — alarm but never auto-mutate.
   :default  — legacy global store backing the hive-mcp datahike conn.
                Audit only — same reasoning as :memory."
  {:carto     {:strategy [:truncate :quarantine :throw] :max-attempts 3}
   :sessions  {:strategy [:truncate :throw]            :max-attempts 2}
   :memory    {:strategy :audit                         :max-attempts 1}
   :default   {:strategy :audit                         :max-attempts 1}})

(defn- read-config-recovery-policy
  "Look up :services :kg :slots <slot> :recovery-policy via the supplied
   path-lookup. Returns a policy map or nil."
  [path-lookup slot]
  (let [raw (path-lookup [:services :kg :slots slot :recovery-policy])]
    (when (map? raw)
      (cond-> raw
        ;; Tolerate string-coerced strategy keywords from config.edn
        (string? (:strategy raw))     (update :strategy keyword)
        (and (vector? (:strategy raw))
             (some string? (:strategy raw)))
        (update :strategy #(mapv (fn [s] (if (string? s) (keyword s) s)) %))))))

(defn resolve-recovery-policy
  "Resolve a slot's heal-and-open! policy via:
     1. config.edn :services :kg :slots <slot> :recovery-policy
     2. default-slot->recovery-policy
     3. nil (heal-and-open! applies its own default)

   Pure with respect to the resolver — caller injects path-lookup
   so tests can drive without disk IO."
  ([slot] (resolve-recovery-policy production-path-lookup slot))
  ([path-lookup slot]
   (or (read-config-recovery-policy path-lookup slot)
       (get default-slot->recovery-policy slot))))

;; -----------------------------------------------------------------------------
;; Resolution helpers (pure)
;; -----------------------------------------------------------------------------

(defn- coerce-keyword
  "Tolerate strings (config.edn parsed value) → keyword. nil-safe."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword v)
    :else        nil))

(defn- read-config-backend
  "Look up :services :kg :slots <slot> :backend via the supplied
   path-lookup. Returns a keyword or nil."
  [path-lookup slot]
  (coerce-keyword (path-lookup [:services :kg :slots slot :backend])))

(defn- resolve-via
  "Pure core: apply Strategy chain (config → defaults → fallback)."
  [path-lookup defaults slot]
  (or (read-config-backend path-lookup slot)
      (get defaults slot)
      fallback-backend))

;; -----------------------------------------------------------------------------
;; Strategy implementation
;; -----------------------------------------------------------------------------

(defrecord ConfigBackendResolver [path-lookup defaults]
  p/IBackendResolver
  (resolve-backend [_ slot] (resolve-via path-lookup defaults slot))
  (default-mapping [_]      defaults))

(defn ->resolver
  "Build a resolver. With no args, uses production config.edn lookup +
   canonical defaults. Test code injects a stub `path-lookup` (a fn of
   `path` → value) and/or override defaults map."
  ([]
   (->resolver production-path-lookup default-slot->backend))
  ([path-lookup]
   (->resolver path-lookup default-slot->backend))
  ([path-lookup defaults]
   (->ConfigBackendResolver path-lookup defaults)))
