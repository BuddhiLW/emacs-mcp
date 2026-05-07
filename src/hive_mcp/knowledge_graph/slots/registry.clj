;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.registry
  "AtomBackedRegistry — ISlotRegistry implementation.

   Composes an IBackendResolver and an IBackendFactory (DIP — depends on
   the abstractions, not on ConfigBackendResolver / LateBoundFactory).

   Holds an atom of `slot → IKGStore` for cached lookup. Lazy: a slot's
   store is created the first time it is requested, then memoised. Calling
   `close-slot!` evicts the cache entry; the next lookup rebuilds.

   Lifecycle is non-destructive — `close-slot!` releases the connection
   handle but does NOT delete on-disk data (AXIOM: Never NUKE Data)."
  (:require [hive-dsl.adt :as adt]
            [hive-dsl.result :as r]
            [hive-mcp.protocols.kg :as pkg]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [taoensso.timbre :as log]))

;; -----------------------------------------------------------------------------
;; SlotInit constructors — thin wrappers over the generated `p/slot-init`
;; -----------------------------------------------------------------------------

(defn- ok      [slot backend store]  (p/slot-init :slot/ok              {:slot slot :backend backend :store store}))
(defn- missing [slot]                (p/slot-init :slot/missing-backend {:slot slot}))
(defn- failed  [slot backend reason] (p/slot-init :slot/factory-failed  {:slot slot :backend backend :reason reason}))

;; -----------------------------------------------------------------------------
;; Init pipeline — pure with respect to the registry atom
;; -----------------------------------------------------------------------------

(defn- ensure-conn-result
  "Run `pkg/ensure-conn!` under rescue. Returns SlotInit ADT."
  [slot backend store]
  (r/rescue (failed slot backend :ensure-conn-threw)
            (do (pkg/ensure-conn! store)
                (ok slot backend store))))

(defn- build-slot
  "Pure(ish): resolver + factory → SlotInit. Side effect = ensure-conn!."
  [resolver factory slot]
  (let [backend (p/resolve-backend resolver slot)]
    (cond
      (nil? backend)
      (missing slot)

      :else
      (let [store (p/make-store factory backend)]
        (if (nil? store)
          (failed slot backend :factory-returned-nil)
          (ensure-conn-result slot backend store))))))

(defn- log-init
  "Side-effect-only — logs the outcome of build-slot."
  [init]
  (case (adt/adt-variant init)
    :slot/ok               (log/info  "kg-slot initialized" (select-keys init [:slot :backend]))
    :slot/missing-backend  (log/warn  "kg-slot has no backend mapping" {:slot (:slot init)})
    :slot/factory-failed   (log/error "kg-slot factory failed"
                                      (select-keys init [:slot :backend :reason]))
    nil))

;; -----------------------------------------------------------------------------
;; AtomBackedRegistry
;; -----------------------------------------------------------------------------

(defrecord AtomBackedRegistry [slot-stores resolver factory]
  p/ISlotRegistry

  (slot-store [this slot]
    (or (get @slot-stores slot)
        (let [init (p/describe-slot this slot)]
          (when (= (adt/adt-variant init) :slot/ok)
            (:store init)))))

  (describe-slot [_ slot]
    (or (when-let [s (get @slot-stores slot)]
          (ok slot ::cached s))
        (let [init (build-slot resolver factory slot)]
          (when (= (adt/adt-variant init) :slot/ok)
            (swap! slot-stores assoc slot (:store init)))
          (log-init init)
          init)))

  (registered [_] (vec (keys @slot-stores)))

  (close-slot! [_ slot]
    (when-let [s (get @slot-stores slot)]
      (r/rescue nil (pkg/close! s))
      (swap! slot-stores dissoc slot)
      (log/info "kg-slot closed" {:slot slot}))
    nil)

  (close-all! [_]
    ;; Snapshot before iteration so the doseq doesn't race against mutations.
    (doseq [[slot s] @slot-stores]
      (r/rescue nil (pkg/close! s))
      (log/info "kg-slot closed" {:slot slot}))
    (reset! slot-stores {})
    nil))

(defn ->registry
  "Build a registry from a resolver + factory (DIP)."
  [resolver factory]
  (->AtomBackedRegistry (atom {}) resolver factory))
