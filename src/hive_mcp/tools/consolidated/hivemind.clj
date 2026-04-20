(ns hive-mcp.tools.consolidated.hivemind
  "Consolidated Hivemind coordination CLI tool."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.hivemind.core :as hm]
            [hive-mcp.hivemind.event-registry :as event-registry]
            [hive-mcp.tools.agent.dispatch :as dispatch]
            [hive-mcp.tools.core :refer [mcp-error]]
            [clojure.string :as str]))

(def ^:private tools-by-name
  (into {} (map (fn [t] [(keyword (str/replace (:name t) "hivemind_" "")) (:handler t)])
                hm/tools)))

(def ^:const default-nudge-template
  "MIDFLIGHT NUDGE: Shout your current state via mcp__hive__hivemind event='progress' — what phase you're in (survey/implement/verify), what you've done recently, what you're currently working on, any blockers. Then continue your task. Report progress at checkpoints per your instructions; don't batch everything into a final shout.")

(defn handle-nudge
  "Nudge a silent ling/drone into shouting its current state.
   Composes over agent dispatch — sends nudge prompt to running agent."
  [{:keys [agent_id message]}]
  (cond
    (str/blank? agent_id)
    (mcp-error "agent_id is required for nudge")

    :else
    (let [nudge-prompt (or (when-not (str/blank? message) message)
                           default-nudge-template)
          result (dispatch/handle-dispatch {:agent_id agent_id
                                            :prompt   nudge-prompt})]
      result)))

(def handlers
  {:shout    (:shout tools-by-name)
   :ask      (:ask tools-by-name)
   :status   (:status tools-by-name)
   :respond  (:respond tools-by-name)
   :messages (:messages tools-by-name)
   :nudge    handle-nudge})

(def handle-hivemind
  (make-cli-handler handlers))

(def tool-def
  {:name "hivemind"
   :consolidated true
   :description "Hivemind coordination: shout (broadcast status), ask (request decision), status (coordinator state), respond (answer ask), messages (agent history), nudge (wake silent ling into shouting state). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["shout" "ask" "status" "respond" "messages" "nudge" "help"]
                                         :description "Hivemind operation to perform"}
                              "event_type" {:type "string"
                                            :enum (event-registry/mcp-enum)
                                            :description "Type of event for shout"}
                              "task" {:type "string"
                                      :description "Current task description"}
                              "message" {:type "string"
                                         :description "Status message"}
                              "data" {:type "object"
                                      :description "Additional event data"}
                              "directory" {:type "string"
                                           :description "Working directory for project-id derivation"}
                              "question" {:type "string"
                                          :description "Question for human coordinator"}
                              "options" {:type "array"
                                         :items {:type "string"}
                                         :description "Available options for ask"}
                              "timeout_ms" {:type "integer"
                                            :description "Timeout in ms (default 300000)"}
                              "ask_id" {:type "string"
                                        :description "ID of the ask to respond to"}
                              "decision" {:type "string"
                                          :description "The decision/response"}
                              "agent_id" {:type "string"
                                          :description "Agent identifier"}}
                 :required ["command"]}
   :handler handle-hivemind})

(def tools [tool-def])
