(ns hive-mcp.tools.crystal
  "Crystal/wrap workflow tools for session crystallization.

   Handles progressive crystallization of session data into long-term memory.

   DDD: Application service layer exposing crystal domain functionality.

   Result DSL: Internal logic returns Result maps ({:ok val} or {:error category}).
   Single try-result boundary at each handler level. Zero nested try-catch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.hooks :as crystal-hooks]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.effects]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.extensions.registry :as ext]
            [clojure.data.json :as json]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Result DSL Helpers (boundary pattern)
;;; =============================================================================

(defn- try-result
  "Execute thunk f returning Result; catch unexpected exceptions as error Result.
   Unlike try-effect, expects f to return a Result map directly."
  [category f]
  (try
    (f)
    (catch Exception e
      (log/error e (str (name category) " failed"))
      (result/err category {:message (.getMessage e)}))))

(defn- result->mcp
  "Convert Result to MCP response.
   {:ok data} -> (mcp-json data), {:error ...} -> (mcp-error message)."
  [r]
  (if (result/ok? r)
    (mcp-json (:ok r))
    (mcp-error (or (:message r) (:cause r) (str (:error r))))))

;;; =============================================================================
;;; Extension Delegation Helpers
;;; =============================================================================

;;; =============================================================================
;;; Shared Helpers (pure calculations)
;;; =============================================================================

(defn- hive-mcp-el-available?
  "Check if hive-mcp.el is loaded in Emacs."
  []
  (let [{:keys [success result]} (ec/eval-elisp "(featurep 'hive-mcp)")]
    (and success (= result "t"))))

(defn- resolve-agent
  "Resolve effective agent-id with fallback chain. Logs warnings for fallbacks."
  [{:keys [agent_id]}]
  (let [ctx-id (ctx/current-agent-id)
        env-id (System/getenv "CLAUDE_SWARM_SLAVE_ID")]
    (when (and (nil? agent_id) (nil? ctx-id))
      (log/warn "wrap-crystallize: agent_id not passed explicitly."
                (if env-id
                  (str "Falling back to env var " env-id)
                  "Defaulting to 'coordinator'. Lings should pass agent_id explicitly.")))
    (or agent_id ctx-id env-id "coordinator")))

;;; =============================================================================
;;; Side-Effect Helpers (isolated actions)
;;; =============================================================================

(defn- fetch-elisp-data
  "Fetch wrap data from elisp side. Returns data map or nil."
  [effective-dir]
  (when (hive-mcp-el-available?)
    (let [elisp-call (if effective-dir
                       (format "(json-encode (hive-mcp-api-wrap-gather \"%s\"))" effective-dir)
                       "(json-encode (hive-mcp-api-wrap-gather))")
          {:keys [success result]} (ec/eval-elisp elisp-call)]
      (when success
        (rescue nil (json/read-str result :key-fn keyword))))))

;;; =============================================================================
;;; Auto-KG Edge Creation
;;; =============================================================================

(defn- build-crystal-edges-default
  "Default: no KG edges. Addon overrides when available."
  [_params]
  {:derived-from nil :co-accessed nil :total-edges 0 :capped? false})

(defn- ck-a
  "Create KG edges for wrap crystallization.
   Delegates to addon extension if available, otherwise uses default."
  [summary-id harvested project-id agent-id & {:as opts}]
  (let [params (merge {:summary-id summary-id
                       :harvested harvested
                       :project-id project-id
                       :agent-id agent-id}
                      opts)]
    (if-let [f (ext/get-extension :ck/a)]
      (f params)
      (build-crystal-edges-default params))))

;;; =============================================================================
;;; Harvest / Crystallize Helpers
;;; =============================================================================

(defn- harvest
  "Harvest session data. Returns Result."
  ([effective-dir] (harvest effective-dir nil))
  ([effective-dir agent-id]
   (result/try-effect* :crystal/harvest-failed
                       (crystal-hooks/harvest-all {:directory effective-dir
                                                   :agent-id agent-id}))))

(defn- crystallize-session-result
  "Run crystallize-session, handling domain-level :error. Returns Result."
  [harvested project-id]
  (try
    (let [r (crystal-hooks/crystallize-session harvested)]
      (if (:error r)
        (result/err :crystal/crystallize-failed
                    {:message (:error r) :project-id project-id})
        (result/ok r)))
    (catch Exception e
      (result/err :crystal/crystallize-failed
                  {:message (.getMessage e)}))))

(defn- emit-wrap-notify!
  "Best-effort event dispatch for wrap notification."
  [agent-id cr-result project-id stats]
  (try
    (ev/dispatch [:crystal/wrap-notify
                  {:agent-id agent-id
                   :session-id (:session cr-result)
                   :project-id project-id
                   :created-ids (some-> (:summary-id cr-result) vector)
                   :stats stats}])
    (log/info "wrap-crystallize: emitted wrap_notify for" agent-id "project:" project-id
              "summary-id:" (:summary-id cr-result))
    (catch Exception e
      (log/warn "wrap-crystallize: failed to emit wrap_notify:" (.getMessage e)))))

;;; =============================================================================
;;; Pure Logic Functions (Result-returning)
;;; =============================================================================

(defn- gather*
  "Gather session data. Returns Result with combined crystal + elisp data."
  [{:keys [directory agent_id]}]
  (let [effective-dir (or directory (ctx/current-directory))
        effective-agent (or agent_id (ctx/current-agent-id))]
    (log/info "wrap-gather with crystal harvesting" (str "directory:" effective-dir))
    (result/let-ok [harvested (harvest effective-dir effective-agent)]
                   (let [elisp-result (fetch-elisp-data effective-dir)]
                     (result/ok {:crystal harvested
                                 :elisp elisp-result
                                 :session (:session harvested)
                                 :summary (merge (:summary harvested)
                                                 {:has-elisp-data (some? elisp-result)})})))))

(defn- crystallize*
  "Crystallize session data into long-term memory. Returns Result.
   Resets per-agent session-start timestamp after successful crystallization
   so subsequent sessions measure duration correctly."
  [{:keys [directory] :as params}]
  (let [effective-dir (or directory (ctx/current-directory))
        effective-agent (resolve-agent params)
        project-id (scope/get-current-project-id effective-dir)
        t-start (System/currentTimeMillis)]
    (log/info "wrap-crystallize" (str "agent-id:" effective-agent " directory:" effective-dir))
    (result/let-ok [harvested (harvest effective-dir effective-agent)
                    _t1 (do (log/info "wrap-crystallize: harvest" (- (System/currentTimeMillis) t-start) "ms")
                            (result/ok nil))
                    cr-result (crystallize-session-result harvested project-id)
                    _t2 (do (log/info "wrap-crystallize: crystallize" (- (System/currentTimeMillis) t-start) "ms")
                            (result/ok nil))]
                   (let [safe-stats (or (when (map? (:stats cr-result)) (:stats cr-result)) {})
                         summary-id (:summary-id cr-result)]
                     ;; Auto-KG edges — fire-and-forget (takes ~5s for 50 edges).
                     ;; Previously deref-blocked with 15s timeout. KG edge results
                     ;; are not needed for the wrap response.
                     (when summary-id
                       (future
                         (try
                           (let [kg-result (ck-a summary-id harvested project-id effective-agent)]
                             (log/info "wrap-crystallize: KG edges completed"
                                       "edges:" (:total-edges kg-result)
                                       "capped?" (:capped? kg-result)))
                           (catch Throwable t
                             (log/warn "wrap-crystallize: KG edges failed" (ex-message t))))))
                     (emit-wrap-notify! effective-agent cr-result project-id safe-stats)
                     ;; Reset session-start for this agent so next session measures fresh
                     (crystal/reset-session-start! effective-agent)
                     (log/info "wrap-crystallize: total" (- (System/currentTimeMillis) t-start) "ms")
                     (result/ok (assoc cr-result :project-id project-id))))))

(defn- permeate*
  "Process wrap queue entries. Returns Result."
  [{:keys [directory include_children]}]
  (let [effective-dir (or directory (ctx/current-directory))
        project-id (scope/get-current-project-id effective-dir)
        include-children? (not (false? include_children))]
    (log/info "permeate-crystals: processing wrap queue for project:" project-id
              "include-children:" include-children?)
    (let [queue-items (if include-children?
                        (ds/get-unprocessed-wraps-for-hierarchy project-id)
                        (ds/get-unprocessed-wraps-for-project project-id))
          agent-ids (mapv :wrap-queue/agent-id queue-items)
          project-ids (distinct (map :wrap-queue/project-id queue-items))]
      (doseq [item queue-items]
        (ds/mark-wrap-processed! (:wrap-queue/id item)))
      (result/ok {:processed (count queue-items)
                  :agent-ids agent-ids
                  :project-id project-id
                  :project-ids-matched (vec project-ids)
                  :include-children include-children?
                  :status "ok"}))))

;;; =============================================================================
;;; Public Handlers (thin boundary: try-result + result->mcp)
;;; =============================================================================

(defn handle-wrap-gather
  "Gather session data for wrap workflow without storing.

   Uses progressive crystallization to harvest:
   - Session progress notes (from kanban completions)
   - Completed tasks (ephemeral notes tagged session-progress)
   - Git commits since session start
   - Recall patterns (for smart promotion)

   Params:
   - directory: Working directory for git operations.

   Returns gathered data for confirmation before crystallization."
  [params]
  (result->mcp (try-result :crystal/gather-failed #(gather* params))))

(defn handle-wrap-crystallize
  "Crystallize session data into long-term memory.

   Takes harvested data (from wrap-gather) and:
   1. Creates session summary (short-term duration)
   2. Promotes entries that meet score threshold
   3. Flushes recall buffer
   4. Emits wrap_notify event for hivemind permeation

   Params:
   - agent_id (REQUIRED for lings): Ling's slave-id.
   - directory: Working directory for project scoping.

   Call after wrap-gather when ready to persist."
  [params]
  (result->mcp (try-result :crystal/crystallize-failed #(crystallize* params))))

(defn handle-permeate-crystals
  "Process wrap queue entries from ling sessions.

   Coordinator calls this to process wrap notifications from lings:
   1. Gets unprocessed wraps from DataScript queue (filtered by project)
   2. Marks each as processed
   3. Returns stats about what was permeated

   Params:
   - directory: Working directory for project scoping.
   - include_children: When true (default), includes wraps from child projects."
  [params]
  (result->mcp (try-result :crystal/permeate-failed #(permeate* params))))

;; Tool definitions
(def tools
  "REMOVED: Flat crystal tools no longer exposed. Use consolidated `session` tool with `wrap` command."
  [])
