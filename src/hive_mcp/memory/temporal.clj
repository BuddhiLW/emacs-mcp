(ns hive-mcp.memory.temporal
  "Temporal write plumbing for memory mutations.

   Dual-writes memory mutations to Datahike alongside Chroma, creating an
   immutable audit trail with full temporal query support (history-db, as-of-db,
   since-db).

   Architecture:
   - Chroma remains the primary store (embeddings, search, CRUD)
   - Datahike records every mutation event with :mem-mutation/* schema
   - Temporal queries via connection.clj facade (history-db, as-of-db, since-db)

   9 Critical Write Paths (from decision 20260220165526-641f7cbc):
   Priority 1 (information loss):
     handle-feedback, move-to-done!, handle-expire
   Priority 2 (state tracking):
     move-to-status!, handle-log-access, apply-decay!
   Priority 3 (lineage):
     persist-promotion!, reground-entry!, handle-migrate-project

   Usage:
     (record-mutation! {:entry-id \"20260220...\"
                        :op :feedback
                        :data {:feedback \"helpful\" :new-count 5}
                        :previous-value {:helpful-count 4}
                        :agent-id \"ling-xyz\"
                        :project-id \"hive\"})

   Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
   SPDX-License-Identifier: AGPL-3.0-or-later"
  (:require [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.agent.context :as ctx]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))

;; =============================================================================
;; Mutation ID Generation
;; =============================================================================

(defn- gen-mutation-id
  "Generate a unique mutation event ID.
   Format: mut-yyyyMMddTHHmmss-XXXXXX"
  []
  (let [now (java.time.LocalDateTime/now)
        formatter (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss")
        timestamp (.format now formatter)
        random-hex (format "%06x" (rand-int 0xFFFFFF))]
    (str "mut-" timestamp "-" random-hex)))

;; =============================================================================
;; Core Temporal Write
;; =============================================================================

(def ^:private valid-ops
  "Valid mutation operation types."
  #{:feedback :kanban-done :kanban-move :kanban-delete :expire :decay
    :promote :reground :migrate :log-access :cleanup})

(def ^:private ephemeral-ops
  "Mutation ops NOT written to the durable trail (access telemetry; the signal
   already lives in the entry's :access-count counter)."
  #{:log-access})

(defn- persist-mutation?
  "True when `op` should be written to the durable audit trail."
  [op]
  (not (contains? ephemeral-ops op)))

(defn record-mutation!
  "Record a memory mutation event to Datahike for temporal tracking.

   Arguments:
     opts - Map with:
       :entry-id       - Memory entry ID that was mutated (required)
       :op             - Mutation operation keyword (required, from valid-ops)
       :data           - Mutation payload map (what changed)
       :previous-value - Previous state (for destructive ops)
       :agent-id       - Agent that triggered mutation (optional, auto-detected)
       :project-id     - Project scope (optional, auto-detected)

   Returns:
     {:ok true :mutation-id \"mut-...\"} on success
     {:ok false :error \"...\"} on failure (non-fatal, logs warning)

   Note: Failures are non-fatal — Chroma write already succeeded.
   This is a best-effort temporal trail, not a transaction coordinator."
  [{:keys [entry-id op data previous-value agent-id project-id]}]
  {:pre [(string? entry-id) (contains? valid-ops op)]}
  (try
    (when (and (persist-mutation? op) (kg-conn/temporal-store?))
      (let [mutation-id (gen-mutation-id)
            agent-id (or agent-id
                         (ctx/current-agent-id)
                         (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                         "unknown")
            project-id (or project-id "unknown")
            tx-data [(cond-> {:mem-mutation/id        mutation-id
                              :mem-mutation/entry-id  entry-id
                              :mem-mutation/op        op
                              :mem-mutation/timestamp (java.util.Date.)
                              :mem-mutation/agent-id  agent-id
                              :mem-mutation/project-id project-id}
                       data
                       (assoc :mem-mutation/data (pr-str data))

                       previous-value
                       (assoc :mem-mutation/previous-value (pr-str previous-value)))]]
        (kg-conn/transact! tx-data)
        (log/debug "Temporal mutation recorded" {:id mutation-id :op op :entry-id entry-id})
        {:ok true :mutation-id mutation-id}))
    (catch Exception e
      (log/warn "Temporal mutation recording failed (non-fatal)"
                {:op op :entry-id entry-id :error (.getMessage e)})
      {:ok false :error (.getMessage e)})))

(defn record-mutation-silent!
  "Like record-mutation! but swallows all errors completely.
   Use in hot paths where even logging overhead matters."
  [opts]
  (rescue nil (record-mutation! opts)))

;; =============================================================================
;; Batch Recording (for cleanup/decay cycles)
;; =============================================================================

(defn record-mutations-batch!
  "Record multiple mutation events in a single Datahike transaction.
   More efficient than individual record-mutation! calls for bulk operations.

   Arguments:
     mutations - Sequence of mutation option maps (same shape as record-mutation!)

   Returns:
     {:ok true :count N} on success
     {:ok false :error \"...\"} on failure"
  [mutations]
  (try
    (when (and (seq mutations) (kg-conn/temporal-store?))
      (let [now (java.util.Date.)
            agent-id (or (ctx/current-agent-id)
                         (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                         "unknown")
            tx-data (mapv (fn [{:keys [entry-id op data previous-value project-id]}]
                            (cond-> {:mem-mutation/id         (gen-mutation-id)
                                     :mem-mutation/entry-id   (or entry-id "unknown")
                                     :mem-mutation/op         (or op :unknown)
                                     :mem-mutation/timestamp  now
                                     :mem-mutation/agent-id   agent-id
                                     :mem-mutation/project-id (or project-id "unknown")}
                              data
                              (assoc :mem-mutation/data (pr-str data))
                              previous-value
                              (assoc :mem-mutation/previous-value (pr-str previous-value))))
                          (filter (comp persist-mutation? :op) mutations))]
        (kg-conn/transact! tx-data)
        (log/debug "Temporal batch recorded" {:count (count tx-data)})
        {:ok true :count (count tx-data)}))
    (catch Exception e
      (log/warn "Temporal batch recording failed (non-fatal)"
                {:count (count mutations) :error (.getMessage e)})
      {:ok false :error (.getMessage e)})))

;; =============================================================================
;; Query Helpers (for addon consumers)
;; =============================================================================

(defn query-mutations
  "Query mutation history for an entry ID.
   Returns sequence of mutation events sorted by timestamp.

   Arguments:
     entry-id - Memory entry ID to query history for
     opts     - Optional map with:
       :op    - Filter by operation type
       :limit - Max results (default: 50)
       :since - java.util.Date to filter mutations after

   Returns:
     Sequence of mutation maps, or nil if temporal store not available."
  [entry-id & [{:keys [op limit since]
                :or {limit 50}}]]
  (when (kg-conn/temporal-store?)
    (try
      (let [base-query (if op
                         '[:find [(pull ?e [*]) ...]
                           :in $ ?eid ?op
                           :where
                           [?e :mem-mutation/entry-id ?eid]
                           [?e :mem-mutation/op ?op]]
                         '[:find [(pull ?e [*]) ...]
                           :in $ ?eid
                           :where
                           [?e :mem-mutation/entry-id ?eid]])
            results (if op
                      (kg-conn/query base-query entry-id op)
                      (kg-conn/query base-query entry-id))
            filtered (if since
                       (filter #(pos? (compare (:mem-mutation/timestamp %) since)) results)
                       results)]
        (->> filtered
             (sort-by :mem-mutation/timestamp)
             (take limit)
             vec))
      (catch Exception e
        (log/warn "Mutation query failed" {:entry-id entry-id :error (.getMessage e)})
        nil))))

(defn mutation-count
  "Count total mutations for an entry or globally.

   Arguments:
     entry-id - Optional entry ID (nil = global count)

   Returns:
     Integer count, or 0 if temporal store unavailable."
  [& [entry-id]]
  (if-not (kg-conn/temporal-store?)
    0
    (try
      (if entry-id
        (or (first (first (kg-conn/query '[:find (count ?e)
                                           :in $ ?eid
                                           :where [?e :mem-mutation/entry-id ?eid]]
                                         entry-id)))
            0)
        (or (first (first (kg-conn/query '[:find (count ?e)
                                           :where [?e :mem-mutation/id _]])))
            0))
      (catch Exception _
        0))))

;; =============================================================================
;; Guarded Prune (heap-pressure-gated retention bound)
;; =============================================================================

(def ^:private default-retention
  "Retention bounds for prune-mutations!.
   :max-per-entry — keep at most this many newest events per entry-id.
   :max-age-ms    — age-out anything older than this TTL (default 30d).
   :batch-cap     — max retractions per invocation, so one pass cannot itself
                    spike heap."
  {:max-per-entry 20
   :max-age-ms    (* 1000 60 60 24 30)
   :batch-cap     500})

(defn- heap-pressure-level
  "Best-effort heap-pressure level -> :ok | :soft | :hard. Never throws.
   Prefers the canonical hive-knowledge mem-guard when its ns is on the
   classpath (live combined JVM); hive-mcp must NOT compile-depend on the
   addon, so it is resolved dynamically. Falls back to an inline Runtime heap
   fraction against the same default watermarks {:soft 0.80 :hard 0.92}."
  []
  (or
   (try
     (when-let [check (requiring-resolve 'hive-knowledge.cache.mem-guard/check)]
       (:level (check nil nil)))
     (catch Throwable _ nil))
   (try
     (let [rt   (Runtime/getRuntime)
           used (- (.totalMemory rt) (.freeMemory rt))
           mx   (.maxMemory rt)
           frac (if (pos? mx) (/ (double used) (double mx)) 0.0)]
       (cond (>= frac 0.92) :hard
             (>= frac 0.80) :soft
             :else          :ok))
     (catch Throwable _ :ok))))

(defn prune-mutations!
  "GUARDED prune of the :mem-mutation audit trail. Best-effort, non-fatal.

   Retracts mutation entities that exceed the retention bound:
     - older than :max-age-ms (TTL age-out), OR
     - beyond the newest :max-per-entry events for their entry-id.
   Retractions are capped at :batch-cap per call.

   Heap-gated: by default only runs when heap pressure is :soft/:hard
   (mem-guard). Pass :force? true for an unconditional cap-total sweep.

   noHistory caveat (forward-only): with :db/noHistory now on :mem-mutation/*
   (norm 006) these retractions reclaim datoms cleanly instead of accumulating
   retraction history — apply norm 006 BEFORE relying on this prune.

   CAVEAT: under :hard pressure this still materializes the whole trail via the
   query before retracting (:batch-cap bounds only the tx, not the query). For
   a very large trail, run an age-out-only/windowed pass first. (Follow-up.)

   Returns {:ok true :pruned N :level kw} or {:ok false :error \"...\"}."
  [& [{:keys [max-per-entry max-age-ms batch-cap force?]
       :or {max-per-entry (:max-per-entry default-retention)
            max-age-ms    (:max-age-ms default-retention)
            batch-cap     (:batch-cap default-retention)}}]]
  (try
    (if-not (kg-conn/temporal-store?)
      {:ok false :error "temporal store unavailable"}
      (let [level (heap-pressure-level)]
        (if (and (not force?) (= :ok level))
          {:ok true :pruned 0 :level level :skipped :no-pressure}
          (let [cutoff   (java.util.Date. (- (System/currentTimeMillis) (long max-age-ms)))
                rows     (kg-conn/query '[:find ?e ?eid ?ts
                                          :where
                                          [?e :mem-mutation/id _]
                                          [?e :mem-mutation/entry-id ?eid]
                                          [?e :mem-mutation/timestamp ?ts]])
                aged     (filter (fn [[_ _ ts]] (neg? (compare ts cutoff))) rows)
                overflow (mapcat (fn [[_ es]]
                                   (->> es
                                        (sort-by #(nth % 2))
                                        (drop-last max-per-entry)))
                                 (group-by second rows))
                victims  (->> (concat aged overflow)
                              (map first)
                              distinct
                              (take batch-cap)
                              vec)]
            (when (seq victims)
              (kg-conn/transact! (mapv (fn [eid] [:db/retractEntity eid]) victims)))
            (log/info "Pruned :mem-mutation trail"
                      {:pruned (count victims) :level level :force? (boolean force?)})
            {:ok true :pruned (count victims) :level level}))))
    (catch Exception e
      (log/warn "Mutation prune failed (non-fatal)" {:error (.getMessage e)})
      {:ok false :error (.getMessage e)})))
