(ns hive-mcp.system.registry
  "Registries for lifecycle protocol implementations.

   Addons register IShutdownHook / ISweepable / IResourceOwner instances
   via requiring-resolve into these registries on their IAddon.init!.
   hive-mcp orchestrators (server/lifecycle, system/sweep_coordinator)
   read these registries to dispatch shutdown and sweep work.

   All registrations are idempotent by :name (shutdown-name, sweep-name)
   or :owner-id (IResourceOwner). Re-registration overwrites silently.

   Registries are plain atoms — no compile-time dep on addon code."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.protocols.lifecycle :as proto]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Registry Atoms
;; =============================================================================
;;
;; `defonce` preserves registry contents across ns reload, so addon-side
;; registrations performed during init! are not silently dropped when a
;; developer reloads this namespace from the REPL. Keys are whatever the
;; impl returns from its id fn (shutdown-name / sweep-name / owner-id);
;; typically strings, but keywords are accepted — comparison is by
;; value-equality.

(defonce shutdown-registry (atom {}))   ; shutdown-name -> IShutdownHook
(defonce sweep-registry    (atom {}))   ; sweep-name    -> ISweepable
(defonce resource-registry (atom {}))   ; owner-id      -> IResourceOwner

;; =============================================================================
;; Shutdown API
;; =============================================================================

(defn register-shutdown!
  "Register an IShutdownHook implementation. Keyed by (shutdown-name impl).
   Re-registering the same name silently overwrites. Returns the impl."
  [impl]
  {:pre [(satisfies? proto/IShutdownHook impl)]}
  (let [name     (proto/shutdown-name impl)
        priority (proto/shutdown-priority impl)]
    (swap! shutdown-registry assoc name impl)
    (log/info "Registered shutdown hook" {:name name :priority priority})
    impl))

(defn unregister-shutdown!
  "Remove the shutdown hook registered under `name`. Idempotent: returns
   nil whether or not an entry existed."
  [name]
  (swap! shutdown-registry dissoc name)
  (log/info "Unregistered shutdown hook" {:name name})
  nil)

(defn registered-shutdown-hooks
  "Return the registered IShutdownHook impls as a seq sorted by
   `shutdown-priority` ascending (lower priority runs earlier)."
  []
  (sort-by proto/shutdown-priority (vals @shutdown-registry)))

;; =============================================================================
;; Sweep API
;; =============================================================================

(defn register-sweep!
  "Register an ISweepable implementation. Keyed by (sweep-name impl).
   Re-registering the same name silently overwrites. Returns the impl."
  [impl]
  {:pre [(satisfies? proto/ISweepable impl)]}
  (let [name     (proto/sweep-name impl)
        interval (proto/sweep-interval-s impl)]
    (swap! sweep-registry assoc name impl)
    (log/info "Registered sweep" {:name name :interval-s interval})
    impl))

(defn unregister-sweep!
  "Remove the sweep registered under `name`. Idempotent."
  [name]
  (swap! sweep-registry dissoc name)
  (log/info "Unregistered sweep" {:name name})
  nil)

(defn registered-sweeps
  "Return the registered ISweepable impls as an (unsorted) seq. The sweep
   coordinator decides scheduling cadence per-impl."
  []
  (vals @sweep-registry))

;; =============================================================================
;; Resource API
;; =============================================================================

(defn register-resource-owner!
  "Register an IResourceOwner implementation. Keyed by (owner-id impl).
   Re-registering the same owner-id silently overwrites. Returns the impl."
  [impl]
  {:pre [(satisfies? proto/IResourceOwner impl)]}
  (let [id (proto/owner-id impl)]
    (swap! resource-registry assoc id impl)
    (log/info "Registered resource owner" {:owner-id id})
    impl))

(defn unregister-resource-owner!
  "Remove the resource owner registered under `owner-id`. Idempotent."
  [owner-id]
  (swap! resource-registry dissoc owner-id)
  (log/info "Unregistered resource owner" {:owner-id owner-id})
  nil)

(defn get-resource-owner
  "Return the IResourceOwner registered under `owner-id`, or nil."
  [owner-id]
  (get @resource-registry owner-id))

;; =============================================================================
;; Observability
;; =============================================================================

(defn registry-snapshot
  "Return a flat, printable snapshot of all three registries — intended
   for integration tests and the /status MCP tool. Never throws."
  []
  {:shutdown  (into [] (map (juxt proto/shutdown-name proto/shutdown-priority))
                   (registered-shutdown-hooks))
   :sweeps    (into [] (map (juxt proto/sweep-name proto/sweep-interval-s))
                   (registered-sweeps))
   :resources (keys @resource-registry)})
