(ns hive-mcp.saa.registry-contract-test
  "W2 contract suite: the SAA registry façade + four child registries + core-seed.

   C1  register-by-key! then resolve returns it; same-owner re-register is an
       idempotent replace; cross-owner second write keeps the first (+ warns).
   C6  LSP — every resolver satisfies its protocol with ZERO addons.
   C7  resolve-tools is provider-scoped: provider A's slice != provider B's;
       :saa/core neutral fallback when a provider has no slice.
   C10 no cross-owner clobber: register owner X then owner Y; deregister X
       leaves Y intact."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.saa.registry :as registry]
            [hive-mcp.saa.registry.phase-providers :as r-providers]
            [hive-mcp.saa.registry.scorers :as r-scorers]
            [hive-mcp.saa.registry.tool-intents :as r-intents]
            [hive-mcp.saa.core-seed :as core-seed]
            [hive-mcp.saa.types :as types]
            [hive-mcp.saa.support :as support]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each support/with-fresh-registry)

;; =============================================================================
;; core-seed — :saa/core populated before any addon arrives
;; =============================================================================

(deftest core-seed-populates-all-registries
  (testing "install! seeds one provider/scorer/planner and the neutral intents"
    (let [{:keys [providers scorers planners tool-intents]} (core-seed/install!)]
      (is (= 1 providers))
      (is (= 1 scorers))
      (is (= 1 planners))
      (is (= 5 tool-intents) "neutral DEFAULT intents: read search web write exec"))))

;; =============================================================================
;; C1 — register-by-key! round-trip + conflict/replace semantics
;; =============================================================================

(deftest c1-register-by-key-then-resolve
  (testing "a tool-intent registered via register-by-key! resolves back out"
    (let [out (registry/register-by-key!
               :addon-a :saa/tool-intent
               [(types/saa-registry-entry
                 :saa/tool-intent {:intent :read :tools ["addon-a-read"] :owner :addon-a})])]
      (is (= [:ok] out))
      (is (some #{"addon-a-read"} (registry/resolve-tools :addon-a :read))
          "the addon's slice is unioned into resolve-tools"))))

(deftest c1-register-by-key-ignores-non-entry
  (testing "a non-SaaRegistryEntry value is ignored, not registered"
    (is (= [:ignored]
           (registry/register-by-key! :addon-a :saa/scorer [{:not "an entry"}])))))

(deftest c1-same-owner-reregister-is-replace
  (testing "same owner + same id re-register is an idempotent :replaced"
    (r-scorers/reset-for-test!)
    (is (= :ok       (r-scorers/register! :owner-x :sid {:scorer :A})))
    (is (= :replaced (r-scorers/register! :owner-x :sid {:scorer :A2})))
    (is (= :A2 (:scorer (r-scorers/lookup :sid))) "replace swapped the value")))

(deftest c1-cross-owner-write-keeps-first
  (testing "cross-owner second write is :conflict and first-write-wins"
    (r-scorers/reset-for-test!)
    (is (= :ok       (r-scorers/register! :owner-x :sid {:scorer :A})))
    (is (= :conflict (r-scorers/register! :owner-y :sid {:scorer :B})))
    (is (= :A     (:scorer (r-scorers/lookup :sid))) "first owner's value retained")
    (is (= :owner-x (:owner (r-scorers/lookup :sid))) "first owner retained")))

;; =============================================================================
;; C6 — LSP: every resolver satisfies its protocol with ZERO addons
;; =============================================================================

(deftest c6-resolvers-always-satisfy-protocol
  (testing "lookup-*-or-default never returns nil and always satisfies its protocol"
    ;; :saa/core is the only owner present (fixture); no addon registered.
    (is (satisfies? psaa/IPhaseProvider
                    (registry/lookup-phase-provider-or-default :nonexistent)))
    (is (satisfies? psaa/IObservationScorer
                    (registry/lookup-scorer-or-default :nonexistent)))
    (is (satisfies? psaa/IPlanSynthesizer
                    (registry/lookup-planner-or-default :nonexistent)))))

(deftest c6-resolvers-zero-arg-arity-satisfies
  (testing "the zero-arg resolver arity returns the boot-seeded default"
    (is (satisfies? psaa/IPhaseProvider    (registry/lookup-phase-provider-or-default)))
    (is (satisfies? psaa/IObservationScorer (registry/lookup-scorer-or-default)))
    (is (satisfies? psaa/IPlanSynthesizer   (registry/lookup-planner-or-default)))))

(defspec c6-resolvers-lsp-for-any-missing-id 50
  (prop/for-all [id gen/keyword]
    (and (satisfies? psaa/IPhaseProvider    (registry/lookup-phase-provider-or-default id))
         (satisfies? psaa/IObservationScorer (registry/lookup-scorer-or-default id))
         (satisfies? psaa/IPlanSynthesizer   (registry/lookup-planner-or-default id)))))

;; =============================================================================
;; C7 — resolve-tools is provider-scoped + :saa/core fallback
;; =============================================================================

(deftest c7-resolve-tools-provider-scoped
  (testing "provider A's slice differs from provider B's; core fallback when absent"
    (r-intents/register! :prov-a :read {:tools ["a-only"]})
    (r-intents/register! :prov-b :read {:tools ["b-only"]})
    (let [a    (registry/resolve-tools :prov-a :read)
          b    (registry/resolve-tools :prov-b :read)
          none (registry/resolve-tools :prov-with-no-slice :read)
          core (registry/resolve-tools :saa/core :read)]
      (is (not= a b) "distinct providers resolve distinct tool sets")
      (is (some #{"a-only"} a))
      (is (not (some #{"b-only"} a)) "provider A never sees provider B's slice")
      (is (some #{"b-only"} b))
      (is (= core none)
          "a provider with no slice falls back to exactly the :saa/core neutral set")
      (is (= a (vec (sort a))) "resolve-tools returns a sorted vector"))))

(deftest c7-resolve-tools-unions-core-with-provider
  (testing "a provider slice is unioned with the :saa/core neutral fallback"
    (r-intents/register! :prov-c :read {:tools ["c-extra"]})
    (let [resolved (registry/resolve-tools :prov-c :read)]
      (is (some #{"c-extra"} resolved) "provider slice present")
      (is (some #{"read"} resolved) ":saa/core neutral token present"))))

;; =============================================================================
;; C10 — no cross-owner clobber across deregister
;; =============================================================================

(deftest c10-deregister-owner-leaves-other-owner-intact
  (testing "phase-providers: deregister X removes only X, Y survives"
    (r-providers/reset-for-test!)
    (r-providers/register! :owner-x :pid-x {:provider :X})
    (r-providers/register! :owner-y :pid-y {:provider :Y})
    (is (= #{:pid-x} (r-providers/deregister-by-owner! :owner-x)))
    (is (nil? (r-providers/lookup :pid-x)) "X's entry removed")
    (is (= :Y (:provider (r-providers/lookup :pid-y))) "Y's entry intact")))

(deftest c10-tool-intent-deregister-removes-only-owner-slice
  (testing "tool-intents: deregister X removes only X's slice, Y + core survive"
    (r-intents/register! :owner-x :read {:tools ["x-tool"]})
    (r-intents/register! :owner-y :read {:tools ["y-tool"]})
    (r-intents/deregister-by-owner! :owner-x)
    (is (nil? (r-intents/lookup-owner-slice :owner-x :read)) "X slice gone")
    (is (= #{"y-tool"} (r-intents/lookup-owner-slice :owner-y :read)) "Y slice intact")
    (is (some #{"read"} (:tools (r-intents/lookup :read)))
        ":saa/core neutral slice survives an addon deregister")))

(deftest c10-facade-deregister-by-owner-scoped
  (testing "façade deregister-by-owner! never clobbers the :saa/core seed"
    (r-scorers/register! :addon-z :saa/scorer {:scorer :Z})
    (registry/deregister-by-owner! :addon-z)
    (is (satisfies? psaa/IObservationScorer
                    (registry/lookup-scorer-or-default))
        ":saa/core scorer still backs the default after an addon deregister")))
