(ns hive-mcp.protocols.lifecycle
  "Protocols for system lifecycle orchestration — shutdown, periodic sweep,
   per-entity resource ownership.

   hive-mcp core defines these abstractions only. Concrete implementations
   live in addon projects (hive-agent, hive-knowledge, hive-qdrant,
   hive-chroma-client, hive-proximum, lsp-mcp, hive-nats) and register
   themselves with hive-mcp.system.registry via requiring-resolve.

   This namespace MUST NOT require any addon ns.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IShutdownHook Protocol (Ordered Shutdown Participation)
;;; ============================================================================
;;;
;;; Reload-safety: `defprotocol` is NOT idempotent. Re-evaluating this form
;;; generates a fresh host interface class in the current classloader,
;;; silently invalidating every defrecord extender that was compiled against
;;; the previous interface. That shows up as `satisfies?` returning false
;;; and protocol dispatch failing with "No implementation of method ... for
;;; class: <record>" — the exact failure mode observed after L2 multi-store
;;; registry refactor when nREPL / addon load races caused the protocol ns
;;; to re-evaluate after addons had already compiled their stores.
;;;
;;; The `defonce`-guarded block below ensures `defprotocol` runs exactly
;;; once per JVM. On subsequent reloads of this namespace, the existing
;;; interface class and method Vars are preserved, so extenders compiled
;;; against them keep dispatching correctly.
;;;
;;; Priority bands (suggested, not enforced):
;;;    0-99   external service stop (WS, HTTP)
;;;  100-199  subprocess kill (lings, LSP)
;;;  200-299  client close (NATS, Chroma, Qdrant)
;;;  300-399  store close (Datalevin, Datahike, Proximum)
;;;  400+     final bookkeeping (session-end hooks, coordinator mark)

(defonce ^:private -ishutdownhook-defined? (atom false))

(when (compare-and-set! -ishutdownhook-defined? false true)
  (defprotocol IShutdownHook
    "Participation contract for ordered system shutdown.
     Implementations are registered with hive-mcp.system.registry and
     invoked in ascending `shutdown-priority` order during orchestrated
     stop. Lower priorities run first."

    (shutdown-priority [this]
      "Return an integer priority for shutdown ordering. Lower runs earlier.
       See priority-band guidance in the namespace docstring.")

    (shutdown-name [this]
      "Return a human-readable identifier for this hook, used in logs and
       shutdown reports (e.g. \"nats-client\", \"qdrant-conn\", \"lings-pool\").")

    (shutdown! [this ctx]
      "Perform the stop action. `ctx` is a map of the form
       {:reason kw :timeout-ms int} carrying the shutdown reason (e.g.
       :sigterm, :user-request, :test-teardown) and the per-hook timeout
       budget. Implementations must be idempotent and must not throw on
       double-shutdown — return gracefully if already stopped.")))

;;; ============================================================================
;;; ISweepable Protocol (Periodic Background Maintenance)
;;; ============================================================================
;;; Reload-safe: see note on IShutdownHook above.
;;;
;;; A sweep is a bounded, periodic maintenance pass — e.g. expiring stale
;;; cache entries, reclaiming zombie lings, pruning closed channels. The
;;; orchestrator schedules `sweep!` every `sweep-interval-s` seconds.

(defonce ^:private -isweepable-defined? (atom false))

(when (compare-and-set! -isweepable-defined? false true)
  (defprotocol ISweepable
    "Participation contract for periodic background maintenance.
     The orchestrator calls `sweep!` on each registered implementation at
     its declared cadence, collecting per-sweep metrics for observability."

    (sweep-interval-s [this]
      "Return the desired interval between sweeps, in seconds. The
       orchestrator treats this as a hint; actual scheduling may jitter
       to avoid thundering-herd across many sweepers.")

    (sweep-name [this]
      "Return a human-readable identifier for this sweeper, used in logs
       and metrics (e.g. \"entry-expiry\", \"ling-reaper\", \"channel-gc\").")

    (sweep! [this ctx]
      "Perform one sweep pass. `ctx` is a map of the form {:now-ms long}
       carrying the orchestrator's wall-clock reference for this tick.
       Returns a map {:swept int :errors seq} where :swept is the count
       of items processed and :errors is a (possibly empty) seq of
       per-item error descriptions. Implementations must not throw;
       unexpected failures should surface via :errors.")))

;;; ============================================================================
;;; IResourceOwner Protocol (Per-Entity Resource Ownership)
;;; ============================================================================
;;; Reload-safe: see note on IShutdownHook above.
;;;
;;; Some entities (e.g. an individual ling) own a cluster of resources —
;;; core.async channels, cached state, distributed claims — that must be
;;; released atomically when the entity is reaped, even outside a full
;;; system shutdown. IResourceOwner provides that per-entity contract.

(defonce ^:private -iresourceowner-defined? (atom false))

(when (compare-and-set! -iresourceowner-defined? false true)
  (defprotocol IResourceOwner
    "Per-entity resource ownership contract. Implementations represent a
     single logical entity (e.g. one ling, one session) and are responsible
     for releasing all resources they own when the entity is reaped."

    (owner-id [this]
      "Return the entity identifier this owner represents, typically a
       stable string (e.g. a ling-id, session-id). Used for logging and
       cross-referencing against the registry.")

    (owned-resources [this]
      "Return an inventory map describing currently-owned resources, for
       observability and debugging. Shape is implementation-specific but
       should be a flat map of resource-category -> count-or-identifier
       (e.g. {:channels 3 :claims 1 :cache-keys 17}). Must be cheap to
       call — no I/O.")

    (release-all! [this]
      "Release every resource owned by this entity: close channels,
       invalidate caches, release distributed claims, cancel timers.
       Must be idempotent — double-release is a no-op. Must not throw;
       partial failures should be logged and swallowed so downstream
       release paths still run.")))
