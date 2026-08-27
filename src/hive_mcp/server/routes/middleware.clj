(ns hive-mcp.server.routes.middleware
  "Composable handler wrappers (middleware) for MCP tool dispatch.

   Each wrapper has a single responsibility (SRP). They compose via ->
   threading in build-middleware-chain.

   Execution order (request flows inward):
   handler → nats-notify → retry → async → [default-async] → guard
   → normalize → compress → piggybacks → context → response

   DDD: Application Service layer — request processing pipeline."
  (:require [hive-mcp.server.routes.identity :as id]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.channel.async-result :as async-buf]
            [hive-mcp.dsl.response :as compress]
            [hive-mcp.extensions.registry :as ext]
            [hive-dsl.context.identity :as ctx-id]
            [taoensso.timbre :as log]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [hive-mcp.channel.task-signal :as task-signal]
            [hive-mcp.channel.activation :as activation]
            [hive-spi.guard.ports :as gp]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Hot-Reload Retry
;; =============================================================================

(def ^:const hot-reload-retry-delay-ms 100)
(def ^:const hot-reload-max-retries 3)

(defn- hot-reload-error?
  [^Throwable e]
  (let [msg (str (.getMessage e))]
    (or (re-find #"(?i)var.*not.*found" msg)
        (re-find #"(?i)unbound|undefined" msg)
        (re-find #"(?i)no.*protocol.*method" msg)
        (instance? IllegalStateException e))))

(defn wrap-handler-retry
  "Wrap handler with retry logic for hot-reload resilience."
  [handler]
  (fn [args]
    (loop [attempt 1]
      (let [result (try
                     {:ok (handler args)}
                     (catch Exception e
                       (if (and (hot-reload-error? e)
                                (< attempt hot-reload-max-retries))
                         {:retry e}
                         (throw e))))]
        (if (:retry result)
          (do
            (log/warn "Hot-reload retry" {:attempt attempt
                                          :max hot-reload-max-retries
                                          :error (ex-message (:retry result))})
            (Thread/sleep hot-reload-retry-delay-ms)
            (recur (inc attempt)))
          (:ok result))))))


;; =============================================================================
;; NATS Notification
;; =============================================================================

(def ^:private mutating-tool-commands
  {"memory"  #{"add" "feedback"}
   "kanban"  #{"move" "create"}
   "session" #{"start" "stop"}
   "agent"   #{"spawn" "kill"}})

(defn- mutating-call? [tool-name args]
  (when-let [commands (get mutating-tool-commands tool-name)]
    (contains? commands (some-> (:command args) name))))

(defn wrap-handler-nats-notify
  "Post-execution hook: publishes NATS notification for mutating operations."
  [handler tool-name]
  (fn [args]
    (let [result (handler args)]
      (when (mutating-call? tool-name args)
        (try
          (when-let [publish! (requiring-resolve 'hive-mcp.nats.bridge/publish-tool-notification!)]
            (publish! {:tool-name  (keyword (str tool-name "-" (name (:command args))))
                       :event-type :tool-executed
                       :timestamp  (System/currentTimeMillis)
                       :data       {:args-summary (select-keys args [:command :name :task-id :id :type])}}))
          (catch Exception e
            (log/debug "[NATS] Tool notification failed for" tool-name (.getMessage e)))))
      result)))


;; =============================================================================
;; Context Binding
;; =============================================================================

(defn wrap-handler-context
  "Bind request context: keywordize args, resolve identity, bind ctx vars."
  [handler]
  (fn [args]
    (let [args (walk/keywordize-keys args)]
      (binding [ctx/*request-cache* (atom {})]
        (let [agent-id (id/extract-agent-id args nil)
              project-id (id/extract-project-id args)
              directory (id/extract-directory args)]
          (crystal/record-session-start! agent-id)
          (ctx/with-request-context {:agent-id agent-id
                                     :project-id project-id
                                     :directory directory}
            (handler args)))))))


;; =============================================================================
;; Content Transform Wrappers
;; =============================================================================

(defn wrap-handler-normalize
  "Normalize handler result to content array."
  [handler]
  (fn [args]
    (id/normalize-content (handler args))))

(defn wrap-handler-compress
  "Apply response compression when `compact` param is present."
  [handler]
  (fn [args]
    (let [mode (compress/resolve-compress-mode args)
          content (handler args)]
      (if mode
        (compress/compress-content content mode)
        content))))

(defn wrap-handler-response
  "Wrap handler result in {:content ...} response map."
  [handler]
  (fn [args]
    {:content (handler args)}))


;; =============================================================================
;; Async Execution
;; =============================================================================

(defn wrap-handler-default-async-for-commands
  "Inject :async true for commands in `commands` set when not explicitly set."
  [handler commands]
  (fn [{:keys [command] :as args}]
    (let [cmd-kw (when command (keyword command))
          should-default? (and (contains? commands cmd-kw)
                               (not (contains? args :async)))]
      (handler (cond-> args
                 should-default? (assoc :async true))))))

(defn wrap-handler-async
  "Intercept async:true calls — return ack, spawn future for real execution."
  [handler tool-name]
  (fn [args]
    (if (:async args)
      (let [task-id (str "atask-" (random-uuid))
            caller-id (or (:_caller_id args) "coordinator")]
        (future
          (try
            (let [clean-args (dissoc args :async)
                  result (handler clean-args)]
              (async-buf/enqueue-result! caller-id
                                         {:task-id task-id :tool tool-name
                                          :status :completed :result result}))
            (catch Exception e
              (log/error e "async-result: background execution failed for task" task-id)
              (async-buf/enqueue-result! caller-id
                                         {:task-id task-id :tool tool-name
                                          :status :error :error (.getMessage e)}))))
        [{:type "text"
          :text (pr-str {:queued true :task-id task-id :tool tool-name})}])
      (handler args))))

(defn- guard-refusal
  "Render `decision` as the content array for a refused call."
  [tool-name decision]
  [{:type "text"
    :isError true
    :text (str "REFUSED by the hive guard — " tool-name "\n\n"
               (:guard/reason decision)
               (when-let [cites (seq (:guard/citations decision))]
                 (str "\n\nStated by: " (str/join ", " cites)))
               (when-let [rid (:guard/rule-id decision)]
                 (str "\nRule: " rid)))}])

(defn wrap-handler-guard
  "Gate the call through the `:guard/decide` seam before the handler runs.

   The vendor-independent FLOOR: every client speaks MCP, so a rule enforced
   here holds under clients that have no hook system of their own.

   Soft seam — core does not depend on the guard addon. With no
   `:guard/decide` extension registered the call proceeds untouched.

     :deny — short-circuits; the refusal is returned and the tool never runs.
     :warn — the tool runs and the advisory is appended to its content.
     else  — proceeds.

   A seam that THROWS is logged and the call proceeds: a broken guard must not
   brick every tool on the server."
  [handler tool-name]
  (fn [args]
    (let [decision (when-let [decide (ext/get-extension gp/decide-ext-key)]
                     (try
                       (decide :mcp {:tool tool-name
                                     :input args
                                     :agent-id (id/extract-agent-id args nil)
                                     :cwd (id/extract-directory args)})
                       (catch Exception e
                         (log/error e "guard: seam threw; call NOT judged"
                                    {:tool tool-name})
                         nil)))]
      (case (:guard/verdict decision)
        :deny (do (log/warn "guard: refused a tool call"
                            {:tool tool-name :rule (:guard/rule-id decision)})
                  (guard-refusal tool-name decision))
        :warn (conj (vec (id/normalize-content (handler args)))
                    {:type "text"
                     :text (str "GUARD WARNING — " (:guard/reason decision)
                                (when-let [rid (:guard/rule-id decision)]
                                  (str " [" rid "]")))})
        (handler args)))))


;; =============================================================================
;; Piggyback Draining
;; =============================================================================

(defn- resolve-child-project-ids
  "Resolve descendant project-ids using the project hierarchy tree
   (.hive-project.edn), NOT the Datascript slave registry. Tree-based
   resolution survives ling cleanup — shouts from terminated child-project
   lings remain visible to the parent coordinator."
  [project-id]
  (when project-id
    (try
      (when-let [desc-fn (requiring-resolve 'hive-mcp.knowledge-graph.scope/descendant-scopes)]
        (let [child-pids (desc-fn project-id)]
          (when (seq child-pids)
            (log/debug "Piggyback: including descendant project-ids for" project-id ":" child-pids)
            (set child-pids))))
      (catch Exception e
        (log/debug "Piggyback: descendant project-id resolution failed (non-fatal):" (.getMessage e))
        nil))))

(defn- get-piggyback-messages [agent-id project-id]
  (require 'hive-mcp.channel.piggyback)
  (let [child-pids (resolve-child-project-ids project-id)]
    ((resolve 'hive-mcp.channel.piggyback/get-messages)
     agent-id
     :project-id project-id
     :additional-project-ids child-pids)))

(defn- drain-memory-piggyback
  ([caller-id] (drain-memory-piggyback caller-id nil))
  ([caller-id ctx]
   (require 'hive-mcp.channel.memory-piggyback)
   ((resolve 'hive-mcp.channel.memory-piggyback/drain!) caller-id ctx)))

(defn wrap-handler-piggybacks
  "Unified piggyback wrapper — drains all 4 channels in a single pass.
   Task cues harvested from the request args steer the MEMORY drain that rides
   this response; they are empty unless task-signal/enabled?. The cues then feed
   `activation/drain-ctx`, which an activation provider may extend with pinned
   entry ids — absent a provider the ctx is the cues alone."
  ([handler] (wrap-handler-piggybacks handler nil))
  ([handler tool-name]
   (fn [args]
     (let [task-tokens (task-signal/cues tool-name args)
           content (handler args)
           caller-id (or (:_caller_id args) "coordinator")
           async-drain (async-buf/drain! caller-id)
           memory-drain (drain-memory-piggyback
                         caller-id
                         (activation/drain-ctx {:tool-name tool-name
                                                :cues task-tokens
                                                :caller-id caller-id}))
           catchup-blocks (when-let [drain-fn (ext/get-extension :cu/piggyback-drain)]
                            (try (drain-fn caller-id)
                                 (catch Exception e
                                   (log/debug "catchup-piggyback drain failed:" (.getMessage e))
                                   nil)))
           caller (id/extract-caller-identity args)
           scope (id/extract-project-scope args)
           hm-agent-id (ctx-id/make-piggyback-agent-id caller scope)
           hm-project-id (ctx-id/project-scope-string scope)
           hivemind-msgs (get-piggyback-messages hm-agent-id hm-project-id)]

       (cond-> content
         async-drain
         (id/wrap-delimited-block "TOOLRESULT" (pr-str async-drain))

         memory-drain
         (id/wrap-memory-piggyback-content memory-drain)

         (seq catchup-blocks)
         (as-> c (reduce-kv
                   (fn [acc tag body]
                     (id/wrap-delimited-block acc
                       (str/upper-case (name tag))
                       (if (string? body) body (pr-str body))))
                   c catchup-blocks))

         true
         (id/wrap-piggyback hivemind-msgs))))))


;; =============================================================================
;; Middleware Chain Composition
;; =============================================================================

(defn build-middleware-chain
  "Compose the 9-layer middleware chain around a raw tool handler.
   Testable in isolation — no tool definition machinery needed.

   The guard sits INSIDE context (so it judges keywordized args with identity
   already resolved) and OUTSIDE async (so a denied call is never spawned as a
   future)."
  [handler tool-name default-async-commands]
  (-> handler
      (wrap-handler-nats-notify tool-name)
      wrap-handler-retry
      (wrap-handler-async tool-name)
      (cond-> (seq default-async-commands)
        (wrap-handler-default-async-for-commands default-async-commands))
      (wrap-handler-guard tool-name)
      wrap-handler-normalize
      wrap-handler-compress
      (wrap-handler-piggybacks tool-name)
      wrap-handler-context
      wrap-handler-response))
