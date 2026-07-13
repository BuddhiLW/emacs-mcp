(ns hive-mcp.knowledge-graph.store.datahike-contract-test
  "Datahike application of the backend-agnostic IKGStore contract.

   Binds the ONE shared suite (store.contract) to the Datahike driver via its
   StoreFactory. Runs only under the :test-backends alias with the
   hive-datahike sibling on the classpath (supplied by local.deps.edn); when
   the driver is absent the suite skips and passes vacuously.

   Datahike-SPECIFIC behaviour (temporal history/as-of/since, non-destructive
   reset, on-disk delete guards) is asserted in store.datahike-test, not here."
  (:require [clojure.test :refer [deftest]]
            [hive-mcp.knowledge-graph.store.contract :as contract]
            [hive-mcp.knowledge-graph.store.harness :as harness]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest datahike-ikgstore-contract
  (contract/kg-store-contract-tests
   (harness/datahike-factory)
   :label "datahike"))
