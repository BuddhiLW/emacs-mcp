(ns hive-mcp.crystal.harvest.collect
  "Harvest collection — gathers session data from all sources in parallel.

   Provides harvest-all which orchestrates 8 sources (progress, tasks, commits,
   recalls, hivemind, kanban, kg-edges, kanban-movements) and returns a unified
   harvested map.

   Uses JVM-native direct access (Chroma, git subprocess, DataScript) to bypass
   Emacs single-threaded serialization. Legacy Emacs-based fallbacks kept for
   on-kanban-done compatibility.

   Extracted from crystal/hooks.clj (Wave 2) — CPPB Collect layer.

   DDD: Infrastructure service — IO collection from multiple sources."
  (:require [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.dns.result :as result]
            [hive-mcp.events.core :as ev]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.channel.piggyback :as piggyback]
            [clojure.data.json :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.crystal.harvest.attribution :as attr]
            [hive-mcp.crystal.harvest.partition :as part]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- eval-elisp-safe
  "Eval elisp with timeout. Returns {:success :result :error :timed-out}."
  [elisp timeout-ms]
  (let [r (result/try-effect* :elisp/eval-failed
                              (ec/eval-elisp-with-timeout elisp timeout-ms))]
    (if (result/ok? r)
      (let [v (:ok r)]
        (when (:timed-out v)
          (log/warn "eval-elisp-safe: timed out"))
        v)
      {:success false :error (:message r)})))

(defn- parse-json-safe
  "Parse JSON string, returning nil on failure."
  [s]
  (result/rescue nil (json/read-str s :key-fn keyword)))

(defn- harvest-error
  "Structured error map for a failed harvest source.

   Shape: {:type :harvest-failed :fn FN-NAME :msg MSG}. Returned under the
   :error key of the harvest result AND carried verbatim as the :context of
   the :system/error telemetry event — one map, one source of truth.
   `harvest-all` aggregates these under :errors."
  [fn-name msg]
  {:type :harvest-failed
   :fn fn-name
   :msg (str msg)})

(defn- emit-harvest-error!
  "Dispatch [:system/error ...] telemetry for ERR, a `harvest-error` map.

   Returns nil. Never throws: a missing :system/error handler or a failing
   effect must not break the harvest it observes."
  [err]
  (result/rescue nil
                 (ev/dispatch [:system/error
                               {:error-type (:type err)
                                :source (str "crystal.harvest.collect/" (:fn err))
                                :message (:msg err)
                                :context err}]))
  nil)

;; =============================================================================
;; Legacy Emacs-based Harvest Functions
;; =============================================================================
;; Kept for fallback and compatibility. Prefer direct-* versions.

(defn ^:deprecated harvest-session-progress
  "DEPRECATED: Use harvest-all (direct Chroma access). Legacy Emacs roundtrip version."
  ([] (harvest-session-progress nil))
  ([{:keys [directory]}]
   (result/rescue {:notes [] :count 0}
                  (let [dir (or directory (ctx/current-directory))
                        project-id (when dir (scope/get-current-project-id dir))
                        session-tag (crystal/session-tag)
                        elisp (if project-id
                                (format "(json-encode (hive-mcp-memory-query 'note nil %s 50 'ephemeral nil))"
                                        (pr-str project-id))
                                "(json-encode (hive-mcp-memory-query 'note nil nil 50 'ephemeral nil))")
                        {:keys [success result error]} (eval-elisp-safe elisp 12000)]
                    (if success
                      (let [raw-notes (parse-json-safe result)
                            notes (filterv map? (if (sequential? raw-notes) raw-notes []))]
                        {:notes notes
                         :count (count notes)
                         :session session-tag
                         :project-id project-id})
                      (let [err (harvest-error "harvest-session-progress" error)]
                        (log/error "harvest-session-progress: Emacs query failed:" error)
                        (emit-harvest-error! err)
                        {:notes []
                         :count 0
                         :error err}))))))

(defn ^:deprecated harvest-completed-tasks
  "DEPRECATED: Use harvest-all (direct DataScript+Chroma access). Legacy Emacs roundtrip version."
  ([] (harvest-completed-tasks nil))
  ([{:keys [directory]}]
   (result/rescue {:tasks [] :count 0 :ds-count 0 :emacs-count 0}
                  (let [dir (or directory (ctx/current-directory))
                        project-id (when dir (scope/get-current-project-id dir))
                        ds-result (result/try-effect*
                                   :harvest/datascript-failed
                                   (->> (ds/get-completed-tasks-this-session
                                         :project-id project-id)
                                        (mapv (fn [t]
                                                {:id (:completed-task/id t)
                                                 :title (:completed-task/title t)
                                                 :completed-at (:completed-task/completed-at t)
                                                 :agent-id (:completed-task/agent-id t)
                                                 :source :datascript}))))
                        ds-ok? (result/ok? ds-result)
                        ds-tasks (if ds-ok? (:ok ds-result) [])
                        elisp-ephemeral (if project-id
                                          (format "(hive-mcp-memory-query 'note '(\"kanban\") %s 50 'ephemeral nil)"
                                                  (pr-str project-id))
                                          "(hive-mcp-memory-query 'note '(\"kanban\") nil 50 'ephemeral nil)")
                        elisp-short (if project-id
                                      (format "(hive-mcp-memory-query 'note '(\"kanban\") %s 50 'short-term nil)"
                                              (pr-str project-id))
                                      "(hive-mcp-memory-query 'note '(\"kanban\") nil 50 'short-term nil)")
                        elisp (format "(json-encode (append %s %s))" elisp-ephemeral elisp-short)
                        {:keys [success result error]} (eval-elisp-safe elisp 15000)
                        emacs-tasks (if success
                                      (let [parsed (parse-json-safe result)]
                                        (->> (if (sequential? parsed) parsed [])
                                             (filter map?)
                                             (mapv #(assoc % :source :emacs))))
                                      [])
                        all-tasks (concat ds-tasks emacs-tasks)
                        failures (cond-> []
                                   (not ds-ok?) (conj (str "datascript: " (:message ds-result)))
                                   (not success) (conj (str "emacs: " error)))
                        err (when (seq failures)
                              (harvest-error "harvest-completed-tasks"
                                             (str/join "; " failures)))]
                    (when err
                      (log/error "harvest-completed-tasks: source failed:" (:msg err))
                      (emit-harvest-error! err))
                    (cond-> {:tasks all-tasks
                             :count (count all-tasks)
                             :ds-count (count ds-tasks)
                             :emacs-count (count emacs-tasks)
                             :project-id project-id}
                      err (assoc :error err))))))

(defn ^:deprecated harvest-git-commits
  "DEPRECATED: Use harvest-all (direct JVM subprocess). Legacy Emacs roundtrip version."
  ([] (harvest-git-commits nil))
  ([{:keys [directory agent-id]}]
   (result/rescue {:commits [] :count 0}
                  (let [dir (or directory (ctx/current-directory))
                        effective-agent (or agent-id (ctx/current-agent-id))
                        start (or (crystal/get-session-start effective-agent)
                                  (crystal/get-session-start "_global")
                                  (crystal/get-session-start nil))
                        since (if start (.toString start) "midnight")
                        elisp (if dir
                                (format "(let ((default-directory %s)) (shell-command-to-string \"git log --since='%s' --oneline 2>/dev/null\"))"
                                        (pr-str dir) since)
                                (format "(shell-command-to-string \"git log --since='%s' --oneline 2>/dev/null\")" since))
                        {:keys [success result error]} (eval-elisp-safe elisp 10000)]
                    (if success
                      (let [commits (when (and result (not (str/blank? result)))
                                      (str/split-lines (str/trim result)))]
                        {:commits (or commits [])
                         :count (count (or commits []))
                         :directory dir})
                      (let [err (harvest-error "harvest-git-commits" error)]
                        (log/error "harvest-git-commits: Emacs command failed:" error)
                        (emit-harvest-error! err)
                        {:commits []
                         :count 0
                         :error err}))))))

;; =============================================================================
;; Direct Harvest Functions (bypass Emacs — JVM-native)
;; =============================================================================

(defn- harvest-progress-direct
  "Harvest progress notes directly from Chroma (no Emacs roundtrip)."
  [{:keys [directory]}]
  (result/rescue {:notes [] :count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
                       raw (facade/query-entries :type "note"
                                                 :project-id (or project-id "global")
                                                 :limit 100)
                       notes (->> raw
                                  (filter #(= "ephemeral" (:duration %)))
                                  (take 50)
                                  vec)
                       ms (- (System/currentTimeMillis) t0)]
                   (log/info "harvest-progress-direct:" (count notes) "notes in" ms "ms"
                             "(from" (count raw) "total entries)")
                   {:notes notes
                    :count (count notes)
                    :project-id project-id})))

(defn- harvest-tasks-direct
  "Harvest completed tasks from DataScript + Chroma (no Emacs roundtrip)."
  [{:keys [directory]}]
  (result/rescue {:tasks [] :count 0 :ds-count 0 :chroma-count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
                       ds-tasks (result/rescue []
                                               (->> (ds/get-completed-tasks-this-session
                                                     :project-id project-id)
                                                    (mapv (fn [t]
                                                            {:id (:completed-task/id t)
                                                             :title (:completed-task/title t)
                                                             :completed-at (:completed-task/completed-at t)
                                                             :agent-id (:completed-task/agent-id t)
                                                             :source :datascript}))))
                       chroma-tasks (result/rescue []
                                                   (let [entries (facade/query-entries :type "note"
                                                                                       :tags ["kanban"]
                                                                                       :project-id (or project-id "global")
                                                                                       :limit 50)]
                                                     (->> entries
                                                          (filter map?)
                                                          (mapv #(assoc % :source :chroma)))))
                       all-tasks (concat ds-tasks chroma-tasks)
                       ms (- (System/currentTimeMillis) t0)]
                   (log/info "harvest-tasks-direct:" (count all-tasks) "tasks in" ms "ms"
                             "(ds:" (count ds-tasks) "chroma:" (count chroma-tasks) ")")
                   {:tasks all-tasks
                    :count (count all-tasks)
                    :ds-count (count ds-tasks)
                    :chroma-count (count chroma-tasks)
                    :project-id project-id})))

(defn- harvest-commits-direct
  "Harvest git commits via JVM subprocess (no Emacs roundtrip)."
  [{:keys [directory agent-id]}]
  (result/rescue {:commits [] :count 0}
                 (let [dir (or directory (ctx/current-directory))
                       effective-agent (or agent-id (ctx/current-agent-id))
                       start (or (crystal/get-session-start effective-agent)
                                 (crystal/get-session-start "_global")
                                 (crystal/get-session-start nil))
                       since (if start (.toString start) "midnight")
                       t0 (System/currentTimeMillis)
                       {:keys [exit out err]} (sh "git" "log"
                                                  (str "--since=" since)
                                                  "--oneline"
                                                  :dir (or dir "."))
                       ms (- (System/currentTimeMillis) t0)]
                   (if (zero? exit)
                     (let [commits (when (and out (not (str/blank? out)))
                                     (str/split-lines (str/trim out)))]
                       (log/info "harvest-commits-direct:" (count (or commits [])) "commits in" ms "ms")
                       {:commits (or commits [])
                        :count (count (or commits []))
                        :directory dir})
                     (do
                       (log/warn "harvest-commits-direct: git failed exit=" exit "err=" err "in" ms "ms")
                       {:commits []
                        :count 0
                        :error {:type :git-failed :exit exit :err err}})))))

(defn- harvest-hivemind-messages
  "Harvest hivemind shouts since session start (no Emacs roundtrip)."
  [{:keys [directory agent-id]}]
  (result/rescue {:messages [] :count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       effective-agent (or agent-id (ctx/current-agent-id))
                       start (or (crystal/get-session-start effective-agent)
                                 (crystal/get-session-start "_global")
                                 (crystal/get-session-start nil))
                       since-ms (if start (.toEpochMilli start) 0)
                       t0 (System/currentTimeMillis)
                       messages (piggyback/fetch-history :since since-ms
                                                        :limit 100
                                                        :project-id project-id)
                       ms (- (System/currentTimeMillis) t0)]
                   (log/info "harvest-hivemind-messages:" (count messages) "shouts in" ms "ms"
                             "since" (or (some-> start .toString) "epoch"))
                   {:messages (vec messages)
                    :count (count messages)
                    :project-id project-id})))

(defn- harvest-kanban-activity
  "Harvest kanban task activity for the session window."
  [{:keys [directory]}]
  (result/rescue {:tasks-completed [] :completed-count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
                       completed (result/rescue []
                                   (let [raw (ds/get-completed-tasks-this-session
                                              :project-id project-id)]
                                     (->> raw
                                          (mapv (fn [t]
                                                  {:id (or (:completed-task/id t) (:id t))
                                                   :title (or (:completed-task/title t) (:title t))
                                                   :completed-at (or (:completed-task/completed-at t)
                                                                     (:completed-at t))
                                                   :agent-id (or (:completed-task/agent-id t)
                                                                  (:agent-id t))})))))
                       ms (- (System/currentTimeMillis) t0)]
                   (log/info "harvest-kanban-activity:" (count completed)
                             "completed tasks in" ms "ms")
                   {:tasks-completed completed
                    :completed-count (count completed)
                    :project-id project-id})))

(defn- harvest-kg-edges-direct
  "Harvest KG edges created since session start (no Emacs roundtrip)."
  [{:keys [directory agent-id]}]
  (result/rescue {:edges [] :count 0 :by-relation {}}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       effective-agent (or agent-id (ctx/current-agent-id))
                       start (or (crystal/get-session-start effective-agent)
                                 (crystal/get-session-start "_global")
                                 (crystal/get-session-start nil))
                       t0 (System/currentTimeMillis)]
                   (if start
                     (let [edges (kg-edges/get-edges-since start
                                                           :scope project-id
                                                           :limit 200
                                                           :exclude-relations #{:co-accessed})
                           by-relation (reduce (fn [acc e]
                                                 (let [rel (keyword (or (:kg-edge/relation e) "unknown"))]
                                                   (update acc rel (fnil inc 0))))
                                               {}
                                               edges)
                           ms (- (System/currentTimeMillis) t0)]
                       (log/info "harvest-kg-edges-direct:" (count edges) "edges in" ms "ms"
                                 "by-relation:" by-relation)
                       {:edges (vec edges)
                        :count (count edges)
                        :by-relation by-relation
                        :project-id project-id})
                     (do
                       (log/debug "harvest-kg-edges-direct: no session start, skipping")
                       {:edges [] :count 0 :by-relation {}})))))

(defn- harvest-kanban-movements-direct
  "Harvest kanban status transitions from DataScript (session-scoped)."
  [{:keys [directory]}]
  (result/rescue {:movements [] :count 0 :transitions {}}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
                       movements (result/rescue []
                                   (ds/get-kanban-movements-this-session
                                    :project-id project-id))
                       transitions (reduce (fn [acc mv]
                                             (let [k (str (or (:kanban-movement/from mv) "nil")
                                                          "->"
                                                          (:kanban-movement/to mv))]
                                               (update acc k (fnil inc 0))))
                                           {}
                                           movements)
                       ms (- (System/currentTimeMillis) t0)]
                   (log/info "harvest-kanban-movements:" (count movements)
                             "movements in" ms "ms" "transitions:" transitions)
                   {:movements (vec movements)
                    :count (count movements)
                    :transitions transitions
                    :project-id project-id})))

;; =============================================================================
;; Harvest Result Assembly (pure)
;; =============================================================================

(defn assemble-harvest-result
  "Pure fn: assembles the final harvest result map from deref'd harvest data.
   No IO — only data transformation.

   Takes a single map of all harvested components and returns the unified
   result map with :progress-notes, :completed-tasks, :git-commits, etc."
  [{:keys [progress tasks commits recalls hivemind kanban kg-edges kanban-mvs
           session-timing memory-ids-created memory-ids-accessed
           dir effective-agent errors session]}]
  {:progress-notes      (:notes progress)
   :completed-tasks     (:tasks tasks)
   :git-commits         (:commits commits)
   :recalls             recalls
   :hivemind-messages   (:messages hivemind)
   :kanban-activity     kanban
   :kg-edges-created    kg-edges
   :kanban-movements    kanban-mvs
   :session-timing      session-timing
   :session-temporal    session-timing
   :memory-ids-created  memory-ids-created
   :memory-ids-accessed memory-ids-accessed
   :session             session
   :directory           dir
   :agent-id            effective-agent
   :summary {:progress-count       (:count progress)
             :task-count           (:count tasks)
             :commit-count         (:count commits)
             :recall-count         (count recalls)
             :hivemind-shout-count (:count hivemind)
             :kanban-completed     (:completed-count kanban)
             :kg-edge-count        (:count kg-edges)
             :kanban-movement-count (:count kanban-mvs)
             :created-count        (count memory-ids-created)
             :accessed-count       (count memory-ids-accessed)
             ;; Per-type breakdown of memories created this session (decision,
             ;; convention, …). Feeds the wrap-notify projection so the hivemind
             ;; piggyback reports real counts instead of 0 decisions/0 conventions.
             :created-by-type      (frequencies (keep :type memory-ids-created))}
   :errors (when (seq errors) errors)})

;; =============================================================================
;; Harvest Orchestrator
;; =============================================================================

(defn harvest-all
  "Harvest all session data for wrap crystallization.
   Orchestrates 8 sources in parallel with 10s timeout.

   Returns map with :progress-notes, :completed-tasks, :git-commits, :recalls,
   :hivemind-messages, :kanban-activity, :kg-edges-created, :kanban-movements,
   :session-timing, :session-temporal, :memory-ids-created, :memory-ids-accessed,
   :session, :directory, :agent-id, :summary, :errors.

   Opts:
     :directory  -- working directory for project scoping
     :agent-id   -- agent identity for per-agent session timing"
  ([] (harvest-all nil))
  ([{:keys [directory agent-id] :as _opts}]
   (result/rescue {:progress-notes []
                   :completed-tasks []
                   :git-commits []
                   :recalls {}
                   :hivemind-messages []
                   :kanban-activity {:tasks-completed [] :completed-count 0}
                   :kg-edges-created {:edges [] :count 0 :by-relation {}}
                   :kanban-movements {:movements [] :count 0 :transitions {}}
                   :session-temporal {:session-start nil :session-end nil :duration-minutes 0}
                   :memory-ids-created []
                   :memory-ids-accessed []
                   :session (result/rescue "unknown" (crystal/session-id))
                   :summary {:progress-count 0
                             :task-count 0
                             :commit-count 0
                             :recall-count 0
                             :hivemind-shout-count 0
                             :kanban-completed 0
                             :kg-edge-count 0
                             :kanban-movement-count 0
                             :created-count 0
                             :accessed-count 0
                             :created-by-type {}}
                   :errors [{:type :harvest-failed :fn "harvest-all"}]}
                  (let [dir (or directory (ctx/current-directory))
                        effective-agent (or agent-id (ctx/current-agent-id))
                        project-id (when dir (scope/get-current-project-id dir))
                        t0 (System/currentTimeMillis)
                        f-progress   (pool/with-io (harvest-progress-direct {:directory dir}))
                        f-tasks      (pool/with-io (harvest-tasks-direct {:directory dir}))
                        f-commits    (pool/with-io (harvest-commits-direct {:directory dir :agent-id effective-agent}))
                        f-recalls    (pool/with-io (result/rescue {} (recall/get-buffered-recalls)))
                        f-hivemind   (pool/with-io (harvest-hivemind-messages {:directory dir :agent-id effective-agent}))
                        f-kanban     (pool/with-io (harvest-kanban-activity {:directory dir}))
                        f-kg-edges   (pool/with-io (harvest-kg-edges-direct {:directory dir :agent-id effective-agent}))
                        f-kanban-mvs (pool/with-io (harvest-kanban-movements-direct {:directory dir}))
                        harvest-timeout 10000
                        progress   (deref f-progress harvest-timeout {:notes [] :count 0 :error {:type :harvest-timeout :fn "harvest-session-progress"}})
                        tasks      (deref f-tasks harvest-timeout {:tasks [] :count 0 :error {:type :harvest-timeout :fn "harvest-completed-tasks"}})
                        commits    (deref f-commits harvest-timeout {:commits [] :count 0 :error {:type :harvest-timeout :fn "harvest-git-commits"}})
                        recalls    (deref f-recalls harvest-timeout {})
                        hivemind   (deref f-hivemind harvest-timeout {:messages [] :count 0 :error {:type :harvest-timeout :fn "harvest-hivemind-messages"}})
                        kanban     (deref f-kanban harvest-timeout {:tasks-completed [] :completed-count 0 :error {:type :harvest-timeout :fn "harvest-kanban-activity"}})
                        kg-edges   (deref f-kg-edges harvest-timeout {:edges [] :count 0 :by-relation {} :error {:type :harvest-timeout :fn "harvest-kg-edges"}})
                        kanban-mvs (deref f-kanban-mvs harvest-timeout {:movements [] :count 0 :transitions {} :error {:type :harvest-timeout :fn "harvest-kanban-movements"}})
                        _ (log/info "harvest-all: parallel collection" (- (System/currentTimeMillis) t0) "ms"
                                    "progress:" (:count progress) "tasks:" (:count tasks)
                                    "commits:" (:count commits) "recalls:" (count recalls)
                                    "hivemind:" (:count hivemind)
                                    "kanban:" (:completed-count kanban)
                                    "kg-edges:" (:count kg-edges)
                                    "kanban-mvs:" (:count kanban-mvs))
                        session-start (or (crystal/get-session-start effective-agent)
                                          (crystal/get-session-start "_global")
                                          (crystal/get-session-start nil))
                        session-timing (result/rescue
                                        {:session-start nil :session-end nil :duration-minutes 0}
                                        (crystal/session-timing-metadata
                                         session-start
                                         (java.time.Instant/now)))
                        memory-ids-created (result/rescue [] (recall/flush-created-ids! project-id))
                        _ (log/info "harvest-all: flushed" (count memory-ids-created)
                                    "created-ids for project-id" project-id
                                    "ids:" (mapv :id memory-ids-created))
                        memory-ids-accessed (vec (keys recalls))
                        errors (filterv some? [(:error progress)
                                               (:error tasks)
                                               (:error commits)
                                               (:error hivemind)
                                               (:error kanban)
                                               (:error kg-edges)
                                               (:error kanban-mvs)])
                        session (crystal/session-id)]
                    (assemble-harvest-result
                     {:progress            progress
                      :tasks               tasks
                      :commits             commits
                      :recalls             recalls
                      :hivemind            hivemind
                      :kanban              kanban
                      :kg-edges            kg-edges
                      :kanban-mvs          kanban-mvs
                      :session-timing      session-timing
                      :memory-ids-created  memory-ids-created
                      :memory-ids-accessed memory-ids-accessed
                      :dir                 dir
                      :effective-agent     effective-agent
                      :errors              errors
                      :session             session})))))

(defn harvest-all-by-scope
  "Per-scope variant of `harvest-all`. Returns a `HarvestByScope` shape
   (`hive-mcp.crystal.harvest.by-scope/HarvestByScope`) — every datum
   tagged with the project-id it belongs to.

   Pipeline:
     1. `harvest-all` collects flat session data (current legacy path).
     2. `attribution/attribute-harvest` tags each datum with its pid;
        weak-attribution datums (commits, accessed-ids) inherit the
        harvest-context pid.
     3. `partition/partition-harvest-by-scope` distributes attributed
        datums into ScopeSlice buckets per pid + UmbrellaSlice for
        cross-cutting facts.

   Step 4 of the per-scope wrap emission plan
   (memory `20260504173159-46dc47f1`).

   Note: today's harvest sources still pre-filter by single project-id,
   so a single-scope session produces a `HarvestByScope` with one entry
   in `:by-scope` and the harvest-context pid as that entry's key. The
   shape contract is in place for step-5 fan-out; full multi-scope
   collection (dropping per-source filters or running harvest-all once
   per touched pid) lands as a step-4a follow-up after step-12 measures
   the gap on real sessions.

   Opts (forwarded to harvest-all):
     :directory  -- working directory for project scoping
     :agent-id   -- agent identity for per-agent session timing"
  ([] (harvest-all-by-scope nil))
  ([{:keys [directory agent-id] :as opts}]
   (let [legacy     (harvest-all opts)
         dir        (or directory (:directory legacy))
         source-pid (when dir (scope/get-current-project-id dir))
         attribution (attr/attribute-harvest legacy source-pid)
         hbs        (part/partition-harvest-by-scope attribution)]
     (log/info "harvest-all-by-scope:"
               "scopes:" (count (:by-scope hbs))
               "scope-datums:" (part/scope-datum-count hbs)
               "umbrella-datums:" (part/umbrella-datum-count hbs)
               "source-pid:" source-pid)
     (assoc hbs
            :directory dir
            :agent-id  (or agent-id (:agent-id legacy))
            :session   (:session legacy)
            ;; Carry the legacy summary alongside HarvestByScope for any
            ;; consumer that still wants flat counts during the migration
            ;; window. Step-5 may drop this once synthesis is fan-out.
            :summary   (:summary legacy)))))