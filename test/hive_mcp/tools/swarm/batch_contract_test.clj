(ns hive-mcp.tools.swarm.batch-contract-test
  "Binds `*batchable-factory*` to `make-swarm-runner` and re-runs the
   shared Batchable contract suite from `hive-mcp.batch.contract-test`."
  (:require [clojure.test :refer [deftest testing]]
            [hive-mcp.batch.contract-test :as contract]
            [hive-mcp.tools.swarm.batch :as sbatch]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest swarm-runner-satisfies-batchable-contract
  (testing "hive-mcp.tools.swarm.batch/make-swarm-runner honours the Batchable contract"
    (binding [contract/*batchable-factory* sbatch/make-swarm-runner]
      (#'contract/returns-result-shape)
      (#'contract/never-throws)
      (#'contract/success-matches-summary)
      (#'contract/batch-schema-returns-properties-map))))
