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
