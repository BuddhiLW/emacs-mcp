(ns hive-mcp.knowledge-graph.connection.strategy
  (:require [hive-dsl.batch :as dsl-batch]
            [hive-dsl.result :as r]
            [hive-mcp.knowledge-graph.connection.writer :as writer]
            [hive-mcp.knowledge-graph.protocol :as proto]))

(defprotocol IWriteStrategy
  "One discipline for applying tx-data to the KG store. SRP: each record owns a
   single write mode; OCP: add a mode by adding a record, not by editing
   `transact!`."
  (apply-tx! [strategy tx-data]
    "Apply tx-data under this strategy. Returns nil. Throws when a required
     backend is unavailable so callers never receive a false success."))

(defrecord BatchAccumulator [batch-atom]
  IWriteStrategy
  (apply-tx! [_ tx-data]
    (swap! batch-atom into (dsl-batch/normalize-tx-datum tx-data))))

(defrecord SyncWriter [store]
  IWriteStrategy
  (apply-tx! [_ tx-data]
    (r/rescue nil
              (proto/transact! store (dsl-batch/normalize-tx-datum tx-data)))))

(defrecord CoalescingWriter [store]
  IWriteStrategy
  (apply-tx! [_ tx-data]
    (writer/ensure-writer!)
    (when-not (writer/offer-coalesced! tx-data)
      (writer/write-sync-fallback! store tx-data))))

(defn select-strategy
  "Return the IWriteStrategy for the given write context. Pure factory: the
   effectful reads (dynamic vars, store resolution) happen in the caller and
   arrive as arguments. `ensure-store-fn` is called eagerly for store-backed
   modes so an unavailable backend surfaces at the call site, not in the
   async flush."
  [batch-atom sync? ensure-store-fn]
  (cond
    batch-atom (->BatchAccumulator batch-atom)
    sync?      (->SyncWriter (ensure-store-fn))
    :else      (->CoalescingWriter (ensure-store-fn))))

(defn assert-edge-node-ids!
  "Reject tx-data containing a KG edge datom whose :kg-edge/from or
   :kg-edge/to is not a string. A non-string edge node id crashes the
   datahike AVET writer thread; rejecting it synchronously keeps the
   writer alive and surfaces a catchable error to the caller."
  [tx-data]
  (when (sequential? tx-data)
    (doseq [d tx-data
            :when (map? d)]
      (when (or (contains? d :kg-edge/from)
                (contains? d :kg-edge/to))
        (let [from (:kg-edge/from d)
              to   (:kg-edge/to d)]
          (when-not (and (string? from) (string? to))
            (throw (ex-info "Poison KG edge datom rejected: :kg-edge/from and :kg-edge/to must be strings"
                            {:kg-edge/from from :kg-edge/to to}))))))))
