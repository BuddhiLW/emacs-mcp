(ns hive-mcp.tools.consolidated.workflow.spawn
  "Spawn engine for forge workflows: ling spawning and the spark! orchestrator.

   Key refactorings from parent workflow.clj:
   - spawn-one! unifies spawn-one-vterm! and spawn-one-headless! (DRY)
   - spark! decomposed into compute-route-batches + execute-spawn-batches
   - Drone dispatch extracted to workflow.drone namespace

   Extracted from workflow.clj to reduce cyclomatic complexity."
  (:require [hive-mcp.tools.consolidated.workflow.readiness :as ready]
            [hive-mcp.tools.consolidated.workflow.drone :as drone]
            [hive-mcp.tools.agent.spawn :as spawn]
            [hive-mcp.tools.agent.dispatch :as dispatch]
            [hive-mcp.tools.consolidated.kanban :as c-kanban]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.config.core :as config]
            [hive-mcp.agent.budget-router :as budget-router]
            [hive-mcp.agent.spawn-mode-registry :as spawn-registry]
            [hive-mcp.dns.result :as result]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ── Re-exports for backward compat ──────────────────────────────────────────

(def dispatch-drone-tasks!
  "Re-export from workflow.drone for callers that expect it here."
  drone/dispatch-drone-tasks!)

;; ── Constants ───────────────────────────────────────────────────────────────

(def ^:private vterm-max-slots
  "Hard cap for Emacs-bound lings per daemon (claude or vterm)."
  (spawn-registry/slot-limit :claude))

(def ^:private headless-modes
  "Set of spawn modes that don't require Emacs."
  spawn-registry/headless-modes)

;; ── Budget Routing ──────────────────────────────────────────────────────────

(defn- budget-route-model
  "When budget-routing config is enabled, use budget-router to pick model.
   When disabled (default), returns the requested model unchanged."
  [requested-model]
  (if (config/get-service-value :forge :budget-routing :default false)
    (let [suggestion (budget-router/suggest-model {:model requested-model})
          selected   (:model suggestion)]
      (when (:downgraded? suggestion false)
        (log/info "SPARK: budget-router downgraded model"
                  {:requested requested-model :selected selected
                   :tier (:tier suggestion) :reason (:reason suggestion)}))
      selected)
    requested-model))

;; ── Unified Spawn-One ───────────────────────────────────────────────────────

(defn- make-spawn-params
  "Build spawn handler params. Headless modes include :spawn_mode."
  [{:keys [agent-name effective-dir default-presets model route spawn-mode-kw task-id]}]
  (cond-> {:type "ling" :name agent-name :cwd effective-dir :presets default-presets}
    task-id              (assoc :kanban_task_id task-id)
    model                (assoc :model model)
    (not= :claude route) (assoc :spawn_mode (name spawn-mode-kw))))

(defn- parse-spawn-result
  "Extract agent-id and spawn-mode from spawn handler response."
  [spawn-result fallback-name]
  (let [spawn-text   (when (map? spawn-result) (:text spawn-result))
        spawn-parsed (when (string? spawn-text)
                       (result/rescue nil (json/read-str spawn-text :key-fn keyword)))]
    {:agent-id   (or (:agent-id spawn-parsed) fallback-name)
     :spawn-mode (when-let [sm (:spawn-mode spawn-parsed)] (keyword sm))}))

(defn- try-dispatch!
  "Single dispatch attempt. Returns the raw handle-dispatch result map."
  [agent-id task]
  (dispatch/handle-dispatch
   {:agent_id agent-id
    :prompt   (str (or (:title task) (:id task) "untitled")
                   (when-let [desc (:description task)]
                     (str "\n\n" desc))
                   "\n\nDO NOT spawn drones. Implement directly.")}))

(defn- dispatch-to-ling!
  "Send task prompt to a ready ling and update kanban status.
   Returns nil on success, or {:dispatch-error <msg>} on failure.
   Retries once after 2s on first failure."
  [{:keys [agent-id task effective-dir]}]
  (let [result1 (try-dispatch! agent-id task)]
    (if (:isError result1)
      (do
        (log/warn "DISPATCH: first attempt failed, retrying in 2s"
                  {:agent-id agent-id :error (:text result1)})
        (Thread/sleep 2000)
        (let [result2 (try-dispatch! agent-id task)]
          (if (:isError result2)
            (do
              (log/error "DISPATCH: both attempts failed"
                         {:agent-id agent-id :error (:text result2)})
              {:dispatch-error (str "dispatch failed after 2 attempts: " (:text result2))})
            (do
              (when-let [task-id (:id task)]
                (result/rescue nil
                               (c-kanban/handle-kanban {:command "update" :task_id task-id
                                                        :new_status "inprogress"
                                                        :directory effective-dir})))
              nil))))
      (do
        (when-let [task-id (:id task)]
          (result/rescue nil
                         (c-kanban/handle-kanban {:command "update" :task_id task-id
                                                  :new_status "inprogress"
                                                  :directory effective-dir})))
        nil))))

(defn- spawn-and-wait!
  "IO boundary: spawn a ling and poll for readiness.
   Returns {:agent-id :ready? :slave :elapsed-ms :phase :spawn-mode}."
  [{:keys [agent-name effective-dir default-presets model route spawn-mode-kw task-id]}]
  (let [spawn-result  (spawn/handle-spawn
                       (make-spawn-params {:agent-name      agent-name
                                           :effective-dir   effective-dir
                                           :default-presets default-presets
                                           :model           model
                                           :route           route
                                           :spawn-mode-kw   spawn-mode-kw
                                           :task-id         task-id}))
        parsed        (parse-spawn-result spawn-result agent-name)
        agent-id      (:agent-id parsed)
        reported-mode (or (:spawn-mode parsed) spawn-mode-kw)]
    (assoc (ready/wait-for-ling-ready agent-id reported-mode)
           :agent-id agent-id
           :spawn-mode spawn-mode-kw)))

(defn- handle-ready-ling
  "Pure: assemble success result map after dispatch."
  [{:keys [agent-id title task-id route model spawn-mode best-effort?]}]
  (cond-> {:agent-id agent-id :task-title title :task-id task-id
           :spawned true :route route :model (or model "claude")}
    (not= :claude route) (assoc :spawn-mode spawn-mode)
    best-effort?         (assoc :best-effort true)))

(defn- handle-timeout-ling
  "Handle ling that timed out during readiness.
   Slave exists → best-effort dispatch; no slave → error result."
  [{:keys [spawn-result task effective-dir base-result title task-id route model spawn-mode]}]
  (let [{:keys [agent-id slave elapsed-ms phase]} spawn-result]
    (if slave
      (do
        (log/warn (str "SPARK[" (name route) "]: readiness timeout but slave exists — dispatching anyway")
                  {:agent-id agent-id :elapsed-ms elapsed-ms :phase phase})
        (let [dr (dispatch-to-ling! {:agent-id agent-id :task task
                                     :effective-dir effective-dir})]
          (if-let [err (:dispatch-error dr)]
            (do (log/warn (str "SPARK[" (name route) "]: best-effort dispatch failed")
                          {:agent agent-id :task title :error err})
                (assoc base-result :agent-id agent-id :error err))
            (do (log/info (str "SPARK[" (name route) "]: best-effort dispatch succeeded")
                          {:agent agent-id :task title})
                (handle-ready-ling {:agent-id agent-id :title title :task-id task-id
                                    :route route :model model :spawn-mode spawn-mode
                                    :best-effort? true})))))
      (do
        (log/warn (str "SPARK[" (name route) "]: ling not in DB, skipping dispatch")
                  {:agent-id agent-id :elapsed-ms elapsed-ms :phase phase})
        (assoc base-result :agent-id agent-id
               :error (str "Readiness timeout (" (name (or phase :unknown)) ")"))))))

(defn- spawn-one!
  "Spawn and dispatch a single ling. Unified from spawn-one-vterm! and
   spawn-one-headless! — route determined by :route parameter."
  [{:keys [task effective-dir default-presets model route spawn-mode-kw]}]
  (let [title       (or (:title task) (:id task) "untitled")
        task-id     (:id task)
        agent-name  (str (if (= :claude route) "forja-cl-" "forja-hl-")
                         (System/currentTimeMillis))
        ready-mode  (or spawn-mode-kw (if (= :claude route) :claude :headless))
        base-result {:agent-id agent-name :task-title title :task-id task-id
                     :spawned false :route route}
        r (result/rescue
           base-result
           (let [sw       (spawn-and-wait! {:agent-name      agent-name
                                            :effective-dir   effective-dir
                                            :default-presets default-presets
                                            :model           model
                                            :route           route
                                            :spawn-mode-kw   ready-mode
                                            :task-id         task-id})
                 agent-id (:agent-id sw)]
             (if (:ready? sw)
               (let [dr (dispatch-to-ling! {:agent-id agent-id :task task
                                            :effective-dir effective-dir})]
                 (if-let [err (:dispatch-error dr)]
                   (do (log/warn (str "SPARK[" (name route) "]: dispatch failed after spawn")
                                 {:agent agent-id :task title :error err})
                       (assoc base-result :agent-id agent-id :error err))
                   (do (log/info (str "SPARK[" (name route) "]: spawned+dispatched")
                                 {:agent agent-id :task title :model (or model "claude")})
                       (handle-ready-ling {:agent-id agent-id :title title :task-id task-id
                                           :route route :model model
                                           :spawn-mode (:spawn-mode sw)}))))
               (handle-timeout-ling {:spawn-result sw :task task :effective-dir effective-dir
                                     :base-result base-result :title title :task-id task-id
                                     :route route :model model
                                     :spawn-mode (:spawn-mode sw)}))))]
    (cond-> r
      (and (not (:spawned r)) (not (:error r)) (::result/error (meta r)))
      (assoc :error (get-in (meta r) [::result/error :message] "unknown")))))

;; ── Spark Helpers ───────────────────────────────────────────────────────────

(defn- count-project-lings
  "Count active lings for the given project, split by route type."
  [project-id]
  (let [active-status #{:active :running :working :idle :spawning}
        project-lings (->> (queries/get-all-slaves)
                           (filter #(= 1 (:slave/depth %)))
                           (filter #(active-status (:slave/status %)))
                           (filter (fn [a] (if project-id
                                             (= project-id (:slave/project-id a))
                                             true))))
        active-vterm    (count (remove #(headless-modes (:ling/spawn-mode %)) project-lings))
        active-headless (count (filter #(headless-modes (:ling/spawn-mode %)) project-lings))]
    {:active-vterm active-vterm :active-headless active-headless
     :active-total (+ active-vterm active-headless)}))

(defn- compute-route-batches
  "Allocate ling tasks to vterm/headless batches based on mode and slots.
   Returns [vterm-tasks headless-tasks]."
  [{:keys [effective-spawn-mode ling-tasks max-slots active-counts]}]
  (let [{:keys [active-vterm active-total]} active-counts]
    (case effective-spawn-mode
      (:claude :vterm)
      (let [cap   (min (or max-slots vterm-max-slots) vterm-max-slots)
            avail (max 0 (- cap active-total))]
        [(vec (take avail ling-tasks)) []])
      (:headless :agent-sdk :openrouter)
      (let [avail (max 0 (- (or max-slots 10) active-total))]
        [[] (vec (take avail ling-tasks))])
      ;; :mixed — fill vterm, overflow to headless
      (let [total-cap   (or max-slots 10)
            total-avail (max 0 (- total-cap active-total))
            vt-avail    (min (max 0 (- vterm-max-slots active-vterm)) total-avail)
            vt-batch    (vec (take vt-avail ling-tasks))
            hl-avail    (max 0 (- total-avail (count vt-batch)))
            hl-batch    (vec (take hl-avail (drop vt-avail ling-tasks)))]
        [vt-batch hl-batch]))))

(defn- execute-spawn-batches
  "Spawn lings for vterm and headless task batches.
   Headless spawns run in parallel (futures) since they're independent subprocesses.
   Vterm spawns run sequentially to avoid overwhelming Emacs."
  [{:keys [vterm-tasks headless-tasks effective-dir default-presets model
           headless-spawn-mode]}]
  (let [;; Headless: spawn all in parallel via futures, then deref
        hl-futures (doall (for [task headless-tasks]
                            (future
                              (spawn-one! {:task task :effective-dir effective-dir
                                           :default-presets default-presets :model model
                                           :route :headless :spawn-mode-kw headless-spawn-mode}))))
        ;; Vterm: sequential to avoid Emacs contention
        vt-results (doall (for [task vterm-tasks]
                            (spawn-one! {:task task :effective-dir effective-dir
                                         :default-presets default-presets :model model
                                         :route :claude})))
        ;; Collect headless results with per-future timeout
        ;; Per-future timeout: 2x readiness (60s + 5s retry + 60s) + spawn/dispatch overhead = 140s
        per-future-timeout-ms 140000
        hl-results (mapv (fn [f]
                           (deref f per-future-timeout-ms
                                  {:spawned false :error "Future deref timeout"}))
                         hl-futures)]
    {:vt-results vt-results
     :hl-results hl-results}))

(defn- build-spark-response
  "Merge spawn batch results into unified spark! response."
  [{:keys [vt-results hl-results drone-result active-counts max-slots]}]
  (let [all-ling    (concat vt-results hl-results)
        dr-spawned  (get drone-result :spawned [])
        all-results (concat all-ling dr-spawned)]
    {:spawned       (filterv :spawned all-results)
     :failed        (into (filterv (complement :spawned) all-ling) (get drone-result :failed []))
     :count         (+ (count (filter :spawned all-ling)) (get drone-result :count 0))
     :routes        {:vterm    {:spawned (filterv :spawned vt-results)
                                :count (count (filter :spawned vt-results))
                                :active-before (:active-vterm active-counts)
                                :max-slots vterm-max-slots}
                     :headless {:spawned (filterv :spawned hl-results)
                                :count (count (filter :spawned hl-results))
                                :active-before (:active-headless active-counts)
                                :max-slots (or max-slots 10)}
                     :drone    {:count (get drone-result :count 0)
                                :wave-id (:wave-id drone-result)
                                :spawned dr-spawned}}
     :slots-used    (count (filter :spawned all-ling))
     :active-before (:active-total active-counts)
     :max-slots     (or max-slots 10)}))

(defn- empty-spark-response
  "Empty spark response when no tasks to spawn."
  [active-counts max-slots]
  {:spawned [] :failed [] :count 0
   :routes {:vterm {:count 0 :active-before (:active-vterm active-counts)
                    :max-slots vterm-max-slots}
            :headless {:count 0 :active-before (:active-headless active-counts)
                       :max-slots (or max-slots 10)}
            :drone {:count 0}}
   :slots-used 0
   :active-before (:active-total active-counts)
   :max-slots (or max-slots 10)})

;; ── Orchestrator Mode ──────────────────────────────────────────────────────

(defn- build-orchestrator-prompt
  "Build the structured prompt for an orchestrator ling.
   Bundles all tasks with their pre-gathered context refs."
  [tasks context-result directory]
  (let [task-lines (mapv (fn [task]
                           (let [ctx    (get context-result (:id task))
                                 summary (or (:summary ctx) "")]
                             (str "### Task: " (or (:title task) (:id task)) "\n"
                                  "ID: " (:id task) "\n"
                                  (when-let [desc (:description task)]
                                    (str "Description: " desc "\n"))
                                  (when (seq summary)
                                    (str "Context:\n" summary "\n"))
                                  (when-let [refs (:ctx-refs ctx)]
                                    (str "Context refs: " (pr-str refs) "\n"))
                                  (when-let [kg-ids (:kg-node-ids ctx)]
                                    (str "KG seeds: " (pr-str (take 5 kg-ids)) "\n")))))
                         tasks)]
    (str "You are the kanban orchestrator for this milestone.\n\n"
         "## Tasks (" (count tasks) " total)\n\n"
         (clojure.string/join "\n" task-lines)
         "\n## Instructions\n"
         "1. For each task above, spawn a Task subagent (max 3 parallel)\n"
         "2. Each subagent implements the task using hive MCP tools\n"
         "3. Collect results, then: kanban batch-update to mark done\n"
         "4. Check kanban status for milestone progress\n"
         "5. Loop if more tasks remain\n\n"
         "Working directory: " (or directory "auto") "\n"
         "NEVER spawn lings or call forge-strike. Use Task subagents ONLY.\n")))

(defn- spawn-orchestrator!
  "Spawn a single orchestrator ling with all tasks bundled.
   The orchestrator uses Task subagents for parallelism."
  [{:keys [tasks directory model context-result]}]
  (let [effective-dir  (or directory (ctx/current-directory) (System/getProperty "user.dir"))
        agent-name     (str "forja-orch-" (System/currentTimeMillis))
        prompt         (build-orchestrator-prompt tasks context-result effective-dir)]
    (log/info "SPARK[orchestrator]: spawning 1 orchestrator for" (count tasks) "tasks")
    (let [spawn-result (spawn/handle-spawn
                        {:type    "ling"
                         :name    agent-name
                         :cwd     effective-dir
                         :presets ["orchestrator" "ling" "mcp-first"]
                         :model   model})
          parsed       (parse-spawn-result spawn-result agent-name)
          agent-id     (:agent-id parsed)
          ready-mode   (or (:spawn-mode parsed) :claude)
          ready        (ready/wait-for-ling-ready agent-id ready-mode)]
      (if (or (:ready? ready) (:slave ready))
        (let [dispatch-result (dispatch/handle-dispatch
                               {:agent_id agent-id
                                :prompt   prompt})]
          (if (:isError dispatch-result)
            (do
              (log/error "SPARK[orchestrator]: dispatch failed" {:agent agent-id})
              {:spawned [] :failed [{:agent-id agent-id :error "dispatch failed"}]
               :count 0 :mode :orchestrator})
            (do
              (log/info "SPARK[orchestrator]: dispatched" (count tasks) "tasks to" agent-id)
              ;; Mark all tasks as inprogress
              (doseq [task tasks]
                (result/rescue nil
                               (c-kanban/handle-kanban {:command    "update"
                                                        :task_id    (:id task)
                                                        :new_status "inprogress"
                                                        :directory  effective-dir})))
              {:spawned [{:agent-id agent-id :task-count (count tasks)
                          :mode :orchestrator :spawned true}]
               :failed  []
               :count   1
               :mode    :orchestrator})))
        (do
          (log/error "SPARK[orchestrator]: ling readiness timeout" {:agent agent-id})
          {:spawned [] :failed [{:agent-id agent-id :error "readiness timeout"}]
           :count 0 :mode :orchestrator})))))

;; ── Spark! Orchestrator ─────────────────────────────────────────────────────

(defn spark!
  "Spawn lings or dispatch drones for ready tasks.
   Routes: :drone (wave all), :claude (default Emacs), :vterm, :headless/:agent-sdk/:openrouter,
   :orchestrator (single ling + Task subagents), :mixed (default, classify + split)."
  [{:keys [directory max_slots presets tasks spawn_mode spawn-mode model
           preset seeds ctx_refs kg_node_ids context-result]}]
  (let [model                (budget-route-model model)
        effective-spawn-mode (keyword (or spawn_mode spawn-mode :mixed))
        drone-params         {:directory directory :tasks tasks :model model
                              :preset (or preset (first presets) "drone-worker")
                              :seeds seeds :ctx_refs ctx_refs :kg_node_ids kg_node_ids
                              :max_slots max_slots}]
    (cond
      (= :orchestrator effective-spawn-mode)
      (spawn-orchestrator! {:tasks tasks :directory directory :model model
                            :context-result context-result})

      (= :drone effective-spawn-mode)
      (drone/dispatch-drone-tasks! drone-params)

      :else
      (let [{:keys [drone-tasks ling-tasks]}
            (if (= :mixed effective-spawn-mode)
              (drone/classify-and-split-tasks tasks)
              {:drone-tasks [] :ling-tasks tasks})

            drone-result    (when (seq drone-tasks)
                              (drone/dispatch-drone-tasks! (assoc drone-params :tasks drone-tasks)))
            effective-dir   (or directory (ctx/current-directory) (System/getProperty "user.dir"))
            project-id      (when effective-dir (scope/get-current-project-id effective-dir))
            active-counts   (count-project-lings project-id)
            default-presets (or presets ["ling" "mcp-first" "saa"])

            [vterm-tasks headless-tasks]
            (compute-route-batches {:effective-spawn-mode effective-spawn-mode
                                    :ling-tasks ling-tasks :max-slots max_slots
                                    :active-counts active-counts})

            headless-spawn-mode (if (headless-modes effective-spawn-mode)
                                  effective-spawn-mode :headless)]
        (if (and (empty? vterm-tasks) (empty? headless-tasks) (nil? drone-result))
          (empty-spark-response active-counts max_slots)
          (let [batch-results (execute-spawn-batches
                               {:vterm-tasks vterm-tasks :headless-tasks headless-tasks
                                :effective-dir effective-dir :default-presets default-presets
                                :model model :headless-spawn-mode headless-spawn-mode})]
            (build-spark-response (assoc batch-results
                                         :drone-result drone-result
                                         :active-counts active-counts
                                         :max-slots max_slots))))))))
