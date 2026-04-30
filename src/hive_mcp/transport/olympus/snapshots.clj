(ns hive-mcp.transport.olympus.snapshots
  "Snapshot builders for the Olympus WebSocket transport.

   Queries DataScript for current state and returns JSON-serializable
   maps suitable for pushing to Olympus UI clients.

   Split from transport/olympus.clj (hotspot #14 refactor, plan
   refactor-hotspots-p0.md line 99-104)."
  (:require [datascript.core :as d-core]
            [hive-mcp.dns.result :as result]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-mcp.swarm.datascript.connection :as ds-conn]
            [hive-mcp.project.tree :as project-tree]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; JSON Serialization Helpers
;; =============================================================================

(defn serialize-date
  "Convert java.util.Date to ISO timestamp string for JSON serialization."
  [d]
  (when d
    (if (instance? java.util.Date d)
      (.format java.time.format.DateTimeFormatter/ISO_INSTANT
               (.toInstant ^java.util.Date d))
      (str d))))

(defn serialize-ref
  "Convert DataScript entity ref to ID string."
  [ref]
  (cond
    (nil? ref) nil
    (map? ref) (or (:db/id ref) (str ref))
    :else (str ref)))

;; =============================================================================
;; Snapshot Builders (Query DataScript for current state)
;; =============================================================================

(defn build-agents-snapshot
  "Build agents snapshot from DataScript.
   Returns vector of agent maps for Olympus UI.
   All values are JSON-serializable (dates as ISO strings, refs as IDs)."
  []
  (result/rescue []
                 (->> (ds-queries/get-all-slaves)
                      (map (fn [slave]
                             {:id (:slave/id slave)
                              :name (:slave/name slave)
                              :type (if (= 0 (:slave/depth slave)) :coordinator :ling)
                              :status (some-> (:slave/status slave) name)
                              :project-id (:slave/project-id slave)
                              :cwd (:slave/cwd slave)
                              :presets (vec (:slave/presets slave))
                              :parent (:slave/parent slave)
                              :current-task (:slave/current-task slave)
                              :tasks-completed (or (:slave/tasks-completed slave) 0)
                              :created-at (serialize-date (:slave/created-at slave))}))
                      vec)))

(defn build-waves-snapshot
  "Build waves snapshot from DataScript.
   Returns map of wave-id -> wave state.
   All values are JSON-serializable."
  []
  (result/rescue {}
    ;; Query all waves from DataScript
                 (let [c (ds-conn/ensure-conn)
                       db @c
                       wave-eids (d-core/q '[:find [?e ...]
                                             :where [?e :wave/id _]]
                                           db)]
                   (->> wave-eids
                        (map #(d-core/entity db %))
                        (map (fn [e]
                               (let [wave-id (:wave/id e)]
                                 [wave-id
                                  {:id wave-id
                                   :plan-id (serialize-ref (:wave/plan e))
                                   :total-tasks (:wave/total-tasks e)
                                   :concurrency (:wave/concurrency e)
                                   :active-count (or (:wave/active-count e) 0)
                                   :completed-count (or (:wave/completed-count e) 0)
                                   :failed-count (or (:wave/failed-count e) 0)
                                   :status (some-> (:wave/status e) name)
                                   :started-at (serialize-date (:wave/started-at e))
                                   :completed-at (serialize-date (:wave/completed-at e))}])))
                        (into {})))))

(defn build-kg-snapshot
  "Build knowledge graph snapshot.
   Returns {:entries [...] :edges [...]} for recent KG state.
   Note: This is a lightweight snapshot - full KG may be too large."
  []
  (result/rescue {:entries [] :edges [] :entry-count 0}
    ;; Query recent memory entries (last 50) via memory tools
                 (let [query-fn (requiring-resolve 'hive-mcp.tools.memory.crud/query-by-metadata)
                       recent-entries (when query-fn
                                        (take 50 (query-fn {:limit 50})))]
                   {:entries (or recent-entries [])
                    :edges []  ;; KG edges would come from kg/edges query - simplified for now
                    :entry-count (count recent-entries)})))

(defn build-project-tree-snapshot
  "Build project tree snapshot from project.tree DataScript.
   Returns hierarchical structure with ling counts per project.

   HCR Wave 5: Enables Olympus UI project tree navigator."
  []
  (result/rescue {:projects [] :roots [] :children {} :total 0}
                 (let [all-projects (project-tree/query-all-projects)
                       tree-data (project-tree/build-project-tree all-projects)
          ;; Get agents to count lings per project
                       agents (build-agents-snapshot)
                       ling-counts (reduce (fn [acc a]
                                             (if-let [pid (:project-id a)]
                                               (update acc pid (fnil inc 0))
                                               acc))
                                           {}
                                           agents)]
                   {:projects (mapv (fn [p]
                                      {:id (:project/id p)
                                       :path (:project/path p)
                                       :type (some-> (:project/type p) name)
                                       :parent-id (:project/parent-id p)
                                       :tags (vec (or (:project/tags p) []))
                                       :git-root (:project/git-root p)
                                       :ling-count (get ling-counts (:project/id p) 0)
                                       :last-scanned (serialize-date (:project/last-scanned p))})
                                    all-projects)
                    :roots (:roots tree-data)
                    :children (:children tree-data)
                    :total (count all-projects)})))

(defn build-full-snapshot
  "Build complete state snapshot for new client connection.

   Returns:
   {:type :init-snapshot
    :timestamp <ms>
    :data {:agents [...] :waves {...} :kg {...} :project-tree {...}}}"
  []
  {:type :init-snapshot
   :timestamp (System/currentTimeMillis)
   :data {:agents (build-agents-snapshot)
          :waves (build-waves-snapshot)
          :kg (build-kg-snapshot)
          :project-tree (build-project-tree-snapshot)}})
