(ns hive-mcp.tools.consolidated.workflow.drone
  "Drone wave dispatch and task classification for forge workflows.

   Handles the drone path of spark!: dispatching kanban tasks as drone waves
   via wave execution, and classifying tasks as drone-eligible or ling-eligible
   when budget routing is enabled.

   Extracted from workflow/spawn.clj to reduce cyclomatic complexity."
  (:require [hive-mcp.tools.consolidated.kanban :as c-kanban]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.agent.budget-router :as budget-router]
            [hive-mcp.agent.task-classifier :as task-classifier]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.tools.swarm.wave.execution :as wave-execution]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]
            [hive-mcp.workflows.support :as support]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ── Task Conversion ─────────────────────────────────────────────────────────

(defn kanban-task->wave-tasks
  "Convert a kanban task to wave task spec(s).
   Returns empty vec if task lacks :file or :files reference."
  [{:keys [title description file files]}]
  (let [target-files (or (when file [file])
                         (seq files)
                         [])
        task-text    (str (or title "untitled")
                          (when description (str "\n\n" description)))]
    (mapv (fn [f] {:file f :task task-text}) target-files)))

;; ── Drone Wave Dispatch ─────────────────────────────────────────────────────

(defn dispatch-drone-tasks!
  "Dispatch kanban tasks as a drone wave. Tasks without :file are skipped.
   Returns spark-compatible result with wave metadata."
  [{:keys [directory tasks model preset seeds ctx_refs kg_node_ids max_slots]}]
  (let [effective-dir  (or directory (ctx/current-directory) (System/getProperty "user.dir"))
        dispatchable   (filterv #(seq (kanban-task->wave-tasks %)) tasks)
        skipped-tasks  (filterv #(empty? (kanban-task->wave-tasks %)) tasks)
        wave-tasks     (->> dispatchable
                            (mapcat kanban-task->wave-tasks)
                            (take (or max_slots 20))
                            vec)]
    (if (empty? wave-tasks)
      {:spawned [] :failed [] :count 0 :route :drone
       :skipped (count skipped-tasks)
       :reason  "No tasks with :file reference for drone dispatch"}
      (let [r (result/rescue
               {:spawned [] :failed [] :count 0 :route :drone
                :skipped (count skipped-tasks)}
               (let [plan-id                  (ds/create-plan! wave-tasks (or preset "drone-worker"))
                     {:keys [wave-id item-count]} (wave-execution/execute-wave-async!
                                                   plan-id
                                                   (cond-> {:cwd effective-dir :trace true}
                                                     model             (assoc :model model)
                                                     (seq seeds)       (assoc :seeds (vec seeds))
                                                     (seq ctx_refs)    (assoc :ctx-refs ctx_refs)
                                                     (seq kg_node_ids) (assoc :kg-node-ids (vec kg_node_ids))))]
                 (support/mark-tasks-inprogress! c-kanban/handle-kanban effective-dir dispatchable)
                 (log/info "SPARK[drone]: wave dispatched"
                           {:wave-id wave-id :plan-id plan-id
                            :count   item-count :skipped (count skipped-tasks)
                            :model   (or model "default")})
                 {:spawned (mapv (fn [t] {:task-title (:title t) :task-id (:id t)
                                           :spawned true :route :drone})
                                  dispatchable)
                  :failed  []
                  :count   item-count
                  :route   :drone
                  :wave-id wave-id
                  :plan-id plan-id
                  :skipped (count skipped-tasks)
                  :model   (or model "default")}))]
        (cond-> r
          (and (empty? (:spawned r)) (::result/error (meta r)))
          (assoc :failed [{:error (get-in (meta r) [::result/error :message] "unknown")}]))))))

;; ── Task Classification ─────────────────────────────────────────────────────

(defn classify-and-split-tasks
  "When budget routing is enabled, classify each task via task-classifier
   and split into drone-eligible and ling-eligible batches.
   When disabled, all tasks route to :ling (safe default)."
  [tasks]
  (if-not (budget-router/enabled?)
    {:drone-tasks [] :ling-tasks (vec tasks)}
    (let [classified (mapv (fn [task]
                              (let [route (:route (task-classifier/classify-task
                                                   {:title       (or (:title task) "")
                                                    :description (:description task)
                                                    :files       (or (:files task)
                                                                     (when-let [f (:file task)] [f]))}))]
                                (assoc task ::route (or route :ling))))
                            tasks)]
      (log/debug "SPARK: task classification"
                 {:total (count tasks)
                  :drone (count (filter #(= :drone (::route %)) classified))
                  :ling  (count (filter #(not= :drone (::route %)) classified))})
      {:drone-tasks (filterv #(= :drone (::route %)) classified)
       :ling-tasks  (filterv #(not= :drone (::route %)) classified)})))
