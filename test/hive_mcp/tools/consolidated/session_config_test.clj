(ns hive-mcp.tools.consolidated.session-config-test
  "Tests for hive-mcp.tools.consolidated.session-config defconfig surface.

   Covers:
   - defconfig-tests auto-generated properties (totality, defaults, roundtrip, mutations)
   - Defaults resolve when env unset (slave-id is optional, nil OK)
   - Env var resolves to typed value
   - Overrides beat env var lookup
   - Blank env normalization (\"\" -> nil)"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-di.testing :refer [defconfig-tests]]
            [hive-dsl.result :as r]
            [hive-mcp.tools.consolidated.session-config :as config
             :refer [SessionToolConfig-fields resolve-SessionToolConfig]]))

;; ============================================================================
;; Auto-generated property tests (hive-di.testing)
;; ============================================================================

(defconfig-tests SessionToolConfig SessionToolConfig-fields :num-tests 50)

;; ============================================================================
;; Explicit semantic tests
;; ============================================================================

(deftest defaults-resolve-when-env-empty
  (testing "Optional field resolves to nil when no env, no overrides"
    (let [result (resolve-SessionToolConfig {} {:env-fn (constantly nil)})]
      (is (r/ok? result))
      (is (nil? (-> result :ok :swarm-slave-id))
          "swarm-slave-id has no default and is :required false -> nil"))))

(deftest env-var-resolves-to-typed-value
  (testing "CLAUDE_SWARM_SLAVE_ID is read from env-fn"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" "ling-7"}
          result (resolve-SessionToolConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (= "ling-7" (-> result :ok :swarm-slave-id))))))

(deftest overrides-beat-env-vars
  (testing "Explicit overrides win over env-fn lookup"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" "from-env"}
          result (resolve-SessionToolConfig {:swarm-slave-id "from-override"}
                                            {:env-fn env})]
      (is (r/ok? result))
      (is (= "from-override" (-> result :ok :swarm-slave-id))))))

(deftest blank-env-normalizes-to-nil
  (testing "CLAUDE_SWARM_SLAVE_ID=\"\" treated as unset (optional -> nil)"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" ""}
          result (resolve-SessionToolConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (nil? (-> result :ok :swarm-slave-id))
          "blank->nil normalization, no default, optional -> nil"))))

;; ============================================================================
;; Helper fn tests
;; ============================================================================

(deftest swarm-slave-id-helper
  (testing "swarm-slave-id helper returns string-or-nil"
    (let [v (config/swarm-slave-id)]
      (is (or (nil? v) (string? v))))))

(deftest resolve!-returns-map
  (testing "resolve! returns the resolved map directly (not Result)"
    (let [m (config/resolve! {})]
      (is (map? m))
      (is (contains? m :swarm-slave-id)))))
