(ns hive-mcp.swarm.datascript.coordination.wrap-queue
  (:require [datascript.core :as d]
            [hive-mcp.swarm.datascript.connection :as conn]))

(declare add-wrap-notification! get-unprocessed-wraps get-unprocessed-wraps-for-project get-unprocessed-wraps-for-hierarchy mark-wrap-processed!)

(defn add-wrap-notification!
  "Record a ling wrap for coordinator permeation.

   Arguments:
     wrap-id - Unique identifier for this wrap notification
     opts    - Map with keys:
               :agent-id    - ID of the ling that wrapped
               :session-id  - Session tag (e.g., session:2026-01-14:ling-123)
               :project-id  - Project ID for scoping (derived from ling's directory)
               :created-ids - Collection of memory entry IDs created
               :stats       - Map of stats {:notes N :decisions N :conventions N}

   Returns:
     Transaction report"
  [wrap-id {:keys [agent-id session-id project-id created-ids stats]}]
  {:pre [(string? wrap-id)]}
  (let [c (conn/ensure-conn)]
    (d/transact! c
                 [(cond-> {:wrap-queue/id wrap-id
                           :wrap-queue/processed? false
                           :wrap-queue/created-at (conn/now)}
                    agent-id (assoc :wrap-queue/agent-id agent-id)
                    session-id (assoc :wrap-queue/session-id session-id)
                    project-id (assoc :wrap-queue/project-id project-id)
                    (seq created-ids) (assoc :wrap-queue/created-ids (vec created-ids))
                    stats (assoc :wrap-queue/stats stats))])))

(defn get-unprocessed-wraps
  "Get all wrap notifications not yet processed by coordinator.

   Returns:
     Seq of wrap notification maps"
  []
  (let [c (conn/ensure-conn)]
    (d/q '[:find [(pull ?e [*]) ...]
           :where
           [?e :wrap-queue/processed? false]]
         @c)))

(defn get-unprocessed-wraps-for-project
  "Get unprocessed wrap notifications for a specific project.

   Arguments:
     project-id - Project ID to filter by (e.g., derived from directory)

   Returns:
     Seq of wrap notification maps matching the project

   Note: If project-id is nil or 'global', returns all unprocessed wraps."
  [project-id]
  (let [c (conn/ensure-conn)
        db @c]
    (if (or (nil? project-id) (= project-id "global"))
      ;; No filtering - return all
      (d/q '[:find [(pull ?e [*]) ...]
             :where
             [?e :wrap-queue/processed? false]]
           db)
      ;; Filter by project-id
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?pid
             :where
             [?e :wrap-queue/processed? false]
             [?e :wrap-queue/project-id ?pid]]
           db project-id))))

(defn get-unprocessed-wraps-for-hierarchy
  "Get unprocessed wrap notifications for project and all its children.

   Uses prefix matching on project-id to support hierarchical project IDs
   like 'parent:child:grandchild'. A query for 'parent' will match:
   - 'parent'
   - 'parent:child'
   - 'parent:child:grandchild'

   Arguments:
     project-id-prefix - Project ID prefix to match (e.g., 'myproject' matches
                         'myproject', 'myproject:submodule', etc.)

   Returns:
     Seq of wrap notification maps for matching projects

   Note: If project-id-prefix is nil or 'global', returns all unprocessed wraps."
  [project-id-prefix]
  (let [c (conn/ensure-conn)
        db @c]
    (if (or (nil? project-id-prefix) (= project-id-prefix "global"))
      ;; No filtering - return all
      (d/q '[:find [(pull ?e [*]) ...]
             :where
             [?e :wrap-queue/processed? false]]
           db)
      ;; Filter by project-id prefix using clojure.string/starts-with?
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?prefix
             :where
             [?e :wrap-queue/processed? false]
             [?e :wrap-queue/project-id ?pid]
             [(clojure.string/starts-with? ?pid ?prefix)]]
           db project-id-prefix))))

(defn mark-wrap-processed!
  "Mark a wrap notification as processed.

   Arguments:
     wrap-id - ID of the wrap notification to mark

   Returns:
     Transaction report or nil if wrap-id not found"
  [wrap-id]
  (let [c (conn/ensure-conn)
        eid (d/q '[:find ?e .
                   :in $ ?id
                   :where [?e :wrap-queue/id ?id]]
                 @c wrap-id)]
    (when eid
      (d/transact! c
                   [[:db/add eid :wrap-queue/processed? true]]))))
