(ns hive-mcp.scheduler.vulcan
  "KG-aware task prioritization for the Forge Belt."
  (:require [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; KG Dependency Queries (thin I/O wrappers — testable via with-redefs)
;; =============================================================================

(defn get-task-deps
  "Query KG for the task IDs that a given task depends on."
  [task-id]
  (rescue #{}
          (let [edges (kg-edges/get-edges-from task-id)
                dep-edges (filter #(= :depends-on (:kg-edge/relation %)) edges)]
            (set (map :kg-edge/to dep-edges)))))

(defn task-exists?
  "Check if a task still exists in the memory store."
  [task-id]
  (rescue false
          (some? (facade/get-entry-by-id task-id))))

;; =============================================================================
;; Pure Readiness Calculations
;; =============================================================================

(defn dep-satisfied?
  "Check if a single dependency is satisfied."
  [dep-id completed-ids exists-fn]
  (or (contains? completed-ids dep-id)
      (not (exists-fn dep-id))))

(defn task-ready?
  "Check if a task has all its dependencies satisfied."
  [task-id completed-ids deps-fn exists-fn]
  (let [deps (deps-fn task-id)]
    (if (empty? deps)
      true
      (every? #(dep-satisfied? % completed-ids exists-fn) deps))))

(defn filter-ready-tasks
  "Filter todo tasks to only those with all deps satisfied."
  [tasks completed-ids deps-fn exists-fn]
  (filterv (fn [task]
             (task-ready? (:id task) completed-ids deps-fn exists-fn))
           tasks))

;; =============================================================================
;; Wave Number Computation
;; =============================================================================

(defn compute-wave-number
  "Compute the wave number (dependency depth) for a task."
  ([task-id deps-fn]
   (compute-wave-number task-id deps-fn (atom {}) #{}))
  ([task-id deps-fn memo visiting]
   (if-let [cached (get @memo task-id)]
     cached
     (if (contains? visiting task-id)
       ;; Cycle detected — treat as wave 0 to avoid infinite recursion
       (do (log/warn "Cycle detected in task deps at" task-id)
           0)
       (let [deps (deps-fn task-id)
             wave (if (empty? deps)
                    0
                    (inc (apply max
                                (map #(compute-wave-number % deps-fn memo
                                                           (conj visiting task-id))
                                     deps))))]
         (swap! memo assoc task-id wave)
         wave)))))

(defn enrich-with-wave-numbers
  "Add :wave-number to each task map based on KG dependency depth."
  [tasks deps-fn]
  (let [memo (atom {})]
    (mapv (fn [task]
            (assoc task :wave-number
                   (compute-wave-number (:id task) deps-fn memo #{})))
          tasks)))

;; =============================================================================
;; Vulcan Sort (priority > wave-number > creation-date)
;; =============================================================================

(def ^:private priority-order
  "Priority ranking for sort: lower number = higher priority."
  {"high" 0 "priority-high" 0
   "medium" 1 "priority-medium" 1
   "low" 2 "priority-low" 2})

(defn sort-vulcan
  "Sort tasks by priority tier, wave number, then creation date."
  [tasks]
  (vec
   (sort (fn [a b]
           (let [pa (get priority-order (or (:priority a) "medium") 1)
                 pb (get priority-order (or (:priority b) "medium") 1)]
             (if (not= pa pb)
               (compare pa pb)
               (let [wa (or (:wave-number a) 0)
                     wb (or (:wave-number b) 0)]
                 (if (not= wa wb)
                   (compare wa wb)
                   (compare (str (:id a)) (str (:id b))))))))
         tasks)))

;; =============================================================================
;; High-Level API (composes the above)
;; =============================================================================

(defn prioritize-tasks
  "Apply full vulcan prioritization pipeline to a list of todo tasks.
   Opts map is open for extension (OCP). Known keys:
   - :task_ids — set/seq of task IDs to whitelist (focus filter)
   - :deps-fn  — override KG dependency lookup
   - :exists-fn — override Chroma existence check"
  ([tasks] (prioritize-tasks tasks #{} {}))
  ([tasks completed-ids] (prioritize-tasks tasks completed-ids {}))
  ([tasks completed-ids {:keys [deps-fn exists-fn task_ids]
                         :or {deps-fn get-task-deps
                              exists-fn task-exists?}}]
   (let [scoped (if (seq task_ids)
                  (filterv #(contains? (set task_ids) (:id %)) tasks)
                  tasks)
         ready (filter-ready-tasks scoped completed-ids deps-fn exists-fn)
         enriched (enrich-with-wave-numbers ready deps-fn)
         sorted (sort-vulcan enriched)
         blocked-count (- (count scoped) (count ready))]
     {:tasks sorted
      :count (count sorted)
      :blocked-count blocked-count
      :scoped-count (count scoped)})))
