(ns hive-mcp.server.routes
  "MCP server route definitions and tool dispatch.

   SRP: Single responsibility for tool route construction and dispatch.

   This module handles:
   - Tool definition conversion to SDK format
   - Piggyback message embedding for hivemind communication
   - Server spec building with capability-based filtering
   - Hot-reload support for tools"
  (:require [hive-mcp.tools.registry :as tools]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.server.registration]              ; side-effect: tools/list defmethod (filters deprecated)
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.channel.async-result :as async-buf]
            [hive-mcp.dsl.response :as compress]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.addons.core :as addons]
            [hive-dsl.context.identity :as ctx-id]
            [taoensso.timbre :as log]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Specs for Tool Definitions
;; =============================================================================

(s/def ::tool-def
  (s/keys :req-un [::name ::description ::inputSchema ::handler]))

(s/def ::name string?)
(s/def ::description string?)
(s/def ::inputSchema map?)
(s/def ::handler fn?)

(s/def ::tool-response
  (s/keys :req-un [::content]))

(s/def ::content (s/coll-of map?))

;; =============================================================================
;; SRP Helpers for Content Normalization
;; =============================================================================

(defn normalize-content
  "Normalize handler result to content array.
   SRP: Single responsibility for content normalization.
   Handles: sequential (passthrough), map with :content (unwrap MCP response),
   map (wrap), other (text wrap)."
  [result]
  (cond
    (sequential? result) (vec result)
    (and (map? result) (:content result)) (:content result)
    (map? result) [result]
    :else [{:type "text" :text (str result)}]))

(defn find-last-text-idx
  "Find index of last text-type item in content (searching from end).
   SRP: Single responsibility for text item location.
   Returns nil if no text item found."
  [content]
  (some (fn [[idx item]]
          (when (= "text" (:type item)) idx))
        (map-indexed vector (reverse content))))

(defn wrap-delimited-block
  "Append a delimited block to content.
   SRP: Single responsibility for delimiter-wrapped embedding.
   Appends to last text item if exists, otherwise adds new text item.

   Format:
   ---TAG---
   <body>
   ---/TAG---"
  [content tag body]
  (if (and body (seq (str body)))
    (let [block-text (str "\n\n---" tag "---\n"
                          body
                          "\n---/" tag "---")]
      (if-let [last-text-idx (find-last-text-idx content)]
        (let [actual-idx (- (count content) 1 last-text-idx)
              last-item (nth content actual-idx)]
          (assoc content actual-idx
                 (update last-item :text str block-text)))
        (conj content {:type "text" :text block-text})))
    content))

(defn wrap-piggyback
  "Append piggyback messages to content with HIVEMIND delimiters.
   SRP: Single responsibility for piggyback embedding.
   Appends to last text item if exists, otherwise adds new text item.

   Format:
   ---HIVEMIND---
   [{:a \"agent-id\" :e \"event-type\" :m \"message\"}]
   ---/HIVEMIND---"
  [content piggyback]
  (wrap-delimited-block content "HIVEMIND" (when (seq piggyback) (pr-str piggyback))))

;; =============================================================================
;; Agent ID and Project ID Extraction
;; =============================================================================

(defn extract-agent-id
  "Extract agent-id from args map, handling both snake_case and kebab-case keys.

   NOTE: Args are keywordized by wrap-handler-context, so we only check keyword keys.
   MCP tools may use agent_id or agent-id naming convention.

   Returns default if no agent-id found in args."
  [args default]
  (or (:agent_id args)
      (:agent-id args)
      default))

(defn extract-caller-id
  "Extract the actual MCP caller identity for piggyback cursor isolation.

   Priority:
   1. :_caller_id — injected by bb-mcp from CLAUDE_SWARM_SLAVE_ID.
      Always present in modern bb-mcp, never overwritten by user args.
      Distinguishes coordinator ('coordinator') from lings ('ling-xyz').
   2. Fallback to 'coordinator' — for old bb-mcp versions without injection.

   CRITICAL: Do NOT use :agent_id or ctx/current-agent-id here.
   For dispatch-type tools, agent_id is the TARGET (e.g. 'ling-target'),
   not the caller. Using it would create per-target cursors causing
   re-delivery from timestamp 0 on every dispatch to a new target."
  [args]
  (or (:_caller_id args) "coordinator"))

(defn extract-project-id
  "Extract project-id from args map.

   NOTE: Args are keywordized by wrap-handler-context, so we only check keyword keys.

   Tries directory-based derivation if explicit project-id not found.
   Falls back to ctx/current-directory, then server's cwd as last resort.
   Returns nil only if no project context available anywhere.

   Uses request-level memoization for the expensive scope lookup
   (require + resolve + .hive-project.edn read). This is called 5x per
   request (context + 4 piggyback wrappers) with identical args — the
   cache eliminates 4 redundant scope lookups.

   Key priority:
   1. Explicit project_id/project-id
   2. IVessel resolution (vessel owns agent-to-context mapping)
   3. Derived from directory parameter via scope/get-current-project-id
   4. Derived from _caller_cwd (bb-mcp's cwd, injected per-request)
   5. Derived from ctx/current-directory (request context fallback)
   6. Derived from server's working directory (System/getProperty user.dir)"
  [args]
  (or (:project_id args)
      (:project-id args)
      ;; IVessel resolution: query vessels for agent context (formal project-id source).
      ;; Skip bare "coordinator" — it's a generic fallback, not a real agent ID.
      ;; Vessels return the server's own project for it, poisoning piggyback keys.
      (when-let [agent-id (or (:agent_id args) (:agent-id args) (:_caller_id args))]
        (when-not (= agent-id "coordinator")
          (ctx/request-memoize
           [:vessel-project-id agent-id]
           (fn []
             (try
               (require 'hive-mcp.protocols.vessel)
               (when-let [resolve-fn (resolve 'hive-mcp.protocols.vessel/resolve-agent-context)]
                 (:project-id (resolve-fn agent-id)))
               (catch Exception e (log/trace "routes: vessel resolution failed for" agent-id (.getMessage e)) nil))))))
      ;; Derive from directory if present, with caller-cwd, ctx and user.dir fallbacks
      (when-let [dir (or (:directory args)
                         (:_caller_cwd args)
                         (ctx/current-directory)
                         (System/getProperty "user.dir"))]
        (ctx/request-memoize
         [:project-id dir]
         (fn []
           (require 'hive-mcp.tools.memory.scope)
           ((resolve 'hive-mcp.tools.memory.scope/get-current-project-id) dir))))))

;; =============================================================================
;; ADT-Based Identity Extraction
;; =============================================================================

(defn extract-caller-identity
  "Extract CallerId ADT from MCP args.
   Wraps extract-caller-id with ADT coercion."
  [args]
  (ctx-id/parse-caller-id (:_caller_id args)))

(defn extract-project-scope
  "Extract ProjectScope ADT from MCP args.
   Wraps existing extract-project-id with ADT coercion."
  [args]
  (ctx-id/parse-project-scope (extract-project-id args)))

;; =============================================================================
;; Composable Handler Wrappers (SRP: Each wrapper single responsibility)
;; =============================================================================

(def ^:const hot-reload-retry-delay-ms
  "Delay between retries when hot-reload might have invalidated handlers."
  100)

(def ^:const hot-reload-max-retries
  "Maximum retries for hot-reload recovery."
  3)

(defn- hot-reload-error?
  "Check if exception indicates stale var references from hot-reload."

  [^Throwable e]
  (let [msg (str (.getMessage e))]
    (or (re-find #"(?i)var.*not.*found" msg)
        (re-find #"(?i)unbound|undefined" msg)
        (re-find #"(?i)no.*protocol.*method" msg)
        (instance? IllegalStateException e))))

(defn wrap-handler-retry
  "Wrap handler with retry logic for hot-reload resilience.
   
   
   When hot-reload occurs, in-flight tool calls may fail because:
   - Var references point to old, unloaded namespaces
   - Protocol implementations are temporarily unavailable
   
   This wrapper catches these transient errors and retries, giving
   time for refresh-tools! to complete."
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
;; NATS Tool Notification (mutating operations only)
;; =============================================================================

(def ^:private mutating-tool-commands
  "Consolidated tool + command combos that mutate state.
   Only these fire NATS notifications on hive.v1.tool.{tool-name} subjects.
   Read-only operations (query, search, status, list) are excluded."
  {"memory"  #{"add" "feedback"}
   "kanban"  #{"move" "create"}
   "session" #{"start" "stop"}
   "agent"   #{"spawn" "kill"}})

(defn- mutating-call?
  "Check if this tool+command combination is a mutating operation."
  [tool-name args]
  (when-let [commands (get mutating-tool-commands tool-name)]
    (contains? commands (some-> (:command args) name))))

(defn wrap-handler-nats-notify
  "Post-execution hook: publishes tool notification to NATS backbone for mutating operations.
   Non-fatal — failures logged at debug level, never propagated to caller.
   Uses requiring-resolve to avoid circular deps on hive-mcp.nats.bridge.

   Placement: innermost wrapper (between handler and retry) so notifications
   fire inside async futures too, not just for the ack."
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

(defn wrap-handler-context
  "Wrap handler to bind request context for tool execution.
   SRP: Single responsibility - context binding + args normalization + request cache.

   Normalizes args by keywordizing string keys (MCP SDK passes JSON with
   string keys, but handlers expect keyword keys for destructuring).

   Binds *request-cache* BEFORE extracting project-id so the first
   extract-project-id call is cached for the 4 subsequent calls in
   the piggyback wrappers (memory, catchup, hivemind, async).

   Extracts agent-id, project-id, directory from args and binds
   them via hive-mcp.agent.context/with-request-context.

   Directory fallback chain:
   1. Explicit :directory in args
   2. Server's working directory (System/getProperty user.dir)

   This ensures tools always have a directory context, preventing
   scope leakage where entries get stored as 'global' when callers
   don't explicitly pass directory.

   This enables tool handlers to access context via:
   - (ctx/current-agent-id)
   - (ctx/current-project-id)
   - (ctx/current-directory)
   - (ctx/request-memoize k f) for per-request caching

   BUG FIX: MCP clients send JSON args with string keys (e.g. {\"directory\" ...})
   but Clojure {:keys [directory]} destructuring only matches keyword keys.
   Keywordizing args ensures handlers work correctly with both MCP and direct calls."
  [handler]
  (fn [args]
    ;; Keywordize args to handle both MCP (string keys) and direct calls (keyword keys)
    (let [args (walk/keywordize-keys args)]
      ;; Bind request cache FIRST so extract-project-id's result is cached
      ;; for the 4 subsequent piggyback wrappers (saves 4x require+resolve+scope-lookup)
      (binding [ctx/*request-cache* (atom {})]
        (let [agent-id (extract-agent-id args nil)
              project-id (extract-project-id args)
              directory (or (:directory args)
                            (:_caller_cwd args)
                            (System/getProperty "user.dir"))]
          ;; Record session start on first tool call per agent (idempotent CAS).
          ;; Enables accurate session-timing-metadata in wrap/crystallize.
          (crystal/record-session-start! agent-id)
          (ctx/with-request-context {:agent-id agent-id
                                     :project-id project-id
                                     :directory directory}
            (handler args)))))))

(defn wrap-handler-normalize
  "Wrap handler to normalize its result to content array.
   SRP: Single responsibility - content normalization only.

   (wrap-handler-normalize handler) returns handler that:
   - Calls original handler
   - Normalizes result via normalize-content"
  [handler]
  (fn [args]
    (normalize-content (handler args))))

(defn- get-piggyback-messages
  "Get hivemind piggyback messages for agent+project.
   SRP: Single responsibility - piggyback retrieval.
   Encapsulates dynamic require/resolve pattern.

   CRITICAL: project-id scoping prevents cross-project shout leakage.
   Without it, coordinator-Y would consume shouts meant for coordinator-X."
  [agent-id project-id]
  (require 'hive-mcp.channel.piggyback)
  ((resolve 'hive-mcp.channel.piggyback/get-messages) agent-id :project-id project-id))

(defn- drain-memory-piggyback
  "Drain next batch of memory entries for a caller session.
   SRP: Single responsibility - memory piggyback retrieval.
   Returns drain result map or nil if nothing pending."
  [caller-id]
  (require 'hive-mcp.channel.memory-piggyback)
  ((resolve 'hive-mcp.channel.memory-piggyback/drain!) caller-id))

(defn wrap-memory-piggyback-content
  "Append memory piggyback batch to content with MEMORY delimiters.
   SRP: Single responsibility for memory piggyback embedding.

   Format:
   ---MEMORY---
   {:batch [...] :remaining N :total M :delivered D :seq S}
   ---/MEMORY---"
  [content drain-result]
  (wrap-delimited-block content "MEMORY" (when drain-result (pr-str drain-result))))

(defn wrap-handler-memory-piggyback
  "Wrap handler to attach memory piggyback entries.
   SRP: Single responsibility - memory piggyback embedding only.

   Drains next batch of buffered memory entries (axioms, conventions)
   within 32K char budget. Zero-cost when no entries pending.

   Runs BEFORE hivemind piggyback in the middleware chain so both
   channels can append to content independently.

   SESSION-SCOPED: Uses _caller_id only (no project dimension) for buffer
   key alignment with catchup enqueue. One bb-mcp instance = one session
   = one project, so caller-id alone provides sufficient isolation."
  [handler]
  (fn [args]
    (let [content (handler args)
          caller-id (or (:_caller_id args) "coordinator")
          drain-result (drain-memory-piggyback caller-id)]
      (wrap-memory-piggyback-content content drain-result))))

(defn wrap-handler-catchup-piggyback
  "Drain addon piggyback blocks as separate delimiter tags.
   Results arrive via piggyback on subsequent calls.
   Zero-cost when no blocks pending or extension not registered.

   SESSION-SCOPED: Uses _caller_id only for buffer key alignment with catchup enqueue."
  [handler]
  (fn [args]
    (let [content (handler args)]
      (if-let [drain-fn (ext/get-extension :cu/piggyback-drain)]
        (let [caller-id (or (:_caller_id args) "coordinator")
              blocks (try (drain-fn caller-id)
                          (catch Exception e
                            (log/debug "catchup-piggyback drain failed:" (.getMessage e))
                            nil))]
          (if (seq blocks)
            (reduce-kv
             (fn [c tag body]
               (wrap-delimited-block c
                                     (clojure.string/upper-case (name tag))
                                     (if (string? body) body (pr-str body))))
             content
             blocks)
            content))
        content))))

(defn wrap-handler-piggyback
  "Wrap handler to attach hivemind piggyback messages.
   SRP: Single responsibility - piggyback embedding only.

   Expects handler to return normalized content (vector of items).
   Uses caller identity for cursor tracking, retrieves piggyback,
   embeds in content.

   CRITICAL: project-id scoping ensures coordinators only see their
   project's shouts, preventing cross-project message consumption.

   CURSOR ISOLATION: Uses _caller_id (injected by bb-mcp) for per-caller
   cursor isolation. Each MCP session (coordinator, ling-1, ling-2) gets
   its own cursor, preventing lings from consuming coordinator's shouts.
   Falls back to 'coordinator' for old bb-mcp versions."
  [handler]
  (fn [args]
    (let [content (handler args)
          caller (extract-caller-identity args)
          scope (extract-project-scope args)
          agent-id (ctx-id/make-piggyback-agent-id caller scope)
          project-id (ctx-id/project-scope-string scope)
          piggyback (get-piggyback-messages agent-id project-id)]
      (wrap-piggyback content piggyback))))

(defn wrap-handler-default-async-for-commands
  "Inject :async true into args when command is in `commands` set and
   the caller did not set :async explicitly. Used by tools that want
   certain operations (typically writes) to default to queued execution
   while keeping reads synchronous.

   Sits OUTER than wrap-handler-async in the middleware chain so the
   downstream interceptor sees the injected flag. Opt-out: caller passes
   :async false. Opt-in for reads: caller passes :async true."
  [handler commands]
  (fn [{:keys [command] :as args}]
    (let [cmd-kw (when command (keyword command))
          should-default? (and (contains? commands cmd-kw)
                               (not (contains? args :async)))]
      (handler (cond-> args
                 should-default? (assoc :async true))))))

(defn wrap-handler-async
  "Wrap handler to intercept async tool calls.
   SRP: Single responsibility - async interception only.

   When args contain :async true, returns immediate ack and spawns
   a future for the real handler execution. The future's result is
   enqueued into the async-result buffer for piggyback delivery.

   When :async is absent or false, passes through to handler normally.

   Position in chain: AFTER normalize (so ack goes through piggyback chain)
   but BEFORE piggybacks (so ack still gets hivemind/memory blocks).

   SESSION-SCOPED: Uses _caller_id only for async buffer key alignment."
  [handler tool-name]
  (fn [args]
    (if (:async args)
      (let [task-id (str "atask-" (random-uuid))
            caller-id (or (:_caller_id args) "coordinator")]
        ;; Spawn background execution
        (future
          (try
            (let [;; Remove :async flag before passing to real handler
                  clean-args (dissoc args :async)
                  result (handler clean-args)]
              (async-buf/enqueue-result! caller-id
                                         {:task-id task-id
                                          :tool tool-name
                                          :status :completed
                                          :result result}))
            (catch Exception e
              (log/error e "async-result: background execution failed for task" task-id)
              (async-buf/enqueue-result! caller-id
                                         {:task-id task-id
                                          :tool tool-name
                                          :status :error
                                          :error (.getMessage e)}))))
        ;; Return immediate ack (goes through normalize → piggyback chain)
        [{:type "text"
          :text (pr-str {:queued true :task-id task-id :tool tool-name})}])
      ;; No async flag → pass through
      (handler args))))

(defn wrap-handler-async-piggyback
  "Wrap handler to drain async results as piggyback content.
   SRP: Single responsibility - async result delivery only.

   Drains completed async results for the calling session
   and appends them as ---TOOLRESULT--- delimited blocks.

   Zero-cost when no async results pending.

   Runs alongside memory-piggyback and hivemind-piggyback in the
   middleware chain so all three channels can append independently.

   SESSION-SCOPED: Uses _caller_id only for buffer key alignment
   with async enqueue.

   Format:
   ---TOOLRESULT---
   {:results [...] :remaining N :total M :delivered D}
   ---/TOOLRESULT---"
  [handler]
  (fn [args]
    (let [content (handler args)
          caller-id (or (:_caller_id args) "coordinator")
          drain-result (async-buf/drain! caller-id)]
      (wrap-delimited-block content "TOOLRESULT"
                            (when drain-result (pr-str drain-result))))))

(defn wrap-handler-compress
  "Wrap handler to apply response compression when `compact` param is present.
   SRP: Single responsibility - token-efficient response compression.

   Checks args for :compact param and applies compression mode:
   - compact: true         → :compact mode (strip verbose fields + omit defaults)
   - compact: \"minimal\"  → :minimal mode (IDs + preview + abbreviated keys)
   - compact: \"compact\"  → :compact mode
   - compact: false/nil    → passthrough (no compression)

   Position in chain: AFTER normalize (content array exists) and BEFORE
   piggybacks (don't compress hivemind/memory blocks).

   See hive-mcp.dsl.response for compression implementation."
  [handler]
  (fn [args]
    (let [mode (compress/resolve-compress-mode args)
          content (handler args)]
      (if mode
        (compress/compress-content content mode)
        content))))

(defn wrap-handler-piggybacks
  "Unified piggyback wrapper — drains all 4 channels in a single pass.
   Replaces individual async/memory/catchup/hivemind piggyback wrappers
   in the make-tool chain, reducing middleware from 10 to 7 layers.

   Extracts caller-id ONCE (was extracted 3x independently), drains all
   channels, appends all delimited blocks to content.

   Channel drain order (append order):
   1. TOOLRESULT — async completion results
   2. MEMORY — axioms, conventions, enrichment batches
   3. Catchup enrichment blocks — dynamic tags (synthesis, kg-insights, context)
   4. HIVEMIND — agent shouts

   SESSION-SCOPED: Uses _caller_id for buffer key alignment (channels 1-3).
   PROJECT-SCOPED: Hivemind uses ADT identity extraction for cursor isolation."
  [handler]
  (fn [args]
    (let [content (handler args)
          caller-id (or (:_caller_id args) "coordinator")

          ;; 1. Async results
          async-drain (async-buf/drain! caller-id)

          ;; 2. Memory entries
          memory-drain (drain-memory-piggyback caller-id)

          ;; 3. Catchup enrichment (extension-based, may not be registered)
          catchup-blocks (when-let [drain-fn (ext/get-extension :cu/piggyback-drain)]
                           (try (drain-fn caller-id)
                                (catch Exception e
                                  (log/debug "catchup-piggyback drain failed:" (.getMessage e))
                                  nil)))

          ;; 4. Hivemind (ADT identity for cursor isolation)
          caller (extract-caller-identity args)
          scope (extract-project-scope args)
          hm-agent-id (ctx-id/make-piggyback-agent-id caller scope)
          hm-project-id (ctx-id/project-scope-string scope)
          hivemind-msgs (get-piggyback-messages hm-agent-id hm-project-id)]

      (cond-> content
        async-drain
        (wrap-delimited-block "TOOLRESULT" (pr-str async-drain))

        memory-drain
        (wrap-memory-piggyback-content memory-drain)

        (seq catchup-blocks)
        (as-> c (reduce-kv
                  (fn [acc tag body]
                    (wrap-delimited-block acc
                      (clojure.string/upper-case (name tag))
                      (if (string? body) body (pr-str body))))
                  c catchup-blocks))

        true
        (wrap-piggyback hivemind-msgs)))))

(defn wrap-handler-response
  "Wrap handler to build SDK response format.
   SRP: Single responsibility - response building only.

   Wraps handler result in {:content ...} map."
  [handler]
  (fn [args]
    {:content (handler args)}))

;; =============================================================================
;; Tool Definition Conversion
;; =============================================================================

(s/fdef make-tool
  :args (s/cat :tool-def ::tool-def)
  :ret ::tool-response)

(defn make-tool
  "Convert a tool definition with :handler to SDK format.
   Wraps handler to attach pending hivemind messages via content embedding.

   Uses composable handler wrappers (SRP: each wrapper single responsibility):
   - wrap-handler-nats-notify: publishes NATS notification for mutating ops (non-fatal)
   - wrap-handler-retry: auto-retry on hot-reload transient errors
   - wrap-handler-async: intercept async:true calls, return ack, spawn future
   - wrap-handler-normalize: converts result to content array
   - wrap-handler-compress: compact:true → compress JSON text (strip verbose fields)
   - wrap-handler-piggybacks: unified drain of all 4 channels (async, memory, catchup, hivemind)
   - wrap-handler-context: binds request context for tool execution
   - wrap-handler-response: builds {:content ...} response

   Composition via -> threading (8-layer chain):
   handler -> nats-notify -> retry -> async-intercept -> normalize -> compress -> piggybacks -> context -> response

   The NATS notifier is innermost (between handler and retry) so it fires
   inside async futures too, not just for the ack. Only mutating operations
   (memory add/feedback, kanban move/create, session start/stop, agent spawn/kill)
   publish notifications. Non-fatal: failures logged at debug, never propagated.

   The async interceptor sits AFTER retry (so it can retry on hot-reload errors)
   but BEFORE normalize (so the ack [{:type text}] passes through the piggyback chain).

   The unified piggybacks wrapper drains all 4 channels (async, memory, catchup,
   hivemind) in a single pass, extracting caller-id once instead of 3x.

   REQUEST-LEVEL MEMOIZATION: context wrapper binds *request-cache* (atom {})
   before any work. extract-project-id uses ctx/request-memoize to cache its
   expensive scope lookup — called 2x per request (context + piggybacks wrapper)
   but computed only once. Handlers can also use request-memoize for their own
   per-request caching needs.

   CRITICAL: context must wrap piggybacks so ctx/current-directory is bound
   when extract-project-id runs."
  [{:keys [name description inputSchema handler deprecated default-async-commands]}]
  (let [schema-ext (ext/get-schema-extensions name)
        merged-schema (if schema-ext
                        (update inputSchema :properties merge schema-ext)
                        inputSchema)]
    (cond-> {:name name
             :description description
             :inputSchema merged-schema
             :handler (-> handler
                          (wrap-handler-nats-notify name) ; NATS notification for mutating ops
                          wrap-handler-retry              ; Hot-reload resilience
                          (wrap-handler-async name)       ; async:true → ack + future
                          (cond-> (seq default-async-commands)
                            (wrap-handler-default-async-for-commands default-async-commands))
                          wrap-handler-normalize
                          wrap-handler-compress           ; compact: true → compress JSON text
                          wrap-handler-piggybacks         ; unified: 4 channels in 1 pass
                          wrap-handler-context            ; binds ctx for piggybacks
                          wrap-handler-response)}
      deprecated (assoc :deprecated true))))

;; =============================================================================
;; Server Spec Building
;; =============================================================================

(defn build-server-spec
  "Build MCP server spec with role-based and capability-based tool filtering.

   MUST be called AFTER init-embedding-provider! to get accurate Chroma status.

   ROLE BRANCHING (Self-Call Prevention):
   - Coordinator (default): Full tool set including deprecated shims
   - Child ling (HIVE_MCP_ROLE=child-ling): Restricted tool set excluding
     agent, wave, workflow, multi, delegate, olympus, emacs.
     Prevents recursive spawning chains: Ling->agent.spawn->Ling->...

   PHASE 2 STRANGLE: For coordinator, includes ALL tools (deprecated have :deprecated true).
   The server.clj multimethod override filters deprecated from tools/list response.

   Uses tools/get-all-tools for dynamic kanban tool switching:
   - Chroma available -> mcp_mem_kanban_* tools
   - Chroma unavailable -> org_kanban_native_* tools (fallback)

   Deprecated tools are (coordinator only):
   - INCLUDED in spec with :deprecated true (callable via tools/call)
   - FILTERED by server.clj multimethod override (hidden from tools/list)"
  []
  (let [dynamic-tools (ext/get-registered-tools)
        addon-tools   (addons/active-addon-tools)]
    (if-let [_ (when (guards/child-ling?) true)]
      ;; Child ling: restricted tool set (no agent/wave/workflow/multi/delegate/olympus/emacs)
      (let [child-tools (tools/get-child-ling-tools)
            role (guards/get-role)
            depth (guards/ling-depth)]
        (log/info "Building CHILD LING server spec with" (count child-tools) "tools"
                  "(role:" role "depth:" depth
                  "excluded:" (count tools/child-excluded-tool-names) "tool categories)"
                  "dynamic:" (count dynamic-tools) "addon:" (count addon-tools))
        {:name "hive-mcp"
         :version "0.1.0"
         :tools (mapv make-tool (concat child-tools
                                        dynamic-tools addon-tools))})
      ;; Coordinator: full tool set including deprecated shims
      (let [all-tools (tools/get-all-tools :include-deprecated? true)
            deprecated-count (count (filter :deprecated all-tools))
            visible-count (- (count all-tools) deprecated-count)]
        (log/info "Building server spec with" (count all-tools) "tools"
                  "(" visible-count "visible," deprecated-count "deprecated)"
                  "dynamic:" (count dynamic-tools) "addon:" (count addon-tools))
        {:name "hive-mcp"
         :version "0.1.0"
         :tools (mapv make-tool (concat all-tools
                                        dynamic-tools addon-tools))}))))

;; DEPRECATED: Static spec kept for backward compatibility with tests
;; Prefer build-server-spec for capability-aware tool list
(def emacs-server-spec
  {:name "hive-mcp"
   :version "0.1.0"
   ;; hivemind/tools already included in tools/tools aggregation
   :tools (mapv make-tool tools/tools)})

;; =============================================================================
;; Hot-Reload Support
;; =============================================================================

(defn refresh-tools!
  "Hot-reload all tools in the running server.

   Uses role-based and capability-based filtering:
   - Coordinator: re-checks Chroma, includes deprecated shims
   - Child ling: restricted tool set (same as build-server-spec)

   PHASE 2 STRANGLE: For coordinator, registers ALL tools (including deprecated).
   Deprecated tools are excluded from tools/list but remain callable for
   backward compatibility during the grace period (sunset: 2026-04-01).

   Parameters:
     server-context-atom - atom containing the server context with :tools key

   Returns:
     count of tools refreshed, or nil if no context"
  [server-context-atom]
  (when-let [context @server-context-atom]
    (let [tools-atom (:tools context)
          child? (guards/child-ling?)
          ;; Branch tool selection on role
          selected-tools (if child?
                           (tools/get-child-ling-tools)
                           (tools/get-all-tools :include-deprecated? true))
          new-tools (mapv make-tool selected-tools)
          deprecated-count (if child?
                             0
                             (count (filter :deprecated selected-tools)))]
      ;; Clear and re-register all tools
      (reset! tools-atom {})
      (doseq [tool new-tools]
        (swap! tools-atom assoc (:name tool) {:tool (dissoc tool :handler)
                                              :handler (:handler tool)}))
      (if child?
        (log/info "Hot-reloaded" (count new-tools) "tools (child-ling restricted)")
        (log/info "Hot-reloaded" (count new-tools) "tools"
                  "(including" deprecated-count "deprecated shims for backward compat)"))
      (count new-tools))))

(defn debug-tool-handler
  "Get info about a registered tool handler (for debugging).

   Parameters:
     server-context-atom - atom containing the server context
     tool-name - string name of the tool to inspect

   Returns:
     map with :name, :handler-class, :tool-keys or nil if not found"
  [server-context-atom tool-name]
  (when-let [context @server-context-atom]
    (let [tools-atom (:tools context)
          tool-entry (get @tools-atom tool-name)]
      (when tool-entry
        {:name tool-name
         :handler-class (str (type (:handler tool-entry)))
         :tool-keys (keys (:tool tool-entry))}))))

;; =============================================================================
;; Tool Registration for Agent Delegation
;; =============================================================================

(defn register-tools-for-delegation!
  "Register tools for agent delegation with role-based filtering.

   Coordinator: Includes deprecated tools for backward compatibility.
   Child ling: Restricted tool set (no agent/wave/workflow/multi/delegate).

   Delegates to hive-mcp.agent.core/register-tools! with selected tools.

   Returns:
     count of tools registered"
  []
  (require 'hive-mcp.agent.core)
  (let [register-tools! (resolve 'hive-mcp.agent.core/register-tools!)
        child? (guards/child-ling?)
        selected-tools (if child?
                         (tools/get-child-ling-tools)
                         (tools/get-all-tools :include-deprecated? true))
        deprecated-count (if child?
                           0
                           (count (filter :deprecated selected-tools)))]
    (register-tools! selected-tools)
    (if child?
      (log/info "Registered" (count selected-tools) "tools for child-ling delegation (restricted)")
      (log/info "Registered" (count selected-tools) "tools for agent delegation"
                "(including" deprecated-count "deprecated shims)"))
    (count selected-tools)))
