(ns hive-mcp.system.sweepers.orphan-channel
  "ISweepable impl that detects IResourceOwner entries whose underlying
   ling process is dead and releases their resources.

   Walks `hive-mcp.system.registry/registered-resource-owners`, asks each owner
   for its `owner-id`, then consults `hive-mcp.agent.ling.spawn/find-ling` (via
   requiring-resolve) to decide liveness:

     - find-ling returns nil          → owner is orphaned → release-all! + unregister
     - find-ling not on classpath     → treat every owner as alive (conservative;
                                        avoids false orphaning when hive-agent
                                        or its spawn ns hasn't loaded yet)
     - release-all! or unregister     → errors logged, sweep continues

   Auto-registers with hive-mcp.system.registry on ns load."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.system.registry :as reg]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Liveness probe
;; =============================================================================

(defn- resolve-find-ling
  "Return hive-mcp.agent.ling.spawn/find-ling if resolvable, else nil.
   Caller treats nil as 'assume alive' — see ns docstring."
  []
  (try (requiring-resolve 'hive-mcp.agent.ling.spawn/find-ling)
       (catch Throwable _ nil)))

(defn- orphan?
  "Return true iff `owner`'s ling-id fails to resolve through find-ling.
   When find-ling itself is nil (spawn ns not loaded) return false so no
   owner is ever falsely orphaned."
  [find-ling owner]
  (if (nil? find-ling)
    false
    (let [id (lifecycle/owner-id owner)]
      (try
        (not (boolean (find-ling id)))
        (catch Throwable t
          (log/warn t "orphan-channel sweep: find-ling threw; assuming alive"
                    {:owner-id id})
          false)))))

;; =============================================================================
;; ISweepable impl
;; =============================================================================

(defrecord OrphanChannelSweep []
  lifecycle/ISweepable
  (sweep-interval-s [_] 300)            ; 5 minutes
  (sweep-name [_] "channels/orphan")
  (sweep! [_ _ctx]
    (let [find-ling (resolve-find-ling)
          owners    (reg/registered-resource-owners)
          orphaned  (filter (partial orphan? find-ling) owners)
          errors    (volatile! [])]
      (doseq [o orphaned]
        (let [id (try (lifecycle/owner-id o) (catch Throwable _ nil))]
          (try
            (lifecycle/release-all! o)
            (reg/unregister-resource-owner! id)
            (catch Throwable t
              (log/error t "orphan-channel sweep: release failed"
                         {:owner-id id})
              (vswap! errors conj {:owner-id id
                                   :error    (.getMessage t)})))))
      {:swept (count orphaned) :errors @errors})))

;; =============================================================================
;; Auto-registration
;; =============================================================================

(defonce ^:private -registered?
  (do (reg/register-sweep! (->OrphanChannelSweep)) true))
