(ns hive-mcp.events.effects.memory
  "Memory and wrap effect handlers for the hive-mcp event system.

   Effects implemented:
   - :memory-write      - Add entry to Chroma memory
   - :wrap-notify       - Record ling wrap for coordinator permeation
   - :wrap-crystallize  - Run wrap crystallization for session learnings

   Also provides handler injection points for DIP compliance:
   - set-memory-write-handler!     - Wire memory CRUD layer
   - set-wrap-crystallize-handler! - Wire tools/crystal layer

   Usage:
   ```clojure
   (require '[hive-mcp.events.effects.memory :as mem-effects])
   (mem-effects/register-memory-effects!)
   ```"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.swarm.datascript :as ds]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Injected Handler State
;; =============================================================================

(defonce ^:private memory-write-handler (atom nil))
(defonce ^:private wrap-crystallize-handler (atom nil))

(defn set-memory-write-handler!
  "Set the handler function for :memory-write effect.
   Called during server initialization to wire infrastructure layer.

   Example:
     (set-memory-write-handler! memory-crud/handle-add)"
  [f]
  (reset! memory-write-handler f))

(defn set-wrap-crystallize-handler!
  "Set the handler function for :wrap-crystallize effect.
   Called during server initialization to wire tools layer.

   DIP: Events layer uses this injection point instead of importing tools/crystal.

   Example:
     (set-wrap-crystallize-handler! crystal/handle-wrap-crystallize)"
  [f]
  (reset! wrap-crystallize-handler f))

;; =============================================================================
;; Effect: :memory-write (EVENTS-04)
;; =============================================================================

(defn- resolve-memory-write-handler
  "Resolve memory write handler: injected atom > requiring-resolve fallback.
   Returns fn or nil."
  []
  (or @memory-write-handler
      (try
        (requiring-resolve 'hive-mcp.tools.memory.crud.write/handle-add)
        (catch Exception e (log/trace "[effects/memory] memory-write handler not resolvable:" (.getMessage e)) nil))))

(defn- handle-memory-write
  "Execute a :memory-write effect - add entry to Chroma memory.

   Creates a new memory entry via the memory CRUD system.
   Automatically handles duplicate detection and tag merging.

   Resolution chain: injected handler > requiring-resolve fallback.

   Expected data shape:
   {:type      \"note\" | \"snippet\" | \"convention\" | \"decision\"
    :content   \"The content to store\"
    :tags      [\"tag1\" \"tag2\"]     ; optional
    :duration  \"ephemeral\" | \"short\" | \"medium\" | \"long\" | \"permanent\" ; optional
    :directory \"/path/to/project\"}   ; optional, for scoping"
  [{:keys [type content] :as data}]
  (when (and type content)
    (if-let [handler (resolve-memory-write-handler)]
      (try
        (handler data)
        (log/debug "[EVENT] Memory entry created:" type)
        (catch Exception e
          (log/error "[EVENT] Memory write failed:" (.getMessage e))))
      (log/error "[EVENT] Memory write handler not resolvable - both atom and requiring-resolve failed"))))

;; =============================================================================
;; Effect: :wrap-notify
;; =============================================================================

(defn- handle-wrap-notify
  "Execute a :wrap-notify effect - record ling wrap to DataScript.

   Expected data shape:
   {:wrap-id     \"wrap-uuid\"  ; auto-generated if not provided
    :agent-id    \"ling-123\"
    :session-id  \"session:2026-01-14:ling-123\"
    :project-id  \"hive-mcp\"
    :created-ids [\"note-1\" \"note-2\"]
    :stats       {:notes 2 :decisions 0}}"
  [{:keys [wrap-id agent-id session-id project-id created-ids stats]}]
  (let [wid (or wrap-id (str "wrap-" (java.util.UUID/randomUUID)))]
    (ds/add-wrap-notification! wid
                               {:agent-id agent-id
                                :session-id session-id
                                :project-id project-id
                                :created-ids created-ids
                                :stats stats})))

;; =============================================================================
;; Effect: :wrap-crystallize (Session Complete)
;; =============================================================================

(defn- resolve-wrap-handler
  "Resolve wrap crystallize handler: injected atom > requiring-resolve fallback.
   Returns fn or nil."
  []
  (or @wrap-crystallize-handler
      (try
        (requiring-resolve 'hive-mcp.tools.crystal/handle-wrap-crystallize)
        (catch Exception e (log/trace "[effects/memory] wrap-crystallize handler not resolvable:" (.getMessage e)) nil))))

(defn- handle-wrap-crystallize
  "Execute a :wrap-crystallize effect - run wrap crystallization.

   Triggers the wrap crystallize workflow to persist session learnings
   to long-term memory. Part of the session_complete workflow.

   Resolution chain: injected handler > requiring-resolve fallback.
   This eliminates the silent-nil footgun when set-wrap-crystallize-handler!
   is not called during init.

   Expected data shape:
   {:agent-id \"ling-123\" :directory \"/path/to/project\"}"
  [{:keys [agent-id directory]}]
  (if-let [handler (resolve-wrap-handler)]
    (try
      (handler {:agent_id agent-id :directory directory})
      (log/info "[EVENT] Wrap crystallize completed for:" agent-id)
      (catch Exception e
        (log/error "[EVENT] Wrap crystallize failed:" (.getMessage e))))
    (log/error "[EVENT] Wrap crystallize handler not resolvable - both atom and requiring-resolve failed")))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-memory-effects!
  "Register all memory effect handlers.

   Effects registered:
   - :memory-write      - Add entry to Chroma memory
   - :wrap-notify       - Record ling wrap to DataScript
   - :wrap-crystallize  - Run wrap crystallization

   Called from hive-mcp.events.effects/register-effects!"
  []
  (ev/reg-fx :memory-write handle-memory-write)
  (ev/reg-fx :wrap-notify handle-wrap-notify)
  (ev/reg-fx :wrap-crystallize handle-wrap-crystallize)
  (log/info "[hive-events.memory] Memory effects registered: :memory-write :wrap-notify :wrap-crystallize"))
