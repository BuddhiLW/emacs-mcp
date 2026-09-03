(ns hive-mcp.addons.mcp-bridge
  "IMcpBridge protocol for proxying to external MCP servers.

   Bridges extend the IAddon lifecycle with transport-specific operations:
   - Transport management (start/stop external MCP process or connection)
   - Tool discovery (list-remote-tools from external MCP via JSON-RPC)
   - Tool proxying (call-tool forwards to external, returns result)

   Transport types:
     :stdio - Subprocess via ProcessBuilder, JSON-RPC over stdin/stdout
     :sse   - Server-Sent Events (HTTP streaming)
     :http  - Streamable HTTP (MCP 2025-03-26+)

   Architecture:
     IMcpBridge is a *companion* protocol to IAddon. Concrete bridges
     implement both. The addon lifecycle (init!/shutdown!) manages the
     bridge transport via start-bridge!/stop-bridge!.

     Remote tools are auto-wrapped as local tool-defs by proxy-tool-defs,
     so they flow through the same make-tool middleware chain (piggyback,
     compress, context) as native hive-mcp tools.

   Usage:
     ;; Implement both IAddon + IMcpBridge on a record
     (defrecord HaystackBridge [state]
       IAddon
       (addon-name [_] :bridge/haystack)
       ...
       IMcpBridge
       (transport-type [_] :stdio)
       (start-bridge! [_ opts] ...)
       (stop-bridge! [_] ...)
       (call-tool [_ tool-name params] ...)
       (list-remote-tools [_] ...))

     ;; Register as addon — tools auto-discovered
     (register-addon! (->HaystackBridge (atom nil)))"
  (:require [hive-addon.protocol :as proto]
            [hive-mcp.addons.core :as addon]
            [hive-mcp.dns.result :as r]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IMcpBridge Protocol
;; =============================================================================

(defprotocol IMcpBridge
  "Protocol for MCP bridge addons that proxy to external MCP servers.

   Implementors must also satisfy IAddon for addon lifecycle integration.
   The bridge transport is started during init! and stopped during shutdown!."

  (transport-type [this]
    "Return transport keyword: :stdio, :sse, or :http.

     Determines how the bridge communicates with the external MCP server.
     Used by pool manager and health checks to select appropriate strategies.")

  (start-bridge! [this opts]
    "Start the bridge transport (spawn process, open connection, etc.).

     Called during addon init! phase. Options vary by transport type:
       :stdio — {:command [\"python\" \"-m\" \"server\"] :env {\"KEY\" \"val\"}}
       :sse   — {:url \"http://localhost:8080/sse\" :headers {}}
       :http  — {:url \"http://localhost:8080/mcp\" :headers {}}

     Returns:
       {:success? true}  on successful startup
       {:success? false :errors [\"...\"]}  on failure")

  (stop-bridge! [this]
    "Stop the bridge transport and release resources.

     Called during addon shutdown! phase. Should be idempotent — safe to
     call on an already-stopped bridge.

     Returns:
       {:success? true}  on clean shutdown
       {:success? false :errors [\"...\"]}  on failure")

  (bridge-status [this]
    "Return current bridge health and connection state.

     Returns map with at least:
       {:connected? bool
        :transport  :stdio|:sse|:http
        :uptime-ms  long|nil
        :remote-tool-count int}")

  (call-tool [this tool-name params]
    "Proxy a tool call to the remote MCP server.

     Sends a tools/call JSON-RPC request and returns the result.
     Blocks until response or timeout.

     Arguments:
       tool-name - String name of the remote tool (without namespace prefix)
       params    - Map of tool parameters

     Returns:
       {:content [{:type \"text\" :text \"...\"}]}  on success
       {:isError true :content [{:type \"text\" :text \"error msg\"}]}  on failure

     Throws:
       ex-info on transport-level failures (connection lost, timeout)")

  (list-remote-tools [this]
    "Discover and return tool definitions from the remote MCP server.

     Sends a tools/list JSON-RPC request and parses the response.
     Returns raw MCP tool definitions (name, description, inputSchema).

     Returns:
       Seq of maps, each with:
         {:name        \"tool_name\"
          :description \"what it does\"
          :inputSchema {:type \"object\" :properties {...}}}

     Returns empty seq if bridge is not connected or discovery fails."))

;; =============================================================================
;; Valid Transport Types
;; =============================================================================

(def transport-types
  "Valid transport types for MCP bridges.

   :stdio — Subprocess communication via stdin/stdout JSON-RPC
   :sse   — Server-Sent Events over HTTP (legacy MCP transport)
   :http  — Streamable HTTP (MCP 2025-03-26 spec)"
  #{:stdio :sse :http})

;; =============================================================================
;; Proxy Middleware
;; =============================================================================
;;
;; Composable middleware for proxy handlers. Each middleware fn has the shape:
;;   (fn [handler] -> handler')
;;
;; Applied via compose-middleware (reduce). Last fn in vector wraps outermost.
;; Bridge-level middleware wraps INSIDE the make-tool chain (piggyback, context,
;; compress, etc.), so proxy handlers get full hive-mcp infrastructure.
;;
;; Execution flow:
;;   make-tool middleware (outermost) → user middleware → default middleware → base handler
;;
;; Default middleware (always applied unless :skip-defaults? true):
;;   1. stringify-keys — convert keyword keys back to JSON string keys
;;   2. strip-params   — remove hive-mcp internal params (:directory, :agent_id, etc.)

(def default-strip-params
  "Hive-mcp params stripped before forwarding to remote tools.
   These are injected by make-tool middleware (wrap-handler-context)
   and have no meaning to external MCP servers."
  #{:directory :agent_id :agent-id :_caller_id
    :compact :async :project_id :project-id})

(defn compose-middleware
  "Compose a vector of middleware fns around a handler.

   Each middleware fn: (fn [handler] -> handler')
   Applied left-to-right via reduce — last fn in vector wraps outermost.

   Execution order is reverse of vector:
     (compose-middleware h [a b c])
     ;; => c(b(a(h)))
     ;; Call: c runs → b runs → a runs → h runs

   Returns the composed handler."
  [handler middleware-fns]
  (reduce (fn [h mw] (mw h)) handler middleware-fns))

;; --- Built-in Middleware ---

(defn wrap-proxy-strip-params
  "Middleware: remove hive-mcp-specific params before forwarding.

   The make-tool middleware chain injects params like :directory,
   :agent_id, :_caller_id that remote tools don't understand.
   This strips them before the call reaches the bridge.

   Arguments:
     param-keys - Set of keyword keys to remove

   Usage: (wrap-proxy-strip-params #{:directory :agent_id})"
  [param-keys]
  (fn [handler]
    (fn [params]
      (handler (apply dissoc params param-keys)))))

(defn wrap-proxy-stringify-keys
  "Middleware: convert keyword keys back to string keys for remote tool.

   The make-tool wrap-handler-context keywordizes args for handler
   convenience, but remote MCP tools expect string keys (JSON format).
   This converts back before forwarding to the bridge."
  []
  (fn [handler]
    (fn [params]
      (handler (walk/stringify-keys params)))))

(defn wrap-proxy-logging
  "Middleware: log proxy calls with timing.

   Logs bridge prefix, tool name, param count, elapsed time.
   Uses timbre debug level to avoid noise in production.

   Arguments:
     bridge-prefix - String prefix for log context
     tool-name     - String remote tool name

   Usage: (wrap-proxy-logging \"haystack\" \"convert\")"
  [bridge-prefix tool-name]
  (fn [handler]
    (fn [params]
      (let [start (System/nanoTime)]
        (try
          (let [result (handler params)
                elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
            (log/debug "Bridge proxy call"
                       {:bridge bridge-prefix
                        :tool tool-name
                        :params-count (count params)
                        :elapsed-ms (long elapsed-ms)})
            result)
          (catch Exception e
            (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
              (log/warn "Bridge proxy call failed"
                        {:bridge bridge-prefix
                         :tool tool-name
                         :elapsed-ms (long elapsed-ms)
                         :error (.getMessage e)})
              (throw e))))))))

(defn wrap-proxy-timeout
  "Middleware: enforce timeout on proxy calls.

   Wraps handler in a future with deref timeout.
   Returns error content on timeout (does not throw).

   Arguments:
     timeout-ms - Maximum execution time in milliseconds

   Usage: (wrap-proxy-timeout 30000)"
  [timeout-ms]
  (fn [handler]
    (fn [params]
      (let [f (future (handler params))
            result (deref f timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do
            (future-cancel f)
            {:type "text"
             :text (str "Bridge call timed out after " timeout-ms "ms")})
          result)))))

(defn wrap-proxy-retry
  "Middleware: retry failed proxy calls on transport errors.

   Retries on Exception (transport failures) up to max-retries.
   Does NOT retry on successful error responses (:isError) from remote —
   only transport-level failures.

   Arguments:
     max-retries    - Maximum number of retry attempts
     retry-delay-ms - Delay between retries in milliseconds

   Usage: (wrap-proxy-retry 3 500)"
  [max-retries retry-delay-ms]
  (fn [handler]
    (fn [params]
      (loop [attempt 1]
        (let [result (try
                       {:ok (handler params)}
                       (catch Exception e
                         (if (< attempt max-retries)
                           {:retry e}
                           (throw e))))]
          (if (:retry result)
            (do
              (log/debug "Bridge proxy retry"
                         {:attempt attempt :max max-retries
                          :error (.getMessage (:retry result))})
              (Thread/sleep ^long retry-delay-ms)
              (recur (inc attempt)))
            (:ok result)))))))

(defn wrap-proxy-params
  "Middleware: inject/merge extra params into every call.

   Useful for auth tokens, API keys, or default values that
   the remote tool expects but aren't in the local schema.
   Caller params take precedence (merge order: extra then caller).

   Arguments:
     extra-params - Map of params to inject

   Usage: (wrap-proxy-params {:api_key \"sk-...\"})"
  [extra-params]
  (fn [handler]
    (fn [params]
      (handler (merge extra-params params)))))

(defn default-proxy-middleware
  "Returns the default middleware stack for proxy handlers.

   Stack (innermost to outermost execution):
   1. stringify-keys — convert keyword keys back to JSON string keys
   2. strip-params   — remove hive-mcp internal params

   Returns vector of middleware fns. Callers can extend:
     (into (default-proxy-middleware) [(wrap-proxy-retry 3 500)])"
  []
  [(wrap-proxy-stringify-keys)
   (wrap-proxy-strip-params default-strip-params)])

;; =============================================================================
;; Proxy Tool Generation
;; =============================================================================

(defn- make-proxy-handler
  "Create the base proxy handler that forwards calls to a remote bridge.

   Returns: (fn [params] -> result-map)

   The handler:
   - Calls (call-tool bridge tool-name params)
   - Preserves all content items (multi-content responses)
   - Wraps transport errors as text error responses"
  [bridge tool-name]
  (fn [params]
    (let [effect (r/try-effect* :bridge/call-failed (call-tool bridge tool-name params))]
      (if (r/err? effect)
        {:type "text"
         :text (str "Bridge call failed: " (:message effect))}
        (let [result (:ok effect)]
          (if (:isError result)
            {:type "text"
             :text (str "Remote tool error: "
                        (->> (:content result)
                             (keep :text)
                             (str/join "\n")))}
            (let [content (:content result)]
              (if (and (sequential? content) (> (count content) 1))
                content ;; Multi-item: return seq (normalize-content handles)
                {:type "text"
                 :text (or (-> content first :text)
                           (pr-str result))}))))))))

(defn proxy-tool-def
  "Generate a local tool-def that proxies to a remote tool via bridge.

   The generated handler calls (call-tool bridge tool-name params) with
   composable middleware wrapping. Tool name is namespaced with bridge-prefix
   for isolation: e.g. \"haystack:convert\" from bridge :bridge/haystack.

   The tool-def flows through make-tool's middleware chain (piggyback,
   compress, context) like any native hive-mcp tool.

   Arguments:
     bridge        - IMcpBridge implementation
     bridge-prefix - String prefix for namespace isolation
     remote-tool   - Remote tool definition map from list-remote-tools
     opts          - Options map (optional):
       :middleware      - Vector of middleware fns [(fn [handler] -> handler)]
                          Applied AFTER default middleware (wraps outermost).
       :skip-defaults?  - If true, skip default middleware (strip/stringify).
                          Default false.
       :schema-xf       - Transform inputSchema: (fn [schema] -> schema')
       :name-xf         - Custom naming: (fn [tool-name prefix] -> string)
       :desc-xf         - Custom description: (fn [description prefix] -> string)

   Returns:
     MCP tool-def map with :name :description :inputSchema :handler :bridge-source"
  ([bridge bridge-prefix remote-tool]
   (proxy-tool-def bridge bridge-prefix remote-tool {}))
  ([bridge bridge-prefix {:keys [name description inputSchema] :as _remote-tool}
    {:keys [middleware skip-defaults? schema-xf name-xf desc-xf]}]
   (let [namespaced-name (if name-xf
                           (name-xf name bridge-prefix)
                           (str bridge-prefix ":" name))
         final-desc      (if desc-xf
                           (desc-xf description bridge-prefix)
                           (str description " [via " bridge-prefix "]"))
         base-schema     (or inputSchema {:type "object" :properties {}})
         final-schema    (cond-> base-schema schema-xf schema-xf)
         base-handler    (make-proxy-handler bridge name)
         default-mw      (when-not skip-defaults? (default-proxy-middleware))
         all-mw          (into (vec default-mw) (or middleware []))
         final-handler   (if (seq all-mw)
                           (compose-middleware base-handler all-mw)
                           base-handler)]
     {:name         namespaced-name
      :description  final-desc
      :inputSchema  final-schema
      :handler      final-handler
      :bridge-source bridge-prefix})))

(defn proxy-tool-defs
  "Generate proxy tool-defs for all remote tools discovered by a bridge.

   Calls list-remote-tools, wraps each as a local tool-def with namespaced
   name, proxy handler, and middleware chain. These flow through make-tool.

   Arguments:
     bridge        - IMcpBridge implementation (must also satisfy IAddon)
     bridge-prefix - String prefix for tool name isolation
     opts          - Options map (optional):
       :middleware      - Vector of middleware fns for all proxy handlers
       :skip-defaults?  - If true, skip default middleware (strip/stringify)
       :schema-xf       - Transform inputSchema for all tools
       :tool-filter     - Predicate: (fn [remote-tool] -> bool) to include
       :name-xf         - Custom naming for all tools
       :desc-xf         - Custom description for all tools

   Returns:
     Vector of tool-def maps ready for register-tool!"
  ([bridge bridge-prefix]
   (proxy-tool-defs bridge bridge-prefix {}))
  ([bridge bridge-prefix opts]
   (r/rescue (do (log/warn "Failed to generate proxy tool-defs"
                           {:bridge bridge-prefix})
                 [])
             (let [remote-tools (list-remote-tools bridge)
                   filtered     (cond->> remote-tools
                                  (:tool-filter opts) (filter (:tool-filter opts)))]
               (mapv #(proxy-tool-def bridge bridge-prefix % opts) filtered)))))

;; =============================================================================
;; NoopMcpBridge (Fallback for development/testing)
;; =============================================================================

(def ^:private noop-msg "NoopMcpBridge: No bridge configured.")

(defrecord NoopMcpBridge [id]
  proto/IAddon

  (addon-id [_] id)

  (addon-type [_] :mcp-bridge)

  (capabilities [_] #{:mcp-bridge})

  (initialize! [this opts]
    (start-bridge! this opts))

  (shutdown! [this]
    (stop-bridge! this))

  (tools [this]
    (proxy-tool-defs this (clojure.core/name id)))

  (schema-extensions [_] {})

  (excluded-tools [_] #{})

  (hooks [_] {})

  (health [_]
    {:status :down
     :details {:version "0.0.0"
               :description "No-operation MCP bridge for testing"}})

  IMcpBridge

  (transport-type [_] :stdio)

  (start-bridge! [_ _opts]
    {:success? true
     :errors []
     :metadata {:noop true}})

  (stop-bridge! [_]
    {:success? true
     :errors []})

  (bridge-status [_]
    {:connected?       false
     :transport        :stdio
     :uptime-ms        nil
     :remote-tool-count 0})

  (call-tool [_ _tool-name _params]
    {:isError true
     :content [{:type "text" :text noop-msg}]})

  (list-remote-tools [_]
    []))

(defn ->noop-bridge
  "Create a NoopMcpBridge for testing."
  ([] (->noop-bridge :bridge/noop))
  ([id] (->NoopMcpBridge id)))

;; =============================================================================
;; Bridge Registry & Management
;; =============================================================================

(defonce ^:private active-bridges
  (atom {}))

(defn register-bridge!
  "Register and initialize an MCP bridge as an addon.

   Registers with the addon system, then stores bridge-specific metadata
   for pool management and health checks.

   Arguments:
     bridge - Record implementing both IAddon and IMcpBridge
     opts   - Init options passed to start-bridge!

   Returns:
     {:success? bool :addon-name kw :bridge-prefix str}"
  [bridge & [opts]]
  {:pre [(satisfies? proto/IAddon bridge)
         (satisfies? IMcpBridge bridge)
         (contains? transport-types (transport-type bridge))]}
  (let [name-kw      (proto/addon-id bridge)
        prefix       (clojure.core/name name-kw)
        addon-result (addon/register-addon! bridge)]
    (if (:success? addon-result)
      (let [init-result (addon/init-addon! name-kw opts)]
        (when (:success? init-result)
          (swap! active-bridges assoc name-kw
                 {:bridge       bridge
                  :prefix       prefix
                  :transport    (transport-type bridge)
                  :registered-at (java.time.Instant/now)}))
        (assoc init-result :bridge-prefix prefix))
      addon-result)))

(defn unregister-bridge!
  "Unregister an MCP bridge, stopping transport and removing addon."
  [name-kw]
  (swap! active-bridges dissoc name-kw)
  (addon/unregister-addon! name-kw))

(defn get-bridge
  "Get a registered bridge by addon name keyword."
  [name-kw]
  (get-in @active-bridges [name-kw :bridge]))

(defn list-bridges
  "List all registered MCP bridges with their status."
  []
  (->> @active-bridges
       (mapv (fn [[name-kw {:keys [bridge prefix transport registered-at]}]]
               {:name          name-kw
                :prefix        prefix
                :transport     transport
                :registered-at registered-at
                :status        (bridge-status bridge)}))))

;; =============================================================================
;; Predicates
;; =============================================================================

(defn bridge?
  "Check if object implements IMcpBridge protocol."
  [x]
  (satisfies? IMcpBridge x))

(defn bridge-addon?
  "Check if object implements both IAddon and IMcpBridge."
  [x]
  (and (satisfies? proto/IAddon x)
       (satisfies? IMcpBridge x)))

(defn bridge-connected?
  "Check if a registered bridge is connected and healthy."
  [name-kw]
  (when-let [bridge (get-bridge name-kw)]
    (:connected? (bridge-status bridge))))

(comment
  ;; Development REPL examples

  ;; Create and register noop bridge
  (def noop (->noop-bridge :bridge/test))
  (bridge? noop)         ;=> true
  (bridge-addon? noop)   ;=> true
  (transport-type noop)  ;=> :stdio

  ;; Register as addon
  (register-bridge! noop {})
  (list-bridges)

  ;; Proxy tool generation (empty for noop)
  (proxy-tool-defs noop "test")

  ;; Cleanup
  (unregister-bridge! :bridge/test)

  ;; Example: what a real bridge's addon-tools would return
  ;; (addon-tools haystack-bridge)
  ;; => [{:name "haystack:convert"
  ;;       :description "Convert documents [via haystack]"
  ;;       :inputSchema {...}
  ;;       :handler <proxy-fn>}]
  )
