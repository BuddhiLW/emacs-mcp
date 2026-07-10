;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.events.handlers.saa-config-test
  "Tests for hive-mcp.events.handlers.saa-config defconfig surface.

   Covers:
   - defconfig-tests auto-generated properties (totality, defaults, roundtrip, mutations)
   - Default agent id resolves when env unset
   - CLAUDE_SWARM_SLAVE_ID env var resolves to typed value
   - Overrides beat env vars
   - effective-agent-id coalesce semantics"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.testing :refer [defconfig-tests]]
            [hive-dsl.result :as r]
            [hive-mcp.events.handlers.saa-config :as saa-config
             :refer [SAAHandlerConfig-fields resolve-SAAHandlerConfig]]))

;; ============================================================================
;; Auto-generated property tests (hive-di.testing)
;; ============================================================================

(defconfig-tests SAAHandlerConfig SAAHandlerConfig-fields :num-tests 50)

;; ============================================================================
;; Explicit semantic tests
;; ============================================================================

(deftest defaults-resolve-when-env-empty
  (testing "Default 'unknown-agent' resolves when no env, no overrides"
    (let [result (resolve-SAAHandlerConfig {} {:env-fn (constantly nil)})]
      (is (r/ok? result))
      (is (= "unknown-agent" (-> result :ok :slave-agent-id))))))

(deftest env-var-resolves-to-typed-value
  (testing "CLAUDE_SWARM_SLAVE_ID read from env-fn"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" "swarm-ling-42"}
          result (resolve-SAAHandlerConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (= "swarm-ling-42" (-> result :ok :slave-agent-id))))))

(deftest overrides-beat-env-vars
  (testing "Explicit overrides win over env-fn lookup"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" "from-env"}
          result (resolve-SAAHandlerConfig {:slave-agent-id "from-override"}
                                           {:env-fn env})]
      (is (r/ok? result))
      (is (= "from-override" (-> result :ok :slave-agent-id))))))

(deftest blank-env-falls-back-to-default
  (testing "CLAUDE_SWARM_SLAVE_ID=\"\" treated as unset, default applies"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" ""}
          result (resolve-SAAHandlerConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (= "unknown-agent" (-> result :ok :slave-agent-id))
          "blank->nil normalization, then default"))))

;; ============================================================================
;; Helper fn tests
;; ============================================================================

(deftest slave-agent-id-non-empty
  (testing "slave-agent-id always returns a non-empty string"
    (let [id (saa-config/slave-agent-id)]
      (is (string? id))
      (is (seq id)))))

(deftest effective-agent-id-prefers-arg
  (testing "effective-agent-id returns explicit caller-arg when provided"
    (is (= "explicit" (saa-config/effective-agent-id "explicit"))
        "explicit non-nil arg wins over resolved config")))

(deftest effective-agent-id-falls-back-to-config
  (testing "effective-agent-id falls back to slave-agent-id when arg nil"
    (let [resolved (saa-config/slave-agent-id)]
      (is (= resolved (saa-config/effective-agent-id nil))
          "nil caller-arg → resolved config value"))))
