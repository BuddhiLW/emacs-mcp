(ns hive-mcp.agent.type-registry-test
  (:require [clojure.test :refer [deftest is testing are]]
            [hive-mcp.agent.type-registry :as atr]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry structure
;; =============================================================================

(deftest registry-has-all-variants
  (testing "Registry contains exactly the 3 sum type variants"
    (is (= #{:coordinator :ling :drone} atr/all-types))
    (is (= 3 (count atr/registry)))))

(deftest registry-preserves-order
  (testing "array-map preserves insertion order (coordinator > ling > drone)"
    (is (= [:coordinator :ling :drone] (keys atr/registry)))))

(deftest every-variant-has-required-keys
  (testing "All variants have the required metadata keys"
    (let [required-keys #{:description :depth :spawn-modes :capabilities
                          :permissions :slot-limit :model-tier :mcp? :can-chain?
                          :readiness}]
      (doseq [[variant-key variant-val] atr/registry]
        (testing (str "Variant " variant-key)
          (doseq [rk required-keys]
            (is (contains? variant-val rk)
                (str variant-key " missing key " rk))))))))

;; =============================================================================
;; Derived views
;; =============================================================================

(deftest all-types-derived
  (is (= #{:coordinator :ling :drone} atr/all-types)))

(deftest all-type-strings-derived
  (is (= #{"coordinator" "ling" "drone"} atr/all-type-strings)))

(deftest mcp-types-excludes-coordinator
  (testing "MCP enum only includes spawnable types (not coordinator)"
    (is (not (some #{"coordinator"} atr/mcp-types)))
    (is (some #{"ling"} atr/mcp-types))
    (is (some #{"drone"} atr/mcp-types))))

(deftest type->depth-mapping
  (is (= {:coordinator 0 :ling 1 :drone 2} atr/type->depth)))

(deftest depth->type-mapping
  (is (= {0 :coordinator 1 :ling 2 :drone} atr/depth->type)))

(deftest type->capabilities-not-empty
  (doseq [[k caps] atr/type->capabilities]
    (testing (str k " has capabilities")
      (is (set? caps))
      (is (pos? (count caps))))))

(deftest type->permissions-structure
  (doseq [[k perms] atr/type->permissions]
    (testing (str k " has permissions map")
      (is (map? perms))
      (is (contains? perms :can-spawn?))
      (is (contains? perms :can-delegate?))
      (is (contains? perms :can-kill?)))))

;; =============================================================================
;; Validation functions
;; =============================================================================

(deftest valid-type?-test
  (testing "Keywords"
    (is (true? (atr/valid-type? :ling)))
    (is (true? (atr/valid-type? :drone)))
    (is (true? (atr/valid-type? :coordinator)))
    (is (false? (atr/valid-type? :invalid)))
    (is (false? (atr/valid-type? :hivemind))))
  (testing "Strings"
    (is (true? (atr/valid-type? "ling")))
    (is (true? (atr/valid-type? "drone")))
    (is (true? (atr/valid-type? "coordinator")))
    (is (false? (atr/valid-type? "invalid"))))
  (testing "Nil returns false"
    (is (false? (atr/valid-type? nil)))))

(deftest type-depth-test
  (is (= 0 (atr/type-depth :coordinator)))
  (is (= 1 (atr/type-depth :ling)))
  (is (= 2 (atr/type-depth :drone)))
  (testing "Unknown type defaults to 2 (drone depth)"
    (is (= 2 (atr/type-depth :unknown)))))

(deftest depth->agent-type-test
  (is (= :coordinator (atr/depth->agent-type 0)))
  (is (= :ling (atr/depth->agent-type 1)))
  (is (= :drone (atr/depth->agent-type 2)))
  (testing "Depth 3+ defaults to drone"
    (is (= :drone (atr/depth->agent-type 3)))
    (is (= :drone (atr/depth->agent-type 99)))))

;; =============================================================================
;; Spawn modes
;; =============================================================================

(deftest spawnable?-test
  (is (false? (atr/spawnable? :coordinator)))
  (is (true? (atr/spawnable? :ling)))
  (is (true? (atr/spawnable? :drone))))

(deftest valid-spawn-mode?-test
  (testing "Ling spawn modes"
    (is (true? (atr/valid-spawn-mode? :ling :claude)))
    (is (true? (atr/valid-spawn-mode? :ling :vterm)))
    (is (true? (atr/valid-spawn-mode? :ling :headless)))
    (is (true? (atr/valid-spawn-mode? :ling :agent-sdk)))
    (is (false? (atr/valid-spawn-mode? :ling :openrouter))
        ":openrouter is a provider, not a spawn-mode"))
  (testing "Drone spawn modes"
    (is (true? (atr/valid-spawn-mode? :drone :headless)))
    (is (false? (atr/valid-spawn-mode? :drone :openrouter))
        ":openrouter is no longer a drone spawn-mode (it's a provider)")
    (is (false? (atr/valid-spawn-mode? :drone :claude)))
    (is (false? (atr/valid-spawn-mode? :drone :vterm)))
    (is (false? (atr/valid-spawn-mode? :drone :agent-sdk))))
  (testing "Coordinator has no spawn modes"
    (is (false? (atr/valid-spawn-mode? :coordinator :claude))))
  (testing "Addon-contributed mode is accepted via registry fallback"
    (let [reg-mode! (requiring-resolve 'hive-mcp.agent.spawn-mode-registry/register-mode!)
          dereg!    (requiring-resolve 'hive-mcp.agent.spawn-mode-registry/deregister-mode!)]
      (reg-mode! :test-addon-mode
                 {:description "test" :requires-emacs? false :io-model :api
                  :slot-limit nil :mcp? true :alias-of nil
                  :capabilities #{:dispatch :kill}})
      (try
        (is (true? (atr/valid-spawn-mode? :ling :test-addon-mode))
            "Ling accepts addon-contributed modes")
        (is (true? (atr/valid-spawn-mode? :drone :test-addon-mode))
            "Drone accepts addon-contributed modes")
        (finally
          (dereg! :test-addon-mode))))))

;; =============================================================================
;; Capabilities and permissions
;; =============================================================================

(deftest has-capability?-test
  (testing "Coordinator capabilities"
    (is (true? (atr/has-capability? :coordinator :spawn)))
    (is (true? (atr/has-capability? :coordinator :kill)))
    (is (true? (atr/has-capability? :coordinator :broadcast))))
  (testing "Ling capabilities"
    (is (true? (atr/has-capability? :ling :read)))
    (is (true? (atr/has-capability? :ling :write)))
    (is (true? (atr/has-capability? :ling :delegate)))
    (is (false? (atr/has-capability? :ling :spawn)))
    (is (false? (atr/has-capability? :ling :kill))))
  (testing "Drone capabilities"
    (is (true? (atr/has-capability? :drone :read)))
    (is (true? (atr/has-capability? :drone :propose-diff)))
    (is (false? (atr/has-capability? :drone :write)))
    (is (false? (atr/has-capability? :drone :delegate)))))

(deftest has-permission?-test
  (testing "Coordinator permissions"
    (is (true? (atr/has-permission? :coordinator :can-spawn?)))
    (is (true? (atr/has-permission? :coordinator :can-approve-diffs?))))
  (testing "Ling anti-cascade: cannot spawn"
    (is (false? (atr/has-permission? :ling :can-spawn?)))
    (is (true? (atr/has-permission? :ling :can-delegate?))))
  (testing "Drone: most restricted"
    (is (false? (atr/has-permission? :drone :can-spawn?)))
    (is (false? (atr/has-permission? :drone :can-delegate?)))
    (is (false? (atr/has-permission? :drone :can-kill?)))))

;; =============================================================================
;; Tool chaining
;; =============================================================================

(deftest can-chain-tools?-test
  (is (true? (atr/can-chain-tools? :coordinator)))
  (is (true? (atr/can-chain-tools? :ling)))
  (is (false? (atr/can-chain-tools? :drone)))
  (testing "Unknown type defaults to false"
    (is (false? (atr/can-chain-tools? :unknown)))))

;; =============================================================================
;; Slot limits
;; =============================================================================

(deftest slot-limit-test
  (is (= 1 (atr/slot-limit :coordinator)))
  (is (= 6 (atr/slot-limit :ling)))
  (is (nil? (atr/slot-limit :drone))))

;; =============================================================================
;; Model tier defaults
;; =============================================================================

(deftest default-model-tier-test
  (is (= :premium (atr/default-model-tier :coordinator)))
  (is (= :standard (atr/default-model-tier :ling)))
  (is (= :economy (atr/default-model-tier :drone)))
  (testing "Unknown defaults to :standard"
    (is (= :standard (atr/default-model-tier :unknown)))))

;; =============================================================================
;; MCP enum
;; =============================================================================

(deftest mcp-enum-test
  (let [enums (atr/mcp-enum)]
    (is (vector? enums))
    (is (every? string? enums))
    (is (some #{"ling"} enums))
    (is (some #{"drone"} enums))
    (is (not (some #{"coordinator"} enums)))))

;; =============================================================================
;; Describe
;; =============================================================================

(deftest describe-test
  (is (string? (atr/describe :coordinator)))
  (is (string? (atr/describe :ling)))
  (is (string? (atr/describe :drone)))
  (is (nil? (atr/describe :nonexistent))))
