(ns hive-mcp.agent.hive-agent-bridge
  "DEPRECATED: Bridge to external agent loop extension (:ag/run).
   Superseded by hive-claude Agent SDK (sdk-drone backend).
   Kept for backward compat — will be removed in a future release."
  {:deprecated "0.16.0"}
  (:require [hive-mcp.config.core :as config]
            [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]))

(defn- default-model
  "Resolve the default drone model from config."
  []
  (config/default-drone-model))

(defn resolve-run-agent
  "Look up the agent loop extension."
  []
  (ext/get-extension :ag/run))

(defn resolve-build-context
  "Look up the context builder extension."
  []
  (ext/get-extension :ag/context))

(defn resolve-tool-definitions
  "Look up the tool definitions extension."
  []
  (ext/get-extension :ag/tools))

(defn hive-agent-available?
  "Check if the agent loop extension is registered."
  []
  (ext/extension-available? :ag/run))

(defn adapt-hive-agent-result
  "Adapt agent extension result to hive-mcp format."
  [ha-result model]
  (let [error? (:error ha-result)
        status (if error? :error :completed)]
    {:status status
     :result (or (:result ha-result) "")
     :steps []
     :tool_calls_made (or (:tool-calls-made ha-result) 0)
     :tokens {:input 0 :output 0 :total 0}
     :model (or model "unknown")
     :hive-agent-metadata {:turns (or (:turns ha-result) 0)
                           :kg-path (:kg-path ha-result)
                           :source :hive-agent}}))

(defn run-agent-via-bridge
  "Run a task through the agent loop extension if available, or return nil."
  [{:keys [task model max-turns preset-content project-id files cwd compress?] :as _opts}]
  (if-let [run-agent-fn (resolve-run-agent)]
    (do
      (log/info {:event :hive-agent-bridge/dispatching
                 :model model
                 :max-turns max-turns
                 :task-preview (subs task 0 (min 100 (count task)))})
      (try
        (let [result (run-agent-fn (cond-> {:task task
                                            :model (or model (default-model))
                                            :max-turns (or max-turns 250)
                                            :preset-content preset-content
                                            :project-id project-id}
                                     files        (assoc :files files)
                                     cwd          (assoc :cwd cwd)
                                     (some? compress?) (assoc :kg-compress? compress?)))]
          (log/info {:event :hive-agent-bridge/completed
                     :turns (:turns result)
                     :tool-calls (:tool-calls-made result)
                     :error? (:error result)})
          (adapt-hive-agent-result result model))
        (catch Exception e
          (log/error {:event :hive-agent-bridge/error
                      :error (.getMessage e)})
          {:status :error
           :result (str "Agent extension error: " (.getMessage e))
           :steps []
           :tool_calls_made 0
           :tokens {:input 0 :output 0 :total 0}
           :model (or model "unknown")})))
    (do
      (log/debug "Agent extension not available, caller should use fallback path")
      nil)))
