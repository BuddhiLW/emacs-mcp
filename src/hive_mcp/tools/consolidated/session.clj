(ns hive-mcp.tools.consolidated.session
  "Consolidated Session CLI tool for lifecycle and context store operations."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.session-complete :as session-handlers]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.tools.core :refer [mcp-error mcp-json]]
            [hive-mcp.tools.crystal :as crystal]
            [hive-mcp.tools.catchup :as catchup]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.channel.context-store :as ctx-store]
            [hive-mcp.context.reconstruction :as reconstruction]
            [hive-mcp.dns.result :as result]
            [hive-mcp.tools.memory.scope :as scope]
            [taoensso.timbre :as log]
            [hive-mcp.tools.consolidated.session-config :as session-config]))

(defn- evict-agent-context!
  "Evict context-store entries for a completing agent."
  [agent-id]
  (when (and agent-id (not= agent-id "unknown") (not= agent-id "coordinator"))
    (try
      (let [tags #{(str "agent:" agent-id)
                   (str "session:" agent-id)
                   agent-id}
            n (ctx-store/evict-by-tags! tags)]
        (when (pos? n)
          (log/info "evict-agent-context! evicted" n "entries for" agent-id))
        {:evicted n})
      (catch Exception e
        (log/warn "evict-agent-context! failed for" agent-id ":" (.getMessage e))
        {:evicted 0 :error (.getMessage e)}))))

(defn- trigger-gc-sweep!
  "Trigger bounded atom GC sweep before crystallization (gc-fix-5).
   Evicts stale/over-capacity entries to free memory before session wrap.
   Non-fatal: if sweep fails, session lifecycle continues."
  []
  (try
    (when-let [trigger-fn (requiring-resolve 'hive-mcp.events.handlers.lifecycle/trigger-sweep!)]
      (let [result (trigger-fn)]
        (when (and (map? result) (pos? (get result :total-evicted 0)))
          (log/info "[gc-fix-5] Pre-crystallization GC sweep evicted"
                    (:total-evicted result) "entries from"
                    (:atom-count result) "atoms in"
                    (:duration-ms result) "ms"))
        result))
    (catch Exception e
      (log/warn "[gc-fix-5] GC sweep failed (non-fatal):" (.getMessage e))
      {:total-evicted 0 :error (.getMessage e)})))

;; ── Pure Result-returning functions ───────────────────────────────────────────

(defn- keywordize-refs
  "Normalize string-keyed ctx_refs map to keyword-keyed."
  [ctx_refs]
  (reduce-kv (fn [m k v] (assoc m (keyword k) v)) {} ctx_refs))

(defn- context-put*
  "Store data in ephemeral context store. Returns Result."
  [{:keys [data tags ttl_ms]}]
  (if-not data
    (result/err :session/context-put {:message "Missing required field: data"})
    (let [args (cond-> []
                 tags   (into [:tags (set tags)])
                 ttl_ms (into [:ttl-ms (long ttl_ms)]))
          id (apply ctx-store/context-put! data args)]
      (result/ok {:ctx-id id :ttl-ms (or ttl_ms ctx-store/default-ttl-ms)}))))

(defn- context-get*
  "Retrieve entry from context store by ID. Returns Result."
  [{:keys [ctx_id]}]
  (if-not ctx_id
    (result/err :session/context-get {:message "Missing required field: ctx_id"})
    (if-let [entry (ctx-store/context-get ctx_id)]
      (result/ok entry)
      (result/ok {:not-found true :ctx-id ctx_id}))))

(defn- context-query*
  "Query context store entries by tags. Returns Result."
  [{:keys [tags limit]}]
  (let [args (cond-> []
               tags  (into [:tags (set tags)])
               limit (into [:limit (long limit)]))
        results (apply ctx-store/context-query args)]
    (result/ok {:count (count results) :entries results})))

(defn- context-evict*
  "Remove entry from context store by ID. Returns Result."
  [{:keys [ctx_id]}]
  (if-not ctx_id
    (result/err :session/context-evict {:message "Missing required field: ctx_id"})
    (let [removed? (ctx-store/context-evict! ctx_id)]
      (result/ok {:evicted removed? :ctx-id ctx_id}))))

(defn- context-stats*
  "Return context store statistics. Returns Result."
  [_params]
  (result/ok (ctx-store/context-stats)))

(defn- context-reconstruct*
  "Reconstruct compressed context from context-store refs and KG traversal. Returns Result."
  [{:keys [ctx_id ctx_refs kg_node_ids scope directory]}]
  (let [effective-refs (cond
                         (seq ctx_refs)
                         (keywordize-refs ctx_refs)

                         ctx_id
                         (when-let [entry (ctx-store/context-get ctx_id)]
                           (let [data (:data entry)]
                             (if (map? data) data {})))

                         :else {})
        effective-kg-ids (vec (or kg_node_ids []))
        effective-scope (or scope
                            (when directory
                              (scope/get-current-project-id directory)))
        r (reconstruction/reconstruct-context
           (or effective-refs {})
           effective-kg-ids
           effective-scope)]
    (result/ok {:reconstructed r
                :chars (count r)
                :refs-count (count effective-refs)
                :kg-nodes-count (count effective-kg-ids)})))

;; ── Public handlers (MCP boundary) ────────────────────────────────────────────

(defn handle-whoami
  "Return the calling agent's identity context."
  [{:keys [agent_id directory]}]
  (let [effective-dir (or directory
                          (ctx/current-directory)
                          (System/getProperty "user.dir"))
        effective-agent-id (or agent_id
                               (ctx/current-agent-id)
                               (session-config/swarm-slave-id)
                               "unknown")
        project-id (when effective-dir
                     (scope/get-current-project-id effective-dir))]
    (log/info "session-whoami" {:agent effective-agent-id :project project-id :cwd effective-dir})
    (mcp-json {:agent-id   effective-agent-id
               :project-id project-id
               :cwd        effective-dir})))

(defn- wrap-async!
  "Execute wrap crystallization + eviction in background thread.
   Logs completion/failure. Notifies via hivemind shout (already in crystallize path)."
  [agent_id directory]
  (let [t0 (System/currentTimeMillis)
        effective-agent (or agent_id
                            (ctx/current-agent-id)
                            (session-config/swarm-slave-id))]
    (future
      (try
        (trigger-gc-sweep!)
        (crystal/handle-wrap-crystallize {:directory directory :agent_id agent_id})
        (evict-agent-context! effective-agent)
        (log/info "session-wrap: DONE" (- (System/currentTimeMillis) t0) "ms")
        (catch Throwable t
          (log/error t "session-wrap: background crystallization failed"
                     {:agent agent_id :elapsed-ms (- (System/currentTimeMillis) t0)}))))))

(defn handle-wrap
  "Wrap session -- fire-and-forget crystallization.
   Returns immediately after kicking off harvest+crystallize in background.
   Completion notified via hivemind shout (:crystal/wrap-notify)."
  [{:keys [agent_id directory]}]
  (let [directory (or directory (ctx/current-directory))]
    (log/info "session-wrap: START (async)" {:agent agent_id :directory directory})
    (wrap-async! agent_id directory)
  (mcp-json {:status "wrap-started"
             :agent-id (or agent_id (ctx/current-agent-id) "coordinator")
             :directory directory
             :message "Crystallization running in background. Completion notified via hivemind."})))

(defn handle-catchup
  "Restore session context from Chroma memory.
   Delegates to catchup/handle-native-catchup which already returns MCP response."
  [{:keys [directory] :as params}]
  (log/info "session-catchup" {:directory directory})
  (let [r (rb/try-result :session/catchup-failed
                         #(result/ok (catchup/handle-native-catchup params)))]
    (if (result/ok? r)
      (:ok r)
      (mcp-error (str "Catchup failed: " (or (:message r) (str (:error r))))))))

(defn handle-context-put
  "Store data in ephemeral context store, returns ctx-id for pass-by-reference."
  [params]
  (rb/result->mcp (rb/try-result :session/context-put-failed #(context-put* params))))

(defn handle-context-get
  "Retrieve entry from context store by ID."
  [params]
  (rb/result->mcp (rb/try-result :session/context-get-failed #(context-get* params))))

(defn handle-context-query
  "Query context store entries by tags."
  [params]
  (rb/result->mcp (rb/try-result :session/context-query-failed #(context-query* params))))

(defn handle-context-evict
  "Remove entry from context store by ID."
  [params]
  (rb/result->mcp (rb/try-result :session/context-evict-failed #(context-evict* params))))

(defn handle-context-stats
  "Return context store statistics."
  [params]
  (rb/result->mcp (rb/try-result :session/context-stats-failed #(context-stats* params))))

(defn handle-context-reconstruct
  "Reconstruct compressed context from context-store refs and KG traversal."
  [params]
  (rb/result->mcp (rb/try-result :session/context-reconstruct-failed #(context-reconstruct* params))))

(defn handle-complete
  "Complete a ling session with GC sweep and context eviction.
   Runs GC sweep before crystallization to free memory (gc-fix-5)."
  [{:keys [agent_id] :as params}]
  ;; gc-fix-5: Run bounded atom GC sweep before crystallization
  (let [gc-result (trigger-gc-sweep!)]
    (log/debug "session-complete: GC sweep result" gc-result))
  (let [result (session-handlers/handle-session-complete params)
        effective-agent (or agent_id
                            (ctx/current-agent-id)
                            (session-config/swarm-slave-id))
        eviction (evict-agent-context! effective-agent)]
    (when eviction
      (log/info "session-complete: context eviction" eviction))
    result))

(def handlers
  {:complete             handle-complete
   :wrap                 handle-wrap
   :whoami               handle-whoami
   :catchup              handle-catchup
   :context-put          handle-context-put
   :context-get          handle-context-get
   :context-query        handle-context-query
   :context-evict        handle-context-evict
   :context-stats        handle-context-stats
   :context-reconstruct  handle-context-reconstruct})

(def handle-session
  (make-cli-handler handlers))

(def tool-def
  {:name "session"
   :consolidated true
   :description "Session lifecycle: complete (commit + kanban + wrap + shout), wrap (crystallize only without commit), whoami (get agent identity context), catchup (restore session context from memory). Context store: context-put (store data, get ID), context-get (retrieve by ID), context-query (search by tags), context-evict (remove by ID), context-stats (store metrics), context-reconstruct (compressed reconstruction from refs). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["complete" "wrap" "whoami" "catchup"
                                                "context-put" "context-get" "context-query"
                                                "context-evict" "context-stats"
                                                "context-reconstruct" "help"]
                                         :description "Session operation to perform"}
                              "commit_msg" {:type "string"
                                            :description "Git commit message (required for complete)"}
                              "task_ids" {:type "array"
                                          :items {:type "string"}
                                          :description "Kanban task IDs to mark done"}
                              "agent_id" {:type "string"
                                          :description "Ling's slave-id (CLAUDE_SWARM_SLAVE_ID)"}
                              "directory" {:type "string"
                                           :description "Working directory for git/kanban scoping"}
                              "data" {:description "[context-put] Structured data to store (any JSON value)"}
                              "ctx_id" {:type "string"
                                        :description "[context-get/context-evict] Context entry ID"}
                              "tags" {:type "array"
                                      :items {:type "string"}
                                      :description "[context-put/context-query] Tags for filtering"}
                              "ttl_ms" {:type "integer"
                                        :description "[context-put] Time-to-live in milliseconds (default: 300000 = 5 min)"}
                              "limit" {:type "integer"
                                       :description "[context-query] Max entries to return (default: 100)"}
                              "ctx_refs" {:type "object"
                                          :description "[context-reconstruct] Map of category->ctx-id for ref resolution"}
                              "kg_node_ids" {:type "array"
                                             :items {:type "string"}
                                             :description "[context-reconstruct] KG node IDs for graph traversal seeds"}
                              "scope" {:type "string"
                                       :description "[context-reconstruct] Project scope for KG traversal"}}
                 :required ["command"]}
   :handler handle-session})

(def tools [tool-def])
