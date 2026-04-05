(ns hive-mcp.tools.consolidated.workflow.forge-ops
  "Forge operational primitives: smite (reap terminal agents), survey
   (query + prioritize kanban tasks), and forge status dashboard.

   Extracted from workflow.clj to reduce cyclomatic complexity.
   Pure operational logic — no MCP handler concerns.

   Forge cycle logic (build-fsm-resources, forge-strike*) lives in
   workflow.forge-cycle to keep CC per-file under 50."
  (:require [hive-mcp.tools.consolidated.kanban :as c-kanban]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.config.core :as config]
            [hive-mcp.agent.budget-router :as budget-router]
            [hive-mcp.scheduler.vulcan :as vulcan]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.dns.result :as result]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ── Smite (reap terminal agents) ────────────────────────────────────────────

(def ^:private terminal-statuses
  "Agent statuses considered terminal for smiting."
  #{:completed :done :error :zombie})

(defn- forja-agent?
  "True if agent was spawned by the forja belt."
  [agent]
  (some-> (:slave/id agent) (str/starts-with? "swarm-forja-")))

(defn- smitable?
  "True if an agent is eligible for smiting (terminal or idle-forja)."
  [agent]
  (let [status (:slave/status agent)]
    (or (terminal-statuses status)
        (and (= :idle status) (forja-agent? agent)))))

(defn- kill-agent!
  "Attempt to kill a single agent. Returns map with :id, :killed, :error."
  [agent]
  (let [id (:slave/id agent)
        r  (result/rescue
            {:id id :killed false}
            (let [ling-agent (ling/->ling id {:cwd        (:slave/cwd agent)
                                              :presets    (or (:slave/presets agent) [])
                                              :project-id (:slave/project-id agent)
                                              :spawn-mode (or (:ling/spawn-mode agent) :claude)})
                  _result    (proto/kill! ling-agent)]
              (log/info "SMITE: killed" {:id id :status (:slave/status agent)})
              {:id id :status (name (:slave/status agent)) :killed true}))]
    (cond-> r
      (and (not (:killed r)) (not (:error r)) (::result/error (meta r)))
      (assoc :error (get-in (meta r) [::result/error :message] "unknown")))))

(defn smite!
  "Kill completed, idle-done, or error lings in the current project."
  [{:keys [directory]}]
  (let [all-agents (queries/get-all-slaves)
        project-id (when directory (scope/get-current-project-id directory))
        candidates (->> all-agents
                        (filter #(= 1 (:slave/depth %)))
                        (filter smitable?)
                        (filter #(or (nil? project-id)
                                     (nil? (:slave/project-id %))
                                     (= project-id (:slave/project-id %)))))]
    (if (empty? candidates)
      {:smited [] :failed [] :count 0}
      (let [results (mapv kill-agent! candidates)]
        {:smited (filterv :killed results)
         :failed (filterv (complement :killed) results)
         :count  (count (filter :killed results))}))))

;; ── Survey (query + prioritize kanban) ──────────────────────────────────────

(defn parse-kanban-tasks
  "Parse kanban list handler response into a vector of task maps."
  [result]
  (let [text   (if (map? result) (:text result) result)
        parsed (when (string? text)
                 (result/rescue nil (json/read-str text :key-fn keyword)))]
    (cond
      (sequential? parsed) parsed
      (map? parsed)        (or (:tasks parsed) [])
      :else                [])))

(defn sort-by-priority-then-created
  "Sort tasks by priority DESC then creation-date ASC."
  [tasks]
  (let [priority-order {"high" 0 "priority-high" 0
                        "medium" 1 "priority-medium" 1
                        "low" 2 "priority-low" 2}]
    (vec (sort (fn [a b]
                 (let [pa (get priority-order (or (:priority a) "medium") 1)
                       pb (get priority-order (or (:priority b) "medium") 1)]
                   (if (= pa pb)
                     (compare (str (:id a)) (str (:id b)))
                     (compare pa pb))))
               tasks))))

(defn- apply-milestone-boundary-filter
  "Post-filter: exclude tasks from milestones whose prerequisites are incomplete.
   Config-gated via [:forge :milestone-boundary]. Graceful degradation."
  [prioritized]
  (if (config/get-service-value :forge :milestone-boundary :default false)
    (result/rescue prioritized
                   (when-let [filter-fn (ext/get-extension :fb/milestone-boundary-filter)]
                     (let [{:keys [tasks excluded-count reason]} (filter-fn (:tasks prioritized))]
                       (when (pos? (or excluded-count 0))
                         (log/info "SURVEY: milestone-boundary excluded" excluded-count "tasks:" reason))
                       (-> prioritized
                           (assoc :tasks tasks)
                           (assoc :count (count tasks))
                           (update :blocked-count + (or excluded-count 0))))))
    prioritized))

(defn survey
  "Query kanban for todo tasks, ranked by KG dependencies (vulcan always-on).
   task_ids acts as an ID whitelist, task_filter as a title-prefix filter.
   Extra keys in opts are passed through to vulcan/prioritize-tasks (OCP).
   When [:forge :milestone-boundary] is true, excludes tasks from blocked milestones."
  [{:keys [directory task_ids task_filter] :as opts}]
  (let [r (result/rescue
           {:tasks [] :count 0}
           (let [result      (c-kanban/handle-kanban {:command "list" :status "todo" :directory directory})
                 all-tasks   (parse-kanban-tasks result)
                 tasks       (cond->> all-tasks
                               (seq task_ids)
                               (filterv #(contains? (set task_ids) (:id %)))
                               (some? task_filter)
                               (filterv #(str/starts-with? (or (:title %) "") task_filter)))
                 prioritized (vulcan/prioritize-tasks tasks #{} (dissoc opts :directory :task_ids :task_filter))
                 ;; Post-filter: milestone boundary enforcement (OCP)
                 filtered    (apply-milestone-boundary-filter prioritized)]
             (log/info "SURVEY: ready" (:count filtered)
                       "blocked" (:blocked-count filtered)
                       "of" (count all-tasks) "total"
                       (when (seq task_ids) (str "(id-whitelist: " (count task_ids) ")"))
                       (when task_filter (str "(filter: " (pr-str task_filter) ")")))
             filtered))]
    (cond-> r
      (::result/error (meta r))
      (assoc :error (get-in (meta r) [::result/error :message])))))

;; ── Forge Status ────────────────────────────────────────────────────────────

(defn forge-status*
  "Compute forge status dashboard data. Returns Result.
   Accepts forge-state atom for reading current state."
  [{:keys [directory]} forge-state]
  (let [state          @forge-state
        all-agents     (queries/get-all-slaves)
        lings          (->> all-agents (filter #(= 1 (:slave/depth %))))
        active-lings   (filter #(#{:active :running :idle :spawning} (:slave/status %)) lings)
        terminal-lings (filter #(terminal-statuses (:slave/status %)) lings)
        kanban-result  (result/rescue nil
                                      (c-kanban/handle-kanban {:command "status" :directory directory}))
        budget-routing? (boolean (config/get-service-value :forge :budget-routing :default false))
        budget-summary  (when budget-routing?
                          (result/rescue {:error "budget-summary-failed"}
                                         (budget-router/fleet-budget-summary)))]
    (result/ok (cond-> {:forge  (assoc state :modes {:fsm            true
                                                     :legacy         (boolean (config/get-service-value :forge :legacy :default false))
                                                     :budget-routing budget-routing?})
                        :lings  {:total    (count lings)
                                 :active   (count active-lings)
                                 :terminal (count terminal-lings)
                                 :ids      (mapv :slave/id active-lings)}
                        :kanban kanban-result}
                 budget-summary (assoc :budget budget-summary)))))
