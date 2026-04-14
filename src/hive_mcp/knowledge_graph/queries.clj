(ns hive-mcp.knowledge-graph.queries
  "Graph queries for Knowledge Graph traversal and analysis.

   Provides traversal algorithms and analytical queries over the KG:
   - traverse: Walk graph from starting node (BFS)
   - find-path: Find shortest path between nodes
   - impact-analysis: Find all dependents of a node
   - subgraph: Extract subgraph visible from a scope
   - find-contradictions: Locate conflicting knowledge
   - get-node-context: Full context for node display
   - batch-get-node-contexts: Batch version for N+1 elimination"

  (:require [clojure.set :as set]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.scope :as scope]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Configuration
;;; =============================================================================

(def ^:private default-max-depth
  "Default maximum traversal depth to prevent infinite loops."
  3)

(def ^:private dependency-relations
  "Relations that indicate dependency (for impact analysis).
   Includes :refines - when A refines B, and B becomes stale, A should also become stale."
  #{:depends-on :implements :derived-from :refines})

;;; =============================================================================
;;; Helper Functions
;;; =============================================================================

(defn- edge-matches-scope?
  "Check if edge is visible from given scope.
   If no scope provided, all edges match."
  [edge scope-set]
  (if (nil? scope-set)
    true
    (let [edge-scope (:kg-edge/scope edge)]
      (or (nil? edge-scope)  ;; Edges without scope are visible everywhere
          (contains? scope-set edge-scope)))))

(defn- edge-matches-relations?
  "Check if edge relation is in the allowed set.
   If no relations filter, all edges match."
  [edge relations]
  (if (nil? relations)
    true
    (contains? relations (:kg-edge/relation edge))))

(defn- get-adjacent-edges
  "Get edges adjacent to a node in the specified direction.

   Direction:
   - :outgoing - edges FROM this node
   - :incoming - edges TO this node
   - :both - all connected edges"
  [node-id direction]
  (case direction
    :outgoing (edges/get-edges-from node-id)
    :incoming (edges/get-edges-to node-id)
    :both (concat (edges/get-edges-from node-id)
                  (edges/get-edges-to node-id))))

(defn- edge->neighbor
  "Extract neighbor node ID from edge relative to current node.
   For outgoing edges: neighbor is :kg-edge/to
   For incoming edges: neighbor is :kg-edge/from"
  [edge current-node-id]
  (if (= (:kg-edge/from edge) current-node-id)
    (:kg-edge/to edge)
    (:kg-edge/from edge)))

;;; =============================================================================
;;; Traversal (BFS)
;;; =============================================================================

(defn traverse
  "Walk graph from a starting node using breadth-first search.

   Arguments:
     start-node-id - Node ID to start traversal from
     opts - Map with optional keys:
            :direction  - :outgoing (default), :incoming, or :both
            :relations  - Set of relation types to follow (default: all)
            :max-depth  - Maximum traversal depth (default: 3)
            :scope      - Limit to edges within visible scopes

   Returns:
     Sequence of {:node-id <id>
                  :edge <edge-map>
                  :depth <n>
                  :path [<node-ids from start>]}

   Example:
     (traverse \"memory-123\" {:direction :outgoing
                               :relations #{:implements :depends-on}
                               :max-depth 2
                               :scope \"hive-mcp:agora\"})"
  [start-node-id opts]
  {:pre [(string? start-node-id)]}
  (let [{:keys [direction relations max-depth scope]
         :or {direction :outgoing
              max-depth default-max-depth}} opts
        scope-set (when scope (scope/visible-scope-tags scope))]
    (log/debug "Traversing from:" start-node-id
               "direction:" direction
               "max-depth:" max-depth
               "relations:" relations
               "scope:" scope)
    (loop [queue [[start-node-id 0 [start-node-id]]]  ;; [node-id, depth, path]
           visited #{start-node-id}
           results []]
      (if (empty? queue)
        results
        (let [[current-node depth path] (first queue)
              remaining-queue (rest queue)]
          (if (>= depth max-depth)
            ;; At max depth, skip expanding neighbors
            (recur remaining-queue visited results)
            ;; Get adjacent edges and filter
            (let [adjacent-edges (get-adjacent-edges current-node direction)
                  matching-edges (->> adjacent-edges
                                      (filter #(edge-matches-scope? % scope-set))
                                      (filter #(edge-matches-relations? % relations)))
                  ;; Extract unvisited neighbors
                  new-neighbors (->> matching-edges
                                     (map #(vector (edge->neighbor % current-node) %))
                                     (filter #(not (contains? visited (first %)))))
                  ;; Build result entries for new neighbors
                  new-results (mapv (fn [[neighbor-id edge]]
                                      {:node-id neighbor-id
                                       :edge edge
                                       :depth (inc depth)
                                       :path (conj path neighbor-id)})
                                    new-neighbors)
                  ;; Build queue entries for new neighbors
                  new-queue-entries (mapv (fn [[neighbor-id _]]
                                            [neighbor-id
                                             (inc depth)
                                             (conj path neighbor-id)])
                                          new-neighbors)
                  new-visited (into visited (map first new-neighbors))]
              (recur (into (vec remaining-queue) new-queue-entries)
                     new-visited
                     (into results new-results)))))))))

;;; =============================================================================
;;; Shortest Path (BFS)
;;; =============================================================================

(defn find-path
  "Find shortest path between two nodes.

   Arguments:
     from-node - Source node ID
     to-node   - Target node ID
     opts      - Optional map with:
                 :direction  - :outgoing (default), :incoming, or :both
                 :relations  - Set of relations to follow
                 :max-depth  - Maximum search depth (default: 10)
                 :scope      - Limit to visible scopes

   Returns:
     {:path [<node-ids>] :edges [<edge-maps>] :length n}
     or nil if no path exists"
  [from-node to-node opts]
  {:pre [(string? from-node) (string? to-node)]}
  (when (= from-node to-node)
    (log/debug "find-path: from=to, returning trivial path")
    {:path [from-node] :edges [] :length 0})
  (let [{:keys [direction relations max-depth scope]
         :or {direction :both
              max-depth 10}} opts
        scope-set (when scope (scope/visible-scope-tags scope))]
    (log/debug "Finding path from:" from-node "to:" to-node)
    (loop [queue [[from-node [from-node] []]]  ;; [node, path, edges]
           visited #{from-node}]
      (cond
        (empty? queue)
        (do (log/debug "No path found from" from-node "to" to-node)
            nil)

        (> (count (second (first queue))) (inc max-depth))
        (do (log/debug "Max depth reached, no path found")
            nil)

        :else
        (let [[current path path-edges] (first queue)
              remaining-queue (rest queue)]
          (if (= current to-node)
            ;; Found the target
            {:path path :edges path-edges :length (dec (count path))}
            ;; Explore neighbors
            (let [adjacent-edges (get-adjacent-edges current direction)
                  matching-edges (->> adjacent-edges
                                      (filter #(edge-matches-scope? % scope-set))
                                      (filter #(edge-matches-relations? % relations)))
                  new-neighbors (->> matching-edges
                                     (map #(vector (edge->neighbor % current) %))
                                     (filter #(not (contains? visited (first %)))))
                  new-queue-entries (mapv (fn [[neighbor edge]]
                                            [neighbor
                                             (conj path neighbor)
                                             (conj path-edges edge)])
                                          new-neighbors)
                  new-visited (into visited (map first new-neighbors))]
              (recur (into (vec remaining-queue) new-queue-entries)
                     new-visited))))))))

;;; =============================================================================
;;; Impact Analysis
;;; =============================================================================

(defn impact-analysis
  "Find all nodes that depend on given node.
   Useful before modifying/deleting an entry.

   Arguments:
     node-id - Node to analyze
     opts    - Optional map with:
               :max-depth - Limit transitive depth (default: 5)
               :scope     - Limit to visible scopes

   Returns:
     {:direct [<node-ids>]
      :transitive [<node-ids excluding direct>]
      :total-count n
      :by-relation {<relation> [<node-ids>]}}"
  [node-id & [opts]]
  {:pre [(string? node-id)]}
  (let [{:keys [max-depth scope]
         :or {max-depth 5}} opts
        ;; Direct dependents: nodes with outgoing dependency edges TO this node
        ;; (incoming edges from perspective of node-id)
        direct-edges (edges/get-edges-to node-id)
        direct-dependent-edges (filter #(contains? dependency-relations
                                                   (:kg-edge/relation %))
                                       direct-edges)
        direct-nodes (set (map :kg-edge/from direct-dependent-edges))

        ;; Transitive dependents via traversal
        ;; Follow incoming dependency relations (who depends on nodes that depend on me)
        traversal-results (traverse node-id
                                    {:direction :incoming
                                     :relations dependency-relations
                                     :max-depth max-depth
                                     :scope scope})
        all-transitive (set (map :node-id traversal-results))
        transitive-only (set/difference all-transitive direct-nodes)

        ;; Group by relation type
        by-relation (reduce (fn [acc {:keys [node-id edge]}]
                              (update acc (:kg-edge/relation edge)
                                      (fnil conj []) node-id))
                            {}
                            traversal-results)]
    (log/info "Impact analysis for" node-id
              ": direct=" (count direct-nodes)
              "transitive=" (count transitive-only))
    {:direct (vec direct-nodes)
     :transitive (vec transitive-only)
     :total-count (+ (count direct-nodes) (count transitive-only))
     :by-relation by-relation}))

;;; =============================================================================
;;; Subgraph Extraction
;;; =============================================================================

(defn subgraph
  "Extract subgraph visible from a scope.

   Arguments:
     scope - Scope string (e.g., 'hive-mcp:agora')

   Returns:
     {:nodes [<unique node IDs>]
      :edges [<edge maps>]
      :node-count n
      :edge-count n}"
  [scope]
  {:pre [(or (nil? scope) (string? scope))]}
  (let [scope-set (scope/visible-scope-tags scope)
        all-edges (edges/get-all-edges)
        visible-edges (filter #(edge-matches-scope? % scope-set) all-edges)
        ;; Extract unique nodes from edges
        nodes-from-edges (set (concat (map :kg-edge/from visible-edges)
                                      (map :kg-edge/to visible-edges)))]
    (log/debug "Subgraph for scope:" scope
               "- edges:" (count visible-edges)
               "- nodes:" (count nodes-from-edges))
    {:nodes (vec nodes-from-edges)
     :edges visible-edges
     :node-count (count nodes-from-edges)
     :edge-count (count visible-edges)}))

;;; =============================================================================
;;; Contradiction Detection
;;; =============================================================================

(defn find-contradictions
  "Find edges with :contradicts relation in scope.

   Arguments:
     scope - Optional scope to limit search

   Returns:
     Sequence of {:node-a <id> :node-b <id> :edge <edge-map>}"
  [& [scope]]
  (let [scope-set (when scope (scope/visible-scope-tags scope))
        contradiction-edges (edges/get-edges-by-relation :contradicts)
        visible-contradictions (filter #(edge-matches-scope? % scope-set)
                                       contradiction-edges)]
    (log/debug "Found" (count visible-contradictions) "contradictions"
               (when scope (str "in scope " scope)))
    (mapv (fn [edge]
            {:node-a (:kg-edge/from edge)
             :node-b (:kg-edge/to edge)
             :edge edge
             :confidence (:kg-edge/confidence edge)
             :scope (:kg-edge/scope edge)})
          visible-contradictions)))

;;; =============================================================================
;;; Node Context
;;; =============================================================================

(defn get-node-context
  "Get full context for a node: incoming, outgoing, confidence stats.
   Useful for displaying node details.

   Arguments:
     node-id - Node to get context for

   Returns:
     {:node-id <id>
      :incoming {:count n :edges [...] :by-relation {...}}
      :outgoing {:count n :edges [...] :by-relation {...}}
      :confidence {:avg n :min n :max n}
      :scopes [<unique scopes>]}"
  [node-id]
  {:pre [(string? node-id)]}
  (let [incoming (edges/get-edges-to node-id)
        outgoing (edges/get-edges-from node-id)
        all-edges (into incoming outgoing)

        ;; Group by relation
        incoming-by-rel (group-by :kg-edge/relation incoming)
        outgoing-by-rel (group-by :kg-edge/relation outgoing)

        ;; Confidence stats
        confidences (keep :kg-edge/confidence all-edges)
        confidence-stats (when (seq confidences)
                           {:avg (/ (reduce + confidences) (count confidences))
                            :min (apply min confidences)
                            :max (apply max confidences)})

        ;; Unique scopes
        scopes (set (keep :kg-edge/scope all-edges))]
    (log/debug "Node context for" node-id
               ": incoming=" (count incoming)
               "outgoing=" (count outgoing))
    {:node-id node-id
     :incoming {:count (count incoming)
                :edges incoming
                :by-relation (into {} (map (fn [[k v]] [k (count v)])
                                           incoming-by-rel))}
     :outgoing {:count (count outgoing)
                :edges outgoing
                :by-relation (into {} (map (fn [[k v]] [k (count v)])
                                           outgoing-by-rel))}
     :confidence confidence-stats
     :scopes (vec scopes)}))

;;; =============================================================================
;;; Batch Node Context (N+1 elimination)
;;; =============================================================================

(defn batch-get-node-contexts
  "Get full context for multiple nodes in 2 queries instead of 2*N.
   Uses batch edge queries with DataScript/Datalevin collection bindings.

   Arguments:
     node-ids - Collection of node IDs to get context for

   Returns:
     Map of {node-id -> context-map} where context-map has the same shape
     as get-node-context output:
       {:node-id <id>
        :incoming {:count n :edges [...] :by-relation {...}}
        :outgoing {:count n :edges [...] :by-relation {...}}
        :confidence {:avg n :min n :max n}
        :scopes [<unique scopes>]}"
  [node-ids]
  (if (empty? node-ids)
    {}
    (let [ids (vec (distinct node-ids))
          ;; 2 queries total instead of 2*N
          edges-from-map (edges/batch-get-edges-from ids)
          edges-to-map   (edges/batch-get-edges-to ids)]
      (into {}
            (map (fn [node-id]
                   (let [incoming (get edges-to-map node-id [])
                         outgoing (get edges-from-map node-id [])
                         all-edges (into incoming outgoing)

                         incoming-by-rel (group-by :kg-edge/relation incoming)
                         outgoing-by-rel (group-by :kg-edge/relation outgoing)

                         confidences (keep :kg-edge/confidence all-edges)
                         confidence-stats (when (seq confidences)
                                           {:avg (/ (reduce + confidences)
                                                    (count confidences))
                                            :min (apply min confidences)
                                            :max (apply max confidences)})

                         scopes (set (keep :kg-edge/scope all-edges))]
                     [node-id
                      {:node-id node-id
                       :incoming {:count (count incoming)
                                  :edges incoming
                                  :by-relation (into {} (map (fn [[k v]] [k (count v)])
                                                             incoming-by-rel))}
                       :outgoing {:count (count outgoing)
                                  :edges outgoing
                                  :by-relation (into {} (map (fn [[k v]] [k (count v)])
                                                             outgoing-by-rel))}
                       :confidence confidence-stats
                       :scopes (vec scopes)}]))
                 ids)))))

;;; =============================================================================
;;; Connected Components (Utility)
;;; =============================================================================

(defn find-connected-nodes
  "Find all nodes connected to a given node (reachable in either direction).

   Arguments:
     node-id - Starting node
     opts    - Optional map with :max-depth

   Returns:
     Set of connected node IDs (including start node)"
  [node-id & [opts]]
  {:pre [(string? node-id)]}
  (let [max-depth (get opts :max-depth 10)
        results (traverse node-id {:direction :both
                                   :max-depth max-depth})]
    (into #{node-id} (map :node-id results))))

;;; =============================================================================
;;; Orphan Detection
;;; =============================================================================

(defn find-orphan-nodes
  "Find nodes in the graph that have no connections.

   Note: This requires knowing all node IDs, which are stored in Chroma.
   This function only finds nodes that appear in edges but have no other connections.
   For full orphan detection, integrate with Chroma memory query.

   Returns:
     Set of node IDs that appear only once in the graph (potential orphans)"
  []
  (let [all-edges (edges/get-all-edges)
        node-occurrences (frequencies
                          (concat (map :kg-edge/from all-edges)
                                  (map :kg-edge/to all-edges)))]
    ;; Nodes with only one occurrence might be leaf nodes
    ;; True orphan detection requires memory integration
    (set (keys (filter #(= 1 (val %)) node-occurrences)))))
