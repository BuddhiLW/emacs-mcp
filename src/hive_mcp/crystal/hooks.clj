(ns hive-mcp.crystal.hooks
  "Event hooks for progressive crystallization.

   All error handling uses hive-mcp.dns.result DSL:
   - result/rescue for non-blocking fallbacks (replaces safe-effect, log-and-nil)
   - result/try-effect* for IO boundaries (elisp, Chroma, channels)
   - Zero raw try-catch blocks.

   DDD: Domain service layer for crystal/wrap events."
  (:require [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.hooks.core :as hooks]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.dns.result :as result]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.channel.piggyback :as piggyback]
            [clojure.data.json :as json]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Shared Helpers (result-dsl based)
;; =============================================================================

(defn- eval-elisp-safe
  "Eval elisp with timeout. Returns {:success :result :error :timed-out}.
   Catches exceptions via try-effect*, converting back to legacy shape."
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

;; =============================================================================
;; Kanban DONE Hook
;; =============================================================================

(defn on-kanban-done
  "Hook called when a kanban task moves to DONE."
  [{:keys [id title project-id _context _priority _started] :as task}]
  (log/info "Kanban DONE hook triggered for task:" id title "project-id:" project-id)
  ;; Register in DataScript for wrap harvesting (non-blocking)
  (result/rescue nil
                 (do (ds/register-completed-task! id {:title title :project-id project-id})
                     (log/debug "Registered completed task in DataScript:" id "project-id:" project-id)))
  (let [;; Generate progress note
        progress-note (crystal/task-to-progress-note
                       (assoc task :completed-at (.toString (java.time.Instant/now))))
        ;; Convert tags vector to elisp list format
        tags-elisp (str "(" (str/join " " (map pr-str (:tags progress-note))) ")")
        ;; Store via Emacs memory
        elisp (format "(hive-mcp-memory-add 'note %s '%s nil 'ephemeral)"
                      (pr-str (:content progress-note))
                      tags-elisp)
        {:keys [success result error timed-out]} (eval-elisp-safe elisp 10000)]
    (when timed-out
      (log/warn "on-kanban-done: elisp eval timed out for task:" id))
    (if success
      (do
        (log/info "Created progress note for completed task:" id)
        ;; Broadcast event for swarm awareness
        (when (channel/server-connected?)
          (channel/broadcast! {:type "task-completed"
                               :task-id id
                               :title title
                               :progress-note-id result}))
        {:success true
         :progress-note-id result
         :task task})
      (do
        (log/error "Failed to create progress note:" error)
        {:success false
         :error error
         :task task}))))

(defn extract-task-from-kanban-entry
  "Extract task data from a kanban memory entry."
  [entry]
  (let [content (:content entry)
        project-id (some (fn [tag]
                           (when (and (string? tag) (str/starts-with? tag "scope:project:"))
                             (subs tag (count "scope:project:"))))
                         (:tags entry))]
    (if (map? content)
      (cond-> {:id (:id entry)
               :title (:title content)
               :context (:context content)
               :priority (or (:priority content) "medium")
               :started (:started content)
               :status (:status content)}
        project-id (assoc :project-id project-id))
      ;; Handle string content (legacy format)
      (cond-> {:id (:id entry)
               :title (str content)
               :context nil
               :priority "medium"
               :started nil
               :status "done"}
        project-id (assoc :project-id project-id)))))

;; =============================================================================
;; Memory Access Hook
;; =============================================================================

(defn on-memory-accessed
  "Hook called when memory entries are accessed."
  [{:keys [entry-ids source session project] :as _params}]
  (let [current-session (or session (crystal/session-id))]
    (doseq [entry-id entry-ids]
      (let [event (recall/create-recall-event
                   {:source source
                    :session current-session
                    :project project
                    :explicit? (not (contains? #{"catchup" "wrap"} source))})]
        (recall/buffer-recall! entry-id event)))
    {:tracked (count entry-ids)
     :source source}))

;; =============================================================================
;; Wrap Harvest Hook
;; =============================================================================

(defn harvest-session-progress
  "Harvest session progress notes for wrap workflow."
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
                      (do
                        (log/error "harvest-session-progress: Emacs query failed:" error)
                        {:notes []
                         :count 0
                         :error {:type :harvest-failed
                                 :fn "harvest-session-progress"
                                 :msg error}}))))))

(defn harvest-completed-tasks
  "Harvest completed task progress notes for wrap."
  ([] (harvest-completed-tasks nil))
  ([{:keys [directory]}]
   (result/rescue {:tasks [] :count 0 :ds-count 0 :emacs-count 0}
                  (let [dir (or directory (ctx/current-directory))
                        project-id (when dir (scope/get-current-project-id dir))
           ;; DataScript registry (project-scoped)
                        ds-tasks (result/rescue []
                                                (->> (ds/get-completed-tasks-this-session
                                                      :project-id project-id)
                                                     (mapv (fn [t]
                                                             {:id (:completed-task/id t)
                                                              :title (:completed-task/title t)
                                                              :completed-at (:completed-task/completed-at t)
                                                              :agent-id (:completed-task/agent-id t)
                                                              :source :datascript}))))
           ;; Fallback source: Emacs memory (kanban-tagged notes)
           ;; Use project-id for scoped queries when available
                        elisp-ephemeral (if project-id
                                          (format "(hive-mcp-memory-query 'note '(\"kanban\") %s 50 'ephemeral nil)"
                                                  (pr-str project-id))
                                          "(hive-mcp-memory-query 'note '(\"kanban\") nil 50 'ephemeral nil)")
                        elisp-short (if project-id
                                      (format "(hive-mcp-memory-query 'note '(\"kanban\") %s 50 'short-term nil)"
                                              (pr-str project-id))
                                      "(hive-mcp-memory-query 'note '(\"kanban\") nil 50 'short-term nil)")
                        elisp (format "(json-encode (append %s %s))" elisp-ephemeral elisp-short)
                        {:keys [success result]} (eval-elisp-safe elisp 15000)
                        emacs-tasks (if success
                                      (let [parsed (parse-json-safe result)]
                                        (->> (if (sequential? parsed) parsed [])
                                             (filter map?)
                                             (mapv #(assoc % :source :emacs))))
                                      [])
                        all-tasks (concat ds-tasks emacs-tasks)]
                    {:tasks all-tasks
                     :count (count all-tasks)
                     :ds-count (count ds-tasks)
                     :emacs-count (count emacs-tasks)
                     :project-id project-id}))))

(defn harvest-git-commits
  "Harvest git commits since session start (or midnight if no session-start recorded)."
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
                      (do
                        (log/error "harvest-git-commits: Emacs command failed:" error)
                        {:commits []
                         :count 0
                         :error {:type :harvest-failed
                                 :fn "harvest-git-commits"
                                 :msg error}}))))))

;; =============================================================================
;; Direct Harvest Functions (bypass Emacs — JVM-native)
;; =============================================================================
;; These eliminate the Emacs single-threaded serialization bottleneck.
;; Old Emacs-based versions are kept for on-kanban-done and fallback.

(defn- harvest-progress-direct
  "Harvest progress notes directly from Chroma (no Emacs roundtrip).
   Queries ephemeral notes for the project, filtered client-side by duration."
  [{:keys [directory]}]
  (result/rescue {:notes [] :count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
          ;; Query Chroma directly — metadata-only, no embedding computation
                       raw (chroma/query-entries :type "note"
                                                 :project-id (or project-id "global")
                                                 :limit 100)
          ;; Filter to ephemeral duration (progress notes)
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
  "Harvest completed tasks directly from DataScript + Chroma (no Emacs roundtrip).
   DataScript has in-session tasks; Chroma has kanban-tagged notes."
  [{:keys [directory]}]
  (result/rescue {:tasks [] :count 0 :ds-count 0 :chroma-count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
          ;; DataScript registry (in-process, instant)
                       ds-tasks (result/rescue []
                                               (->> (ds/get-completed-tasks-this-session
                                                     :project-id project-id)
                                                    (mapv (fn [t]
                                                            {:id (:completed-task/id t)
                                                             :title (:completed-task/title t)
                                                             :completed-at (:completed-task/completed-at t)
                                                             :agent-id (:completed-task/agent-id t)
                                                             :source :datascript}))))
          ;; Chroma query for kanban-tagged notes (replaces Emacs memory query)
                       chroma-tasks (result/rescue []
                                                   (let [entries (chroma/query-entries :type "note"
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
  "Harvest git commits directly via JVM subprocess (no Emacs roundtrip).
   Uses clojure.java.shell/sh instead of emacsclient eval."
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
  "Harvest hivemind shouts since session start (no Emacs roundtrip).
   Uses piggyback/fetch-history for untracked access to message buffer."
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
  "Harvest kanban task activity for the session window.
   Combines DataScript completed-task registry with broader task queries."
  [{:keys [directory]}]
  (result/rescue {:tasks-completed [] :completed-count 0}
                 (let [dir (or directory (ctx/current-directory))
                       project-id (when dir (scope/get-current-project-id dir))
                       t0 (System/currentTimeMillis)
                       ;; Session-scoped completed tasks from DataScript registry
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

(defn harvest-all
  "Harvest all session data for wrap crystallization.
   Returns map with :progress-notes, :completed-tasks, :git-commits, :recalls, :hivemind-messages,
   :kanban-activity, :session-timing, :session-temporal (alias), :memory-ids-created (flushed),
   :memory-ids-accessed (recall buffer keys), :session, :directory, :agent-id, :summary, :errors.

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
                             :created-count 0
                             :accessed-count 0}
                   :errors [{:type :harvest-failed :fn "harvest-all"}]}
                  (let [dir (or directory (ctx/current-directory))
                        effective-agent (or agent-id (ctx/current-agent-id))
                        project-id (when dir (scope/get-current-project-id dir))
                        t0 (System/currentTimeMillis)
                        ;; Fire all 3 harvests in parallel using DIRECT functions
                        ;; (bypass Emacs serialization — JVM-native Chroma + git)
                        ;; Bounded by shared IO pool
                        f-progress (pool/with-io (harvest-progress-direct {:directory dir}))
                        f-tasks    (pool/with-io (harvest-tasks-direct {:directory dir}))
                        f-commits  (pool/with-io (harvest-commits-direct {:directory dir :agent-id effective-agent}))
                        f-recalls  (pool/with-io (result/rescue {} (recall/get-buffered-recalls)))
                        f-hivemind (pool/with-io (harvest-hivemind-messages {:directory dir :agent-id effective-agent}))
                        f-kanban   (pool/with-io (harvest-kanban-activity {:directory dir}))
                        ;; Collect with 10s timeout (direct calls: Chroma ~200ms, git ~100ms)
                        harvest-timeout 10000
                        progress (deref f-progress harvest-timeout {:notes [] :count 0 :error {:type :harvest-timeout :fn "harvest-session-progress"}})
                        tasks    (deref f-tasks harvest-timeout {:tasks [] :count 0 :error {:type :harvest-timeout :fn "harvest-completed-tasks"}})
                        commits  (deref f-commits harvest-timeout {:commits [] :count 0 :error {:type :harvest-timeout :fn "harvest-git-commits"}})
                        recalls  (deref f-recalls harvest-timeout {})
                        hivemind (deref f-hivemind harvest-timeout {:messages [] :count 0 :error {:type :harvest-timeout :fn "harvest-hivemind-messages"}})
                        kanban   (deref f-kanban harvest-timeout {:tasks-completed [] :completed-count 0 :error {:type :harvest-timeout :fn "harvest-kanban-activity"}})
                        _ (log/info "harvest-all: parallel collection" (- (System/currentTimeMillis) t0) "ms"
                                    "progress:" (:count progress) "tasks:" (:count tasks)
                                    "commits:" (:count commits) "recalls:" (count recalls)
                                    "hivemind:" (:count hivemind)
                                    "kanban:" (:completed-count kanban))
                        session-start (or (crystal/get-session-start effective-agent)
                                          (crystal/get-session-start "_global")
                                          (crystal/get-session-start nil))
                        session-timing (result/rescue
                                        {:session-start nil :session-end nil :duration-minutes 0}
                                        (crystal/session-timing-metadata
                                         session-start
                                         (java.time.Instant/now)))
           ;; Flush created IDs (project-scoped, destructive read)
                        memory-ids-created (result/rescue [] (recall/flush-created-ids! project-id))
                        _ (log/info "harvest-all: flushed" (count memory-ids-created)
                                    "created-ids for project-id" project-id
                                    "ids:" (mapv :id memory-ids-created))
           ;; Auto-KG: memory IDs accessed this session (from recall buffer keys)
                        memory-ids-accessed (vec (keys recalls))
           ;; Aggregate errors from sub-harvests
                        errors (filterv some? [(:error progress)
                                               (:error tasks)
                                               (:error commits)
                                               (:error hivemind)
                                               (:error kanban)])]
                    {:progress-notes (:notes progress)
                     :completed-tasks (:tasks tasks)
                     :git-commits (:commits commits)
                     :recalls recalls
                     :hivemind-messages (:messages hivemind)
                     :kanban-activity kanban
                     :session-timing session-timing
                     :session-temporal session-timing
                     :memory-ids-created memory-ids-created
                     :memory-ids-accessed memory-ids-accessed
                     :session (crystal/session-id)
                     :directory dir
                     :agent-id effective-agent
                     :summary {:progress-count (:count progress)
                               :task-count (:count tasks)
                               :commit-count (:count commits)
                               :recall-count (count recalls)
                               :hivemind-shout-count (:count hivemind)
                               :kanban-completed (:completed-count kanban)
                               :created-count (count memory-ids-created)
                               :accessed-count (count memory-ids-accessed)}
                     :errors (when (seq errors) errors)}))))

;; =============================================================================
;; Extension Delegation Helpers
;; =============================================================================

(defn- delegate-or-noop
  "Try to delegate to extension fn, fall back to default value."
  [ext-key default-val args]
  (if-let [f (ext/get-extension ext-key)]
    (apply f args)
    (do
      (log/debug "Extension not available, returning default for" ext-key)
      default-val)))

;; =============================================================================
;; Noop Defaults (lifecycle operations)
;; =============================================================================

(defn- surface-rescue-error
  "If rescue/guard attached error metadata, surface :error into map for backward compat.
   rescue and guard (hive-dsl.result) attach {:hive-dsl.result/error {:message ...}}."
  [m]
  (if-let [err (:hive-dsl.result/error (meta m))]
    (assoc m :error (:message err))
    m))

(def ^:private noop-a {:promoted 0 :skipped 0 :below 0 :evaluated 0})
(def ^:private noop-b {:decayed 0 :pruned 0 :fresh 0 :evaluated 0})
(def ^:private noop-c {:promoted 0 :candidates 0 :total-scanned 0})
(def ^:private noop-d {:decayed 0 :expired 0 :total-scanned 0})
(def ^:private noop-e {:files-captured 0})

(def ^:private ^:const op-timeout 15000)

(defn- timed-deref [fut default]
  (let [r (result/guard Exception default
                        (deref fut op-timeout ::timeout))]
    (if (= r ::timeout)
      (do (future-cancel fut) (assoc default :error "timed-out"))
      (surface-rescue-error r))))

(defn- run-lifecycle-ops!
  "Run post-crystallization lifecycle operations in parallel with timeout guard.
   Optional harvested map is passed through to :ch/e."
  [project-id directory & {:keys [harvested]}]
  (let [scope-arg [{:scope project-id :created-by "crystallize-session"}]
        run (fn [k noop args]
              (pool/with-io (surface-rescue-error
                             (result/rescue noop (delegate-or-noop k noop args)))))
        fa (run :ch/a noop-a scope-arg)
        fb (run :ch/b noop-b scope-arg)
        fc (run :ch/c noop-c [{:directory directory :limit 100}])
        fd (run :ch/d noop-d [{:directory directory :limit 50}])
        fe (run :ch/e noop-e [{:directory directory
                               :project-id project-id
                               :harvested harvested}])
        [ra rb rc rd re] (mapv timed-deref [fa fb fc fd fe]
                               [noop-a noop-b noop-c noop-d noop-e])]
    {:promotion-stats (select-keys ra [:promoted :skipped :below :evaluated :error])
     :decay-stats (select-keys rb [:decayed :pruned :fresh :evaluated :error])
     :xpoll-stats (select-keys rc [:promoted :candidates :total-scanned :error])
     :memory-decay-stats (select-keys rd [:decayed :expired :total-scanned :error])
     :file-provenance-stats (select-keys re [:files-captured :files-skipped :edges-created :error])}))

;; =============================================================================
;; Temporal Block Formatting (Step-5: Auto-KG wrap metadata)
;; =============================================================================

(defn- format-temporal-block
  "Format session timing and memory-ID counts as a markdown block.
   Appended to session summary content for temporal traceability.
   Nil-safe: handles missing memory-ids gracefully (steps 2-4 may not be merged yet)."
  [{:keys [session-start session-end duration-minutes]}
   {:keys [memory-ids-created memory-ids-accessed]}]
  (let [lines (cond-> ["\n\n### Temporal Metadata"
                       (str "- Session start: " (or session-start "unknown"))
                       (str "- Session end: " (or session-end "unknown"))
                       (str "- Duration: " (or duration-minutes 0) " minutes")]
                (seq memory-ids-created)
                (conj (str "- Memory entries created: " (count memory-ids-created)))
                (seq memory-ids-accessed)
                (conj (str "- Memory entries accessed: " (count memory-ids-accessed))))]
    (str/join "\n" lines)))

;; =============================================================================
;; Crystallization Hook
;; =============================================================================

(defn crystallize-session
  "Crystallize session data into long-term memory.
   Tries progress/task/commit summary first, falls back to memory-activity
   summary for coordinator sessions."
  [{:keys [progress-notes completed-tasks git-commits directory _recalls] :as harvested}]
  (log/info "Crystallizing session:" (crystal/session-id) (when directory (str "directory:" directory)))

  (let [project-id (or (when directory (scope/get-current-project-id directory)) "global")
        session-timing (or (:session-timing harvested)
                           (crystal/session-timing-metadata nil (java.time.Instant/now)))
        summary (or (crystal/summarize-session-progress
                     (concat progress-notes completed-tasks)
                     git-commits)
                    (crystal/summarize-memory-activity
                     {:created  (count (or (:memory-ids-created harvested) []))
                      :accessed (count (or (:memory-ids-accessed harvested) []))}))]
    (if (nil? summary)
      ;; No content — still run lifecycle ops for maintenance
      (let [lifecycle (run-lifecycle-ops! project-id directory :harvested harvested)]
        (log/info "No content to crystallize for session:" (crystal/session-id))
        (merge {:skipped true
                :reason "no-content"
                :session (crystal/session-id)
                :project-id project-id
                :session-timing session-timing
                :stats (:summary harvested)}
               lifecycle))
      ;; Content exists — start lifecycle IN PARALLEL with Chroma indexing
      ;; (lifecycle ops don't depend on the Chroma entry-id)
      (let [temporal-block (format-temporal-block session-timing harvested)
            content (str (:content summary) temporal-block)
            base-tags (into (or (:tags summary) []) ["auto-kg" "session-wrap" "temporal"])
            tags (scope/inject-project-scope base-tags project-id)
            expires (dur/calculate-expires "short")
            ;; Start lifecycle ops on solo executor (avoids IO pool nesting —
            ;; run-lifecycle-ops! internally submits 5 ops to IO pool via pool/with-io)
            lifecycle-fut (pool/with-solo (run-lifecycle-ops! project-id directory :harvested harvested))
            t0 (System/currentTimeMillis)
            store-r (result/try-effect* :crystal/store-failed
                                        (chroma/index-memory-entry!
                                         {:type "note"
                                          :content content
                                          :tags tags
                                          :duration "short"
                                          :expires (or expires "")
                                          :project-id project-id
                                          :content-hash (chroma/content-hash content)}))
            chroma-ms (- (System/currentTimeMillis) t0)
            ;; Collect lifecycle results (likely already done — they ran during embedding)
            ;; Use full op-timeout: lifecycle started before chroma, so it's been running
            ;; for chroma-ms already. If not done after op-timeout total, give up.
            lifecycle (deref lifecycle-fut op-timeout
                             {:error "lifecycle-timeout"})]
        (log/info "crystallize-session: chroma" chroma-ms "ms, lifecycle overlapped")
        (if (result/ok? store-r)
          (let [entry-id (:ok store-r)]
            (log/info "Created session summary in Chroma:" entry-id "project:" project-id)
            (merge {:summary-id entry-id
                    :session (crystal/session-id)
                    :project-id project-id
                    :session-timing session-timing
                    :stats (:summary harvested)}
                   lifecycle))
          {:error (:message store-r)
           :session (crystal/session-id)})))))

;; =============================================================================
;; Auto-Wrap Session-End Handler
;; =============================================================================

(defn- on-session-end
  "Handler for session-end event."
  [event-ctx]
  (log/info "Auto-wrap triggered on session-end:" (:reason event-ctx "shutdown"))
  (let [r (result/try-effect* :crystal/session-end-failed
            ;; Try to get directory from event context or request context
                              (let [dir (or (:directory event-ctx) (ctx/current-directory))
                                    agent-id (or (:agent-id event-ctx) (ctx/current-agent-id))
                                    harvested (harvest-all {:directory dir :agent-id agent-id})
                                    result (crystallize-session harvested)]
              ;; Broadcast wrap completion if channel available
                                (when (channel/server-connected?)
                                  (channel/broadcast! {:type "session-ended"
                                                       :wrap-completed true
                                                       :session (:session result)
                                                       :project-id (:project-id result)
                                                       :stats (:stats result)}))
                                (log/info "Auto-wrap completed:" (:summary-id result) "project:" (:project-id result))
                                {:success true
                                 :summary-id (:summary-id result)
                                 :project-id (:project-id result)
                                 :stats (:stats result)}))]
    (if (result/ok? r)
      (:ok r)
      {:success false
       :error (:message r)})))

;; =============================================================================
;; Hook Registration
;; =============================================================================

(defonce ^:private hooks-registered? (atom false))

(defn register-hooks!
  "Register crystal hooks with the event system."
  [registry]
  (when-not @hooks-registered?
    (log/info "Registering crystal hooks")
    ;; Register auto-wrap handler for session-end
    (hooks/register-hook registry :session-end on-session-end)
    (log/info "Registered auto-wrap handler for :session-end")
    ;; Subscribe to task-completed events if channel is available
    (when (channel/server-connected?)
      (result/rescue nil
        ;; The channel subscription would go here
        ;; For now, hooks are called directly from handlers
                     (log/debug "Channel hooks registered")))
    (reset! hooks-registered? true)
    {:registered true}))

(comment
  ;; Example usage

  ;; When a kanban task completes
  (on-kanban-done {:id "task-123"
                   :title "Implement crystal module"
                   :context "Part of progressive crystallization feature"
                   :priority "high"
                   :started "2026-01-04T10:00:00"})

  ;; Harvest session data for wrap
  (harvest-all)

  ;; Crystallize the session
  (crystallize-session (harvest-all)))
