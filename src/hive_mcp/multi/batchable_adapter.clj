(ns hive-mcp.multi.batchable-adapter
  "DefaultBatchableAdapter — LSP-clean fallback Batchable for any tool that
   does NOT ship an explicit Batchable record.

   Delegates per-op execution to `hive-mcp.batch/run-operations`, which
   resolves each op's handler via the supplied `:resolve-handler` fn and
   collects results in the standard `{:success :waves :summary}` shape.

   Substitutability guarantee: a tool with an explicit Batchable record
   (e.g. MemoryBatchable that issues a single Datahike tx) must produce
   the same external shape as the default adapter. LSP property test in
   batchable_adapter_test enforces this.

   Decision: 20260429230453-7e7627cc"
  (:require [hive-mcp.batch :as batch]
            [hive-mcp.batch.protocol :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord DefaultBatchableAdapter [tool-name resolve-handler emit-fx]
  proto/Batchable
  (batch-execute [_ ops opts]
    (batch/run-operations
     ops
     (merge {:resolve-handler resolve-handler
             :emit-fx         (or emit-fx (fn [& _] nil))}
            opts)))
  (batch-schema [_]
    {:type "object"
     :properties {:operations {:type "array"}
                  :dry_run    {:type "boolean"}}}))

(defn make-default-adapter
  "Construct a DefaultBatchableAdapter bound to the given resolver and
   optional emit-fx callback."
  ([tool-name resolve-handler]
   (->DefaultBatchableAdapter tool-name resolve-handler nil))
  ([tool-name resolve-handler emit-fx]
   (->DefaultBatchableAdapter tool-name resolve-handler emit-fx)))
