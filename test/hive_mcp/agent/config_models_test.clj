(ns hive-mcp.agent.config-models-test
  "Tests for config-driven model resolution.

   Verifies that agent/config.clj, agent/routing.clj, hive_agent_bridge.clj,
   and drone/backend/hive_agent.clj correctly read model defaults from
   hive-mcp.config.core's :models key."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.config.core :as config]
            [hive-mcp.config.source :as src]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

;; These tests assert what `default-config` DECLARES. Reading through the
;; process-wide atom made them assert whatever the developer last wrote to
;; ~/.config/hive-mcp/config.edn — on this machine that is a single :testing
;; routing entry and a local drone model, so every structural assertion failed
;; for reasons that had nothing to do with the code.
(use-fixtures :each
  (fn [f]
    (binding [config/*config-source*
              (src/atom-source (atom config/default-config) config/default-config)]
      (f))))

(defn- with-config-override
  "Execute f with a temporary config override at the given dotted key path.
   Restores original value after f completes."
  [key-path override-value f]
  (let [config-atom (src/-config-atom config/*config-source*)
        original    (config/get-config-value key-path)]
    (try
      (swap! config-atom assoc-in
             (config/parse-key-path key-path) override-value)
      (f)
      (finally
        (swap! config-atom assoc-in
               (config/parse-key-path key-path) original)))))

;; =============================================================================
;; config.clj — :models key exists in defaults
;; =============================================================================

(deftest test-default-config-has-models-key
  (testing ":models key exists in default-config"
    (is (some? (config/get-config-value "models")))
    (is (map? (config/get-config-value "models")))))

(deftest test-default-config-models-structure
  (testing ":models has :task-models, :routing, :default-model"
    (is (some? (config/get-config-value "models.task-models")))
    (is (some? (config/get-config-value "models.routing")))
    (is (some? (config/get-config-value "models.default-model")))))

(deftest test-default-config-task-models-keys
  (testing ":models.task-models has :coding, :coding-alt, :arch, :docs"
    (let [tm (config/get-config-value "models.task-models")]
      (is (contains? tm :coding))
      (is (contains? tm :coding-alt))
      (is (contains? tm :arch))
      (is (contains? tm :docs))
      (is (string? (:coding tm))))))

(deftest test-default-config-routing-keys
  (testing ":models.routing has all 6 task types"
    (let [routing (config/get-config-value "models.routing")]
      (is (contains? routing :testing))
      (is (contains? routing :refactoring))
      (is (contains? routing :implementation))
      (is (contains? routing :bugfix))
      (is (contains? routing :documentation))
      (is (contains? routing :general))
      ;; Each route has :primary and :secondary
      (doseq [[_task-type route] routing]
        (is (string? (:primary route)))
        (is (string? (:secondary route)))))))

;; =============================================================================
;; agent/config.clj — load-task-models reads from config
;; =============================================================================

(deftest test-agent-config-load-task-models
  (testing "load-task-models reads from config.edn :models.task-models"
    (require 'hive-mcp.agent.config :reload)
    (let [load-fn (requiring-resolve 'hive-mcp.agent.config/load-task-models)]
      ;; load-task-models is private, test via requiring-resolve or var deref
      (when-not load-fn
        ;; Try var access for private fn
        (let [load-fn* @(ns-resolve 'hive-mcp.agent.config 'load-task-models)]
          (is (some? load-fn*) "load-task-models should exist")
          (when load-fn*
            (let [result (load-fn*)]
              (is (map? result))
              (is (contains? result :coding)))))))))

(deftest test-agent-config-override-from-config
  (testing "Overriding models.task-models in config changes load-task-models output"
    (require 'hive-mcp.agent.config :reload)
    (let [load-fn @(ns-resolve 'hive-mcp.agent.config 'load-task-models)]
      (with-config-override "models.task-models"
        {:coding "test/override-coding"
         :arch "test/override-arch"}
        (fn []
          (let [result (load-fn)]
            (is (= "test/override-coding" (:coding result)))
            (is (= "test/override-arch" (:arch result)))))))))

;; =============================================================================
;; agent/routing.clj — load-model-routes reads from config
;; =============================================================================

(deftest test-routing-load-model-routes
  (testing "load-model-routes reads from config.edn :models.routing"
    (require 'hive-mcp.agent.routing :reload)
    (let [load-fn @(ns-resolve 'hive-mcp.agent.routing 'load-model-routes)]
      (is (some? load-fn) "load-model-routes should exist")
      (when load-fn
        (let [result (load-fn)]
          (is (map? result))
          (is (contains? result :testing))
          ;; Check :reason strings are injected
          (is (string? (get-in result [:testing :reason]))))))))

(deftest test-routing-override-from-config
  (testing "Overriding models.routing in config changes load-model-routes output"
    (require 'hive-mcp.agent.routing :reload)
    (let [load-fn @(ns-resolve 'hive-mcp.agent.routing 'load-model-routes)]
      (with-config-override "models.routing.testing.primary" "test/custom-primary"
        (fn []
          (let [result (load-fn)
                testing-route (get result :testing)]
            (is (= "test/custom-primary" (:primary testing-route)))
            ;; :reason should still be injected from code
            (is (string? (:reason testing-route)))))))))

;; =============================================================================
;; drone default model reads from config
;; =============================================================================

(deftest test-drone-default-model-resolves-through-the-service-key
  (testing "default-drone-model reads services.drone.default-model"
    ;; The drone path resolves services.drone.default-model (env
    ;; DRONE_DEFAULT_MODEL, fallback "devstral-small:24b"), NOT
    ;; models.default-model. This suite once asserted the latter, so it could
    ;; only pass on a machine whose drone service happened to be unset AND
    ;; whose models key happened to say kimi.
    (let [baseline (config/default-drone-model)]
      (with-config-override "services.drone.default-model" "test/bridge-model"
        (fn []
          (is (= "test/bridge-model" (config/default-drone-model))
              "override of the drone service key takes effect")))
      (is (= baseline (config/default-drone-model))
          "override is restored"))))
