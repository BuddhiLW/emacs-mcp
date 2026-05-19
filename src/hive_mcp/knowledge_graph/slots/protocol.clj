;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.protocol
  "Role-sized ports for slot-aware KG storage routing — DIP boundary.

   Three independent capabilities, three protocols (ISP):

     IBackendResolver  → slot → backend keyword  (Strategy)
     IBackendFactory   → backend → IKGStore      (Factory Method)
     ISlotRegistry     → slot → IKGStore handle  (Registry + lifecycle)

   The public facade (`slots`) composes the three. Concrete implementations
   live in `.config`, `.factory`, `.registry` so adding a new resolution
   policy or factory variant is a new module, not a modification of
   existing ones (OCP).

   Init outcome is a closed sum (defadt) — callers `adt-case` and the
   compiler enforces exhaustiveness when variants are added."
  (:require [hive-dsl.adt :refer [defadt]]))

;; -----------------------------------------------------------------------------
;; Domain ADTs — closed sums beat ad-hoc maps for slot init outcomes
;; -----------------------------------------------------------------------------

(defadt SlotInit
  "Outcome of `ensure-slot!`. `:slot/ok` carries the live store; the failure
   variants name what went wrong without nesting result maps."
  [:slot/ok               {:slot any? :backend keyword? :store any?}]
  [:slot/missing-backend  {:slot any?}]
  [:slot/factory-failed   {:slot any? :backend keyword? :reason any?}])

;; -----------------------------------------------------------------------------
;; Role-sized ports
;; -----------------------------------------------------------------------------

(defprotocol IBackendResolver
  "Resolves a slot to its backend identity. Pure — no IO, no atoms.
   Strategy pattern: swap the resolver to change config policy without
   touching the registry or the factory."
  (resolve-backend [this slot]
    "Return the backend keyword for `slot`, or nil when no mapping exists.")
  (default-mapping [this]
    "Return the slot→backend default map (introspection / docs)."))

(defprotocol IBackendFactory
  "Creates a fresh IKGStore for a given backend. Stateless — the same
   factory may be invoked multiple times for the same backend with
   different option maps. Factory Method pattern."
  (make-store [this backend] [this backend opts]
    "Construct an IKGStore for `backend`. The 2-arg form forwards an
     `opts` map to the underlying create-store fn — e.g.
     `{:recovery-policy ...}` for the datalevin slot.
     The 1-arg form defaults `opts` to `{}` (back-compat).
     Returns the store on success, nil on failure.")
  (supported-backends [this]
    "Return a set of backend keywords this factory can construct."))

(defprotocol ISlotRegistry
  "Stateful map of slot → live IKGStore handle. Lazy on first access.
   Lifecycle owner — close-slot! / close-all! release resources without
   destroying on-disk data (non-destructive per AXIOM 'Never NUKE Data').

   This is the only stateful port — splitting it out keeps Resolver and
   Factory pure and unit-testable, and gives the facade a single seam
   to mock for fixtures."
  (slot-store     [this slot]    "Return the IKGStore for `slot`, lazy-init.")
  (describe-slot  [this slot]    "Same as slot-store but returns SlotInit ADT for diagnostics.")
  (registered     [this]         "Snapshot of currently-initialized slots.")
  (close-slot!    [this slot]    "Close + evict the store for `slot`. Idempotent.")
  (close-all!     [this]         "Close every cached store."))
