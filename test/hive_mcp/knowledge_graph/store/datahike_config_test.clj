;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.store.datahike-config-test
  "Verify DatahikeKGConfig honors env > config.edn > default chain.

   Regression: pre-fix, :db-path was hardcoded to default-db-path and
   the config.edn `:services :datahike :path` key was silently ignored —
   contributing factor to the 2026-04-28 live-KG-wipe incident."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.resolve :as resolve]
            [hive-mcp.knowledge-graph.store.datahike-config :as dhc]
            [hive-dsl.result :as r]))

(def file-with-services
  (constantly {:services {:datahike {:path "/from/file/path"
                                     :id   "abcd1234-5678-90ab-cdef-1234567890ab"}}}))

(deftest db-path-env-wins
  (testing "HIVE_KG_DB_PATH env var wins over config.edn"
    (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                   {:env-fn (fn [v]
                              (case v
                                "HIVE_KG_DB_PATH" "/from/env"
                                nil))
                    :file-fn file-with-services})]
      (is (r/ok? result))
      (is (= "/from/env" (:db-path (:ok result)))))))

(deftest db-path-from-config-edn-when-env-unset
  (testing ":services :datahike :path config.edn key honored"
    (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                   {:env-fn (constantly nil)
                    :file-fn file-with-services})]
      (is (r/ok? result))
      (is (= "/from/file/path" (:db-path (:ok result)))))))

(deftest db-path-default-when-nothing-set
  (testing "Hardcoded default applies only when env + config.edn both empty"
    (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                   {:env-fn (constantly nil)
                    :file-fn (constantly nil)})]
      (is (r/ok? result))
      (is (= "data/kg/datahike" (:db-path (:ok result)))))))

(deftest store-id-optional
  (testing "store-id is :required false; resolves nil when unset everywhere"
    (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                   {:env-fn (constantly nil)
                    :file-fn (constantly nil)})]
      (is (r/ok? result))
      (is (nil? (:store-id (:ok result)))))))

(deftest store-id-from-config-edn
  (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                 {:env-fn (constantly nil)
                  :file-fn file-with-services})]
    (is (r/ok? result))
    (is (= "abcd1234-5678-90ab-cdef-1234567890ab" (:store-id (:ok result))))))

(deftest backend-default-is-file
  (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                 {:env-fn (constantly nil)})]
    (is (r/ok? result))
    (is (= :file (:backend (:ok result))))))

(deftest backend-env-coerced-to-keyword
  (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields {}
                 {:env-fn (fn [v]
                            (case v
                              "HIVE_KG_DH_BACKEND" "memory"
                              nil))})]
    (is (r/ok? result))
    (is (= :memory (:backend (:ok result))))))

(deftest override-trumps-all
  (testing "Caller-passed override wins over env, config.edn, defaults"
    (let [result (resolve/resolve-config dhc/DatahikeKGConfig-fields
                   {:db-path "/from/override"}
                   {:env-fn (constantly "/from/env")
                    :file-fn file-with-services})]
      (is (r/ok? result))
      (is (= "/from/override" (:db-path (:ok result)))))))
