(ns hive-mcp.tools.events.core
  "Subcommand router + MCP tool definition for the `events` tool.

   The tool exposes hive-agent's event observability surface to the MCP
   transport: it can enable/disable opt-in observers, tail/dump the ring
   buffer, query buffer stats, and register/remove handlers and fx at
   runtime via fully-qualified symbols.

   All subcommands return `{:ok bool :command kw ...}` shaped maps; the
   `handle` entrypoint serializes those as MCP `mcp-json` / `mcp-error`
   responses."
  (:require [clojure.data.json                :as json]
            [clojure.string                   :as str]
            [hive-mcp.tools.core              :as tcore]
            [hive-mcp.tools.events.commands   :as cmd]))

;; =============================================================================
;; Subcommand dispatch
;; =============================================================================

(def ^:private handlers
  {:enable           cmd/handle-enable
   :disable          cmd/handle-disable
   :tail             cmd/handle-tail
   :dump             cmd/handle-dump
   :stats            cmd/handle-stats
   :register-handler cmd/handle-register-handler
   :unreg-fx         cmd/handle-unreg-fx
   :help             cmd/handle-help})

(defn- normalize-command
  [cmd]
  (cond
    (keyword? cmd) cmd
    (string? cmd)  (keyword (str/replace cmd #"^:" ""))
    :else          nil))

(defn- to-mcp
  "Serialize a command result map to an MCP response."
  [{:keys [ok] :as result}]
  (if ok
    (tcore/mcp-json result)
    (tcore/mcp-error (json/write-str result))))

(defn handle
  "MCP tool entrypoint. Route on `:command` to the matching subcommand."
  [{:keys [command] :as params}]
  (let [cmd (normalize-command command)
        h   (get handlers cmd)]
    (cond
      (or (nil? cmd) (= cmd :help))
      (to-mcp (cmd/handle-help params))

      (nil? h)
      (to-mcp {:ok false
               :command (or cmd command)
               :error (str "Unknown command: " command
                           ". Valid: " (str/join ", " (sort (map name (keys handlers)))))})

      :else
      (to-mcp (h params)))))

;; =============================================================================
;; Tool definition
;; =============================================================================

(def tool-def
  {:name "events"
   :description (str "hive-agent observability surface. "
                     "Subcommands: enable, disable, tail, dump, stats, register-handler, unreg-fx, help. "
                     "enable/disable take {:agent-ids [...] | :all true}. "
                     "tail/dump/stats take {:agent-id ID}. "
                     "register-handler takes {:event-id KW :symbol \"ns/name\"}. "
                     "unreg-fx takes {:fx-id KW}. "
                     "Use command='help' to list all.")
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :description "Subcommand: enable, disable, tail, dump, stats, register-handler, unreg-fx, help"}
                              "agent-id" {:type "string"
                                          :description "[tail/dump/stats] Agent identifier"}
                              "agent_id" {:type "string"
                                          :description "[tail/dump/stats] Alias for agent-id"}
                              "agent-ids" {:type "array"
                                           :items {:type "string"}
                                           :description "[enable/disable] Agent identifiers to filter on. Pass :all=true or agent-ids='all' for the wildcard set."}
                              "all" {:type "boolean"
                                     :description "[enable/disable] When true, observe every agent."}
                              "n" {:type "integer"
                                   :description "[tail] Number of trailing entries (default 50)"}
                              "event-id" {:type "string"
                                          :description "[register-handler] Event id keyword (e.g. :loop/turn-started)"}
                              "symbol" {:type "string"
                                        :description "[register-handler] Fully-qualified symbol of the handler var"}
                              "fx-id" {:type "string"
                                       :description "[unreg-fx] FX id to remove"}}
                 :required ["command"]}
   :handler handle})

(def tools [tool-def])
