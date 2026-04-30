(ns hive-mcp.tools.memory.batch-contract-test
  "Binds `*batchable-factory*` to `make-memory-runner` and re-runs the
   shared Batchable contract suite from `hive-mcp.batch.contract-test`."
  (:require [clojure.test :refer [deftest testing]]
            [hive-mcp.batch.contract-test :as contract]
            [hive-mcp.tools.memory.batch :as mbatch]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest memory-runner-satisfies-batchable-contract
  (testing "hive-mcp.tools.memory.batch/make-memory-runner honours the Batchable contract"
    (binding [contract/*batchable-factory* mbatch/make-memory-runner]
      (#'contract/returns-result-shape)
      (#'contract/never-throws)
      (#'contract/success-matches-summary)
      (#'contract/batch-schema-returns-properties-map))))
