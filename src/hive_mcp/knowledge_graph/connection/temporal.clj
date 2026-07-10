(ns hive-mcp.knowledge-graph.connection.temporal
  "Temporal (time-travel) query facade over the active KG store.

   Datahike exposes history / as-of / since DB values; DataScript and
   Datalevin do not. Every fn here returns nil — and `temporal-store?`
   returns false — when the active store is non-temporal."
  (:require [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.connection.store :as store]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn temporal-store?
  "Check if the current store supports temporal queries (time-travel).
   Returns true for Datahike, false for DataScript/Datalevin.

   Use this to guard temporal query calls in application code."
  []
  (proto/temporal-store? (store/ensure-store!)))

(defn history-db
  "Get a database containing all historical facts.

   Returns a DB value that includes retracted datoms, enabling
   queries over the complete history of the store.

   Returns nil if the store does not support temporal queries.

   Example:
     (when (temporal-store?)
       (query '[:find ?e ?attr ?v ?added
                :where [?e ?attr ?v _ ?added]]
              (history-db)))"
  []
  (let [s (store/ensure-store!)]
    (when (proto/temporal-store? s)
      (proto/history-db s))))

(defn as-of-db
  "Get the database as of a specific point in time.

   Arguments:
     tx-or-time - Transaction ID (integer) or java.util.Date timestamp

   Returns a DB value representing the state at that point,
   or nil if the store does not support temporal queries.

   Example:
     ;; Query state from 1 hour ago
     (as-of-db (java.util.Date. (- (System/currentTimeMillis) 3600000)))"
  [tx-or-time]
  (let [s (store/ensure-store!)]
    (when (proto/temporal-store? s)
      (proto/as-of-db s tx-or-time))))

(defn since-db
  "Get a database containing only facts added since a point in time.

   Arguments:
     tx-or-time - Transaction ID (integer) or java.util.Date timestamp

   Returns a DB value with only facts added after that point,
   or nil if the store does not support temporal queries.

   Useful for incremental change tracking and sync operations."
  [tx-or-time]
  (let [s (store/ensure-store!)]
    (when (proto/temporal-store? s)
      (proto/since-db s tx-or-time))))

(defn- datahike-q
  "Apply datahike.api/q (resolved at call time) to query `q` against db
   value `db`, threading optional `inputs` after the db."
  [q db inputs]
  (let [q-fn (requiring-resolve 'datahike.api/q)]
    (if (seq inputs)
      (apply q-fn q db inputs)
      (q-fn q db))))

(defn query-history
  "Query against the full history database.

   Arguments:
     q      - Datalog query
     inputs - Optional additional query inputs

   Returns query results against history DB, enabling queries
   that span all historical states (including retracted facts).

   Returns nil if the store does not support temporal queries.

   Example:
     ;; Find all values an attribute ever had
     (query-history '[:find ?v ?added
                      :in $ ?e ?attr
                      :where [?e ?attr ?v _ ?added]]
                    [:kg-edge/id \"some-id\"] :kg-edge/weight)"
  [q & inputs]
  (when-let [hdb (history-db)]
    (datahike-q q hdb inputs)))

(defn query-as-of
  "Query the database as it was at a specific point in time.

   Arguments:
     tx-or-time - Transaction ID (integer) or java.util.Date timestamp
     q          - Datalog query
     inputs     - Optional additional query inputs

   Returns query results from the point-in-time snapshot,
   or nil if the store does not support temporal queries.

   Example:
     ;; What edges existed yesterday?
     (query-as-of yesterday
                  '[:find ?id
                    :where [?e :kg-edge/id ?id]])"
  [tx-or-time q & inputs]
  (when-let [aodb (as-of-db tx-or-time)]
    (datahike-q q aodb inputs)))
