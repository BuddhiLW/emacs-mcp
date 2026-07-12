(ns hive-mcp.recall.golden
  "The golden recall corpus, the fault taxonomy, and a store that can be
   deliberately broken.

   WHY THIS EXISTS
   ---------------
   The root failure of 2026-07-12 was not that recall broke. It is that recall
   broke SILENTLY: `memory search` kept returning 8 confident rows with
   plausible distances, and every agent downstream believed them. Nothing in
   the system could distinguish

       'the store holds no match for your query'          (a query outcome)

   from

       'your query encoder disagrees with your index'     (a SYSTEM FAULT)

   Both surfaced as a short, confident, wrong result list. This namespace makes
   the second one nameable, so a test can assert on it and a canary can fire.

   THE CONTRACT ENCODED HERE
   -------------------------
   An empty or anchor-less result set drawn from a POPULATED store is a system
   fault, not a query outcome. `recall-fault` returns a fault map (never throws,
   never returns a bare false) so the failing assertion PRINTS what broke.

   THE STORE HAS A BUG SWITCH
   --------------------------
   `->golden-store` takes `:emit`:

     :distance    — the post-MEM-P0-EMBED-LANE contract. Lower is nearer.
     :similarity  — the PRE-fix Milvus boundary, reproduced exactly: a COSINE
                    index reports proximity as a SIMILARITY (higher is nearer)
                    and milvus-clj carries it under the `:distance` key. The
                    store still ranks correctly internally (it knows its own
                    metric) and still returns its true top-k — only the UNIT
                    stamped on `:distance` is wrong. Downstream
                    (`chroma.search/merge-and-rerank`) then sorts ASCENDING and
                    hands back the worst of the top-k first, and the `take
                    limit` after the `limit * 2` overfetch in
                    `tools.memory.search/run-store-query` drops the best hits
                    entirely.

   That switch is the whole point: a canary that cannot be made to fail proves
   nothing. `:similarity` MUST make the suite go red."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hive-mcp.protocols.memory :as mem-proto]))

;; =============================================================================
;; The corpus
;; =============================================================================

(def anchor-id
  "The live entry the 2026-07-12 failure could not retrieve. The query
   'SIGSEGV crash JDK 25 bug use Java 21' names four rare literal tokens that
   appear together in exactly this entry and nowhere else in the corpus — if a
   retrieval pipeline cannot find THIS, the lexical channel is dead.

   The id is the real one so the `^:integration` tier can assert the same
   anchor against the live store."
  "20260511194834-344a3bc0")

(def anchor-query
  "The query that failed in production."
  "SIGSEGV crash JDK 25 bug use Java 21")

(defn- e
  [id type tags content]
  {:id id :type type :tags tags :content content :project-id "hive"})

(def golden-corpus
  "A small populated store. Exactly one entry carries the anchor's rare literal
   tokens (sigsegv / jdk / 25 / 21). The distractors deliberately share the
   COMMON tokens of the query ('crash', 'bug', 'use', 'java') so a pipeline
   cannot pass by accident on stopword overlap — it has to rank the rare tokens
   above the common ones, which is exactly what a working vector lane does and
   exactly what an inverted one does not."
  (into
   [(e anchor-id "note" ["jvm" "runtime"]
       (str "JDK 25 has a SIGSEGV crash bug in the JIT compiler that kills the "
            "MCP server on startup. Use Java 21 until it is fixed upstream."))]
   [(e "d-01" "note" ["build"]     "Gradle build cache bug: clean before you use the daemon.")
    (e "d-02" "note" ["jvm"]       "Java records are value-like carriers; use them for DTOs.")
    (e "d-03" "note" ["ops"]       "The nightly job will crash if the disk fills; add a use-quota alarm.")
    (e "d-04" "decision" ["kg"]    "Edge provenance tiers gate graph expansion. Statistical edges are opt-in.")
    (e "d-05" "note" ["clojure"]   "Prefer transducers over lazy seqs in hot loops.")
    (e "d-06" "note" ["milvus"]    "Milvus COSINE indexes report proximity as a similarity, not a distance.")
    (e "d-07" "convention" ["test"] "Never weaken a test assertion to make it pass.")
    (e "d-08" "note" ["qdrant"]    "Qdrant scroll pages default to 32 points; raise it for large scans.")
    (e "d-09" "note" ["python"]    "The python bridge will crash on a bad venv; use uv to pin it.")
    (e "d-10" "note" ["docs"]      "Write the failure mode down before you write the fix.")
    (e "d-11" "axiom" ["data"]     "COPY-NEVER-MOVE for irreplaceable data.")
    (e "d-12" "note" ["emacs"]     "vterm needs a compiled module; the elisp use-package form is not enough.")
    (e "d-13" "note" ["jvm"]       "A JVM crash dump lands in hs_err_pid; read it before you guess.")
    (e "d-14" "note" ["net"]       "gRPC INTERNAL: Panic! means the server died, not the client.")
    (e "d-15" "note" ["memory"]    "Superseded entries must never come back from a search.")
    ;; A carto snippet — `run-store-query` excludes tag "carto" by default.
    ;; It shares the anchor's tokens on purpose: if the exclusion regressed,
    ;; this row would surface and the exclusion test would catch it.
    (e "carto-1" "note" ["carto"]  "SIGSEGV JDK 25 Java 21 crash bug use — carto snippet, must be excluded.")]))

(def populated-count (count golden-corpus))

;; =============================================================================
;; A deterministic embedder — no network, no model, real cosine
;; =============================================================================

(defn tokens
  "Lowercased word set. A binary bag-of-words IS a vector; cosine over it is a
   real cosine, so the golden store exercises genuine vector ranking without a
   live embedding provider."
  [s]
  (->> (str/split (str/lower-case (str s)) #"[^a-z0-9]+")
       (remove str/blank?)
       set))

(defn cosine
  "Cosine similarity of two binary bags. 1.0 = identical, 0.0 = disjoint."
  [a b]
  (let [na (count a) nb (count b)]
    (if (or (zero? na) (zero? nb))
      0.0
      (/ (double (count (set/intersection a b)))
         (Math/sqrt (double (* na nb)))))))

;; =============================================================================
;; The fault taxonomy — the thing that was missing
;; =============================================================================

(defn recall-fault
  "Nil when recall is healthy; otherwise a FAULT MAP naming what broke.

   Never throws, and never returns a bare boolean — a failing `is` must print
   the diagnosis, not `false`.

   Arguments (map):
     :label        — human name of the case, echoed into the fault
     :populated?   — was the store known to hold entries at query time?
     :results      — what the pipeline returned (seq of maps with :id)
     :must-contain — ids that MUST be present (the anchors)

   Faults:
     :recall/empty-from-populated-store
         Zero rows out of a store we KNOW is populated. This is the shape the
         2026-07-12 outage wore. It is never a legitimate answer to a query
         whose anchor is in the corpus.

     :recall/anchor-missing
         Rows came back, but not the one that must be there. This is the
         'confident and wrong' shape — the dangerous one, because a caller
         cannot tell it from success.

   `:populated? false` yields nil: an empty store returning nothing is honest."
  [{:keys [label populated? results must-contain]}]
  (let [results (vec results)
        got     (set (keep :id results))
        want    (set must-contain)
        missing (set/difference want got)]
    (cond
      (not populated?)
      nil

      (empty? results)
      {:fault        :recall/empty-from-populated-store
       :label        label
       :diagnosis    (str "a populated store returned zero rows. This is a SYSTEM "
                          "FAULT, not a query outcome — the query encoder, the "
                          "index, or the ranking unit disagree.")
       :expected-ids (vec want)}

      (seq missing)
      {:fault        :recall/anchor-missing
       :label        label
       :diagnosis    (str "the store returned " (count results) " confident rows "
                          "but not the anchor. Indistinguishable from success at "
                          "the call site — this is why the outage was silent.")
       :missing-ids  (vec missing)
       :returned-ids (mapv :id results)}

      :else nil)))

(defn rank-fault
  "Nil when `results` are ordered nearest-first; otherwise a fault map.

   The pipeline's contract is DISTANCE — lower is nearer — end to end. A run
   whose distances ascend is either sorted backwards or is carrying a
   similarity in a field the whole pipeline reads as a distance
   (MEM-P0-EMBED-LANE). Rows with no :distance are ignored: tag/KG enrichment
   hits legitimately have none."
  [{:keys [label results]}]
  (let [ds (keep :distance results)]
    (when (and (seq ds) (not (apply <= ds)))
      {:fault     :recall/rank-inverted
       :label     label
       :diagnosis (str "results are not ordered nearest-first. Either the sort "
                       "reversed, or a similarity (higher = better) is being "
                       "carried in the :distance field (lower = better).")
       :distances (vec ds)})))

;; =============================================================================
;; The golden store — with a bug switch
;; =============================================================================

(defn- excluded?
  [entry exclude-tags]
  (boolean (some (set (:tags entry)) (or exclude-tags []))))

(defn- ranked
  "The store's own view: its true top-k, nearest first. A real vector store
   always gets THIS right — it knows its own metric. `emit` decides only what
   unit it stamps on the `:distance` key it hands across the boundary."
  [corpus query {:keys [limit type exclude-tags]} emit]
  (let [q (tokens query)]
    (->> corpus
         (filter #(or (nil? type) (= type (:type %))))
         (remove #(excluded? % exclude-tags))
         (map (fn [entry]
                (let [sim (cosine q (tokens (:content entry)))]
                  (assoc entry
                         :similarity sim
                         :distance   (case emit
                                       :similarity sim              ;; the bug
                                       (max 0.0 (- 1.0 sim)))))))   ;; the contract
         (sort-by :similarity >)                ;; true nearest-first, always
         (take (or limit 10))
         vec)))

(defrecord GoldenMemoryStore [corpus emit]
  mem-proto/IMemoryStore
  (connect!                  [_ _])
  (disconnect!               [_])
  (connected?                [_] true)
  (health-check              [_] {:healthy? true})
  (add-entry!                [_ _])
  (get-entry                 [_ id] (first (filter #(= id (:id %)) corpus)))
  (update-entry!             [_ _ _])
  (delete-entry!             [_ _])
  (query-entries             [_ opts] (vec (take (:limit opts 100) corpus)))
  (search-similar            [_ q opts] (ranked corpus q opts emit))
  (supports-semantic-search? [_] true)
  (cleanup-expired!          [_])
  (entries-expiring-soon     [_ _ _])
  (find-duplicate            [_ _ _ _])
  (store-status              [_] {:ok true :count (count corpus)})
  (reset-store!              [_]))

(defn ->golden-store
  "A populated store over `golden-corpus`.

   `emit` = :distance   → the contract (lower is nearer). Suite must be GREEN.
   `emit` = :similarity → MEM-P0-EMBED-LANE reproduced. Suite must be RED."
  ([] (->golden-store :distance))
  ([emit] (->GoldenMemoryStore golden-corpus emit)))

(defn ->empty-store
  "A store that is genuinely empty. Returning nothing from THIS is honest, and
   `recall-fault` must not call it a fault — otherwise the canary cries wolf."
  ([] (->GoldenMemoryStore [] :distance)))

;; =============================================================================
;; Store registration — never leaks across tests
;; =============================================================================

(defn with-store*
  [store f]
  (let [prior (get (mem-proto/registered-stores) :default)]
    (try
      (mem-proto/register-store! :default store)
      (f)
      (finally
        (if prior
          (mem-proto/register-store! :default prior)
          (mem-proto/unregister-store! :default))))))

(defmacro with-store
  "Register `store` as :default for the body, then restore what was there."
  [store & body]
  `(with-store* ~store (fn [] ~@body)))
