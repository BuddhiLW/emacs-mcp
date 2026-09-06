(ns hive-mcp.backends.knowledge-graph.store.datalevin-contract-test
  "Datalevin application of the backend-agnostic IKGStore contract.

   Binds the ONE shared suite (store.contract) to the Datalevin driver via its
   StoreFactory. Runs only under the :test-backends alias with the
   hive-datalevin sibling on the classpath (supplied by local.deps.edn); when
   the driver is absent the suite skips and passes vacuously.

   Datalevin-SPECIFIC behaviour (concurrent-transact linearizability) is
   asserted in store.datalevin-concurrent-test, not here."
  (:require [clojure.test :refer [deftest]]
            [hive-mcp.knowledge-graph.store.contract :as contract]
            [hive-mcp.knowledge-graph.store.harness :as harness]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest datalevin-ikgstore-contract
  (contract/kg-store-contract-tests
   (harness/datalevin-factory)
   :label "datalevin"))
