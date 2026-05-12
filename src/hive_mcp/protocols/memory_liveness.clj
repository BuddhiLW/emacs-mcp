(ns hive-mcp.protocols.memory-liveness
  "Cross-store resilience seam — `IMemoryStoreLiveness`.

   Lives in its own namespace (instead of `hive-mcp.protocols.memory`)
   so reloading or AOT-compiling this protocol does not disturb the
   16-method `IMemoryStore` interface that every store extends. Adding,
   editing or reloading liveness is mechanical; touching the core
   protocol's class identity invalidates every AOT-compiled store
   record (manifests as `satisfies? IMemoryStore` returning false even
   when the record's class still implements the host interface).

   ISP rationale: liveness/reconnect is orthogonal to data-plane (CRUD)
   AND to admin (collection lifecycle). Three single-responsibility
   methods keep the seam narrow — a future 'WebSocket store' or
   'in-memory test store' can implement it trivially via reify.

   Stores that don't extend this protocol fall through to a pass-through
   path in `hive-mcp.vectordb.resilience`: the layer catches the
   exception, logs it, and re-raises (no kick, no retry). Absence of an
   impl never breaks a working store.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; Reload-safety: same defonce-guarded `defprotocol` pattern as the rest
;;; of the protocol surface. See note in `hive-mcp.protocols.memory` for
;;; the reasoning (defprotocol is not idempotent — bare reload silently
;;; invalidates every defrecord extender compiled against the old
;;; interface). Even though this ns is much smaller than memory.clj, the
;;; same pitfall applies and the same fix is mechanical.

(defonce ^:private -iliveness-defined? (atom false))

(when (compare-and-set! -iliveness-defined? false true)
  (defprotocol IMemoryStoreLiveness
    "Optional capability: drives the MCP-side resilience layer's reconnect
     path. Implementors own the probe RPC and the heal loop; the resilience
     layer only orchestrates them."

    (-probe! [store]
      "Issue the cheapest possible round-trip RPC to verify reachability.
       Return truthy on success. Throw on transport-fatal failure (the
       resilience layer interprets a throw as 'still dead, keep retrying').
       MUST be idempotent and side-effect-free at the application level —
       a single read-shaped RPC is the only allowed side effect.")

    (-kick-reconnect! [store]
      "Idempotent: drop the dead client, invalidate any liveness cache,
       and signal the store's heal loop to (re)start. Returns nil. Safe
       to call multiple times; subsequent calls while a heal loop is
       running should no-op.")

    (-await-reconnect! [store budget-ms]
      "Block up to `budget-ms` for the heal loop to verify recovery via a
       probe round-trip. Return true if the store is alive at the end of
       the wait, false on timeout. MUST NOT throw — the resilience layer
       wants a clean boolean, never an exception.")))

(defn liveness-store?
  "Check if the store extends the resilience seam."
  [store]
  (satisfies? IMemoryStoreLiveness store))
