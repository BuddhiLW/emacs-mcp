(ns hive-mcp.knowledge-graph.connection-trifecta-test
  "Trifecta coverage for the pure write-strategy layer extracted from
   connection.clj into connection.strategy (SLAP-KGCONN L2): the
   `select-strategy` dispatcher and the `assert-edge-node-ids!` poison-datom
   guard. Both are pure (input -> value / input -> throw), the ideal
   golden + property + mutation targets, and they lock the behavior of the
   forms relocated by the L2 extraction.

   First run must seed the golden snapshots:
     UPDATE_GOLDEN=true clj -M:test
   then commit test/golden/hive-mcp/connection-*.edn so the golden-derived
   mutation oracle has its baseline."
  (:require [clojure.test.check.generators :as gen]
            [hive-mcp.knowledge-graph.connection.strategy :as strategy]
            [hive-test.trifecta :refer [deftrifecta]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Adapters — trifecta's single-input shape over the strategy fns.
;; -----------------------------------------------------------------------------

(defn run-select-strategy
  "Classify the IWriteStrategy `select-strategy` returns for a write context.
   `ensure-store-fn` is a sentinel — select-strategy only calls it to construct
   the store-backed records, whose TYPE (not store) is the observable."
  [{:keys [batch? sync?]}]
  (let [batch-atom (when batch? (atom []))
        record     (strategy/select-strategy batch-atom sync? (constantly ::store))]
    (.getSimpleName (type record))))

(defn run-assert-edge
  "`:ok` when assert-edge-node-ids! accepts tx-data, `:rejected` when it throws
   the poison-datom guard (non-string :kg-edge/from|:kg-edge/to)."
  [tx-data]
  (try
    (strategy/assert-edge-node-ids! tx-data)
    :ok
    (catch clojure.lang.ExceptionInfo _ :rejected)))

;; -----------------------------------------------------------------------------
;; Predicates
;; -----------------------------------------------------------------------------

(def ^:private strategy-names
  #{"BatchAccumulator" "SyncWriter" "CoalescingWriter"})

(defn strategy-name? [s] (contains? strategy-names s))

(defn assert-result? [k] (contains? #{:ok :rejected} k))

;; -----------------------------------------------------------------------------
;; Generators
;; -----------------------------------------------------------------------------

(def ^:private gen-write-context
  (gen/hash-map :batch? gen/boolean :sync? gen/boolean))

(def ^:private gen-node-id
  ;; Strings pass the guard; integers are poison — mix so both branches fire.
  (gen/one-of [(gen/not-empty gen/string-alphanumeric) gen/small-integer]))

(def ^:private gen-edge-datum
  (gen/hash-map :kg-edge/from gen-node-id :kg-edge/to gen-node-id))

(def ^:private gen-tx-data
  (gen/vector gen-edge-datum 0 5))

;; -----------------------------------------------------------------------------
;; Trifecta: select-strategy — pure dispatch (batch > sync > coalesce)
;; -----------------------------------------------------------------------------

(deftrifecta select-strategy-dispatch
  hive-mcp.knowledge-graph.connection-trifecta-test/run-select-strategy
  {:golden-path "test/golden/hive-mcp/connection-select-strategy.edn"
   :cases       {:batch            {:batch? true  :sync? false}
                 :batch-precedence {:batch? true  :sync? true}
                 :sync             {:batch? false :sync? true}
                 :coalesce         {:batch? false :sync? false}}
   :gen         gen-write-context
   :pred        strategy-name?
   :num-tests   200
   :mutations   [["always-batch"    (fn [_] "BatchAccumulator")]
                 ["always-coalesce" (fn [_] "CoalescingWriter")]
                 ["ignore-batch"    (fn [{:keys [sync?]}]
                                      (if sync? "SyncWriter" "CoalescingWriter"))]]})

;; -----------------------------------------------------------------------------
;; Trifecta: assert-edge-node-ids! — poison-datom guard
;; -----------------------------------------------------------------------------

(deftrifecta assert-edge-guard
  hive-mcp.knowledge-graph.connection-trifecta-test/run-assert-edge
  {:golden-path "test/golden/hive-mcp/connection-assert-edge.edn"
   :cases       {:valid-edge [{:kg-edge/from "a" :kg-edge/to "b"}]
                 :poison-from [{:kg-edge/from 1 :kg-edge/to "b"}]
                 :poison-to   [{:kg-edge/from "a" :kg-edge/to 2}]
                 :non-edge    [{:some/attr "x"}]
                 :retract-vec [[:db/add "e" :kg-edge/from "x"]]
                 :non-seq     {:not "sequential"}}
   :gen         gen-tx-data
   :pred        assert-result?
   :apply?      false
   :num-tests   200
   :mutations   [["always-ok"       (fn [_] :ok)]
                 ["always-rejected" (fn [_] :rejected)]
                 ["ignore-to"       (fn [tx-data]
                                      ;; Only guards :kg-edge/from — misses poison :to.
                                      (if (and (sequential? tx-data)
                                               (some #(and (map? %)
                                                           (contains? % :kg-edge/from)
                                                           (not (string? (:kg-edge/from %))))
                                                     tx-data))
                                        :rejected
                                        :ok))]]})
