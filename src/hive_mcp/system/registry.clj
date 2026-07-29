(ns hive-mcp.system.registry
  "Registries for lifecycle protocol implementations.

   Addons register IShutdownHook / ISweepable / IResourceOwner instances
   via requiring-resolve into these registries on their IAddon.init!.
   hive-mcp orchestrators (server/lifecycle, system/sweep_coordinator)
   read these registries to dispatch shutdown and sweep work.

   All registrations are idempotent by :name (shutdown-name, sweep-name)
   or :owner-id (IResourceOwner). Re-registration overwrites silently.

   Storage is hive-spi.lifecycle.registry — exactly one set of slots exists
   per JVM, shared with every consumer that registers through hive-spi. This
   namespace keeps hive-mcp's key-deriving arities and return shapes on top
   of it."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-spi.lifecycle.ports :as ports]
            [hive-spi.lifecycle.registry :as sreg]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Shutdown API
;; =============================================================================

(defn register-shutdown!
  "Register an IShutdownHook implementation. Keyed by (shutdown-name impl).
   Re-registering the same name silently overwrites. Returns the impl."
  [impl]
  {:pre [(satisfies? ports/IShutdownHook impl)]}
  (let [name     (ports/shutdown-name impl)
        priority (ports/shutdown-priority impl)]
    (sreg/register-shutdown! name impl)
    (log/info "Registered shutdown hook" {:name name :priority priority})
    impl))

(defn unregister-shutdown!
  "Remove the shutdown hook registered under `name`. Idempotent: returns
   nil whether or not an entry existed."
  [name]
  (sreg/unregister-shutdown! name)
  (log/info "Unregistered shutdown hook" {:name name})
  nil)

(defn registered-shutdown-hooks
  "Return the registered IShutdownHook impls as a seq sorted by
   `shutdown-priority` ascending (lower priority runs earlier)."
  []
  (sreg/registered-shutdown-hooks))

;; =============================================================================
;; Sweep API
;; =============================================================================

(defn register-sweep!
  "Register an ISweepable implementation. Keyed by (sweep-name impl).
   Re-registering the same name silently overwrites. Returns the impl."
  [impl]
  {:pre [(satisfies? ports/ISweepable impl)]}
  (let [name     (ports/sweep-name impl)
        interval (ports/sweep-interval-s impl)]
    (sreg/register-sweep! name impl)
    (log/info "Registered sweep" {:name name :interval-s interval})
    impl))

(defn unregister-sweep!
  "Remove the sweep registered under `name`. Idempotent."
  [name]
  (sreg/unregister-sweep! name)
  (log/info "Unregistered sweep" {:name name})
  nil)

(defn registered-sweeps
  "Return the registered ISweepable impls as an (unsorted) seq. The sweep
   coordinator decides scheduling cadence per-impl."
  []
  (vals (sreg/registered-sweeps)))

;; =============================================================================
;; Resource API
;; =============================================================================

(defn register-resource-owner!
  "Register an IResourceOwner implementation. Keyed by (owner-id impl).
   Re-registering the same owner-id silently overwrites. Returns the impl."
  [impl]
  {:pre [(satisfies? ports/IResourceOwner impl)]}
  (let [id (ports/owner-id impl)]
    (sreg/register-resource-owner! id impl)
    (log/info "Registered resource owner" {:owner-id id})
    impl))

(defn unregister-resource-owner!
  "Remove the resource owner registered under `owner-id`. Idempotent."
  [owner-id]
  (sreg/unregister-resource-owner! owner-id)
  (log/info "Unregistered resource owner" {:owner-id owner-id})
  nil)

(defn get-resource-owner
  "Return the IResourceOwner registered under `owner-id`, or nil."
  [owner-id]
  (sreg/get-resource-owner owner-id))

(defn registered-resource-owners
  "Return the registered IResourceOwner impls as an (unsorted) seq."
  []
  (vals (sreg/registered-resource-owners)))

;; =============================================================================
;; Observability
;; =============================================================================

(defn registry-snapshot
  "Return a flat, printable snapshot of all three registries — intended
   for integration tests and the /status MCP tool. Never throws."
  []
  {:shutdown  (into [] (map (juxt ports/shutdown-name ports/shutdown-priority))
                   (registered-shutdown-hooks))
   :sweeps    (into [] (map (juxt ports/sweep-name ports/sweep-interval-s))
                   (registered-sweeps))
   :resources (keys (sreg/registered-resource-owners))})

;; =============================================================================
;; State capture / restore (test isolation)
;; =============================================================================

(defn capture-all
  "Capture the full {k -> impl} state of all three registries as a single
   restorable value. Lossless (unlike registry-snapshot). For test isolation."
  []
  (let [{:keys [shutdown sweeps owners]} (sreg/registry-snapshot)]
    {:shutdown shutdown
     :sweep    sweeps
     :resource owners}))

(defn reset-all!
  "Clear all three registries. Returns nil. For test isolation."
  []
  (sreg/reset-all!))

(defn restore-all!
  "Restore registries from a `capture-all` value. Returns nil. For test
   isolation — restored impls bypass the register-* :pre (they were already
   validated at original registration)."
  [{:keys [shutdown sweep resource]}]
  (sreg/restore-all! {:shutdown shutdown :sweeps sweep :owners resource}))
