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
            [hive-mcp.knowledge-graph.slots.breaker :as cb]
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

(defn- resolve-late
  "Late-bound requiring-resolve. Used for cross-project protocol
   probing (vec stores live in hive-proximum, kg stores in hive-mcp)."
  [sym]
  (r/rescue nil (requiring-resolve sym)))

(defn- vec-store?
  "Predicate via late-bind so this ns doesn't compile-couple to
   hive-proximum.vec.protocol."
  [s]
  (when-let [pred (resolve-late 'hive-proximum.vec.protocol/vec-store?)]
    (boolean (pred s))))

(defn- vec-open!
  [s]
  (when-let [open-fn (resolve-late 'hive-proximum.vec.protocol/open!)]
    (open-fn s)))

(defn- vec-close!
  [s]
  (when-let [close-fn (resolve-late 'hive-proximum.vec.protocol/close!)]
    (close-fn s)))

(defn- open-store!
  "Lifecycle dispatch. Vec stores (IVecStore) get `vec/open!`; KG
   stores (IKGStore) get `pkg/ensure-conn!`. Vec is checked first
   because some implementations satisfy BOTH protocols (legacy
   bridge); the vec verb is the more specific one."
  [store]
  (cond
    (vec-store? store)    (vec-open! store)
    (pkg/kg-store? store) (pkg/ensure-conn! store)))

(defn- close-store!
  "Symmetric lifecycle close. Idempotent on both protocol families."
  [store]
  (cond
    (vec-store? store)    (vec-close! store)
    (pkg/kg-store? store) (pkg/close! store)))

(defn- ensure-conn-result
  "Run the appropriate lifecycle open. Returns SlotInit ADT.

   Logs the swallowed throwable body before returning
   `:slot/factory-failed`. Without this log, lifecycle errors (LMDB WAL
   corruption, datahike store-id mismatch, classpath issues) surface
   only as `slot-store` returning nil — which can take many indirection
   layers to root-cause. See decision 20260508-storage-1 hardening."
  [slot backend store]
  (try
    (open-store! store)
    (ok slot backend store)
    (catch Throwable t
      (log/error t "kg-slot ensure-conn! threw"
                 {:slot     slot
                  :backend  backend
                  :store    (some-> store class .getName)
                  :ex-data  (ex-data t)
                  :cause    (some-> t .getCause .getMessage)})
      (failed slot backend :ensure-conn-threw))))

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

(defn- breaker-blocked
  "SlotInit returned when the L1.1 circuit breaker is :open. Mirrors
   `failed` but tags `:reason` so operators see the cause without
   trawling the log timeline."
  [slot]
  (p/slot-init :slot/factory-failed
               {:slot slot :backend nil :reason :breaker-open}))

(defrecord AtomBackedRegistry [slot-stores resolver factory breakers breaker-policy]
  p/ISlotRegistry

  (slot-store [this slot]
    (or (get @slot-stores slot)
        (let [init (p/describe-slot this slot)]
          (when (= (adt/adt-variant init) :slot/ok)
            (:store init)))))

  (describe-slot [_ slot]
    (or (when-let [s (get @slot-stores slot)]
          (ok slot ::cached s))
        ;; ENGINE-L1.1 — gate slot construction by the per-slot breaker.
        ;; When :open, refuse to retry the (expensive, possibly lock-held)
        ;; ensure-conn! and surface a typed factory-failed init instead.
        (let [b (cb/attempt breakers slot breaker-policy)]
          (if (= :block (cb/decision b))
            (do (log-init (breaker-blocked slot))
                (breaker-blocked slot))
            (let [init (build-slot resolver factory slot)
                  variant (adt/adt-variant init)]
              (case variant
                :slot/ok (do (swap! slot-stores assoc slot (:store init))
                             (cb/record-success! breakers slot))
                (:slot/missing-backend :slot/factory-failed)
                (cb/record-failure! breakers slot breaker-policy)
                nil)
              (log-init init)
              init)))))

  (registered [_] (vec (keys @slot-stores)))

  (close-slot! [_ slot]
    (when-let [s (get @slot-stores slot)]
      (r/rescue nil (close-store! s))
      (swap! slot-stores dissoc slot)
      (log/info "kg-slot closed" {:slot slot}))
    nil)

  (close-all! [_]
    ;; Snapshot before iteration so the doseq doesn't race against mutations.
    (doseq [[slot s] @slot-stores]
      (r/rescue nil (close-store! s))
      (log/info "kg-slot closed" {:slot slot}))
    (reset! slot-stores {})
    nil))

(defn ->registry
  "Build a registry from a resolver + factory (DIP).

   Optional `breaker-policy` overrides
   `hive-mcp.knowledge-graph.slots.breaker/default-policy` — caller
   supplies a map merged into the defaults at construction time."
  ([resolver factory]
   (->registry resolver factory {}))
  ([resolver factory breaker-overrides]
   (->AtomBackedRegistry (atom {}) resolver factory
                         (atom {})
                         (merge cb/default-policy (or breaker-overrides {})))))
