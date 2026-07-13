(ns hive-mcp.agent.drone.session-kg
  "Per-drone session store with observation recording, reasoning tracking, and context reconstruction."
  (:require [hive-mcp.protocols.kg :as kg]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.dns.result :as result]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.knowledge-graph.slots.factory :as kg-factory]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- truncate
  "Truncate a string to max-len characters with ellipsis."
  [s max-len]
  (if (and (string? s) (> (count s) max-len))
    (str (subs s 0 max-len) "...")
    (str s)))

(defn- extract-key-facts
  "Extract key facts from a tool result."
  [tool result]
  (let [success? (:success result)
        text (or (get-in result [:result :text])
                 (get-in result [:result])
                 "")]
    (cond-> []
      true (conj (if success? (str tool " succeeded") (str tool " failed")))

      (and success? (= tool "read_file") (string? text))
      (into (when-let [ns-match (re-find #"\(ns\s+([\w\.\-]+)" text)]
              [(str "namespace: " (second ns-match))]))

      (and success? (= tool "grep") (string? text))
      (conj (str "matches: " (count (str/split-lines text))))

      (and (not success?) (:error result))
      (conj (str "error: " (truncate (:error result) 80))))))

(defn- summarize-result
  "Create a short summary of a tool result."
  [tool result]
  (let [success? (:success result)
        text (or (get-in result [:result :text])
                 (str (get-in result [:result]))
                 "")]
    (if success?
      (str tool ": " (truncate text 100))
      (str tool " FAILED: " (truncate (or (:error result) "unknown") 80)))))

(def session-schema
  "Additional schema attributes for drone session store."
  {;; Observation nodes
   :obs/id          {:db/unique :db.unique/identity}
   :obs/turn        {}
   :obs/tool        {}
   :obs/summary     {}
   :obs/success     {}
   :obs/timestamp   {}
   :obs/file        {}
   :obs/key-facts   {:db/cardinality :db.cardinality/many}

   ;; Reasoning nodes
   :reason/id       {:db/unique :db.unique/identity}
   :reason/turn     {}
   :reason/intent   {}
   :reason/rationale {}
   :reason/timestamp {}

   ;; Goal tracking
   :goal/id         {:db/unique :db.unique/identity}
   :goal/description {}
   :goal/status     {}
   :goal/turn-set   {}

   ;; Dependency edges between observations
   :dep/from        {}
   :dep/to          {}
   :dep/relation    {}})

(defn session-db-path
  "Compute the temp database path for a drone session."
  [drone-id]
  (str "/tmp/drone-" drone-id "/kg"))

(defn create-session-kg!
  "Create an isolated Datalevin-backed session store for a drone.
   Returns nil when the datalevin backend is not on the classpath."
  [drone-id]
  (let [db-path (session-db-path drone-id)]
    (log/info "Creating session store for drone" {:drone-id drone-id :path db-path})
    (result/rescue nil
                   (let [store (kg-factory/backend->store :datalevin
                                                          {:db-path db-path
                                                           :extra-schema session-schema})]
                     (when store
                       (kg/ensure-conn! store)
                       (log/info "Session Store initialized" {:drone-id drone-id :path db-path
                                                              :session-attrs (count session-schema)})
                       store)))))

(defn close-session-kg!
  "Close a session store and optionally clean up temp directory."
  [store drone-id & {:keys [cleanup?] :or {cleanup? true}}]
  (when store
    (result/rescue nil
                   (kg/close! store)
                   (when cleanup?
                     (let [dir (io/file (session-db-path drone-id))]
                       (when (.exists dir)
                         (doseq [f (reverse (file-seq dir))]
                           (.delete f))
                         (let [parent (.getParentFile dir)]
                           (when (and (.exists parent) (empty? (.listFiles parent)))
                             (.delete parent))))))
                   (log/info "Session Store closed" {:drone-id drone-id :cleaned-up? cleanup?}))))

(defn record-observation!
  "Record a tool execution result as a compressed observation in the session store."
  [store turn tool result & [opts]]
  (let [obs-id (str "obs-" turn "-" tool)]
    (if-let [ext-fn (ext/get-extension :sk/record-obs!)]
      (ext-fn store turn tool result opts)
      (do
        (when store
          (result/rescue nil
                         (let [summary (summarize-result tool result)
                               key-facts (extract-key-facts tool result)
                               obs-entity (cond-> {:obs/id obs-id
                                                   :obs/turn turn
                                                   :obs/tool tool
                                                   :obs/summary summary
                                                   :obs/success (boolean (:success result))
                                                   :obs/timestamp (java.util.Date.)}
                                            (seq key-facts) (assoc :obs/key-facts (set key-facts))
                                            (:file opts) (assoc :obs/file (:file opts)))]
                           (kg/transact! store [obs-entity])
                           (log/debug "Recorded observation" {:obs-id obs-id :turn turn :tool tool}))))
        obs-id))))

(defn record-reasoning!
  "Record an LLM reasoning step in the session store."
  [store turn intent rationale]
  (let [reason-id (str "reason-" turn)]
    (if-let [ext-fn (ext/get-extension :sk/record-rsn!)]
      (ext-fn store turn intent rationale)
      (do
        (when store
          (result/rescue nil
                         (let [reason-entity {:reason/id reason-id
                                              :reason/turn turn
                                              :reason/intent (truncate intent 200)
                                              :reason/rationale (truncate rationale 200)
                                              :reason/timestamp (java.util.Date.)}]
                           (kg/transact! store [reason-entity])
                           (log/debug "Recorded reasoning" {:reason-id reason-id :turn turn}))))
        reason-id))))

(defn- query-recent-observations
  "Query the last N observations from the session store, ordered by turn."
  [store max-obs]
  (result/rescue []
                 (let [results (kg/query store
                                         '[:find ?id ?turn ?tool ?summary ?success
                                           :where
                                           [?e :obs/id ?id]
                                           [?e :obs/turn ?turn]
                                           [?e :obs/tool ?tool]
                                           [?e :obs/summary ?summary]
                                           [?e :obs/success ?success]])]
                   (->> results
                        (sort-by second >)
                        (take max-obs)
                        vec))))

(defn- query-recent-reasoning
  "Query the last N reasoning nodes from the session store, ordered by turn."
  [store max-reasons]
  (result/rescue []
                 (let [results (kg/query store
                                         '[:find ?id ?turn ?intent
                                           :where
                                           [?e :reason/id ?id]
                                           [?e :reason/turn ?turn]
                                           [?e :reason/intent ?intent]])]
                   (->> results
                        (sort-by second >)
                        (take max-reasons)
                        vec))))

(defn- query-all-key-facts
  "Query all accumulated key facts from observations."
  [store]
  (result/rescue #{}
                 (let [results (kg/query store
                                         '[:find ?fact
                                           :where
                                           [?e :obs/key-facts ?fact]])]
                   (into #{} (map first) results))))

(defn- query-goals
  "Query active goals from the session store."
  [store]
  (result/rescue []
                 (let [results (kg/query store
                                         '[:find ?id ?desc ?status
                                           :where
                                           [?e :goal/id ?id]
                                           [?e :goal/description ?desc]
                                           [?e :goal/status ?status]])]
                   (vec results))))

(defn reconstruct-context
  "Reconstruct a compact context prompt from the session store state."
  [store task turn]
  (if-let [ext-fn (ext/get-extension :sk/reconstruct)]
    (ext-fn store task turn)
    (if (or (nil? store) (zero? turn))
      task
      (result/rescue task
                     (let [recent-obs (query-recent-observations store 5)
                           recent-reasons (query-recent-reasoning store 3)
                           key-facts (query-all-key-facts store)
                           goals (query-goals store)
                           active-goals (filter #(= :active (nth % 2)) goals)

                           sections (cond-> [(str "TASK: " task)]

                                      (seq key-facts)
                                      (conj (str "\nKNOWN FACTS:\n"
                                                 (str/join "\n" (map #(str "- " %) (take 10 key-facts)))))

                                      (seq recent-obs)
                                      (conj (str "\nRECENT OBSERVATIONS:\n"
                                                 (str/join "\n"
                                                           (map (fn [[_id turn _tool summary success]]
                                                                  (str "T" turn ": " (if success "[OK]" "[FAIL]") " " summary))
                                                                recent-obs))))

                                      (seq recent-reasons)
                                      (conj (str "\nRECENT REASONING:\n"
                                                 (str/join "\n"
                                                           (map (fn [[_id turn intent]]
                                                                  (str "T" turn ": " intent))
                                                                recent-reasons))))

                                      (seq active-goals)
                                      (conj (str "\nACTIVE GOALS:\n"
                                                 (str/join "\n"
                                                           (map (fn [[_id desc _status]]
                                                                  (str "- " desc))
                                                                active-goals))))

                                      true
                                      (conj (str "\nCurrent turn: " turn ". Continue working on the task.")))]
                       (str/join "\n" sections))))))

(defn- extract-session-edges
  "Extract all KG edges from a session store."
  [store]
  (result/rescue []
                 (let [edge-eids (kg/query store '[:find ?e :where [?e :kg-edge/id _]])]
                   (mapv (fn [[eid]]
                           (-> (kg/pull-entity store '[*] eid)
                               (dissoc :db/id)))
                         edge-eids))))

(defn merge-session-to-global!
  "Merge valuable session facts into the global store."
  [session-store global-store & [opts]]
  (if-let [f (ext/get-extension :dl/merge!)]
    (f session-store global-store opts)
    (when (and session-store global-store)
      (let [edges (extract-session-edges session-store)
            min-confidence (or (:min-confidence opts) 0.0)
            filter-fn (or (:filter-fn opts) (constantly true))
            filtered (filter (fn [edge]
                               (and (>= (or (:kg-edge/confidence edge) 1.0) min-confidence)
                                    (filter-fn edge)))
                             edges)]
        (log/info "Merging session edges to global" {:total (count edges)
                                                     :after-filter (count filtered)})
        (let [merge-result (reduce
                            (fn [acc edge]
                              (let [r (result/try-effect* :kg/backend-error
                                                          (kg/transact! global-store [edge]))]
                                (if-let [_ (:ok r)]
                                  (update acc :merged-count inc)
                                  (update acc :errors conj {:edge-id (:kg-edge/id edge)
                                                            :error (:message r)}))))
                            {:merged-count 0 :errors []}
                            filtered)]
          (log/info "Session->global merge complete" (select-keys merge-result [:merged-count]))
          merge-result)))))

(defn- extract-global-edges-for-files
  "Extract edges from global store that reference the given file paths."
  [global-store files]
  (result/rescue []
                 (let [all-edges (kg/query global-store
                                           '[:find ?e ?id ?from ?to ?rel
                                             :where
                                             [?e :kg-edge/id ?id]
                                             [?e :kg-edge/from ?from]
                                             [?e :kg-edge/to ?to]
                                             [?e :kg-edge/relation ?rel]])
                       file-set (set files)
                       relevant (filter (fn [[_e _id from to _rel]]
                                          (or (contains? file-set from)
                                              (contains? file-set to)
                                              (some (fn [f]
                                                      (or (str/includes? (str from) f)
                                                          (str/includes? (str to) f)))
                                                    files)))
                                        all-edges)]
                   (mapv (fn [[eid _id _from _to _rel]]
                           (-> (kg/pull-entity global-store '[*] eid)
                               (dissoc :db/id)))
                         relevant))))

(defn seed-from-global!
  "Seed a session store with relevant context from the global store."
  [session-store global-store task-context]
  (if-let [ext-fn (ext/get-extension :sk/seed!)]
    (ext-fn session-store global-store task-context)
    (if (or (nil? session-store) (nil? global-store))
      0
      (let [files (or (:files task-context) [])
            edges (when (seq files)
                    (extract-global-edges-for-files global-store files))
            seeded-count (count (keep (result/rescue-fn
                                       #(kg/transact! session-store [%]))
                                      edges))]
        (log/info "Seeded session store from global" {:files (clojure.core/count files)
                                                      :edges-seeded seeded-count})
        seeded-count))))
