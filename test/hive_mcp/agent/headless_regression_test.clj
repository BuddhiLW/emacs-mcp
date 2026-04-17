(ns hive-mcp.agent.headless-regression-test
  "Regression tests for :headless spawn-mode routing.

   Bug: :headless lings silently errored on spawn because:
   1. hive-agent init! never ran → no :hive-agent backend in registry
   2. best-headless-for-provider returned nil
   3. resolve-effective-mode silently returned literal :headless
   4. resolve-strategy threw 'No strategy registered' — caught silently
   5. Result: agent status=error, zero hivemind messages

   Fix:
   - Extract pure pick-backend-for-provider (testable without atom)
   - resolve-effective-mode throws ex-info with actionable diagnostic
     when :headless requested but no backend available."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.agent.ling.headless-registry :as hr]))

;; =============================================================================
;; Trifecta: pure pick-backend-for-provider
;; =============================================================================

(deftrifecta pick-backend-for-provider-cases
  hive-mcp.agent.ling.headless-registry/pick-backend-for-provider
  {:golden-path "test/golden/agent/pick-backend-for-provider.edn"
   :apply?      true
   :cases       {:empty-claude             [#{} :claude]
                 :hive-agent-only          [#{:hive-agent} :claude]
                 :claude-sdk-only          [#{:claude-sdk} :claude]
                 :claude-process-only      [#{:claude-process} :claude]
                 :hive-agent-preferred     [#{:hive-agent :claude-sdk :claude-process} :claude]
                 :claude-sdk-over-process  [#{:claude-sdk :claude-process} :claude]
                 :unknown-provider         [#{:hive-agent} :openai]}
   :gen         (gen/tuple
                  (gen/set (gen/elements [:hive-agent :claude-sdk :claude-process]))
                  (gen/return :claude))
   :pred        (fn [result] (or (nil? result) (keyword? result)))
   :num-tests   100
   :mutations   [["always-nil"        (fn [_ _] nil)]
                 ["ignores-preference" (fn [reg _] (first reg))]]})

;; =============================================================================
;; resolve-effective-mode behavior with mocked registry
;; =============================================================================

(defn- with-registered
  "Run f with headless-registry mocked to contain given backend IDs."
  [backend-ids f]
  (with-redefs [hr/registered-headless (fn [] (set backend-ids))]
    (f)))

(deftest headless-resolves-to-hive-agent-when-registered
  (testing ":headless + claude model + :hive-agent registered → :hive-agent"
    (with-registered [:hive-agent]
      #(is (= :hive-agent
              (ling/resolve-effective-mode {:spawn-mode :headless :model "claude"}))))))

(deftest headless-prefers-hive-agent-over-claude-sdk
  (testing "both registered → :hive-agent wins"
    (with-registered [:hive-agent :claude-sdk]
      #(is (= :hive-agent
              (ling/resolve-effective-mode {:spawn-mode :headless :model "claude"}))))))

(deftest headless-falls-back-to-claude-sdk-when-no-hive-agent
  (testing "only :claude-sdk registered → :claude-sdk"
    (with-registered [:claude-sdk]
      #(is (= :claude-sdk
              (ling/resolve-effective-mode {:spawn-mode :headless :model "claude"}))))))

(deftest headless-throws-when-no-backends-registered
  (testing ":headless + empty registry → throws ex-info with actionable info"
    (with-registered []
      #(let [ex (try (ling/resolve-effective-mode {:spawn-mode :headless :model "claude"})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
         (is (some? ex) "Must throw (regression: used to silently return :headless literal)")
         (when ex
           (is (= :headless/no-backend-registered (:type (ex-data ex))))
           (is (contains? (ex-data ex) :registered))
           (is (contains? (ex-data ex) :provider)))))))
