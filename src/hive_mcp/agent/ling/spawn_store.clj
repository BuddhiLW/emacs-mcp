(ns hive-mcp.agent.ling.spawn-store
  "Spawn-time Ling registration port.

   The default implementation delegates to the current swarm store, but callers
   depend on this protocol so spawn orchestration is not coupled to a concrete
   DataScript backend."
  (:require [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as ds-queries]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol ISpawnStore
  (add-slave! [this slave-id attrs])
  (remove-slave! [this slave-id])
  (update-slave! [this slave-id updates])
  (claims-for-slave [this slave-id]))

(defrecord DataScriptSpawnStore []
  ISpawnStore
  (add-slave! [_ slave-id attrs]
    (ds-lings/add-slave! slave-id attrs))

  (remove-slave! [_ slave-id]
    (ds-lings/remove-slave! slave-id))

  (update-slave! [_ slave-id updates]
    (ds-lings/update-slave! slave-id updates))

  (claims-for-slave [_ slave-id]
    (->> (ds-queries/get-all-claims)
         (filter #(= slave-id (:slave-id %)))
         (map :file)
         vec)))

(defonce ^:private active-store (atom (->DataScriptSpawnStore)))

(defn set-store!
  "Install a spawn registration store. Intended for addons/tests that provide a
   non-DataScript implementation."
  [store]
  {:pre [(satisfies? ISpawnStore store)]}
  (reset! active-store store)
  store)

(defn get-store
  []
  @active-store)

(defn reset-store!
  "Restore the default swarm-backed store. Intended for tests."
  []
  (set-store! (->DataScriptSpawnStore)))
