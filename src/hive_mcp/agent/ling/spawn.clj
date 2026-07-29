(ns hive-mcp.agent.ling.spawn
  "Spawn pipeline for Ling agents: plan computation, preset loading, readiness
   dispatch, and the `Ling` defrecord itself."
  (:require [hive-mcp.agent.protocol :refer [IAgent]]
            [hive-mcp.agent.ling.strategy :as strategy]
            [hive-mcp.agent.ling.headless-registry :as headless-reg]
            [hive-mcp.agent.ling.lifecycle :as lifecycle]
            [hive-mcp.agent.ling.spawn-store :as spawn-store]
            [hive-mcp.workflows.catchup-ling :as catchup-ling]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.protocols.dispatch :as dispatch-ctx]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]
            [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn slave->ling-opts
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
        mode (lifecycle/resolve-effective-mode
              {:model effective-model
               :spawn-mode (or (:spawn-mode opts) (:spawn-mode ling))})
        {:keys [depth parent kanban-task-id]
         :or {depth 1}} opts]
    {:effective-model effective-model
     :mode mode
     :strat (lifecycle/resolve-strategy mode)
     :ctx (cond-> (assoc (lifecycle/ling-ctx ling) :model effective-model)
            (:provider opts) (assoc :provider (:provider opts)))
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
   headless backends need explicit injection.
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

(defn- headless-mode?
  [mode]
  (contains? (headless-reg/registered-headless) mode))

(defn- enrich-task
  [{:keys [task cwd kanban-task-id]}]
  (when task
    (let [ling-context-str (r/rescue nil
                             (catchup-ling/ling-catchup
                              {:directory cwd
                               :task task
                               :kanban-task-id kanban-task-id}))]
      (if ling-context-str
        (str ling-context-str "\n\n---\n\n" task)
        task))))

(defn- resolve-headless-inputs
  [{:keys [mode ctx presets]}]
  (let [headless? (headless-mode? mode)]
    {:headless? headless?
     :preset-content (when (and headless? (seq presets))
                       (load-presets-content presets))
     :api-key (when headless?
                (lifecycle/resolve-api-key-for-provider
                 (or (:provider ctx) :openrouter)))}))

(defn- spawn-opts
  "Build the spawn-opts passed to strategy-spawn!. Backend-agnostic: opts pass
   through (incl. any :system-prompt), with resolved preset-content and api-key
   added. Each backend maps these to its own config."
  [opts enriched-task {:keys [preset-content api-key]}]
  (cond-> (if enriched-task
            (assoc opts :task enriched-task)
            opts)
    (seq preset-content)
    (assoc :preset-content preset-content)
    api-key
    (assoc :api-key api-key)))

(defn- initial-slave-attrs
  [{:keys [depth parent presets cwd project-id kanban-task-id]} enriched-task]
  {:status (if enriched-task :working :idle)
   :depth depth
   :parent parent
   :presets presets
   :cwd cwd
   :project-id project-id
   :kanban-task-id kanban-task-id})

(defn- register-requested-slave!
  [{:keys [ling-id] :as plan} enriched-task]
  (spawn-store/add-slave! (spawn-store/get-store)
                          ling-id
                          (initial-slave-attrs plan enriched-task)))

(defn- reconcile-spawned-slave!
  [{:keys [ling-id] :as plan} slave-id enriched-task]
  (when (not= slave-id ling-id)
    (let [store (spawn-store/get-store)]
      (spawn-store/remove-slave! store ling-id)
      (spawn-store/add-slave! store
                              slave-id
                              (assoc (initial-slave-attrs plan enriched-task)
                                     :requested-id ling-id)))))

(defn- stamp-spawn-metadata!
  [{:keys [mode effective-model]} slave-id headless?]
  (let [now (System/currentTimeMillis)]
    (spawn-store/update-slave! (spawn-store/get-store)
                               slave-id
                               (cond-> {:ling/spawn-mode mode
                                         :ling/model (or effective-model "claude")
                                         :slave/alive? true
                                         :slave/spawned-at now
                                         :slave/last-active-at now}
                                 headless?
                                 (assoc :ling/process-alive? true)))))

(defn- register-ling-resources!
  [slave-id]
  (r/rescue nil
    (when-let [make (requiring-resolve 'hive-agent.lifecycle.resources/make-ling-resources)]
      (make slave-id
            (fn [] (spawn-store/claims-for-slave (spawn-store/get-store)
                                                 slave-id))))))

(defn- publish-agent-spawn!
  [{:keys [cwd mode project-id effective-model]} slave-id headless?]
  (r/rescue nil
    (when-let [publish! (requiring-resolve 'hive-mcp.nats.bridge/publish-event!)]
      (publish! {:type       :agent-spawn
                 :agent-id   slave-id
                 :timestamp  (System/currentTimeMillis)
                 :data       {:cwd cwd
                              :mode mode
                              :project-id project-id
                              :headless? headless?
                              :model (or effective-model "claude")}}))))

(defn- publish-slave-spawned!
  [{:keys [depth parent cwd project-id]} slave-id]
  (r/rescue nil
    (when-let [publish-slave! (requiring-resolve 'hive-mcp.swarm.event-bridge/publish-slave-event!)]
      (publish-slave! {:type       :slave-spawned
                       :slave-id   slave-id
                       :name       slave-id
                       :depth      depth
                       :parent-id  parent
                       :cwd        cwd
                       :project-id project-id
                       :timestamp (System/currentTimeMillis)}))))

(defn- register-budget!
  [{:keys [max-budget-usd effective-model]} slave-id]
  (when (and max-budget-usd (pos? max-budget-usd))
    (r/rescue nil
      (when-let [register-fn (requiring-resolve 'hive-mcp.agent.hooks.budget/register-budget!)]
        (register-fn slave-id max-budget-usd {:model (or effective-model "claude")})
        (log/info "Budget guardrail registered for ling"
                  {:ling-id slave-id :max-budget-usd max-budget-usd})))))

(defn- dispatch-when-ready!
  [{:keys [mode cwd presets project-id effective-model]} slave-id enriched-task headless?]
  (when (and enriched-task (not headless?))
    (dispatch-after-ready! {:slave-id slave-id :mode mode :cwd cwd
                            :presets presets :project-id project-id
                            :effective-model effective-model
                            :enriched-task enriched-task})))

(defn- apply-spawn-overlay
  "Generic spawn-opts extension seam. Strips the internal :spawn/request key
   from opts, then applies any extension registered under :spawn/opts-overlay
   as (f opts ctx) -> opts'. Fail-soft: nil or a throw falls back to the
   stripped opts. Applied once in spawn! so plan + executor both see merged opts."
  [opts ling]
  (let [req  (:spawn/request opts)
        opts (dissoc opts :spawn/request)]
    (if-let [f (ext/get-extension :spawn/opts-overlay)]
      (or (r/rescue nil
            (f opts {:request req
                     :cwd (:cwd ling)
                     :project-id (:project-id ling)
                     :task (:task opts)
                     :kanban-task-id (:kanban-task-id opts)}))
          opts)
      opts)))

(defn- execute-spawn-plan!
  "Execute spawn effects: catchup enrichment, store pre-registration, strategy
   spawn, store reconciliation, budget registration, and readiness-based dispatch.

   CRITICAL: For headless backends, strategy-spawn! triggers start! which fires
   the agentic loop immediately. The ling MUST exist in the spawn store before
   that happens, otherwise completion handlers hit a deregistration race (H2
   fix)."
  [plan opts]
  (let [{:keys [strat ctx]} plan
        enriched-task (enrich-task plan)
        headless-inputs (resolve-headless-inputs plan)
        headless? (:headless? headless-inputs)
        spawn-opts (spawn-opts opts enriched-task headless-inputs)]
    (register-requested-slave! plan enriched-task)
    (let [slave-id (strategy/strategy-spawn! strat ctx spawn-opts)]
      (reconcile-spawned-slave! plan slave-id enriched-task)
      (stamp-spawn-metadata! plan slave-id headless?)
      (register-ling-resources! slave-id)
      (publish-agent-spawn! plan slave-id headless?)
      (publish-slave-spawned! plan slave-id)
      (register-budget! plan slave-id)
      (dispatch-when-ready! plan slave-id enriched-task headless?)
      slave-id)))

(defrecord Ling [id cwd presets project-id spawn-mode model provider kg-compress? sliding-window-size agents max-budget-usd]
  IAgent

  (spawn! [this opts]
    (let [opts (apply-spawn-overlay opts this)
          plan (compute-spawn-plan this opts)]
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
          strat (lifecycle/resolve-strategy mode)]
      (ds-lings/update-slave! id {:slave/status :working})
      (ds-lings/add-task! task-id id {:status :dispatched
                                      :prompt resolved-task
                                      :files files})
      (when (seq files)
        (.claim-files! this files task-id))

      ;; NOTE: intentionally do NOT publish :task-dispatched — the JVM-side
      ;; calls above already do every action handle-task-dispatched would do.
      ;; :task-failed / :slave-killed below ARE needed (queue + cleanup).

      (let [resolved-opts (cond-> (assoc task-opts :task resolved-task :task-id task-id)
                            ctx (assoc :dispatch-context ctx))]
        (try
          (strategy/strategy-dispatch! strat (lifecycle/ling-ctx this) resolved-opts)
          (log/info "Task dispatched to ling" {:ling-id id :task-id task-id
                                               :mode mode :files files
                                               :context-type (when ctx
                                                               (dispatch-ctx/context-type ctx))})
          task-id
          ;; Hive axiom: `catch Throwable` on supervision boundaries so
          ;; AssertionError (from :pre/:post in DS helpers, e.g. lings/
          ;; {enter,exit}-critical-op!) surfaces to the task-failed path
          ;; instead of being silently swallowed and leaving the dispatch
          ;; caller parked. This is the ling/vterm dispatch hot-path —
          ;; an Error here used to propagate past the MCP handler and leave
          ;; :dispatched tasks wedged with no :task-failed event fired.
          (catch Throwable e
            (log/error "Failed to dispatch to ling"
                       {:ling-id id :task-id task-id :mode mode :error (ex-message e)})
            (ds-lings/update-task! task-id {:status :failed
                                            :error (ex-message e)})
            ;; :task-failed -> swarm/sync releases claims + processes queue.
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
          strat (lifecycle/resolve-strategy mode)]
      (strategy/strategy-status strat (lifecycle/ling-ctx this) ds-status)))

  (kill! [this]
    (let [{:keys [can-kill? blocking-ops]} (ds-lings/can-kill? id)
          mode (or spawn-mode
                   (when-let [slave (ds-queries/get-slave id)]
                     (:ling/spawn-mode slave))
                   :claude)]
      (if can-kill?
        (do
          ;; Release per-ling owned resources (channels, caches) + unregister.
          ;; Lazy-resolve the release fn so this is a no-op when the addon is absent.
          (r/rescue nil
            (when-let [release (requiring-resolve 'hive-agent.lifecycle.resources/release-ling-resources!)]
              (release id)))
          (.release-claims! this)
          (r/rescue nil
                    (when-let [deregister-fn (requiring-resolve 'hive-mcp.agent.hooks.budget/deregister-budget!)]
                      (deregister-fn id)))
          (let [strat (lifecycle/resolve-strategy mode)
                result (strategy/strategy-kill! strat (lifecycle/ling-ctx this))]
            (when (:killed? result)
              (ds-lings/remove-slave! id)
              ;; Publish agent-kill event via NATS backbone (non-fatal).
              (r/rescue nil
                (when-let [publish! (requiring-resolve 'hive-mcp.nats.bridge/publish-event!)]
                  (publish! {:type       :agent-kill
                             :agent-id   id
                             :timestamp  (System/currentTimeMillis)})))
              ;; :slave-killed -> channel.core+NATS for swarm cleanup handlers.
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
  "Create a new Ling agent instance.
   Omitting :spawn-mode yields lifecycle/default-spawn-mode."
  [id opts]
  (let [model-val (:model opts)
        effective-spawn-mode (lifecycle/resolve-effective-mode
                              {:model model-val
                               :spawn-mode (:spawn-mode opts lifecycle/default-spawn-mode)})]
    (map->Ling (cond-> {:id id
                        :cwd (:cwd opts)
                        :presets (:presets opts [])
                        :project-id (:project-id opts)
                        :spawn-mode effective-spawn-mode
                        :model model-val}
                 (:provider opts)           (assoc :provider (:provider opts))
                 (some? (:kg-compress? opts)) (assoc :kg-compress? (:kg-compress? opts))
                 (some? (:verbose? opts))   (assoc :verbose? (:verbose? opts))
                 (:llm-retries opts)        (assoc :llm-retries (:llm-retries opts))
                 (:sliding-window-size opts) (assoc :sliding-window-size (:sliding-window-size opts))
                 (:agents opts)             (assoc :agents (:agents opts))
                 (:max-budget-usd opts)     (assoc :max-budget-usd (:max-budget-usd opts))))))

(defn create-ling!
  "Create and spawn a new ling agent."
  [id opts]
  (let [ling (->ling id opts)]
    (.spawn! ling opts)))
