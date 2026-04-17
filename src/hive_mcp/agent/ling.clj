(ns hive-mcp.agent.ling
  "Ling agent implementation - Claude Code instances with tool chaining and multi-mode spawn."
  (:require [hive-mcp.agent.protocol :refer [IAgent]]
            [hive-mcp.agent.ling.strategy :as strategy]
            [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-mcp.agent.ling.headless-registry :as headless-reg]
            [hive-mcp.workflows.catchup-ling :as catchup-ling]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.swarm.datascript.schema :as schema]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.protocols.dispatch :as dispatch-ctx]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private provider->secret-key
  "Maps provider keyword to config secret key for API key resolution."
  {:openrouter :openrouter-api-key
   :openai     :openai-api-key
   :venice     :venice-api-key
   :groq       :groq-api-key
   :together   :together-api-key
   :fireworks  :fireworks-api-key})

(defn- resolve-api-key-for-provider
  "Resolve API key from hive-mcp config secrets for a given provider.
   Returns the key string or nil. Bridges config.edn secrets → headless spawn."
  [provider]
  (when-let [secret-key (get provider->secret-key (keyword provider))]
    (r/rescue nil (global-config/get-secret secret-key))))

(defn resolve-effective-mode
  "Pure function: raw spawn inputs -> effective spawn mode keyword.
   Handles OpenRouter model detection and headless registry resolution.
   Public: also used by spawn.clj for mode queries."
  [{:keys [model spawn-mode]}]
  (let [non-claude? (and model (not (schema/claude-model? model)))
        raw-mode (if non-claude?
                   :openrouter
                   (or spawn-mode :claude))]
    (if (= raw-mode :headless)
      (or (headless-reg/best-headless-for-provider :claude)
          (do (log/warn "No headless backend registered for :claude, falling back to :headless"
                        {:registered (headless-reg/registered-headless)})
              :headless))
      raw-mode)))

(defn- resolve-strategy
  "Get the ILingStrategy implementation for a spawn mode.
   Checks terminal registry first, then headless registry.
   Pure OCP — no hardcoded strategy constructors."
  [mode]
  (or (terminal-reg/resolve-terminal-strategy mode)
      (headless-reg/resolve-headless-strategy mode)
      (throw (ex-info (str "No strategy registered for mode: " mode)
                      {:mode mode
                       :registered-terminals (terminal-reg/registered-terminals)
                       :registered-headless (headless-reg/registered-headless)}))))

(defn- ling-ctx
  "Build a context map from a Ling record for strategy calls."
  [ling]
  (cond-> {:id (:id ling)
           :cwd (:cwd ling)
           :presets (:presets ling)
           :project-id (:project-id ling)
           :spawn-mode (:spawn-mode ling)
           :model (:model ling)}
    (:provider ling) (assoc :provider (:provider ling))
    (some? (:kg-compress? ling)) (assoc :kg-compress? (:kg-compress? ling))
    (:sliding-window-size ling) (assoc :sliding-window-size (:sliding-window-size ling))
    (:agents ling) (assoc :agents (:agents ling))))

(defn- slave->ling-opts
  "Extract ling construction opts from a DataScript slave entity."
  [slave]
  {:cwd (:slave/cwd slave)
   :presets (:slave/presets slave)
   :project-id (:slave/project-id slave)
   :spawn-mode (or (:ling/spawn-mode slave) :claude)
   :model (:ling/model slave)})

(declare ->ling)

(defn- compute-spawn-plan
  "Pure computation: derive all spawn decisions from ling state and opts.
   Returns a plan map with resolved mode, model, strategy, and context."
  [ling opts]
  (let [effective-model (or (:model opts) (:model ling))
        mode (resolve-effective-mode {:model effective-model
                                      :spawn-mode (or (:spawn-mode opts) (:spawn-mode ling))})
        {:keys [depth parent kanban-task-id]
         :or {depth 1}} opts]
    {:effective-model effective-model
     :mode mode
     :strat (resolve-strategy mode)
     :ctx (assoc (ling-ctx ling) :model effective-model)
     :depth depth
     :parent parent
     :kanban-task-id kanban-task-id
     :presets (or (:presets opts) (:presets ling))
     :cwd (:cwd ling)
     :project-id (:project-id ling)
     :ling-id (:id ling)
     :max-budget-usd (or (:max-budget-usd opts) (:max-budget-usd ling))
     :task (:task opts)}))

(defn- load-presets-content
  "Load preset content from preset .md files for headless backends.
   Claude CLI lings load presets from .claude/agents/ automatically;
   headless backends (OpenRouter, hive-agent) need explicit injection.
   Returns concatenated preset markdown string, or nil."
  [preset-names]
  (r/rescue nil
    (when (seq preset-names)
      (when-let [get-from-file (requiring-resolve 'hive-mcp.presets.core/get-preset-from-file)]
        (let [preset-dir (or (r/rescue nil
                               (when-let [cfg-fn (requiring-resolve 'hive-mcp.config.core/get-service-value)]
                                 (cfg-fn :presets :dir :env "HIVE_MCP_PRESETS_DIR")))
                             (str (System/getProperty "user.dir") "/presets"))
              contents (->> preset-names
                            (keep (fn [pname]
                                    (when-let [p (get-from-file preset-dir (name pname))]
                                      (:content p))))
                            vec)]
          (when (seq contents)
            (let [joined (str/join "\n\n---\n\n" contents)]
              (log/info "Loaded preset content for headless ling"
                        {:presets (vec preset-names)
                         :loaded-count (count contents)
                         :total-chars (count joined)})
              joined)))))))

(defn- dispatch-after-ready!
  "Wait for terminal readiness, then dispatch task. Runs in a future so
   spawn! returns immediately. Uses readiness polling (not fixed sleep)
   and retries dispatch once on failure.

   Fixes the spawn-then-dispatch workaround: task param on spawn now
   works atomically without requiring a separate dispatch call."
  [{:keys [slave-id mode cwd presets project-id effective-model enriched-task]}]
  (future
    (try
      (let [wait-fn @(requiring-resolve
                      'hive-mcp.tools.consolidated.workflow.readiness/wait-for-ling-ready)
            ready   (wait-fn slave-id mode)
            can-try? (or (:ready? ready) (:slave ready))]
        (if can-try?
          (let [task-ling (->ling slave-id {:cwd cwd
                                            :presets presets
                                            :project-id project-id
                                            :spawn-mode mode
                                            :model effective-model})
                result (r/rescue {} (.dispatch! task-ling {:task enriched-task}))]
            (if-let [err (::r/error (meta result))]
              ;; First attempt failed — retry once after 2s
              (do
                (log/warn "Spawn-dispatch: first attempt failed, retrying in 2s"
                          {:ling-id slave-id :error (:message err)})
                (Thread/sleep 2000)
                (let [retry (r/rescue {} (.dispatch! task-ling {:task enriched-task}))]
                  (if-let [err2 (::r/error (meta retry))]
                    (log/error "Spawn-dispatch: both attempts failed — task lost"
                               {:ling-id slave-id :error (:message err2)})
                    (log/info "Spawn-dispatch: succeeded on retry"
                              {:ling-id slave-id}))))
              (log/info "Spawn-dispatch: task dispatched after readiness wait"
                        {:ling-id slave-id
                         :elapsed-ms (:elapsed-ms ready)
                         :best-effort? (not (:ready? ready))})))
          (log/error "Spawn-dispatch: ling not ready, task will not be dispatched"
                     {:ling-id slave-id
                      :phase (:phase ready)
                      :elapsed-ms (:elapsed-ms ready)})))
      (catch Throwable t
        (log/error t "Spawn-dispatch: unexpected error"
                   {:ling-id slave-id})))))

(defn- execute-spawn-plan!
  "Execute spawn effects: catchup enrichment, DS pre-registration, strategy
   spawn, DS reconciliation, budget registration, and readiness-based dispatch.

   CRITICAL: For headless backends, strategy-spawn! triggers start! which fires
   the agentic loop immediately. The ling MUST exist in DataScript before that
   happens, otherwise completion handlers hit a deregistration race (H2 fix)."
  [plan opts]
  (let [{:keys [mode strat ctx cwd presets project-id ling-id
                effective-model depth parent kanban-task-id
                max-budget-usd task]} plan
        headless? (contains? (headless-reg/registered-headless) mode)
        ling-context-str (when task
                           (r/rescue nil
                                     (catchup-ling/ling-catchup
                                      {:directory cwd
                                       :task task
                                       :kanban-task-id kanban-task-id})))
        enriched-task (when task
                        (if ling-context-str
                          (str ling-context-str "\n\n---\n\n" task)
                          task))
        ;; Load preset content for headless backends — Claude CLI loads these
        ;; from .claude/agents/ automatically, but headless backends (OpenRouter,
        ;; hive-agent TransparentAgenticLoop) need explicit injection into the
        ;; system prompt via :preset-content.
        preset-content (when (and headless? (seq presets))
                         (load-presets-content presets))
        ;; Resolve API key from hive-mcp config secrets for headless backends.
        ;; hive-agent reads System/getenv directly, but secrets may only exist
        ;; in config.edn (resolved via pass(1) at startup). Bridge here.
        resolved-api-key (when headless?
                           (resolve-api-key-for-provider
                            (or (:provider ctx) :openrouter)))
        spawn-opts (cond-> (if enriched-task
                             (assoc opts :task enriched-task)
                             opts)
                     (seq preset-content)
                     (assoc :preset-content preset-content)
                     resolved-api-key
                     (assoc :api-key resolved-api-key))]

    ;; PRE-REGISTER in DataScript BEFORE strategy-spawn!.
    ;; For headless backends, strategy-spawn! may trigger start! which fires
    ;; the agentic loop immediately. The ling must exist in DataScript before
    ;; that happens, or the completion/deregistration handler races against
    ;; a ling entity that doesn't exist yet (H2 registration race fix).
    ;; Set initial status: :working when task provided (prevents Emacs sync
    ;; :slave-ready event from resetting to :idle before dispatch).
    (ds-lings/add-slave! ling-id {:status (if enriched-task :working :idle)
                                  :depth depth
                                  :parent parent
                                  :presets presets
                                  :cwd cwd
                                  :project-id project-id
                                  :kanban-task-id kanban-task-id})

    (let [slave-id (strategy/strategy-spawn! strat ctx spawn-opts)]

      ;; Reconcile: if backend returned a different ID than pre-registered,
      ;; remove the stale entry and re-register under the actual slave-id.
      ;; Common case: slave-id == ling-id, so this is a no-op.
      (when (not= slave-id ling-id)
        (ds-lings/remove-slave! ling-id)
        (ds-lings/add-slave! slave-id {:status (if enriched-task :working :idle)
                                       :depth depth
                                       :parent parent
                                       :presets presets
                                       :cwd cwd
                                       :project-id project-id
                                       :kanban-task-id kanban-task-id
                                       :requested-id ling-id}))

      (ds-lings/update-slave! slave-id (cond-> {:ling/spawn-mode mode
                                                :ling/model (or effective-model "claude")}
                                         headless?
                                         (assoc :ling/process-alive? true)))

      ;; Publish agent-spawn event via NATS backbone (non-fatal).
      ;; Uses requiring-resolve to avoid circular dep on nats.bridge.
      (r/rescue nil
        (when-let [publish! (requiring-resolve 'hive-mcp.nats.bridge/publish-event!)]
          (publish! {:type       :agent-spawn
                     :agent-id   slave-id
                     :timestamp  (System/currentTimeMillis)
                     :data       {:cwd cwd
                                  :mode mode
                                  :project-id project-id
                                  :headless? headless?
                                  :model (or effective-model "claude")}})))

      ;; Publish :slave-spawned to the in-process channel.core bus + NATS
      ;; backbone via swarm.event-bridge. This is what actually fires the
      ;; swarm/sync handlers (Datahike write-through, Olympus emit, etc.)
      ;; for non-Emacs spawn modes. Required to make headless / agent-sdk /
      ;; tmux modes show up in swarm tracking. (requiring-resolve to avoid
      ;; agent → swarm dependency cycle.)
      (r/rescue nil
        (when-let [publish-slave! (requiring-resolve 'hive-mcp.swarm.event-bridge/publish-slave-event!)]
          (publish-slave! {:type       :slave-spawned
                           :slave-id   slave-id
                           :name       slave-id
                           :depth      depth
                           :parent-id  parent
                           :cwd        cwd
                           :project-id project-id
                           :timestamp  (System/currentTimeMillis)})))

      (when (and max-budget-usd (pos? max-budget-usd))
        (r/rescue nil
                  (when-let [register-fn (requiring-resolve 'hive-mcp.agent.hooks.budget/register-budget!)]
                    (register-fn slave-id max-budget-usd {:model (or effective-model "claude")})
                    (log/info "Budget guardrail registered for ling"
                              {:ling-id slave-id :max-budget-usd max-budget-usd}))))

      (when (and enriched-task (not headless?))
        (dispatch-after-ready! {:slave-id slave-id :mode mode :cwd cwd
                                :presets presets :project-id project-id
                                :effective-model effective-model
                                :enriched-task enriched-task}))

      slave-id)))

(defrecord Ling [id cwd presets project-id spawn-mode model provider kg-compress? sliding-window-size agents max-budget-usd]
  IAgent

  (spawn! [this opts]
    (let [plan (compute-spawn-plan this opts)]
      (execute-spawn-plan! plan opts)))

  (dispatch! [this task-opts]
    (let [{:keys [task files _timeout-ms dispatch-context]} task-opts
          ctx (or dispatch-context
                  (when task (dispatch-ctx/ensure-context task)))
          resolved-task (if ctx
                          (:prompt (dispatch-ctx/resolve-context ctx))
                          task)
          task-id (str "task-" (System/currentTimeMillis) "-" (subs id 0 (min 8 (count id))))
          mode (or spawn-mode
                   (when-let [slave (ds-queries/get-slave id)]
                     (:ling/spawn-mode slave))
                   :claude)
          strat (resolve-strategy mode)]
      (ds-lings/update-slave! id {:slave/status :working})
      (ds-lings/add-task! task-id id {:status :dispatched
                                      :prompt resolved-task
                                      :files files})
      (when (seq files)
        (.claim-files! this files task-id))

      ;; NOTE: We deliberately do NOT publish :task-dispatched to channel.core
      ;; here. The JVM-side calls above already perform every action that
      ;; handle-task-dispatched would perform (add-task!, status→:working,
      ;; file claims). Republishing would cause duplicate writes against the
      ;; in-memory registry. The :task-failed and :slave-killed publishes
      ;; below ARE needed because their handlers do queue processing and
      ;; resource cleanup beyond what the JVM path does.

      (let [resolved-opts (cond-> (assoc task-opts :task resolved-task :task-id task-id)
                            ctx (assoc :dispatch-context ctx))]
        (try
          (strategy/strategy-dispatch! strat (ling-ctx this) resolved-opts)
          (log/info "Task dispatched to ling" {:ling-id id :task-id task-id
                                               :mode mode :files files
                                               :context-type (when ctx
                                                               (dispatch-ctx/context-type ctx))})
          task-id
          (catch Exception e
            (log/error "Failed to dispatch to ling"
                       {:ling-id id :task-id task-id :mode mode :error (ex-message e)})
            (ds-lings/update-task! task-id {:status :failed
                                            :error (ex-message e)})
            ;; Publish :task-failed for swarm/sync to release claims and
            ;; process the queue (mirrors handle-task-failed semantics).
            (r/rescue nil
              (when-let [publish-slave! (requiring-resolve 'hive-mcp.swarm.event-bridge/publish-slave-event!)]
                (publish-slave! {:type      :task-failed
                                 :slave-id  id
                                 :task-id   task-id
                                 :error     (ex-message e)
                                 :timestamp (System/currentTimeMillis)})))
            (throw (ex-info "Failed to dispatch to ling"
                            {:ling-id id :task-id task-id :error (ex-message e)}
                            e)))))))

  (status [this]
    (let [ds-status (ds-queries/get-slave id)
          mode (or spawn-mode
                   (:ling/spawn-mode ds-status)
                   :claude)
          strat (resolve-strategy mode)]
      (strategy/strategy-status strat (ling-ctx this) ds-status)))

  (kill! [this]
    (let [{:keys [can-kill? blocking-ops]} (ds-lings/can-kill? id)
          mode (or spawn-mode
                   (when-let [slave (ds-queries/get-slave id)]
                     (:ling/spawn-mode slave))
                   :claude)]
      (if can-kill?
        (do
          (.release-claims! this)
          (r/rescue nil
                    (when-let [deregister-fn (requiring-resolve 'hive-mcp.agent.hooks.budget/deregister-budget!)]
                      (deregister-fn id)))
          (let [strat (resolve-strategy mode)
                result (strategy/strategy-kill! strat (ling-ctx this))]
            (when (:killed? result)
              (ds-lings/remove-slave! id)
              ;; Publish agent-kill event via NATS backbone (non-fatal).
              (r/rescue nil
                (when-let [publish! (requiring-resolve 'hive-mcp.nats.bridge/publish-event!)]
                  (publish! {:type       :agent-kill
                             :agent-id   id
                             :timestamp  (System/currentTimeMillis)})))
              ;; Publish :slave-killed to channel.core (+NATS) so swarm/sync
              ;; handlers run cleanup (resource release, daemon unbind,
              ;; Olympus emit, Datahike forget).
              (r/rescue nil
                (when-let [publish-slave! (requiring-resolve 'hive-mcp.swarm.event-bridge/publish-slave-event!)]
                  (publish-slave! {:type      :slave-killed
                                   :slave-id  id
                                   :timestamp (System/currentTimeMillis)}))))
            result))
        (do
          (log/warn "Cannot kill ling - critical ops in progress"
                    {:id id :blocking-ops blocking-ops})
          {:killed? false
           :reason :critical-ops-blocking
           :blocking-ops blocking-ops}))))

  (agent-type [_]
    :ling)

  (can-chain-tools? [_]
    true)

  (claims [_this]
    (let [all-claims (ds-queries/get-all-claims)]
      (->> all-claims
           (filter #(= id (:slave-id %)))
           (map :file)
           vec)))

  (claim-files! [_this files task-id]
    (when (seq files)
      (doseq [f files]
        (let [{:keys [conflict? held-by]} (ds-queries/has-conflict? f id)]
          (if conflict?
            (do
              (log/warn "File already claimed by another agent"
                        {:file f :held-by held-by :requesting id})
              (ds-lings/add-to-wait-queue! id f))
            (ds-lings/claim-file! f id task-id))))
      (log/info "Files claimed" {:ling-id id :count (count files)})))

  (release-claims! [_this]
    (let [released-count (ds-lings/release-claims-for-slave! id)]
      (log/info "Released claims" {:ling-id id :count released-count})
      released-count))

  (upgrade! [_]
    nil))

(defn ->ling
  "Create a new Ling agent instance."
  [id opts]
  (let [model-val (:model opts)
        effective-spawn-mode (resolve-effective-mode {:model model-val
                                                      :spawn-mode (:spawn-mode opts :claude)})]
    (map->Ling (cond-> {:id id
                        :cwd (:cwd opts)
                        :presets (:presets opts [])
                        :project-id (:project-id opts)
                        :spawn-mode effective-spawn-mode
                        :model model-val}
                 (:provider opts)           (assoc :provider (:provider opts))
                 (some? (:kg-compress? opts)) (assoc :kg-compress? (:kg-compress? opts))
                 (:sliding-window-size opts) (assoc :sliding-window-size (:sliding-window-size opts))
                 (:agents opts)             (assoc :agents (:agents opts))
                 (:max-budget-usd opts)     (assoc :max-budget-usd (:max-budget-usd opts))))))

(defn create-ling!
  "Create and spawn a new ling agent."
  [id opts]
  (let [ling (->ling id opts)]
    (.spawn! ling opts)))

(defn get-ling
  "Get a ling by ID as a Ling record from DataScript."
  [id]
  (when-let [slave (ds-queries/get-slave id)]
    (->ling id (slave->ling-opts slave))))

(defn list-lings
  "List all lings, optionally filtered by project-id."
  [& [project-id]]
  (let [slaves (if project-id
                 (ds-queries/get-slaves-by-project project-id)
                 (ds-queries/get-all-slaves))]
    (->> slaves
         (filter #(= 1 (:slave/depth %)))
         (map #(->ling (:slave/id %) (slave->ling-opts %))))))

(defn get-ling-for-task
  "Get the ling assigned to a kanban task."
  [kanban-task-id]
  (when-let [slave (ds-queries/get-slave-by-kanban-task kanban-task-id)]
    (->ling (:slave/id slave) (slave->ling-opts slave))))

(defn interrupt-ling!
  "Interrupt the current query/task of a running ling."
  [ling-id]
  (if-let [ling (get-ling ling-id)]
    (let [mode (or (:spawn-mode ling)
                   (when-let [slave (ds-queries/get-slave ling-id)]
                     (:ling/spawn-mode slave))
                   :claude)
          strat (resolve-strategy mode)]
      (strategy/strategy-interrupt! strat (ling-ctx ling)))
    {:success? false
     :ling-id ling-id
     :errors [(str "Ling not found: " ling-id)]}))

(defn with-critical-op
  "Execute body while holding a critical operation guard."
  [ling-id op-type body-fn]
  (ds-lings/with-critical-op ling-id op-type
    (body-fn)))
