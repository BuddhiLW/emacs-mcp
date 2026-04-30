(ns hive-mcp.tools.kg.batch-contract-test
  "Binds `*batchable-factory*` to `make-kg-runner` and re-runs the shared
   Batchable contract suite from `hive-mcp.batch.contract-test`."
  (:require [clojure.test :refer [deftest testing]]
            [hive-mcp.batch.contract-test :as contract]
            [hive-mcp.tools.kg.batch :as kgbatch]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest kg-runner-satisfies-batchable-contract
  (testing "hive-mcp.tools.kg.batch/make-kg-runner honours the Batchable contract"
    (binding [contract/*batchable-factory* kgbatch/make-kg-runner]
      (#'contract/returns-result-shape)
      (#'contract/never-throws)
      (#'contract/success-matches-summary)
      (#'contract/batch-schema-returns-properties-map))))
