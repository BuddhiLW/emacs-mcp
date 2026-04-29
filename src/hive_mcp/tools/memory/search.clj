(ns hive-mcp.tools.memory.search
  "Semantic search handler for memory operations.

   Defensive timeout architecture (task 20260404125344-0365f8a8):
   Production hangs were observed from three failure modes —
   Milvus HTTP 'selector manager closed' (dead grpc client after OOM),
   Qdrant grpc 'INTERNAL: Panic!', and unreachable embedding providers.
   Each could leave a bare @future blocked indefinitely, starving the
   MCP handler pool and killing stdio.

   Defense in depth with structured surfacing:
   - Per-stage timeouts: vectordb (15s), post-filter/format (5s).
     Embedding is nested inside the store's search-similar; its budget
     is covered by the vectordb stage and surfaced via ex-classification
     (exceptions containing 'embed' in the message map to :stage :embed).
   - fork-join with a 30s collective budget runs the store and ingest
     queries concurrently, each with its own fallback. A failure on
     one side still yields a partial response.
   - Outer wpool/await! on memory-pool remains the hard ceiling.

   On timeout or throw, we return a Result err:
     {:error :memory/search-failed
      :stage :embed|:vectordb|:post
      :partial <whatever completed>
      :cause <ex-message>}

   Happy-path overhead: one safe-future-call allocation per stage
   (<1ms); the fork-join deadline check is O(tasks). Callers see no
   shape change on success — only a structured err body on failure."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.plan.plans :as plans]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.chroma.search :as chroma-search]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.memory.domain :as domain]
            [hive-mcp.tools.core :refer [coerce-int!]]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result :refer [rescue]]
            [hive-mcp.concurrency.pool :as pool]
            [hive-weave.pool :as wpool]
            [hive-weave.safe :as safe]
            [hive-weave.parallel :as parallel]
            [hive-mcp.agent.context :as ctx]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Timeout budgets
;; =============================================================================

(def ^:const ^:private memory-search-timeout-ms
  "Overall timeout budget for a memory search (collective ceiling).
   30s accommodates multi-project fan-out on cold Milvus paths; it
   also matches the outer wpool/await! safety net."
  30000)

(def ^:const ^:private embed-timeout-ms
  "Per-stage timeout for an embedding round-trip. Embedding is nested
   inside the vectordb stage; this bound is used to classify a timeout
   that fires inside the embedding provider as :stage :embed."
  10000)

(def ^:const ^:private vectordb-timeout-ms
  "Per-stage timeout for one vectordb query (store or ingest side).
   15s covers embed→query→filter on the store; the two sides run
   concurrently so the wall clock is 15s, not 30s."
  15000)

(def ^:const ^:private post-filter-timeout-ms
  "Per-stage timeout for merge/rerank/format. Pure CPU work; 5s is
   a generous ceiling that still prevents a pathological reducer from
   wedging the handler."
  5000)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- extract-title
  "Extract a one-line title from content or document text.
   Skips the 'Type:/Tags:/Content:' metadata prefix in document text."
  [document metadata]
  (let [content (get metadata :content)
        text    (or content document "")]
    (when (seq text)
      (let [;; Skip metadata prefix if present
            clean (if (str/starts-with? text "Type:")
                    (let [idx (str/index-of text "Content:")]
                      (if idx (subs text (+ idx 9)) text))
                    text)
            first-line (first (str/split-lines (str/trim clean)))]
        (when (seq first-line)
          (subs first-line 0 (min 120 (count first-line))))))))

(defn- format-search-result
  "Format a single search result — compact by default.
   Returns id, type, top 5 tags (excluding scope/agent), distance, one-line title."
  [{:keys [id document metadata distance]}]
  (let [raw-tags (when-let [t (get metadata :tags)]
                   (when (not= t "")
                     (str/split t #",")))
        clean-tags (filterv #(not (or (str/starts-with? % "agent:")
                                      (str/starts-with? % "scope:")
                                      (= % "carto")))
                            (or raw-tags []))]
    {:id       id
     :type     (get metadata :type)
     :tags     (vec (take 5 clean-tags))
     :distance distance
     :title    (extract-title document metadata)}))

(defn- content->text
  "Coerce a stored :content value into a string for downstream string ops.
   Kanban (and other structured) entries persist :content as a map (the
   Milvus read path JSON-decodes the round-tripped JSON string back into
   a map per try-parse-json contract); prefer the :title key when present,
   otherwise pr-str so format-search-result's string ops never see a map."
  [c]
  (cond
    (nil? c)    ""
    (string? c) c
    (map? c)    (or (:title c) (pr-str c))
    :else       (str c)))

(defn- store-entry->normalized
  "Map a store-protocol search entry (Milvus flat shape) into the
   common {:id :document :metadata :distance} shape consumed by
   format-search-result and merge-and-rerank."
  [entry]
  (let [tags-vec  (cond
                    (sequential? (:tags entry)) (vec (:tags entry))
                    (string? (:tags entry))    (vec (str/split (:tags entry) #","))
                    :else                      [])
        tags-str  (str/join "," tags-vec)
        tp        (:type entry)
        content   (content->text (:content entry))]
    {:id       (:id entry)
     :document content
     :distance (:distance entry)
     :metadata {:tags       tags-str
                :type       (cond-> tp (keyword? tp) name)
                :content    content
                :project-id (:project-id entry)}}))

(defn- record-co-access!
  "Fire-and-forget co-access recording for search results."
  [formatted project-id created-by]
  (when (>= (count formatted) 2)
    (future
      (rescue nil
              (kg-edges/record-co-access!
               (mapv :id formatted)
               {:scope project-id :created-by created-by})))))

;; =============================================================================
;; Stage classification
;; =============================================================================

(defn- classify-stage
  "Classify which pipeline stage a failure belongs to, by inspecting the
   exception message. Used to populate :stage in the structured err body.

   Heuristic:
   - `embed` / `embedding` substring → :embed (OOM / unreachable provider)
   - anything else coming from vectordb stage → :vectordb
   Default is :vectordb because the embedding call is nested inside
   mem-proto/search-similar and we cannot distinguish at the API boundary."
  [^String msg]
  (if (and msg (or (.contains msg "embed")
                   (.contains msg "Embed")
                   (.contains msg "embedding")))
    :embed
    :vectordb))

(defn- stage-err
  "Construct a structured :memory/search-failed err with stage classification."
  [stage cause-msg partial]
  (result/err :memory/search-failed
              (cond-> {:stage   stage
                       :cause   (or cause-msg "unknown")
                       :message (str "memory search failed at stage " stage
                                     (when cause-msg (str ": " cause-msg)))}
                (some? partial) (assoc :partial partial))))

(defn- unwrap-safe-future
  "Translate the Result returned by safe-future-call into a
   `{:stage :X :value V}` map, preserving the stage label. Returns
   `{:stage :X :err <message>}` on timeout or exception.

   Keeps the happy path allocation-free aside from the outer map."
  [stage r]
  (cond
    (result/ok? r)  {:stage stage :value (:ok r)}
    ;; safe-future returned :weave/timeout or :weave/exception
    :else           {:stage stage
                     :err   (or (:message r)
                                (:name r)
                                (str (:error r)))}))

;; =============================================================================
;; Stages — each wrapped with its own safe-future-call + timeout
;; =============================================================================

(defn- run-store-query
  "Vectordb stage A: the primary store search + one fallback retry
   without exclude-tags (for false-negative recovery).

   Wrapped in `safe-future-call` with vectordb-timeout-ms. A dropped
   HTTP transport kicks the Milvus heal loop via with-resilience."
  [query limit-val type visible-project-ids effective-excludes]
  (safe/safe-future-call
   {:timeout-ms vectordb-timeout-ms :name "memory-search/store"}
   (fn []
     (let [first-pass (with-resilience
                        (mem-proto/search-similar
                         (mem-proto/get-store) query
                         {:limit        (* limit-val 2)
                          :type         type
                          :project-ids  visible-project-ids
                          :exclude-tags effective-excludes}))]
       (if (and (empty? first-pass) (seq effective-excludes))
         (do (log/warn "search fallback: retrying without exclude-tags for query:" query)
             (with-resilience
               (mem-proto/search-similar
                (mem-proto/get-store) query
                {:limit       (* limit-val 2)
                 :type        type
                 :project-ids visible-project-ids})))
         first-pass)))))

(defn- run-ingest-query
  "Vectordb stage B: ingest cross-collection search (carto snippets).
   Optional — when no ingest search-fn is registered, returns an empty
   vector. Wrapped in safe-future-call with vectordb-timeout-ms."
  [query limit-val]
  (safe/safe-future-call
   {:timeout-ms vectordb-timeout-ms :name "memory-search/ingest"}
   (fn []
     (if-let [search-fn (chroma-search/resolve-ingest-search)]
       (rescue []
               (let [raw (search-fn query {:limit limit-val})]
                 (if (and (map? raw) (:ok raw))
                   (chroma-search/normalize-ingest-results (:ok raw))
                   [])))
       []))))

(defn- run-post-filter
  "Post-filter / aggregation stage: normalize, merge, rerank, format.
   Pure CPU but bounded by post-filter-timeout-ms so a pathological
   reducer (e.g. million-row dedupe) cannot wedge the handler."
  [store-results ingest-results limit-val]
  (safe/safe-future-call
   {:timeout-ms post-filter-timeout-ms :name "memory-search/post"}
   (fn []
     (let [normalized-store (mapv store-entry->normalized (or store-results []))
           merged (chroma-search/merge-and-rerank normalized-store
                                                  (or ingest-results [])
                                                  limit-val)]
       (mapv format-search-result merged)))))

;; =============================================================================
;; Plan-routed branch (high-abstraction types)
;; =============================================================================

(defn- search-plans*
  "Search high-abstraction plans. Returns Result."
  [query limit-val type project-id in-project?]
  (let [results (plans/search-plans query
                                    :limit limit-val
                                    :type type
                                    :project-id (when in-project? project-id))
        formatted (mapv (fn [{:keys [id type tags distance preview]}]
                          {:id id
                           :type (or type "plan")
                           :tags tags
                           :distance distance
                           :preview preview})
                        results)]
    (record-co-access! formatted project-id "system:high-abstraction-search")
    (result/ok {:results formatted
                :count (count formatted)
                :query query
                :scope project-id})))

(def ^:private default-exclude-tags
  "Tags excluded from semantic search by default.
   Carto (L1/L2 codebase-mapping snippets) drowns out high-level knowledge."
  ["carto"])

;; =============================================================================
;; Composite search-store* (fork-join across store + ingest)
;; =============================================================================

(defn- search-store*
  "Search the configured memory store + ingest collections concurrently
   with a fork-join collective budget (30s) and per-stage safe-future
   timeouts. Returns a Result.

   On total timeout or unrecoverable error, yields a structured err:
     {:error :memory/search-failed
      :stage :embed|:vectordb|:post
      :partial <partial results if any>
      :cause <ex-message>}

   Budget rationale:
   - store and ingest run in parallel; collective 30s = memory-search-timeout-ms.
   - each vectordb side has its own 15s cap, so one hung backend cannot
     eat the whole budget (the other side still races to complete).
   - post-filter gets 5s — bounded CPU, independent of network health."
  [query limit-val type project-id in-project? include_descendants exclude-tags]
  (let [visible-project-ids (when in-project?
                              (let [visible (kg-scope/visible-scopes project-id)
                                    descendants (when include_descendants
                                                  (kg-scope/descendant-scopes project-id))]
                                (vec (distinct (concat visible descendants)))))
        effective-excludes (into (vec default-exclude-tags) exclude-tags)
        fj (parallel/fork-join
            {:budget-ms memory-search-timeout-ms}
            [:store
             #(unwrap-safe-future :vectordb
                                  (run-store-query query limit-val type
                                                   visible-project-ids
                                                   effective-excludes))
             {:stage :vectordb :err "fork-join store timeout"}]
            [:ingest
             #(unwrap-safe-future :vectordb
                                  (run-ingest-query query limit-val))
             {:stage :vectordb :value []}])
        store-side  (:store fj)
        ingest-side (:ingest fj)]
    (cond
      ;; Store side failed or timed out — surface structured err.
      (contains? store-side :err)
      (let [msg (:err store-side)
            stage (classify-stage msg)]
        (log/warn (ex-info "memory search: store stage failed"
                           {:stage stage :cause msg :query query}))
        (stage-err stage msg nil))

      :else
      (let [store-results  (:value store-side)
            ingest-results (:value ingest-side [])
            post (run-post-filter store-results ingest-results limit-val)]
        (cond
          (result/ok? post)
          (let [formatted (:ok post)]
            (record-co-access! formatted project-id "system:semantic-search")
            (result/ok {:results formatted
                        :count   (count formatted)
                        :query   query
                        :scope   project-id}))

          :else
          (let [msg (or (:message post) (:name post) "post-filter failed")]
            (log/warn (ex-info "memory search: post stage failed"
                               {:stage :post :cause msg :query query}))
            (stage-err :post msg
                       {:store-count  (count store-results)
                        :ingest-count (count ingest-results)})))))))

;; =============================================================================
;; Dispatch
;; =============================================================================

(defn- search-semantic*
  "Pure search logic returning Result. Validates inputs and dispatches to appropriate search backend.
   exclude_tags defaults to [\"carto\"] — pass [] to include carto snippets explicitly."
  [{:keys [query limit type directory include_descendants scope exclude_tags]}]
  (let [directory (or directory (ctx/current-directory))
        openrouter? (plans/high-abstraction-type? type)
        limit-val (coerce-int! limit :limit 10)
        store-ready? (and (mem-proto/store-set?)
                          (mem-proto/supports-semantic-search? (mem-proto/get-store)))]
    (log/info "mcp-memory-search-semantic:" query "type:" type "directory:" directory
              "scope:" scope "exclude_tags:" exclude_tags)
    (if-not store-ready?
      (result/err :memory/store-not-configured
                  {:message (str "Semantic search not configured. "
                                 "Configure a memory store with an embedding provider.")})
      (let [project-id (scope/get-current-project-id directory)
            sf (domain/parse-scope scope project-id)
            [effective-pid in-project?] (domain/scope->effective sf)]
        (if openrouter?
          (search-plans* query limit-val type effective-pid in-project?)
          (search-store* query limit-val type effective-pid in-project?
                         include_descendants (if (some? exclude_tags)
                                               exclude_tags
                                               default-exclude-tags)))))))

(defn handle-search-semantic
  "Search project memory using semantic similarity (vector search).
   HCR Wave 4: Pass include_descendants=true to include child project memories.

   Runs on the dedicated memory-pool so concurrent search traffic is
   isolated from the shared io-pool. Inner stage timeouts (embed/vdb/post)
   provide per-boundary defensive budgets; this outer wpool/await! is
   the hard ceiling that ensures the MCP response cannot hang even if
   a stage wrapper itself is buggy."
  [params]
  (let [timeout-sentinel ::timeout
        raw (wpool/await!
             (pool/memory-pool)
             #(try (search-semantic* params)
                   (catch Throwable t {::caught t}))
             {:timeout-ms memory-search-timeout-ms
              :fallback   timeout-sentinel
              :name       "memory-search"})]
    (cond
      (= raw timeout-sentinel)
      (rb/result->mcp
       (result/err :memory/search-failed
                   {:stage   :vectordb
                    :cause   (str "outer pool timeout after "
                                  memory-search-timeout-ms "ms")
                    :message (str "memory search timed out after "
                                  memory-search-timeout-ms "ms")}))

      (and (map? raw) (contains? raw ::caught))
      (rb/result->mcp
       (rb/try-result :memory/search-failed
                      (fn [] (throw (::caught raw)))))

      :else
      (rb/result->mcp raw))))
