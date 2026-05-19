;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots
  "Public facade for slot-aware KG storage routing — Storage Migration Phase 1.

   Composes the role-sized ports defined in `slots.protocol`:

     ConfigBackendResolver  →  IBackendResolver  (slot → backend)
     LateBoundFactory       →  IBackendFactory   (backend → IKGStore)
     AtomBackedRegistry     →  ISlotRegistry     (slot → IKGStore handle)

   Callers depend on this facade and the IKGStore-shaped fns
   (`transact!`, `query`, `entity`, …) — never on the concrete records.
   That's what gives us LSP across backends (Datalevin, Datahike, …) and
   OCP for new resolution policies / new backends.

   Slot mapping (canonical defaults — see `slots.config`):
     :carto    → Datalevin (LMDB, no .ksv.new rename race)
     :memory   → Datahike  (bitemporal — as-of/since/history)
     :sessions → Datalevin (append-only timestamp idx)
     :default  → Datahike  (legacy global-store callers)

   Test fixtures override per slot via `with-slot-store`."
  (:require [hive-dsl.adt :as adt]
            [hive-dsl.result :as r]
            [hive-mcp.protocols.kg :as pkg]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [hive-mcp.knowledge-graph.slots.config :as slot-cfg]
            [hive-mcp.knowledge-graph.slots.factory :as slot-fact]
            [hive-mcp.knowledge-graph.slots.registry :as slot-reg]
            [taoensso.timbre :as log]))

;; -----------------------------------------------------------------------------
;; Default registry — built once, lazily
;; -----------------------------------------------------------------------------

(defn- build-default-registry
  []
  (slot-reg/->registry (slot-cfg/->resolver) (slot-fact/->factory)))

(defonce ^:private default-registry (atom nil))

(defn registry
  "Return the default registry. Lazy-init on first access; tests inject a
   custom registry via `with-registry`."
  []
  (or @default-registry
      (swap! default-registry (fn [r] (or r (build-default-registry))))))

(defn reset-registry!
  "Drop the cached default registry — next call to `registry` rebuilds.
   Required after REPL `:reload-all` of slots.protocol because record
   instances bound to the OLD protocol class no longer satisfy the
   reloaded one (AXIOM hive-hot defrecord protocol class-identity).
   Non-destructive: closes any cached slot stores first."
  []
  (when-let [reg @default-registry]
    (try (p/close-all! reg) (catch Throwable _ nil)))
  (reset! default-registry nil))

(def ^:dynamic *registry-override* nil)

(defmacro with-registry
  "Bind `reg` as the active registry within `body`. Test fixtures use this
   to swap in an in-memory ISlotRegistry."
  [reg & body]
  `(binding [*registry-override* ~reg]
     ~@body))

(defn- active-registry
  []
  (or *registry-override* (registry)))

;; -----------------------------------------------------------------------------
;; Per-slot store override (test fixtures) — Decorator over the registry
;; -----------------------------------------------------------------------------

(def ^:dynamic *slot-overrides* {})

(defmacro with-slot-store
  "Bind `store` as the active store for `slot` within `body`. The override
   wins over registry lookup but does NOT mutate the underlying registry."
  [slot store & body]
  `(binding [*slot-overrides* (assoc *slot-overrides* ~slot ~store)]
     ~@body))

;; -----------------------------------------------------------------------------
;; Resolution — fixture override > registry
;; -----------------------------------------------------------------------------

(defn store
  "Return the IKGStore for `slot`. Fixture override wins, otherwise lazy-init
   via the active registry."
  [slot]
  (or (get *slot-overrides* slot)
      (p/slot-store (active-registry) slot)))

(defn describe-slot
  "Return the SlotInit ADT for `slot` — useful for diagnostics / logging."
  [slot]
  (p/describe-slot (active-registry) slot))

(defn registered-slots
  "Slots currently cached in the registry."
  []
  (p/registered (active-registry)))

(defn close-slot!
  "Close the cached store for `slot`. Non-destructive on disk."
  [slot]
  (p/close-slot! (active-registry) slot))

(defn close-all!
  "Close every cached slot store. Used at shutdown / test teardown."
  []
  (p/close-all! (active-registry)))

;; -----------------------------------------------------------------------------
;; IKGStore-shaped facade — every fn delegates through `store`
;; -----------------------------------------------------------------------------

(defn transact!
  "Transact `tx-data` into the store backing `slot`."
  [slot tx-data]
  (pkg/transact! (store slot) tx-data))

(defn query
  ([slot q]
   (pkg/query (store slot) q))
  ([slot q inputs]
   (pkg/query (store slot) q inputs)))

(defn entity
  [slot eid]
  (pkg/entity (store slot) eid))

(defn entid
  [slot lookup-ref]
  (pkg/entid (store slot) lookup-ref))

(defn pull-entity
  [slot pattern eid]
  (pkg/pull-entity (store slot) pattern eid))

(defn eids-by-attr
  [slot attr]
  (pkg/eids-by-attr (store slot) attr))

(defn db-snapshot
  [slot]
  (pkg/db-snapshot (store slot)))

;; -----------------------------------------------------------------------------
;; Boot-time heal sweep — ENGINE-L1.2b
;; -----------------------------------------------------------------------------
;;
;; Probe each configured slot via `describe-slot` at boot. Each slot's
;; per-slot recovery-policy fires inside the factory call, so a
;; healable txn-log tail gets truncated before any reader sees an
;; opening throw. The sweep surfaces a `:storage/heal-report` summary
;; so catchup/operator dashboards know which slots are currently
;; degraded.
;;
;; This is NOT a hot path. Call once at process init (after config is
;; mounted, before MCP tools start serving). Subsequent slot reads
;; remain lazy via `slot-store`.
;; -----------------------------------------------------------------------------

(defn- emit-heal-report!
  "Best-effort dispatch into the event bus. Late-resolves so this ns
   stays free of compile-time event deps."
  [payload]
  (r/rescue nil
    (when-let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
      (dispatch [:storage/heal-report payload]))))

(defn- slot-status
  "Describe a single slot and return a status entry — `:ok` /
   `:missing-backend` / `:factory-failed`."
  [reg slot]
  (let [init    (p/describe-slot reg slot)
        variant (adt/adt-variant init)]
    (case variant
      :slot/ok               {:slot slot :status :ok :backend (:backend init)}
      :slot/missing-backend  {:slot slot :status :missing-backend}
      :slot/factory-failed   {:slot slot :status :factory-failed
                              :backend (:backend init)
                              :reason (:reason init)}
      {:slot slot :status :unknown-variant :variant variant})))

(defn heal-sweep!
  "Probe `slots` (default: every slot named in
   `slot-cfg/default-slot->backend`) at boot. Each slot's per-slot
   recovery-policy fires inside the factory, so a healable txn-log
   tail gets truncated as a side effect. Returns a report map:

     {:slots         [{:slot :backend :status [:reason]} ...]
      :degraded-slots [<slot-kw> ...]
      :ok-count      n
      :degraded-count n}

   Emits a `:storage/heal-report` event with the same payload so
   catchup / operator dashboards can surface degraded slots without
   trawling logs."
  ([] (heal-sweep! (keys slot-cfg/default-slot->backend)))
  ([slots]
   (let [reg     (active-registry)
         entries (mapv #(slot-status reg %) slots)
         degraded (filterv #(not= :ok (:status %)) entries)
         report   {:slots          entries
                   :degraded-slots (mapv :slot degraded)
                   :ok-count       (- (count entries) (count degraded))
                   :degraded-count (count degraded)}]
     (if (seq degraded)
       (log/warn "[storage/heal-sweep] Degraded slots after boot probe"
                 {:degraded degraded})
       (log/info "[storage/heal-sweep] All slots opened cleanly"
                 {:slots (mapv :slot entries)}))
     (emit-heal-report! report)
     report)))
