(ns hive-mcp.agent.ling.terminal-addon-property-test
  "Property-based tests for terminal addon registry and strategy adapter.

   Tests verify invariants of:
   - resolve-terminal-strategy: totality (nil for unregistered)
   - register-terminal! + resolve-terminal-strategy: round-trip
   - deregister-terminal!: totality, removes entries
   - register-terminal!: rejects non-protocol objects
   - registered-terminals: returns keyword set
   - TerminalAddonStrategy: rescue wrapping on non-critical paths
   - TerminalAddonStrategy: exception propagation on critical paths
   - TerminalAddonStrategy: happy path delegation"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.generators.core :as gen-core]
            [hive-test.properties :as props]
            [hive-mcp.agent.ling.terminal-registry :as registry]
            [hive-mcp.agent.ling.terminal-addon-strategy :as tas]
            [hive-mcp.agent.ling.strategy :as strategy]
            [hive-mcp.addons.terminal :as terminal]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each clean-registry-fixture)

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-terminal-id
  "Generator for terminal ID keywords."
  (gen/fmap keyword gen-core/gen-non-blank-string))

;; =============================================================================
;; Mock ITerminalAddon
;; =============================================================================

(defn mock-terminal-addon
  "Create a mock ITerminalAddon with configurable behavior.
   Options:
   - :id — terminal ID keyword (default :mock)
   - :throw-on — set of methods that throw (e.g., #{:spawn! :dispatch!})"
  [& {:keys [id throw-on] :or {id :mock throw-on #{}}}]
  (reify
    terminal/ITerminalAddon
    (terminal-id [_] id)
    (terminal-spawn! [_ ctx _opts]
      (if (:spawn! throw-on)
        (throw (ex-info "spawn failed" {:id (:id ctx)}))
        (str "slave-" (:id ctx))))
    (terminal-dispatch! [_ ctx _task-opts]
      (if (:dispatch! throw-on)
        (throw (ex-info "dispatch failed" {:id (:id ctx)}))
        true))
    (terminal-status [_ ctx ds-status]
      (if (:status throw-on)
        (throw (ex-info "status failed" {:id (:id ctx)}))
        (merge {:slave/id (:id ctx) :slave/status :idle} ds-status)))
    (terminal-kill! [_ ctx]
      (if (:kill! throw-on)
        (throw (ex-info "kill failed" {:id (:id ctx)}))
        {:killed? true :id (:id ctx)}))
    (terminal-interrupt! [_ ctx]
      (if (:interrupt! throw-on)
        (throw (ex-info "interrupt failed" {:id (:id ctx)}))
        {:success? true :ling-id (:id ctx)}))))

;; =============================================================================
;; P1: resolve-terminal-strategy — totality (nil for unregistered)
;; =============================================================================

(props/defprop-total p1-resolve-strategy-totality
  registry/resolve-terminal-strategy gen-terminal-id
  {:pred nil?})

(deftest p1-resolve-strategy-nil-for-unregistered
  (testing "Returns nil for any unregistered keyword"
    (is (nil? (registry/resolve-terminal-strategy :nonexistent)))
    (is (nil? (registry/resolve-terminal-strategy :vterm)))
    (is (nil? (registry/resolve-terminal-strategy :headless)))))

;; =============================================================================
;; P2: register-terminal! + resolve-terminal-strategy round-trip
;; =============================================================================

(defspec p2-register-resolve-round-trip 100
  (prop/for-all [term-id gen-terminal-id]
                (let [addon (mock-terminal-addon :id term-id)
                      reg-result (registry/register-terminal! term-id addon)
                      strat (registry/resolve-terminal-strategy term-id)]
                  (and (:registered? reg-result)
                       (some? strat)
                       (satisfies? strategy/ILingStrategy strat)))))

(deftest p2-register-resolve-known-inputs
  (testing "Register and resolve returns ILingStrategy"
    (let [addon (mock-terminal-addon :id :test-vterm)
          result (registry/register-terminal! :test-vterm addon)]
      (is (:registered? result))
      (is (= :test-vterm (:terminal-id result)))
      (let [strat (registry/resolve-terminal-strategy :test-vterm)]
        (is (some? strat))
        (is (satisfies? strategy/ILingStrategy strat))))))

;; =============================================================================
;; P3: deregister-terminal! — totality, removes entries
;; =============================================================================

(props/defprop-total p3-deregister-totality
  registry/deregister-terminal! gen-terminal-id
  {:pred map?})

(deftest p3-deregister-removes-entry
  (testing "Deregister removes a previously registered terminal"
    (let [addon (mock-terminal-addon :id :removable)]
      (registry/register-terminal! :removable addon)
      (is (some? (registry/resolve-terminal-strategy :removable)))
      (let [result (registry/deregister-terminal! :removable)]
        (is (:deregistered? result))
        (is (nil? (registry/resolve-terminal-strategy :removable)))))))

(deftest p3-deregister-no-op-for-unregistered
  (testing "Deregister is no-op for unregistered terminal"
    (let [result (registry/deregister-terminal! :never-registered)]
      (is (not (:deregistered? result))))))

;; =============================================================================
;; P4: register-terminal! rejects non-protocol objects
;; =============================================================================

(deftest p4-register-rejects-non-protocol
  (testing "Register returns error for non-ITerminalAddon objects"
    (let [result (registry/register-terminal! :bad "not-an-addon")]
      (is (not (:registered? result)))
      (is (seq (:errors result)))))
  (testing "Register returns error for nil"
    (let [result (registry/register-terminal! :bad nil)]
      (is (not (:registered? result)))))
  (testing "Register returns error for plain map"
    (let [result (registry/register-terminal! :bad {:some "map"})]
      (is (not (:registered? result))))))

;; =============================================================================
;; P5: registered-terminals returns keyword set
;; =============================================================================

(deftest p5-registered-terminals-is-set
  (testing "Returns empty set when nothing registered"
    (is (= #{} (registry/registered-terminals))))
  (testing "Returns set of registered terminal IDs"
    (registry/register-terminal! :alpha (mock-terminal-addon :id :alpha))
    (registry/register-terminal! :beta (mock-terminal-addon :id :beta))
    (let [terminals (registry/registered-terminals)]
      (is (set? terminals))
      (is (contains? terminals :alpha))
      (is (contains? terminals :beta)))))

;; =============================================================================
;; P6: TerminalAddonStrategy — rescue wrapping on non-critical paths
;; =============================================================================

(deftest p6-strategy-rescue-on-status
  (testing "status returns fallback when addon throws"
    (let [addon (mock-terminal-addon :throw-on #{:status})
          strat (tas/->terminal-addon-strategy addon)
          ctx {:id "ling-1"}
          ds-status {:slave/status :busy}
          result (strategy/strategy-status strat ctx ds-status)]
      (is (map? result))
      (is (:terminal-addon-error? result)))))

(deftest p6-strategy-rescue-on-kill
  (testing "kill returns fallback when addon throws"
    (let [addon (mock-terminal-addon :throw-on #{:kill!})
          strat (tas/->terminal-addon-strategy addon)
          ctx {:id "ling-1"}
          result (strategy/strategy-kill! strat ctx)]
      (is (map? result))
      (is (not (:killed? result))))))

(deftest p6-strategy-rescue-on-interrupt
  (testing "interrupt returns fallback when addon throws"
    (let [addon (mock-terminal-addon :throw-on #{:interrupt!})
          strat (tas/->terminal-addon-strategy addon)
          ctx {:id "ling-1"}
          result (strategy/strategy-interrupt! strat ctx)]
      (is (map? result))
      (is (not (:success? result)))
      (is (seq (:errors result))))))

;; =============================================================================
;; P7: TerminalAddonStrategy — critical paths propagate exceptions
;; =============================================================================

(deftest p7-strategy-spawn-propagates
  (testing "spawn! throws when addon throws"
    (let [addon (mock-terminal-addon :throw-on #{:spawn!})
          strat (tas/->terminal-addon-strategy addon)
          ctx {:id "ling-1"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (strategy/strategy-spawn! strat ctx {}))))))

(deftest p7-strategy-dispatch-propagates
  (testing "dispatch! throws when addon throws"
    (let [addon (mock-terminal-addon :throw-on #{:dispatch!})
          strat (tas/->terminal-addon-strategy addon)
          ctx {:id "ling-1"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (strategy/strategy-dispatch! strat ctx {:task "test"}))))))

;; =============================================================================
;; P8: TerminalAddonStrategy — happy path delegation
;; =============================================================================

(deftest p8-strategy-happy-path-spawn
  (testing "spawn! delegates and returns slave-id"
    (let [addon (mock-terminal-addon :id :happy)
          strat (tas/->terminal-addon-strategy addon)
          result (strategy/strategy-spawn! strat {:id "ling-1"} {})]
      (is (= "slave-ling-1" result)))))

(deftest p8-strategy-happy-path-dispatch
  (testing "dispatch! delegates and returns true"
    (let [addon (mock-terminal-addon :id :happy)
          strat (tas/->terminal-addon-strategy addon)
          result (strategy/strategy-dispatch! strat {:id "ling-1"} {:task "run tests"})]
      (is (true? result)))))

(deftest p8-strategy-happy-path-status
  (testing "status returns the addon's answer verbatim on the happy path"
    (let [addon (mock-terminal-addon :id :happy)
          strat (tas/->terminal-addon-strategy addon)
          ctx {:id "ling-1"}
          ds-status {:slave/status :busy}
          result (strategy/strategy-status strat ctx ds-status)]
      (is (map? result))
      ;; The adapter owns delegation, not merge policy — which of the addon's
      ;; defaults and the DS snapshot wins is the ADDON's contract. Assert the
      ;; adapter passes both through untouched instead of pinning the mock.
      (is (= (terminal/terminal-status addon ctx ds-status) result)
          "adapter returns exactly what the addon returned")
      (is (= "ling-1" (:slave/id result))
          "addon-supplied keys survive")
      (is (= :busy (:slave/status ds-status))
          "the ds-status argument is not mutated")
      (is (not (:terminal-addon-error? result))
          "the happy path must not tag an addon error"))))

(deftest p8-strategy-happy-path-kill
  (testing "kill! delegates and returns success map"
    (let [addon (mock-terminal-addon :id :happy)
          strat (tas/->terminal-addon-strategy addon)
          result (strategy/strategy-kill! strat {:id "ling-1"})]
      (is (:killed? result)))))

(deftest p8-strategy-happy-path-interrupt
  (testing "interrupt! delegates and returns success map"
    (let [addon (mock-terminal-addon :id :happy)
          strat (tas/->terminal-addon-strategy addon)
          result (strategy/strategy-interrupt! strat {:id "ling-1"})]
      (is (:success? result)))))
