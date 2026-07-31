(ns hive-mcp.agent.executor
  "Tool execution with permission gates and allowlist enforcement."
  (:require [hive-mcp.agent.registry :as registry]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.agent.drone.tool-allowlist :as allowlist]
            [hive-mcp.channel.piggyback-tap :as tap]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.server.permissions :as permissions]
            [hive-dsl.result :as r]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.channel.task-signal :as task-signal]
            [hive-mcp.channel.activation :as activation]))

#_{:clj-kondo/ignore [:deprecated-var]}
(def ^:dynamic ^:deprecated *current-agent-id* ctx/*current-agent-id*)

(defn current-agent-id
  "Get the current agent-id from execution context."
  []
  (ctx/current-agent-id))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn requires-approval?
  "Check if a tool call requires human approval."
  [tool-name perms]
  (and (permissions/dangerous-tool? tool-name)
       (not (contains? (set perms) :auto-approve))))

(defn request-approval!
  "Request human approval via hivemind channel."
  [agent-id tool-name arguments]
  (let [question (format "Agent %s wants to call %s with:\n%s\n\nApprove?"
                         agent-id tool-name (json/write-str arguments))
        response (hivemind/ask! agent-id question ["yes" "no"]
                                :timeout-ms 60000)]
    (= "yes" (:decision response))))

(defn execute-tool
  "Execute a tool by name with arguments."
  [tool-name arguments]
  (if-let [tool (registry/get-tool tool-name)]
    (let [result (r/try-effect* :agent/tool-execution-failed
                                (let [handler (:handler tool)]
                                  (handler arguments)))]
      (if (r/ok? result)
        {:success true :result (:ok result)}
        (do (log/error "Tool execution failed:" tool-name {:error (:message result)})
            {:success false :error (:message result)})))
    {:success false :error (str "Unknown tool: " tool-name)}))

(defn format-tool-result
  "Format tool result as assistant message for conversation history."
  [call-id tool-name result]
  {:role "tool"
   :tool_call_id call-id
   :name tool-name
   :content (if (:success result)
              (let [r (:result result)]
                (if (string? (:text r)) (:text r) (json/write-str r)))
              (str "Error: " (:error result)))})

(defn- append-piggyback-to-results
  "Append piggyback drain text to the last tool result's content.
   Mirrors how wrap-handler-piggybacks appends to the last text item
   in MCP responses — ensures the LLM sees hivemind shouts, memory
   blocks, etc. even on the non-MCP (agentic loop) execution path."
  [results piggyback-text]
  (if (and (seq results) piggyback-text)
    (let [last-idx (dec (count results))
          last-result (nth results last-idx)]
      (assoc results last-idx
             (update last-result :content str piggyback-text)))
    results))

(defn execute-tool-calls
  "Execute a batch of tool calls, respecting allowlist and permissions.
   After execution, drains all piggyback channels (hivemind, memory, async,
   catchup) and appends to the last tool result — ensures headless/OpenRouter
   lings receive hivemind shouts that would otherwise be lost.
   Task cues harvested from this batch steer the MEMORY drain; they are empty
   unless task-signal/enabled?. The cues feed `activation/drain-ctx`, so this
   lane carries the same pins and floor-cap as the MCP tool lane. `:tool-name`
   is the single call's name when the batch holds exactly one, else nil."
  ([agent-id tool-calls permissions]
   (execute-tool-calls agent-id tool-calls permissions nil))
  ([agent-id tool-calls permissions {:keys [tool-allowlist task-type project-id] :as opts}]
   (ctx/with-request-context {:agent-id agent-id :project-id project-id}
     (let [effective-allowlist (when (or tool-allowlist task-type)
                                 (allowlist/resolve-allowlist opts))
           {:keys [allowed rejected]}
           (if effective-allowlist
             (allowlist/enforce-allowlist tool-calls effective-allowlist)
             {:allowed tool-calls :rejected []})
           executed (mapv (fn [{:keys [id name arguments]}]
                            (let [approved? (or (not (requires-approval? name permissions))
                                                (request-approval! agent-id name arguments))]
                              (if approved?
                                (let [result (execute-tool name arguments)]
                                  (format-tool-result id name result))
                                (format-tool-result id name
                                                    {:success false :error "Rejected by human"}))))
                          allowed)
           all-results (into (vec rejected) executed)
           cues (into #{}
                     (comp (mapcat (fn [{:keys [name arguments]}]
                                     (task-signal/cues name arguments)))
                           (take task-signal/max-tokens))
                     allowed)
           drain-ctx (activation/drain-ctx
                      {:tool-name (when (= 1 (count allowed)) (:name (first allowed)))
                       :cues cues
                       :caller-id agent-id})
           ;; Drain piggyback channels — bridges hivemind shouts to agentic loop
           piggyback-text (tap/drain-all! agent-id project-id drain-ctx)]
       (append-piggyback-to-results all-results piggyback-text)))))
