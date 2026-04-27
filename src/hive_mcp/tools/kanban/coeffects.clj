(ns hive-mcp.tools.kanban.coeffects
  "Coeffects for kanban events: input gathering, no mutation.

   Injected via `(inject-cofx :kanban/entry)` etc. on event handlers.
   Each cofx looks up data the pure handler will need, by reading
   stable boundaries (facade lookup, current scope)."
  (:require [hive.events.cofx :as cofx]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.tools.kanban.transitions :as kt]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.vectordb.facade :as facade]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- event-payload
  "Pull the payload map out of the dispatched event tuple `[id payload]`."
  [coeffects]
  (let [[_ payload] (:event coeffects)]
    (or payload {})))

(defn- entry-cofx [coeffects]
  (let [{:keys [task-id]} (event-payload coeffects)]
    (assoc coeffects :kanban/entry (facade/get-entry-by-id task-id))))

(defn- project-id-cofx [coeffects]
  (let [{:keys [directory]} (event-payload coeffects)
        eff-dir (kt/effective-dir directory ctx/current-directory)]
    (assoc coeffects :kanban/project-id (scope/get-current-project-id eff-dir))))

(defn register-all!
  "Idempotent registration of every kanban coeffect."
  []
  (cofx/reg-cofx :kanban/entry      entry-cofx)
  (cofx/reg-cofx :kanban/project-id project-id-cofx))
