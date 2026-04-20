(ns hive-mcp.swarm.datascript.queries
  "Read-only query operations for swarm state.

   Query functions for:
   - Slave queries (get, list, filter by status)
   - Task queries (get, list for slave, completed tasks)
   - Claim queries (get, list, conflict detection)
   - Statistics and debugging"

  (:require [datascript.core :as d]
            [clojure.set]
            [clojure.string :as str]
            [hive-mcp.swarm.datascript.connection :as conn]))

;; Forward declaration for functions used before definition
(declare get-all-slaves)
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Slave Query Functions
;;; =============================================================================

(defn get-slave
  "Get a slave by ID.

   Returns:
     Map with slave attributes or nil if not found"
  [slave-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:slave/id slave-id])]
      (-> (into {} e)
          (dissoc :db/id)
          ;; Resolve refs to IDs
          (update :slave/parent #(when % (:slave/id %)))
          (update :slave/current-task #(when % (:task/id %)))))))

(defn get-slave-by-name
  "Get a slave by name (fallback lookup).

   Used when exact :slave/id match fails. Searches for a slave where
   :slave/name matches the given identifier. Returns the most recently
   created slave if multiple matches exist.

   Arguments:
     name - The slave name to search for (e.g., 'github-connector')

   Returns:
     Map with slave attributes or nil if not found"
  [name]
  (let [c (conn/ensure-conn)
        db @c
        ;; Query all slaves with matching name
        eids (d/q '[:find [?e ...]
                    :in $ ?name
                    :where
                    [?e :slave/id _]
                    [?e :slave/name ?name]]
                  db name)]
    (when (seq eids)
      ;; If multiple matches, return the most recent (by :slave/created-at)
      (->> eids
           (map #(d/entity db %))
           (map (fn [e]
                  (-> (into {} e)
                      (dissoc :db/id)
                      (update :slave/parent #(when % (:slave/id %)))
                      (update :slave/current-task #(when % (:task/id %))))))
           (sort-by :slave/created-at #(compare %2 %1))
           first))))

(defn- extract-keywords
  "Extract meaningful keywords from an identifier for fuzzy matching.

   Strips prefixes (ling-, swarm-) and filters out timestamp-like parts."
  [identifier]
  (let [;; Strip common prefixes
        stripped (cond
                   (str/starts-with? identifier "ling-") (subs identifier 5)
                   (str/starts-with? identifier "swarm-") (subs identifier 6)
                   :else identifier)
        ;; Split into parts
        parts (str/split stripped #"-")
        ;; Filter out timestamp-like parts (pure numbers)
        meaningful (remove #(re-matches #"\d+" %) parts)]
    (set meaningful)))

(defn- keyword-overlap-score
  "Calculate keyword overlap score between two identifiers.
   Returns [overlap-count total-query-keywords] for ranking."
  [query-id candidate-id]
  (let [query-kw (extract-keywords query-id)
        candidate-kw (extract-keywords candidate-id)
        overlap (clojure.set/intersection query-kw candidate-kw)]
    [(count overlap) (count query-kw)]))

(defn- find-best-keyword-match
  "Find the slave with highest keyword overlap with the given identifier.

   Returns the best matching slave if overlap >= 50% of query keywords,
   nil otherwise."
  [identifier]
  (let [all-slaves (get-all-slaves)
        query-kw (extract-keywords identifier)
        query-count (count query-kw)]
    (when (pos? query-count)
      (->> all-slaves
           (map (fn [slave]
                  (let [[overlap-count _] (keyword-overlap-score identifier (:slave/id slave))]
                    {:slave slave
                     :overlap overlap-count
                     :ratio (/ overlap-count query-count)})))
           ;; Require at least 50% keyword match
           (filter #(>= (:ratio %) 0.5))
           ;; Sort by overlap count descending
           (sort-by :overlap #(compare %2 %1))
           first
           :slave))))

(defn get-slave-by-name-or-id
  "Get a slave by ID, with fallback to name lookup and fuzzy keyword matching.

   BUG FIX: Resolves agent-id mismatch between hivemind shouts (which may use
   short IDs like 'ling-wave-fix') and DataScript (which stores full
   spawn IDs like 'swarm-fix-wave-race-condition-1770230187').

   BUG FIX 2: Strips 'agent:' prefix from headless ling auto-shouts.
   The NATS bridge's auto-shout-agent-event! may pass 'agent:ling-xyz'
   but DataScript stores the bare 'ling-xyz'. Without stripping, the
   lookup fails silently and status sync is lost.

   Resolution order:
   0. Strip 'agent:' prefix if present (headless ling bridge IDs)
   1. Exact match on :slave/id
   2. Exact match on :slave/name (with prefix stripping)
   3. Fuzzy match: keyword overlap between shout ID and spawn IDs
      (e.g., 'ling-wave-fix' matches 'swarm-fix-wave-race-condition-...' via 'fix'+'wave')

   Arguments:
     identifier - Either a slave ID or name

   Returns:
     Map with slave attributes or nil if not found"
  [identifier]
  (let [;; Strip 'agent:' prefix used by NATS bridge auto-shouts
        clean-id (if (str/starts-with? identifier "agent:")
                   (subs identifier (count "agent:"))
                   identifier)]
    (or
     ;; 1. Try exact ID match first
     (get-slave clean-id)
     ;; Also try original identifier if prefix was stripped
     (when (not= clean-id identifier) (get-slave identifier))
     ;; 2. Try exact name match (with prefix stripping)
     (let [name (cond
                  (str/starts-with? clean-id "ling-")
                  (subs clean-id 5)

                  (str/starts-with? clean-id "swarm-")
                  ;; For swarm-NAME-TIMESTAMP, extract NAME
                  (let [parts (str/split clean-id #"-")]
                    (when (>= (count parts) 2)
                      (second parts)))

                  :else clean-id)]
       (get-slave-by-name name))
     ;; 3. Fuzzy match by keyword overlap
     (find-best-keyword-match clean-id))))

(defn get-all-slaves
  "Get all slaves in the swarm.

   Returns:
     Seq of maps with slave attributes"
  []
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :where [?e :slave/id _]]
                  db)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)
                    (update :slave/parent #(when % (:slave/id %)))
                    (update :slave/current-task #(when % (:task/id %)))))))))

(defn get-slaves-by-status
  "Get slaves filtered by status.

   Arguments:
     status - Status to filter by

   Returns:
     Seq of slave maps"
  [status]
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :in $ ?status
                    :where
                    [?e :slave/id _]
                    [?e :slave/status ?status]]
                  db status)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)
                    (update :slave/parent #(when % (:slave/id %)))
                    (update :slave/current-task #(when % (:task/id %)))))))))

(defn get-slaves-by-project
  "Get slaves filtered by project-id.

   Arguments:
     project-id - Project ID to filter by

   Returns:
     Seq of slave maps belonging to the project"
  [project-id]
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :in $ ?project-id
                    :where
                    [?e :slave/id _]
                    [?e :slave/project-id ?project-id]]
                  db project-id)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                (-> (into {} e)
                    (dissoc :db/id)
                    (update :slave/parent #(when % (:slave/id %)))
                    (update :slave/current-task #(when % (:task/id %)))))))))

(defn get-child-project-ids
  "Get unique project-ids of slaves whose parent belongs to the given project.

   Used by piggyback assembly to include cross-project descendant shouts.
   A coordinator in project A that spawned lings into project B will get B
   in the returned set, so piggyback can include those lings' messages.

   Arguments:
     parent-project-id - Project ID of the parent/coordinator

   Returns:
     Set of project-id strings (may be empty, never includes nil or parent-project-id)"
  [parent-project-id]
  (let [c (conn/ensure-conn)
        db @c
        child-pids (d/q '[:find [?cpid ...]
                          :in $ ?ppid
                          :where
                          ;; Find slaves in the parent project
                          [?pe :slave/project-id ?ppid]
                          ;; Find children of those parent slaves
                          [?ce :slave/parent ?pe]
                          ;; Get the child's project-id
                          [?ce :slave/project-id ?cpid]]
                        db parent-project-id)]
    (disj (set child-pids) nil parent-project-id)))

(defn get-slave-ids-by-project
  "Get slave IDs for a project (optimized for kill operations).

   Arguments:
     project-id - Project ID to filter by

   Returns:
     Seq of slave ID strings"
  [project-id]
  (let [c (conn/ensure-conn)
        db @c]
    (d/q '[:find [?sid ...]
           :in $ ?project-id
           :where
           [?e :slave/project-id ?project-id]
           [?e :slave/id ?sid]]
         db project-id)))

(defn get-slave-by-kanban-task
  "Get the slave that is assigned to a kanban task.

   Arguments:
     kanban-task-id - Kanban task ID to look up

   Returns:
     Map with slave attributes or nil if no slave is assigned to this task"
  [kanban-task-id]
  (let [c (conn/ensure-conn)
        db @c
        eid (d/q '[:find ?e .
                   :in $ ?task-id
                   :where
                   [?e :slave/kanban-task-id ?task-id]]
                 db kanban-task-id)]
    (when eid
      (let [e (d/entity db eid)]
        (-> (into {} e)
            (dissoc :db/id)
            (update :slave/parent #(when % (:slave/id %)))
            (update :slave/current-task #(when % (:task/id %))))))))

;;; =============================================================================
;;; Task Query Functions
;;; =============================================================================

(defn get-task
  "Get a task by ID.

   Returns:
     Map with task attributes or nil if not found"
  [task-id]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:task/id task-id])]
      (-> (into {} e)
          (dissoc :db/id)
          (update :task/slave #(when % (:slave/id %)))))))

(defn get-tasks-for-slave
  "Get all tasks for a slave.

   Arguments:
     slave-id - Slave to get tasks for
     status   - Optional status filter

   Returns:
     Seq of task maps"
  [slave-id & [status]]
  (let [c (conn/ensure-conn)
        db @c
        slave-eid (:db/id (d/entity db [:slave/id slave-id]))]
    (when slave-eid
      (let [query (if status
                    '[:find [?e ...]
                      :in $ ?slave-eid ?status
                      :where
                      [?e :task/slave ?slave-eid]
                      [?e :task/status ?status]]
                    '[:find [?e ...]
                      :in $ ?slave-eid
                      :where
                      [?e :task/slave ?slave-eid]])
            args (if status [db slave-eid status] [db slave-eid])
            eids (apply d/q query args)]
        (->> eids
             (map #(d/entity db %))
             (map (fn [e]
                    (-> (into {} e)
                        (dissoc :db/id)
                        (update :task/slave #(when % (:slave/id %)))))))))))

(defn get-completed-tasks
  "Get all completed tasks, optionally filtered by slave or time range.

   Options:
   - :slave-id - Filter by specific slave
   - :since - Only tasks completed after this timestamp (java.util.Date)
   - :limit - Maximum number to return (default 100)

   Returns:
     Seq of task maps sorted by completion time (most recent first)"
  [& {:keys [slave-id since limit] :or {limit 100}}]
  (let [c (conn/ensure-conn)
        db @c
        ;; Query all completed tasks with slave info
        completed-tasks (d/q '[:find [(pull ?e [* {:task/slave [:slave/id]}]) ...]
                               :in $ ?status
                               :where
                               [?e :task/status ?status]]
                             db :completed)]
    (->> completed-tasks
         ;; Filter by slave-id if provided
         (filter (fn [task]
                   (or (nil? slave-id)
                       (= slave-id (get-in task [:task/slave :slave/id])))))
         ;; Filter by since timestamp if provided
         (filter (fn [task]
                   (or (nil? since)
                       (and (:task/completed-at task)
                            (.after (:task/completed-at task) since)))))
         ;; Sort by completion time (most recent first)
         (sort-by :task/completed-at #(compare %2 %1))
         ;; Apply limit
         (take limit)
         ;; Clean up the output format
         (map (fn [task]
                (-> task
                    (dissoc :db/id)
                    (update :task/slave #(when % (:slave/id %)))))))))

;;; =============================================================================
;;; Claim Query Functions
;;; =============================================================================

(defn get-claims-for-file
  "Get claim info for a file.

   Returns:
     Map with :slave-id, :task-id, :prior-hash or nil if unclaimed.
     :prior-hash is the file content hash captured at claim acquire time (CC.3)."
  [file-path]
  (let [c (conn/ensure-conn)
        db @c]
    (when-let [e (d/entity db [:claim/file file-path])]
      {:file file-path
       :slave-id (get-in e [:claim/slave :slave/id])
       :task-id (get-in e [:claim/task :task/id])
       :created-at (:claim/created-at e)
       :prior-hash (:claim/prior-hash e)})))

(defn get-all-claims
  "Get all current file claims.

   Returns:
     Seq of {:file :slave-id :task-id :prior-hash} maps.
     :prior-hash is the file content hash captured at claim acquire time (CC.3)."
  []
  (let [c (conn/ensure-conn)
        db @c
        eids (d/q '[:find [?e ...]
                    :where [?e :claim/file _]]
                  db)]
    (->> eids
         (map #(d/entity db %))
         (map (fn [e]
                {:file (:claim/file e)
                 :slave-id (get-in e [:claim/slave :slave/id])
                 :task-id (get-in e [:claim/task :task/id])
                 :created-at (:claim/created-at e)
                 :prior-hash (:claim/prior-hash e)})))))

(defn has-conflict?
  "Check if a file claim would conflict with existing claims.

   Arguments:
     file-path       - File to check
     requesting-slave - Slave wanting to claim (conflicts if different)

   Returns:
     {:conflict? bool :held-by slave-id} or {:conflict? false}"
  [file-path requesting-slave]
  (let [c (conn/ensure-conn)
        db @c]
    (if-let [e (d/entity db [:claim/file file-path])]
      (let [holder-id (get-in e [:claim/slave :slave/id])]
        (if (= holder-id requesting-slave)
          {:conflict? false}
          {:conflict? true :held-by holder-id}))
      {:conflict? false})))

(defn check-file-conflicts
  "Check for conflicts on multiple files.

   Arguments:
     requesting-slave - Slave wanting to claim files
     files           - Collection of file paths to check

   Returns:
     Seq of {:file :held-by} for conflicting files (empty if no conflicts)"
  [requesting-slave files]
  (when (seq files)
    (->> files
         (map (fn [f]
                (let [result (has-conflict? f requesting-slave)]
                  (when (:conflict? result)
                    {:file f :held-by (:held-by result)}))))
         (filter some?))))

;;; =============================================================================
;;; Claim History Query Functions (CC.6)
;;; =============================================================================

(defn get-recent-claim-history
  "Get recent claim history entries.

   CC.6: Tracks file changes made during claim periods for auditing
   and context propagation.

   Options:
   - :file       - Filter by specific file path
   - :slave-id   - Filter by specific slave
   - :since      - Only entries after this timestamp (java.util.Date)
   - :limit      - Maximum number to return (default 50)

   Returns:
     Seq of claim history maps sorted by release time (most recent first)"
  [& {:keys [file slave-id since limit] :or {limit 50}}]
  (let [c (conn/ensure-conn)
        db @c
        ;; Query all claim history entries
        all-history (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :claim-history/id _]]
                         db)]
    (->> all-history
         ;; Filter by file if provided
         (filter (fn [entry]
                   (or (nil? file)
                       (= file (:claim-history/file entry)))))
         ;; Filter by slave-id if provided
         (filter (fn [entry]
                   (or (nil? slave-id)
                       (= slave-id (:claim-history/slave-id entry)))))
         ;; Filter by since timestamp if provided
         (filter (fn [entry]
                   (or (nil? since)
                       (and (:claim-history/released-at entry)
                            (.after (:claim-history/released-at entry) since)))))
         ;; Sort by release time (most recent first)
         (sort-by :claim-history/released-at #(compare %2 %1))
         ;; Apply limit
         (take limit)
         ;; Clean up the output format
         (map (fn [entry]
                (-> entry
                    (dissoc :db/id)
                    ;; Rename keys for cleaner output
                    (clojure.set/rename-keys
                     {:claim-history/id :id
                      :claim-history/file :file
                      :claim-history/slave-id :slave-id
                      :claim-history/prior-hash :prior-hash
                      :claim-history/released-hash :released-hash
                      :claim-history/lines-added :lines-added
                      :claim-history/lines-removed :lines-removed
                      :claim-history/released-at :released-at})))))))

;;; =============================================================================
;;; Statistics & Debugging
;;; =============================================================================

(defn db-stats
  "Get statistics about the current swarm state."
  []
  (let [c (conn/ensure-conn)
        db @c]
    {:slaves (count (d/q '[:find ?e :where [?e :slave/id _]] db))
     :tasks (count (d/q '[:find ?e :where [?e :task/id _]] db))
     :claims (count (d/q '[:find ?e :where [?e :claim/file _]] db))
     :claim-history (count (d/q '[:find ?e :where [?e :claim-history/id _]] db))
     :active-tasks (count (d/q '[:find ?e
                                 :where
                                 [?e :task/status :dispatched]]
                               db))
     :wrap-queue (count (d/q '[:find ?e :where [?e :wrap-queue/id _]] db))
     :unprocessed-wraps (count (d/q '[:find ?e
                                      :where
                                      [?e :wrap-queue/processed? false]]
                                    db))
     :completed-tasks (count (d/q '[:find ?e :where [?e :completed-task/id _]] db))}))

(defn dump-db
  "Dump the current database state for debugging."
  []
  {:slaves (get-all-slaves)
   :tasks (d/q '[:find [(pull ?e [*]) ...]
                 :where [?e :task/id _]]
               @(conn/ensure-conn))
   :claims (get-all-claims)
   :stats (db-stats)})
