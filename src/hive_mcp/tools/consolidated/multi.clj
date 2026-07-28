(ns hive-mcp.tools.consolidated.multi
  "Single MCP entry point routing to all consolidated tools.

   Supports three input modes:
   - Single dispatch: {tool, command, ...params}
   - Batch dispatch: {operations: [...]} with dependency-ordered wave execution
   - DSL dispatch: {dsl: [[verb, params], ...]} compiled to batch operations

   Plus async execution for long-running batches."
  (:require [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.dns.result :refer [rescue]]
            [clojure.string :as str]
            [hive-mcp.multi.registry :as multi-registry]
            [hive-mcp.multi.registry.tools :as r-tools]
            [hive-mcp.agent.context :as ctx]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn tool-names
  "Sorted vec of all consolidated tool names registered via multi.registry.
   Read at call time so addons that register tools post-boot are visible."
  []
  (r-tools/all-names))

(defn get-tool-handler
  "Resolve a consolidated tool handler by name; nil for a nil or unknown tool.
   Routes through `multi.registry/resolve-tool-handler` (DIP) — covers the
   :multi/core seed AND any addon-contributed tools, with a legacy
   flat-tool fallback for non-consolidated tools."
  [tool-name]
  (when tool-name
    (multi-registry/resolve-tool-handler (name tool-name))))

;; ── Lazy Resolution Helpers ───────────────────────────────────────────────────

(defn- resolve-or-err
  "Lazily resolve a fully-qualified symbol. Returns the fn or nil.
   The category argument documents the caller's failure taxonomy."
  [sym _category]
  (rescue nil
          (requiring-resolve sym)))

(defn- resolve-run-multi []
  (resolve-or-err 'hive-mcp.tools.multi/run-multi :batch-resolve-error))

(defn- resolve-format-results []
  (resolve-or-err 'hive-mcp.tools.multi/format-results :format-resolve-error))

(defn- resolve-compile-paragraph []
  (resolve-or-err 'hive-mcp.dsl.verbs/compile-paragraph :dsl-resolve-error))

(defn- resolve-async-fn
  "Lazily resolve a function from hive-mcp.tools.multi-async namespace."
  [fn-name]
  (resolve-or-err (symbol "hive-mcp.tools.multi-async" (name fn-name))
                  :async-resolve-error))

;; ── Help ──────────────────────────────────────────────────────────────────────

(defn- format-multi-help
  "Format help listing all available tools and their commands."
  []
  (str "Multi tool — single entry point for all hive-mcp operations.\n\n"
       "== Single Dispatch ==\n"
       "  multi {\"tool\": \"<name>\", \"command\": \"<cmd>\", ...params}\n\n"
       "== Batch Dispatch ==\n"
       "  multi {\"operations\": [{\"id\": \"op1\", \"tool\": \"memory\", \"command\": \"add\", ...},\n"
       "                         {\"id\": \"op2\", \"tool\": \"kg\", \"command\": \"edge\", \"depends_on\": [\"op1\"]}],\n"
       "         \"dry_run\": false}\n"
       "  Operations are topologically sorted by depends_on and executed in waves.\n"
       "  Independent ops run in parallel within each wave.\n\n"
       "== DSL Dispatch (verb syntax) ==\n"
       "  multi {\"dsl\": [[\"m+\", {\"c\": \"hello\", \"t\": \"note\"}],\n"
       "                   [\"k>\", {\"from\": \"$0\", \"to\": \"node-2\", \"rel\": \"implements\"}]]}\n"
       "  Ops are numbered from $0. In a node-id param (from/to/node_id/...), a bare\n"
       "  op id refers to that op's result id: \"$0\" expands to \"$ref:$0.data.id\".\n"
       "  Verbs: m+ m? m@ m/ (memory), k> k^ k! k# (kg), a+ a? a! ax (agent),\n"
       "         b+ b> b? b# (kanban), s. s~ s? s< (session), g? g+ g! g> (magit),\n"
       "         w! w? wy wn (wave), h! h? (hivemind), p? p@ p/ (preset), c? c! c* (config)\n"
       "  Param aliases: c→content, t→type, #→tags, d→directory, q→query, n→name, f→files\n\n"
       "== Async Execution ==\n"
       "  multi {\"operations\": [...], \"async\": true}       → returns {\"batch_id\": \"...\"}\n"
       "  multi {\"dsl\": [...], \"async\": true}              → returns {\"batch_id\": \"...\"}\n"
       "  multi {\"command\": \"collect\", \"batch_id\": \"...\"}  → get async batch results\n"
       "  multi {\"command\": \"list-async\"}                  → list pending async batches\n"
       "  multi {\"command\": \"cancel-async\", \"batch_id\": \"...\"}  → cancel running batch\n\n"
       "Available tools:\n"
       (str/join "\n" (map #(str "  - " %) (tool-names)))
       "\n\nTo see commands for a specific tool:\n"
       "  multi {\"tool\": \"memory\", \"command\": \"help\"}\n\n"
       "All additional params are forwarded to the target tool handler."))

;; ── Batch Dispatch ────────────────────────────────────────────────────────────

(defn- dispatch-async
  "Dispatch operations asynchronously, returning batch-id immediately."
  [normalized-ops {:keys [dry_run]}]
  (if-let [async-fn (resolve-async-fn 'run-multi-async)]
    (try
      (let [result (async-fn normalized-ops (cond-> {}
                                              dry_run (assoc :dry-run true)))]
        {:type "text" :text (pr-str result)})
      (catch Exception e
        (mcp-error (str "Async dispatch failed: " (ex-message e)))))
    (mcp-error (pr-str {:error "Async module not loaded"
                        :hint "hive-mcp.tools.multi-async is not available"}))))

(defn- thread-caller-directory
  "Add the request directory to operations that do not carry their own scope."
  [operations params]
  (let [directory (ctx/resolve-caller-directory params)]
    (mapv (fn [operation]
            (let [operation (rb/keywordize-map operation)]
              (cond-> operation
                (and directory
                     (nil? (:directory operation))
                     (nil? (:_caller_cwd operation)))
                (assoc :directory directory))))
          operations)))

(defn- dispatch-sync
  "Execute operations synchronously via batch engine."
  [normalized-ops {:keys [dry_run] :as params}]
  (let [run-multi-fn (resolve-run-multi)
        format-fn    (resolve-format-results)
        compact-mode (rescue nil
                             (when-let [resolve-fn (requiring-resolve
                                                    'hive-mcp.dsl.response/resolve-compress-mode)]
                               (resolve-fn params)))]
    (if-not run-multi-fn
      (mcp-error "Batch execution engine not available (hive-mcp.tools.multi/run-multi could not be resolved)")
      (let [result (if dry_run
                     (run-multi-fn normalized-ops :dry-run true)
                     (run-multi-fn normalized-ops))]
        (if format-fn
          (format-fn result :compact compact-mode)
          {:type "text" :text (pr-str result)})))))

(defn- handle-batch
  "Handle batch dispatch mode with dependency-ordered wave execution.
   Supports async: true for non-blocking dispatch."
  [{:keys [operations async] :as params}]
  (cond
    (nil? operations)
    (mcp-error "Batch mode requires 'operations' array. Each op: {id, tool, command, ...params, depends_on?: [ids]}")

    (not (sequential? operations))
    (mcp-error "operations must be an array of {id, tool, command, ...} objects")

    (empty? operations)
    (mcp-error "operations array is empty. Provide at least one operation.")

    :else
    (let [normalized-ops (thread-caller-directory operations params)]
      (if async
        (dispatch-async normalized-ops params)
        (dispatch-sync normalized-ops params)))))

;; =============================================================================
;; DSL Handling
;; =============================================================================

(defn- handle-dsl
  "Handle DSL verb input: compile paragraph → run via batch engine."
  [{:keys [dsl] :as params}]
  (if-let [compile-fn (resolve-compile-paragraph)]
    (try
      (let [ops (compile-fn dsl)]
        (handle-batch (-> params
                          (dissoc :dsl)
                          (assoc :operations ops))))
      (catch Exception e
        (mcp-error (str "DSL compilation failed: " (ex-message e)))))
    (mcp-error (pr-str {:error "DSL module not loaded"
                        :hint "hive-mcp.dsl.verbs is not available"}))))

;; =============================================================================
;; Async Commands
;; =============================================================================

(defn- handle-async-collect
  "Collect results from an async batch by batch_id."
  [{:keys [batch_id]}]
  (if (str/blank? (str batch_id))
    (mcp-error "collect requires 'batch_id' parameter")
    (if-let [collect-fn (resolve-async-fn 'collect-async-result)]
      (try
        {:type "text" :text (pr-str (collect-fn (str batch_id)))}
        (catch Exception e
          (mcp-error (str "Collect failed: " (ex-message e)))))
      (mcp-error (pr-str {:error "Async module not loaded"
                          :hint "hive-mcp.tools.multi-async is not available"})))))

(defn- handle-async-list
  "List all pending/completed async batches."
  [_params]
  (if-let [list-fn (resolve-async-fn 'list-async-batches)]
    (try
      {:type "text" :text (pr-str (list-fn))}
      (catch Exception e
        (mcp-error (str "List async failed: " (ex-message e)))))
    (mcp-error (pr-str {:error "Async module not loaded"
                        :hint "hive-mcp.tools.multi-async is not available"}))))

(defn- handle-async-cancel
  "Cancel a running async batch by batch_id."
  [{:keys [batch_id]}]
  (if (str/blank? (str batch_id))
    (mcp-error "cancel-async requires 'batch_id' parameter")
    (if-let [cancel-fn (resolve-async-fn 'cancel-async-batch)]
      (try
        {:type "text" :text (pr-str (cancel-fn (str batch_id)))}
        (catch Exception e
          (mcp-error (str "Cancel failed: " (ex-message e)))))
      (mcp-error (pr-str {:error "Async module not loaded"
                          :hint "hive-mcp.tools.multi-async is not available"})))))

;; =============================================================================
;; Plan / Run (PR5 — persistent compile-then-run)
;; =============================================================================

(defn- resolve-plan-fn [fn-name]
  (resolve-or-err (symbol "hive-mcp.multi.plan" (name fn-name)) :plan-resolve-error))

(defn- result->mcp
  "Convert a hive-dsl.result Result map to an MCP envelope.

   Result shape: {:ok value} | {:error category ...extra}"
  [result]
  (cond
    (and (map? result) (contains? result :error))
    (mcp-error (str (:error result) ": "
                    (or (:message result) (pr-str (dissoc result :error)))))

    (and (map? result) (contains? result :ok))
    {:type "text" :text (pr-str (:ok result))}

    :else
    {:type "text" :text (pr-str result)}))

(defn- handle-plan
  "Compile ops/dsl into a persisted plan, return plan-id.

   Accepts EITHER `:operations` (raw op vector) OR `:dsl` (verb sentences)."
  [{:keys [operations dsl reason directory]}]
  (let [compile-fn (resolve-plan-fn 'compile-and-persist!)
        compile-paragraph (resolve-compile-paragraph)
        ops (cond
              (sequential? operations)
              (mapv rb/keywordize-map operations)

              (sequential? dsl)
              (when compile-paragraph (compile-paragraph dsl))

              :else nil)]
    (cond
      (or (nil? ops) (empty? ops))
      (mcp-error "plan requires non-empty 'operations' or 'dsl' input")

      (nil? compile-fn)
      (mcp-error "multi.plan/compile-and-persist! not resolvable")

      :else
      (result->mcp (compile-fn ops {:reason reason :directory directory})))))

(defn- handle-run
  "Execute a previously persisted plan by `plan_id`."
  [{:keys [plan_id]}]
  (cond
    (str/blank? (str plan_id))
    (mcp-error "run requires 'plan_id' parameter (use command='plan' to obtain one)")

    :else
    (if-let [run-fn (resolve-plan-fn 'run!)]
      (result->mcp (run-fn (str plan_id) {}))
      (mcp-error "multi.plan/run! not resolvable"))))

;; =============================================================================
;; Main Router
;; =============================================================================

(defn handle-multi
  "Route to consolidated tool by :tool param, forwarding remaining params.

   Supports five dispatch modes:
   1. DSL mode: :dsl present → compile verbs → batch execute
   2. Batch mode: :operations present → dependency-ordered wave execution
   3. Async commands: :command in #{collect, list-async, cancel-async}
   4. Single dispatch: :tool + :command → route to consolidated handler
   5. Help: no tool/operations/dsl → show help text"
  [params]
  (let [normalized (rb/keywordize-map params)
        {:keys [tool command operations dsl]} normalized]
    (cond
      ;; Mutual exclusion: dsl and operations cannot both be present
      (and (some? dsl) (some? operations))
      (mcp-error "Cannot specify both 'dsl' and 'operations'. Use one input mode.")

      ;; DSL mode: dsl present → compile and execute
      (some? dsl)
      (handle-dsl normalized)

      ;; Async commands (no tool required)
      (= "collect" (str command))
      (handle-async-collect normalized)

      (= "list-async" (str command))
      (handle-async-list normalized)

      (= "cancel-async" (str command))
      (handle-async-cancel normalized)

      ;; Plan/Run commands (PR5 — persistent compile-then-run)
      (= "plan" (str command))
      (handle-plan normalized)

      (= "run" (str command))
      (handle-run normalized)

      ;; Batch mode: operations present, no tool specified
      (and (some? operations) (or (nil? tool) (str/blank? (str tool))))
      (handle-batch normalized)

      ;; No tool and no operations -- show help
      (or (nil? tool) (str/blank? (str tool)))
      {:type "text" :text (format-multi-help)}

      ;; Help command at multi level
      (and (= "help" (str tool)) (nil? command))
      {:type "text" :text (format-multi-help)}

      ;; Route to consolidated handler (single dispatch) via the multi.registry —
      ;; covers :multi/core seed + addon contributions, with legacy fallback.
      :else
      (let [tool-str (str/lower-case (str tool))
            handler  (get-tool-handler tool-str)]
        (if handler
          (handler (dissoc normalized :tool))
          (mcp-error (str "Unknown tool: " tool-str
                          ". Available: " (str/join ", " (tool-names)))))))))

(def tool-def
  {:name "multi"
   :consolidated true
   :description (str "Unified entry point for ALL hive-mcp operations. "
                     "Routes to any consolidated tool via {tool, command, ...params}. "
                     "Tools: " (str/join ", " (tool-names)) ". "
                     "Example: {\"tool\": \"memory\", \"command\": \"add\", \"content\": \"...\"}. "
                     "Use {\"tool\": \"<name>\", \"command\": \"help\"} to list commands for a tool. "
                     "All additional params beyond these common ones are forwarded to the target tool. "
                     "Key tool-specific params: "
                     "addon: addon_id, directory, emacs_features, timeout_ms; "
                     "kanban: status, new_status, task_id, title, include_descendants, plan_id, plan_path; "
                     "agent: type, cwd, spawn_mode, presets, task, model, provider, max_budget_usd, parent, kanban_task_id; "
                     "memory: duration, abstraction_level, scope, exclude_tags, limit, verbosity, feedback; "
                     "kg: start_node, node_id, from, to, relation, direction, max_depth, from_node, to_node, confidence; "
                     "wave: tasks, validate, lint_level, wave_id, mode, model, seeds, ctx_refs, kg_node_ids; "
                     "session: commit_msg, task_ids, ctx_id, data, ttl_ms, scope; "
                     "magit: target, count, all, set_upstream, remote; "
                     "emacs: code, buffer, file, line, text, level, function_name, variable_name, pattern. DSL aliases: c=content, t=type, #=tags, d=directory, q=query, n=name, id=id, p=prompt, f=files.")
   :inputSchema {:type "object"
                 :properties {"tool"    {:type "string"
                                         :enum (vec (tool-names))
                                         :description "Target consolidated tool name"}
                              "command" {:type "string"
                                         :description (str "Subcommand for the target tool (e.g. 'add', 'spawn', 'status'). "
                                                           "Also accepts async commands without tool: 'collect', 'list-async', 'cancel-async'")}
                              "directory"  {:type "string"
                                            :description "Working directory for project-scoped operations"}
                              "addon_id"   {:type "string"
                                            :description "Addon ID for addon doctor (e.g. hive.emacs)"}
                              "emacs_features" {:type "array"
                                                :items {:type "string"}
                                                :description "Expected Emacs features for addon doctor; overrides manifest hints"}
                              "timeout_ms" {:type "integer"
                                            :minimum 1
                                            :maximum 30000
                                            :description "Per-feature timeout for addon doctor"}
                              "agent_id"   {:type "string"
                                            :description "Agent identifier for attribution/routing"}
                              "id"         {:type "string"
                                            :description "Entity ID (memory entry, task, node, etc.)"}
                              "content"    {:type "string"
                                            :description "Content for add/create operations"}
                              "type"       {:type "string"
                                            :description "Entity type (note, snippet, ling, drone, etc.)"}
                              "tags"       {:type "array"
                                            :items {:type "string"}
                                            :description "Tags for categorization/filtering"}
                              "query"      {:type "string"
                                            :description "Search query (semantic or text)"}
                              "name"       {:type "string"
                                            :description "Name identifier (agent, preset, etc.)"}
                              "message"    {:type "string"
                                            :description "Message content (shout, commit, etc.)"}
                              "prompt"     {:type "string"
                                            :description "Task prompt for dispatch operations"}
                              "files"      {:type "array"
                                            :items {:type "string"}
                                            :description "File paths for file-scoped operations"}
                              "operations" {:type "array"
                                            :items {:type "object"}
                                            :description (str "Batch operation array. Each op: {id, tool, command, ...params, depends_on?: [ids]}. "
                                                              "Mutually exclusive with 'dsl'.")}
                              "dsl"        {:type "array"
                                            :items {:type "array"}
                                            :description (str "DSL verb sentences as [verb, params] tuples. "
                                                              "Compiled to batch operations automatically. "
                                                              "Verbs: m+ (memory add), m? (query), m@ (get), m/ (search), "
                                                              "k> (kg edge), k^ (traverse), k! (impact), k# (stats), "
                                                              "a+ (agent spawn), a? (status), a! (dispatch), ax (kill), "
                                                              "b+ (kanban create), b> (update), b? (list), b# (status), "
                                                              "s. (session complete), s~ (wrap), s? (whoami), s< (catchup), "
                                                              "g? (git status), g+ (stage), g! (commit), g> (push), "
                                                              "w! (wave dispatch), w? (status), wy (approve), wn (reject), "
                                                              "h! (hivemind shout), h? (ask), "
                                                              "p? (preset list), p@ (get), p/ (search), "
                                                              "c? (config get), c! (set), c* (list). "
                                                              "Param aliases: c=content, t=type, #=tags, d=directory, "
                                                              "q=query, n=name, id=id, p=prompt, f=files. "
                                                              "Ops are numbered from $0. In a node-id param (from/to/node_id/...), "
                                                              "a bare op id refers to that op's result id: \"$0\" expands to "
                                                              "\"$ref:$0.data.id\". Anywhere else, use the explicit \"$ref:$0.<path>\" form. "
                                                              "Example: [[\"m+\", {\"c\": \"note text\", \"t\": \"note\"}], "
                                                              "[\"k>\", {\"from\": \"$0\", \"to\": \"node-2\", \"rel\": \"implements\"}]]. "
                                                              "Mutually exclusive with 'operations'.")}
                              ;; Non-scalar params forwarded to target tools
                              "fields" {:type "array"
                                        :items {:type "string"}
                                        :description "[kanban/project list] Project each result to a subset of fields (e.g. ['id' 'title'])"}
                              "ids" {:type "array"
                                     :items {:type "string"}
                                     :description "[memory batch-get/batch-reembed, kg summarize] Entry or node IDs"}
                              "entry-ids" {:type "array"
                                           :items {:type "string"}
                                           :description "[memory migrate-scoped] Entry IDs"}
                              "task_ids" {:type "array"
                                          :items {:type "string"}
                                          :description "[session/workflow] Kanban task IDs"}
                              "add_tags" {:type "array"
                                          :items {:type "string"}
                                          :description "[kanban retag] Extra tags to add"}
                              "remove_tags" {:type "array"
                                             :items {:type "string"}
                                             :description "[kanban retag] Tags to remove"}
                              "exclude_tags" {:type "array"
                                              :items {:type "string"}
                                              :description "[memory query/search] Tags to exclude from results"}
                              "agent_ids" {:type "array"
                                           :items {:type "string"}
                                           :description "[agent kill-batch] Agent IDs"}
                              "agent-ids" {:type "array"
                                           :items {:type "string"}
                                           :description "[events enable/disable] Agent IDs to filter on"}
                              "kg_node_ids" {:type "array"
                                             :items {:type "string"}
                                             :description "[agent/session/wave] KG node IDs for context resolution seeds"}
                              "kg_depends_on" {:type "array"
                                               :items {:type "string"}
                                               :description "[memory add] Entry IDs this depends on (KG edge)"}
                              "kg_implements" {:type "array"
                                               :items {:type "string"}
                                               :description "[memory add] Entry IDs this implements (KG edge)"}
                              "kg_refines" {:type "array"
                                            :items {:type "string"}
                                            :description "[memory add] Entry IDs this refines (KG edge)"}
                              "kg_supersedes" {:type "array"
                                               :items {:type "string"}
                                               :description "[memory add] Entry IDs this supersedes (KG edge)"}
                              "presets" {:type "array"
                                         :items {:type "string"}
                                         :description "[agent/preset/workflow] Preset names"}
                              "predicates" {:type "array"
                                            :items {:type "string"}
                                            :description "[workflow] Named predicates available at run time"}
                              "participants" {:type "array"
                                              :items {:type "string"}
                                              :description "[agora] Ling slave-ids (min 2)"}
                              "options" {:type "array"
                                         :items {:type "string"}
                                         :description "[hivemind ask] Available options"}
                              "relations" {:type "array"
                                           :items {:type "string"}
                                           :description "[kg] Relation types to follow"}
                              "exclude_relations" {:type "array"
                                                   :items {:type "string"}
                                                   :description "[kg] Relation types to exclude"}
                              "seen" {:type "array"
                                      :items {:type "string"}
                                      :description "[kg] Already-visited node IDs for frontier"}
                              "seeds" {:type "array"
                                       :items {:type "string"}
                                       :description "[kg connect] Node IDs (>=2) / [wave] domain topic seeds"}
                              "diff_ids" {:type "array"
                                          :items {:type "string"}
                                          :description "[wave approve] Specific diff IDs to approve"}
                              "tasks" {:type "array"
                                       :items {:type "object"}
                                       :description "[wave dispatch] Array of {file, task} objects"}
                              "roles" {:type "array"
                                       :items {:type "object"}
                                       :description "[agora] Debate roles"}
                              "debate_roles" {:type "array"
                                              :items {:type "object"}
                                              :description "[agora] Debate roles for stage 2"}
                              "research_roles" {:type "array"
                                                :items {:type "object"}
                                                :description "[agora] Research roles for stage 1"}
                              "inputs" {:type "array"
                                        :description "[kg datalog] Additional Datalog inputs bound by the query's :in clause"}
                              "ctx_refs" {:type "object"
                                          :description "[agent/session/wave] Map of category->ctx-id for compressed context"}
                              "agents" {:type "object"
                                        :description "[agent spawn] Map of agent-name to Claude Agent SDK subagent definition"}
                              "config" {:type "object"
                                        :description "[agora] Optional {threshold, timeout-ms}"}
                              "data" {:type "object"
                                      :description "[hivemind] Additional event data / [session context-put] structured data to store"}
                              "async"      {:type "boolean"
                                            :description (str "When true, execute batch/DSL asynchronously. "
                                                              "Returns {batch_id: \"...\"} immediately. "
                                                              "Use command 'collect' with batch_id to retrieve results later.")}
                              "batch_id"   {:type "string"
                                            :description "Batch ID for async commands: collect, cancel-async"}
                              "parallel"   {:type "boolean"
                                            :description "Run batch operations in parallel"}
                              "dry_run"    {:type "boolean"
                                            :description "Batch mode: validate and plan without executing"}}
                 :additionalProperties true
                 :required []}
   :handler handle-multi})

(def tools [tool-def])