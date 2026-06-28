;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.protocols.vec
  "IVecStore — role-sized port for vector index storage backends.

   Mirrors the IKGStore lifecycle (ensure-conn! / close!) so a vector
   slot can travel through the same SlotRegistry plumbing as a KG slot,
   but exposes the vector-specific verbs (upsert!, search, delete!,
   count, store-status) so vector callers don't depend on Datalog.

   The protocol is deliberately small (ISP): each method is one
   capability the carto / memory vector slot needs and nothing else.
   Concrete implementations (ProximumVecStore, hypothetical
   QdrantVecStore) live under `hive-mcp.knowledge-graph.store.*` and
   are wired in via `hive-mcp.knowledge-graph.slots.factory`.

   The :store-status method returns a snapshot (closed sum / map) for
   diagnostics — slots/health & catchup surface this without leaking
   backend internals.

   `count` is exposed as the protocol method `count` deliberately —
   the namespace excludes `clojure.core/count` so callers `(prox/count
   store)` to align with the IVecStore vocabulary."
  (:refer-clojure :exclude [count]))

;; -----------------------------------------------------------------------------
;; IVecStore — minimum viable vector-store surface
;; -----------------------------------------------------------------------------

(defprotocol IVecStore
  "Role-sized vector store port. Implementations must be safe to call
   from multiple threads — the slot registry caches a single handle
   per slot and shares it across the swarm."

  (ensure-conn! [this]
    "Open / initialize the underlying vector index. Idempotent. MUST
     not throw on the second call. Returns the live handle (impl-defined)
     for chaining; callers SHOULD treat the return as opaque.")

  (upsert! [this id ^floats vector]
           [this id ^floats vector metadata]
    "Insert (or replace) `vector` under external key `id`. Returns
     `this` so callers may thread successive upserts. `metadata` is an
     optional map attached to the vector for filtered search /
     downstream pulls.")

  (search [this ^floats query k]
          [this ^floats query k opts]
    "k-NN search: return a seq of `{:id :distance ...}` maps ordered by
     ascending distance. `opts` may include impl-specific tuning (e.g.
     `:ef` for HNSW beam width). Empty seq when index is empty.")

  (delete! [this id]
    "Remove the vector keyed by external `id`. Returns `this`. No-op when
     the id is absent (must NOT throw).")

  (count [this]
    "Return the number of live vectors in the index. Excludes tombstones
     when the backend supports compaction. 0 for an empty / freshly
     opened store.")

  (close! [this]
    "Release native resources / file handles. Idempotent.
     NON-DESTRUCTIVE: must NOT delete on-disk state — see AXIOM
     'Never NUKE Data — Destruction Requires Explicit, Loud, Guarded Consent'.")

  (store-status [this]
    "Return a diagnostic snapshot — `{:backend :proximum :path \"...\"
     :count N :open? true|false}` (impl-defined keys). Used by health
     surfaces; MUST be cheap (no full scans)."))

;; -----------------------------------------------------------------------------
;; Predicates — let callers gate on capability without reflection
;; -----------------------------------------------------------------------------

(defn vec-store?
  "True when `x` satisfies IVecStore. Safe on `nil`."
  [x]
  (and (some? x) (satisfies? IVecStore x)))
