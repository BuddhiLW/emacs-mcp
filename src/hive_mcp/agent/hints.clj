(ns hive-mcp.agent.hints
  "Structured memory hints for ling priming. Generates pointer-based context instead of full text injection."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-dsl.result :as r]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.queries :as kg-queries]
            [hive-mcp.agent.hints.domain :as domain]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- query-scoped-entries
  "Query Chroma entries filtered by project scope."
  [entry-type tags project-id limit]
  (when-let [_ (mem-proto/store-set?)]
    (let [store (mem-proto/get-store)
          limit-val (domain/or-val limit 20)
          in-project? (when-let [pid project-id]
                        (not= pid "global"))
          entries (mem-proto/query-entries store {:type entry-type
                                                  :limit (min (* limit-val 8) 500)})
          full-scope-tags (cond-> (kg-scope/full-hierarchy-scope-tags project-id)
                            in-project? (disj "scope:global"))
          all-visible-ids (cond-> (set (into (vec (kg-scope/visible-scopes project-id))
                                             (kg-scope/descendant-scopes project-id)))
                            in-project? (disj "global"))
          scoped (filterv #(domain/scope-visible? full-scope-tags all-visible-ids %) entries)
          filtered (if-let [tag-seq (seq tags)]
                     (filterv (fn [entry]
                                (let [entry-tags (set (:tags entry))]
                                  (every? #(contains? entry-tags %) tag-seq)))
                              scoped)
                     scoped)]
      (take limit-val filtered))))

(defn- query-axioms
  "Query axiom entries including legacy tagged conventions."
  [project-id]
  (let [seen (volatile! #{})
        distinct-by-id (fn [coll]
                         (filterv (fn [item]
                                    (let [k (:id item)]
                                      (if-let [_ (get @seen k)]
                                        false
                                        (do (vswap! seen conj k) true))))
                                  coll))
        formal (query-scoped-entries "axiom" nil project-id 100)
        legacy (query-scoped-entries "convention" ["axiom"] project-id 100)]
    (distinct-by-id (concat formal legacy))))

(defn- axiom-ids
  "Extract axiom IDs visible to the given project scope."
  [project-id]
  (r/rescue []
            (let [axioms (query-axioms project-id)]
              (mapv :id axioms))))

(defn- priority-convention-ids
  "Extract IDs of catchup-priority conventions."
  [project-id]
  (r/rescue []
            (let [conventions (query-scoped-entries
                               "convention" ["catchup-priority"] project-id 50)]
              (mapv :id conventions))))

(defn- decision-ids
  "Extract IDs of active decisions for this project."
  [project-id]
  (r/rescue []
            (let [decisions (query-scoped-entries "decision" nil project-id 20)]
              (mapv :id decisions))))

(defn- task-semantic-queries
  "Generate semantic search queries from a task description."
  [task-description]
  (when-let [desc (when-not (str/blank? task-description) task-description)]
    [(str "conventions for: " (subs desc 0 (min 100 (count desc))))]))

(defn- kg-seed-nodes
  "Find KG seed nodes relevant to this project."
  [project-id]
  (r/rescue []
            (let [stats (kg-edges/edge-stats)
                  total-edges (:total-edges stats)]
              (when (> total-edges 5)
                (let [decisions (query-scoped-entries "decision" nil project-id 5)]
                  (->> decisions
                       (mapv :id)
                       (take 3)
                       vec))))))

(defn generate-hints
  "Generate structured memory hints for a ling spawn."
  ([project-id] (generate-hints project-id {}))
  ([project-id {:keys [task extra-ids extra-queries extra-tags]}]
   (let [axioms (axiom-ids project-id)
         priorities (priority-convention-ids project-id)
         decisions (decision-ids project-id)
         task-queries (task-semantic-queries task)
         seeds (kg-seed-nodes project-id)
         read-ids (vec (distinct (concat priorities decisions (domain/or-val extra-ids []))))
         queries (vec (distinct (concat (domain/or-val task-queries []) (domain/or-val extra-queries []))))
         tags (vec (domain/or-val extra-tags []))]

     {:memory-hints
      (cond-> {:axiom-ids axioms}
        (seq read-ids) (assoc :read-ids read-ids)
        (seq queries) (assoc :queries queries)
        (seq tags) (assoc :tags tags)
        (seq seeds) (assoc :kg-seeds seeds :kg-depth 2))})))

(def ^:const max-l1-ids
  "Maximum memory IDs from KG traversal."
  20)

(def ^:const max-l2-queries
  "Maximum semantic queries from KG neighborhood."
  5)

(def ^:const max-l3-seeds
  "Maximum KG seed nodes for ling-side traversal."
  3)

(defn- extract-semantic-themes
  "Extract semantic query themes from KG traversal results."
  [traversal-results entry]
  (let [relation-themes (->> traversal-results
                             (map (comp name :kg-edge/relation :edge))
                             (remove nil?)
                             distinct)
        meaningful (domain/meaningful-tags (domain/or-val (:tags entry) []))
        tag-queries (when-let [tags (seq meaningful)]
                      [(str "conventions for: " (str/join ", " (take 3 tags)))])
        relation-queries (when-let [rels (seq relation-themes)]
                           [(str "entries related via: " (str/join ", " (take 3 rels)))])
        type-query (when-let [t (:type entry)]
                     [(str (name t) " entries in this project")])]
    (->> (concat tag-queries relation-queries type-query)
         (remove nil?)
         (take max-l2-queries)
         vec)))

(defn- fallback-tag-search
  "Fallback hint generation using entry tags when KG has no edges."
  [entry]
  (let [meaningful (domain/meaningful-tags (:tags entry))]
    {:l1-ids []
     :l2-queries (if-let [tags (seq meaningful)]
                   [(str "conventions for: " (str/join ", " (take 4 tags)))]
                   [])
     :l3-seeds []}))

(defn generate-task-hints
  "Generate KG-driven hints for a specific task or memory node."
  [{:keys [task-id memory-id depth] :or {depth 2}}]
  (when-let [node-id (if-let [tid task-id] tid memory-id)]
    (r/rescue {:l1-ids [] :l2-queries [] :l3-seeds []}
              (let [entry (when-let [_ (mem-proto/store-set?)]
                            (mem-proto/get-entry (mem-proto/get-store) node-id))
                    traversal (r/rescue []
                                        (kg-queries/traverse node-id
                                                             {:direction :both
                                                              :max-depth depth}))]
                (if-let [trav (seq traversal)]
                  (let [discovered-ids (->> trav
                                            (map :node-id)
                                            distinct
                                            (take max-l1-ids)
                                            vec)
                        l2-queries (extract-semantic-themes traversal (domain/or-val entry {}))
                        depth-1-nodes (->> trav
                                           (filter #(= 1 (:depth %)))
                                           (map :node-id)
                                           distinct)
                        l3-seeds (->> depth-1-nodes
                                      (take max-l3-seeds)
                                      (mapv (fn [id] {:id id :depth depth})))]
                    {:l1-ids discovered-ids
                     :l2-queries l2-queries
                     :l3-seeds l3-seeds})
                  (fallback-tag-search (domain/or-val entry {})))))))

(defn serialize-hints
  "Serialize memory hints to a compact markdown block for spawn injection."
  [{:keys [memory-hints]} & {:keys [project-name git-info]}]
  (let [{:keys [axiom-ids read-ids queries tags kg-seeds kg-depth]} memory-hints
        header (cond-> ["## Memory Hints (Auto-Injected)" ""]
                 project-name (conj (str "**Project**: " project-name)))
        body ["" "**Mode**: hints (structured pointers, not full text)"
              "**Action**: Process these hints during your Silence phase."
              ""]
        sections (concat
                  (domain/axiom-section axiom-ids)
                  (domain/read-id-section read-ids)
                  (domain/query-section queries)
                  (domain/tag-section tags)
                  (domain/kg-section kg-seeds kg-depth)
                  (domain/git-section git-info))]
    (str/join "\n" (concat header body sections))))

(defn parse-hints-from-context
  "Parse memory hints from an injected context string."
  [context-str]
  (when-let [ctx context-str]
    (when-let [hints-section (when-let [_ (str/includes? ctx "## Memory Hints")]
                               (second (str/split ctx #"## Memory Hints" 2)))]
      (let [ids (domain/parse-memory-ids hints-section)
            queries (domain/parse-search-queries hints-section)
            tags (domain/parse-tag-queries hints-section)
            kg-data (domain/parse-kg-traversals hints-section)
            axiom-ids (domain/parse-axiom-ids hints-section)
            axiom-set (set (domain/or-val axiom-ids []))
            read-ids (vec (remove axiom-set (domain/or-val ids [])))
            result (cond-> {}
                     (seq axiom-ids) (assoc :axiom-ids axiom-ids)
                     (seq read-ids)  (assoc :read-ids read-ids)
                     (seq queries)   (assoc :queries queries)
                     (seq tags)      (assoc :tags tags)
                     (seq (:seeds kg-data)) (assoc :kg-seeds (:seeds kg-data))
                     (:depth kg-data) (assoc :kg-depth (:depth kg-data)))]
        (when-let [_ (seq result)]
          result)))))

(defn execute-hint-ids
  "Fetch memory entries by ID."
  [ids]
  (when-let [id-list (seq ids)]
    (->> id-list
         (mapv (fn [id]
                 (r/rescue nil
                           (when-let [entry (mem-proto/get-entry (mem-proto/get-store) id)]
                             {:id id
                              :content (:content entry)
                              :type (name (domain/or-val (:type entry) "note"))
                              :tags (vec (domain/or-val (:tags entry) []))}))))
         (filterv some?))))

(defn execute-hint-queries
  "Run semantic searches for hint queries."
  [queries]
  (when-let [q-list (seq queries)]
    (->> q-list
         (mapv (fn [q]
                 (let [result (r/guard Exception {:query q :results [] :error "unknown"}
                                       (let [results (mem-proto/search-similar (mem-proto/get-store) q {:limit 5})]
                                         {:query q
                                          :results (mapv (fn [r]
                                                           (let [content (domain/or-val (:content r) "")]
                                                             {:id (:id r)
                                                              :preview (subs content 0
                                                                             (min (count content) 200))
                                                              :type (name (domain/or-val (:type r) "note"))}))
                                                         results)}))]
                   (if-let [err (::r/error (meta result))]
                     (do (log/debug "Failed to execute hint query:" q (:message err))
                         (assoc result :error (:message err)))
                     result))))
         vec)))

(defn execute-hint-kg-traversal
  "Traverse KG from seed nodes for hint resolution."
  [seeds depth]
  (when-let [seed-list (seq seeds)]
    (->> seed-list
         (mapv (fn [seed]
                 (let [result (r/guard Exception {:seed seed :related [] :error "unknown"}
                                       (let [results (kg-queries/traverse
                                                      seed
                                                      {:direction :both
                                                       :relations #{:implements :refines :depends-on
                                                                    :supersedes :derived-from}
                                                       :max-depth (domain/or-val depth 2)})]
                                         {:seed seed
                                          :related (mapv #(select-keys % [:node-id :relation :confidence])
                                                         results)}))]
                   (if-let [err (::r/error (meta result))]
                     (do (log/debug "Failed KG traversal from seed:" seed (:message err))
                         (assoc result :error (:message err)))
                     result))))
         vec)))

(defn execute-all-hints
  "Execute all hint levels: fetch IDs, run queries, traverse KG."
  [hints]
  (let [axiom-entries (execute-hint-ids (:axiom-ids hints))
        read-entries (execute-hint-ids (:read-ids hints))
        search-results (execute-hint-queries (:queries hints))
        kg-results (execute-hint-kg-traversal (:kg-seeds hints) (:kg-depth hints))]
    {:axioms axiom-entries
     :entries read-entries
     :search-results search-results
     :kg-graph kg-results
     :stats {:axioms-fetched (count axiom-entries)
             :entries-fetched (count read-entries)
             :queries-run (count (domain/or-val (:queries hints) []))
             :kg-seeds-traversed (count (domain/or-val (:kg-seeds hints) []))}}))
