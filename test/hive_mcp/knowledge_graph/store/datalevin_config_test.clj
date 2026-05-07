;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.datalevin-config-test
  "Verify DatalevinKGConfig honors env > config.edn > default chain.

   Mirrors datahike-config-test.clj so the migration leaves no resolution
   asymmetry: an operator who relocates the Datahike DB via
   :services :datahike :path expects the same surface for Datalevin."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.resolve :as resolve]
            [hive-mcp.knowledge-graph.store.datalevin-config :as dlc]
            [hive-dsl.result :as r]))

(def file-with-services
  (constantly {:services {:datalevin {:path "/from/file/path"}}}))

(deftest db-path-env-wins
  (testing "HIVE_KG_DATALEVIN_PATH env var wins over config.edn"
    (let [result (resolve/resolve-config dlc/DatalevinKGConfig-fields {}
                   {:env-fn (fn [v]
                              (case v
                                "HIVE_KG_DATALEVIN_PATH" "/from/env"
                                nil))
                    :file-fn file-with-services})]
      (is (r/ok? result))
      (is (= "/from/env" (:db-path (:ok result)))))))

(deftest db-path-from-config-edn-when-env-unset
  (testing ":services :datalevin :path config.edn key honored"
    (let [result (resolve/resolve-config dlc/DatalevinKGConfig-fields {}
                   {:env-fn (constantly nil)
                    :file-fn file-with-services})]
      (is (r/ok? result))
      (is (= "/from/file/path" (:db-path (:ok result)))))))

(deftest db-path-default-when-nothing-set
  (testing "XDG default applies only when env + config.edn both empty"
    (let [result (resolve/resolve-config dlc/DatalevinKGConfig-fields {}
                   {:env-fn (constantly nil)
                    :file-fn (constantly nil)})]
      (is (r/ok? result))
      (is (= dlc/default-db-path (:db-path (:ok result)))))))

(deftest override-trumps-all
  (testing "Caller-passed override wins over env, config.edn, defaults"
    (let [result (resolve/resolve-config dlc/DatalevinKGConfig-fields
                   {:db-path "/from/override"}
                   {:env-fn (constantly "/from/env")
                    :file-fn file-with-services})]
      (is (r/ok? result))
      (is (= "/from/override" (:db-path (:ok result)))))))
