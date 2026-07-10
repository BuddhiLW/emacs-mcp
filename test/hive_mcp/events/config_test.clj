(ns hive-mcp.events.config-test
  "Tests for hive-mcp.events.config defconfig surface.

   Covers:
   - defconfig-tests auto-generated properties (totality, defaults, roundtrip, mutations)
   - Defaults resolve when env unset
   - Env vars resolve to typed values (incl. :depth :int coercion)
   - Overrides beat env vars
   - Runtime override layer (configure-metrics! back-compat)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-di.testing :refer [defconfig-tests]]
            [hive-dsl.result :as r]
            [hive-mcp.events.config :as config
             :refer [EventsMetricsConfig-fields
                     EventsAgentConfig-fields
                     resolve-EventsMetricsConfig
                     resolve-EventsAgentConfig]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ============================================================================
;; Auto-generated property tests (hive-di.testing)
;; ============================================================================

(defconfig-tests EventsMetricsConfig EventsMetricsConfig-fields :num-tests 50)
(defconfig-tests EventsAgentConfig   EventsAgentConfig-fields   :num-tests 50)

;; ============================================================================
;; Fixture: clear runtime overrides between tests
;; ============================================================================

(defn clear-overrides-fixture [f]
  (config/clear-metrics-overrides!)
  (f)
  (config/clear-metrics-overrides!))

(use-fixtures :each clear-overrides-fixture)

;; ============================================================================
;; EventsMetricsConfig — explicit semantic tests
;; ============================================================================

(deftest metrics-defaults-resolve-when-env-empty
  (testing "Defaults produce valid config when no env, no overrides"
    (let [result (resolve-EventsMetricsConfig {} {:env-fn (constantly nil)})]
      (is (r/ok? result))
      (is (= 1000 (-> result :ok :max-timings)))
      (is (= 200  (-> result :ok :max-timings-per-type))))))

(deftest metrics-env-vars-resolve-to-typed-values
  (testing "HIVE_EVENTS_METRICS_* env vars coerce to :int"
    (let [env    {"HIVE_EVENTS_METRICS_MAX_TIMINGS"          "5000"
                  "HIVE_EVENTS_METRICS_MAX_TIMINGS_PER_TYPE" "777"}
          result (resolve-EventsMetricsConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (= 5000 (-> result :ok :max-timings)))
      (is (= 777  (-> result :ok :max-timings-per-type))))))

(deftest metrics-overrides-beat-env-vars
  (testing "Explicit overrides win over env-fn lookup"
    (let [env    {"HIVE_EVENTS_METRICS_MAX_TIMINGS" "999"}
          result (resolve-EventsMetricsConfig {:max-timings 7} {:env-fn env})]
      (is (r/ok? result))
      (is (= 7 (-> result :ok :max-timings))))))

(deftest metrics-blank-env-falls-back-to-default
  (testing "blank string env value treated as unset, default applies"
    (let [env    {"HIVE_EVENTS_METRICS_MAX_TIMINGS" ""}
          result (resolve-EventsMetricsConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (= 1000 (-> result :ok :max-timings))))))

;; ============================================================================
;; EventsAgentConfig — explicit semantic tests
;; ============================================================================

(deftest agent-defaults-when-env-empty
  (testing "All fields nil when env empty (all :required false)"
    (let [result (resolve-EventsAgentConfig {} {:env-fn (constantly nil)})]
      (is (r/ok? result))
      (is (nil? (-> result :ok :agent-id)))
      (is (nil? (-> result :ok :parent-id)))
      (is (nil? (-> result :ok :depth)))
      (is (nil? (-> result :ok :role))))))

(deftest agent-env-vars-resolve-to-typed-values
  (testing "CLAUDE_SWARM_* env vars resolve, depth coerces to :int"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID"  "ling-42"
                  "CLAUDE_SWARM_PARENT_ID" "coordinator"
                  "CLAUDE_SWARM_DEPTH"     "3"
                  "CLAUDE_SWARM_ROLE"      "drone"}
          result (resolve-EventsAgentConfig {} {:env-fn env})]
      (is (r/ok? result))
      (is (= "ling-42"     (-> result :ok :agent-id)))
      (is (= "coordinator" (-> result :ok :parent-id)))
      (is (= 3             (-> result :ok :depth)))
      (is (= "drone"       (-> result :ok :role))))))

(deftest agent-overrides-beat-env-vars
  (testing "Explicit overrides win over env-fn"
    (let [env    {"CLAUDE_SWARM_SLAVE_ID" "from-env"}
          result (resolve-EventsAgentConfig {:agent-id "from-override"}
                                            {:env-fn env})]
      (is (r/ok? result))
      (is (= "from-override" (-> result :ok :agent-id))))))

;; ============================================================================
;; Resolver helper fns
;; ============================================================================

(deftest metrics-config-fn-uses-defaults
  (testing "(metrics-config) returns defaults when no env nor overrides"
    (let [m (config/metrics-config)]
      (is (= 1000 (:max-timings m)))
      (is (= 200  (:max-timings-per-type m))))))

(deftest metrics-config-fn-honors-runtime-overrides
  (testing "set-metrics-override! merges into resolver result"
    (config/set-metrics-override! {:max-timings 50})
    (let [m (config/metrics-config)]
      (is (= 50  (:max-timings m)))
      (is (= 200 (:max-timings-per-type m)))
      "untouched fields keep defaults")))

(deftest metrics-config-fn-honors-caller-overrides
  (testing "Per-call overrides win over runtime overrides"
    (config/set-metrics-override! {:max-timings 50})
    (let [m (config/metrics-config {:max-timings 7})]
      (is (= 7 (:max-timings m))
          "caller arg beats runtime override"))))

(deftest clear-metrics-overrides-resets
  (testing "clear-metrics-overrides! drops runtime layer"
    (config/set-metrics-override! {:max-timings 50})
    (config/clear-metrics-overrides!)
    (is (= 1000 (:max-timings (config/metrics-config))))))

(deftest with-redefs-on-resolver
  (testing "Tests can override the resolver entirely via with-redefs"
    (with-redefs [config/metrics-config (constantly {:max-timings 5
                                                     :max-timings-per-type 3})]
      (let [m (config/metrics-config)]
        (is (= 5 (:max-timings m)))
        (is (= 3 (:max-timings-per-type m)))))))

(deftest agent-config-fn-defaults-to-nil-fields
  (testing "(agent-config) under empty env returns map of nil values"
    ;; Note: this reads real env. We only assert keys exist; values may be
    ;; non-nil if the test runner happens to be a swarm ling.
    (let [a (config/agent-config)]
      (is (contains? a :agent-id))
      (is (contains? a :parent-id))
      (is (contains? a :depth))
      (is (contains? a :role)))))
