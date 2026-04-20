(ns hive-mcp.tools.consolidated.transcript
  "MCP transcript supertool — query agent conversation transcripts.

   DDD Application Service layer: orchestrates ITranscriptStore domain calls
   through an ROP pipeline (hive-dsl.result/let-ok).

   Commands: list, query, tail, since, stats, replay

   Architecture:
     MCP transport → this ns (application) → ITranscriptStore (domain, hive-agent)

   All public fns return Result<map> for uniform error handling."
  (:require [hive-mcp.agent.transcript-query :as tq]
            [hive-dsl.adt :refer [adt-case]]
            [hive-dsl.result :as r]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Store Resolution (SLAP: mechanism layer)
;; =============================================================================

(defn- resolve-transcript-store
  "Resolve ITranscriptStore for agent-id via hive-agent config.
   Returns Result<ITranscriptStore>."
  [agent-id]
  (r/try-effect
   (if-let [f (requiring-resolve 'hive-agent.config/resolve-transcript-store)]
     (let [res (f {:agent-id agent-id})]
       (if (and (map? res) (contains? res :ok))
         (:ok res)
         (throw (ex-info "Store resolution returned non-ok" {:result res}))))
     (throw (ex-info "hive-agent not on classpath" {})))))

(defn- list-jsonl-transcripts
  "Scan JSONL transcript dir for available agent transcripts. Pure-ish (reads fs)."
  []
  (try
    (let [dir (java.io.File. "/tmp/hive-transcripts")]
      (if (.exists dir)
        (->> (.listFiles dir)
             (filter #(str/ends-with? (.getName %) ".jsonl"))
             (mapv (fn [f]
                     (let [name (.getName f)
                           agent-id (str/replace name #"\.jsonl$" "")
                           lines (count (str/split-lines (slurp f)))]
                       {:agent-id  agent-id
                        :source    :jsonl
                        :entries   lines
                        :size-kb   (/ (.length f) 1024.0)
                        :modified  (.lastModified f)}))))
        []))
    (catch Exception e
      (log/warn "[transcript] JSONL scan failed:" (ex-message e))
      [])))

(defn- list-datalevin-transcripts
  "Scan Datalevin transcript dir for available agent stores."
  []
  (try
    (let [dir (java.io.File. "/tmp/hive-transcripts/datalevin")]
      (if (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isDirectory %))
             (mapv (fn [d]
                     {:agent-id (.getName d)
                      :source   :datalevin
                      :size-kb  (->> (file-seq d)
                                     (filter #(.isFile %))
                                     (map #(.length %))
                                     (reduce + 0)
                                     (* (/ 1.0 1024)))
                      :modified (.lastModified d)})))
        []))
    (catch Exception e
      (log/warn "[transcript] Datalevin scan failed:" (ex-message e))
      [])))

;; =============================================================================
;; Query Execution (SLAP: mechanism layer)
;; =============================================================================

(defn- query-jsonl
  "Read and parse JSONL transcript file for agent-id.
   Returns Result<vector<entry>>."
  [agent-id & {:keys [limit offset] :or {limit 100 offset 0}}]
  (r/try-effect
   (let [path (str "/tmp/hive-transcripts/" agent-id ".jsonl")
         file (java.io.File. path)]
     (if (.exists file)
       (->> (str/split-lines (slurp file))
            (drop offset)
            (take limit)
            (mapv #(json/read-str % :key-fn keyword)))
       (throw (ex-info "Transcript not found" {:agent-id agent-id :path path}))))))

(defn- query-jsonl-tail
  "Last N entries from JSONL file."
  [agent-id n]
  (r/try-effect
   (let [path (str "/tmp/hive-transcripts/" agent-id ".jsonl")
         file (java.io.File. path)]
     (if (.exists file)
       (->> (str/split-lines (slurp file))
            (take-last n)
            (mapv #(json/read-str % :key-fn keyword)))
       (throw (ex-info "Transcript not found" {:agent-id agent-id}))))))

(defn- execute-query
  "Dispatch on TranscriptQuery ADT. Returns Result<vector<entry>>.
   Currently uses JSONL backend (auto source). Datalevin path pending
   dual-write fix in loop/core.clj record-turn!."
  [query]
  (adt-case tq/TranscriptQuery query
    :query/by-agent (query-jsonl (:agent-id query))
    :query/by-time  (r/err :transcript/not-implemented
                           {:msg "time-range query requires datalevin dual-write"})
    :query/since    (r/try-effect
                     (let [entries (:ok (query-jsonl (:agent-id query) :limit 10000))]
                       (filterv #(> (or (:turn %) 0) (:turn query)) entries)))
    :query/tail     (query-jsonl-tail (:agent-id query) (:n query))))

;; =============================================================================
;; Response Formatting (SLAP: mechanism layer)
;; =============================================================================

(defn- format-entry-compact
  "Compact entry for list responses."
  [entry]
  (let [content (str (or (:content entry) (:transcript/content entry) ""))]
    {:role    (or (:role entry) (some-> (:transcript/role entry) name))
     :turn    (or (:turn entry) (:transcript/turn entry))
     :preview (subs content 0 (min 120 (count content)))}))

(defn- format-replay
  "Format entries as markdown conversation."
  [entries agent-id]
  (let [header (format "## Transcript: %s (%d entries)\n\n" agent-id (count entries))]
    (->> entries
         (map (fn [e]
                (let [role (or (:role e) (some-> (:transcript/role e) name) "?")
                      content (or (:content e) (:transcript/content e) "")]
                  (format "**[%s]** %s\n" role content))))
         (str/join "\n")
         (str header))))

(defn- compute-stats
  "Compute transcript statistics from entries."
  [entries agent-id]
  (let [roles (frequencies (map #(or (:role %) (some-> (:transcript/role %) name)) entries))
        costs (keep #(or (:cost_usd %) (:transcript/cost-usd %)) entries)]
    {:agent-id    agent-id
     :total       (count entries)
     :by-role     roles
     :turns       (or (apply max 0 (keep #(or (:turn %) (:transcript/turn %)) entries)) 0)
     :total-cost  (reduce + 0.0 costs)}))

;; =============================================================================
;; MCP Command Router (SLAP: intent layer)
;; =============================================================================

(defn handle-transcript
  "Route transcript MCP commands. Returns result map.

   Commands:
     list                     — Available transcripts (JSONL + Datalevin)
     query  {:agent-id}       — Full conversation entries
     tail   {:agent-id :n?}   — Last N entries (default 10)
     since  {:agent-id :turn} — Entries after turn
     stats  {:agent-id}       — Turn count, cost, role breakdown
     replay {:agent-id}       — Formatted markdown conversation"
  [{:keys [command agent-id agent_id id n turn] :as params}]
  (let [agent-id (or agent-id agent_id id)]
  (case command
    "list"
    (let [jsonl (list-jsonl-transcripts)
          dl    (list-datalevin-transcripts)
          all   (->> (concat jsonl dl)
                     (group-by :agent-id)
                     (map (fn [[id sources]] (assoc (first sources) :sources (mapv :source sources))))
                     (sort-by :modified >)
                     vec)]
      {:transcripts all :count (count all)})

    "query"
    (let [result (execute-query (tq/transcript-query :query/by-agent {:agent-id agent-id}))]
      (if (r/ok? result)
        {:entries (mapv format-entry-compact (:ok result))
         :count   (count (:ok result))
         :agent-id agent-id}
        {:error true :message (:message result)}))

    "tail"
    (let [result (execute-query (tq/transcript-query :query/tail {:agent-id agent-id :n (int (or n 10))}))]
      (if (r/ok? result)
        {:entries (mapv format-entry-compact (:ok result))
         :count   (count (:ok result))
         :agent-id agent-id}
        {:error true :message (:message result)}))

    "since"
    (let [result (execute-query (tq/transcript-query :query/since {:agent-id agent-id :turn (int (or turn 0))}))]
      (if (r/ok? result)
        {:entries (mapv format-entry-compact (:ok result))
         :count   (count (:ok result))
         :agent-id agent-id}
        {:error true :message (:message result)}))

    "stats"
    (let [result (execute-query (tq/transcript-query :query/by-agent {:agent-id agent-id}))]
      (if (r/ok? result)
        (compute-stats (:ok result) agent-id)
        {:error true :message (:message result)}))

    "replay"
    (let [result (execute-query (tq/transcript-query :query/by-agent {:agent-id agent-id}))]
      (if (r/ok? result)
        {:markdown (format-replay (:ok result) agent-id)
         :agent-id agent-id}
        {:error true :message (:message result)}))

    ;; Unknown command
    {:error true :message (str "Unknown transcript command: " command)})))
