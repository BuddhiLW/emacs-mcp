(ns hive-mcp.knowledge-graph.store.protocol-isp-test
  "ISP regression tests for IKGStore / IPersistentKGStore split.

   Enforces AXIOM 'Never NUKE Data — Destruction Requires Explicit, Loud,
   Guarded Consent': the destructive `delete-database!` lives on a separate
   protocol that ephemeral backends MUST NOT satisfy."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.knowledge-graph.store.datascript :as ds-store]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest datascript-does-not-satisfy-persistent-protocol-test
  (testing "DataScriptStore (ephemeral) MUST NOT satisfy IPersistentKGStore"
    (let [store (ds-store/create-store)]
      (is (kg/kg-store? store)
          "DataScript satisfies IKGStore (core graph ops)")
      (is (not (kg/persistent-store? store))
          "DataScript does NOT satisfy IPersistentKGStore — destruction is meaningless on an in-memory backend"))))

(deftest delete-database-not-callable-on-ephemeral-test
  (testing "delete-database! cannot be invoked on a DataScriptStore"
    (let [store (ds-store/create-store)]
      ;; Direct protocol invocation: throws "No implementation" because
      ;; DataScriptStore does not extend IPersistentKGStore.
      (is (thrown? IllegalArgumentException
                   (kg/delete-database! store :i-mean-it))))))
