(ns hive-mcp.saa.orchestrator-live-path-test
  "W3 contract suite (C13): the live SAA path works addon-free.

   With ONLY the :saa/core seed (no addon providers), ->saa-orchestrator
   resolves DefaultPhaseProvider / DefaultObservationScorer / NoopPlanSynthesizer
   from the registry (LSP), and run-silence!/run-abstract!/run-act! still stream
   the legacy raw envelope shape the pre-refactor consumer requires. The injected
   provider's :pm/* stream is normalized back to {:type _ ...} envelopes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.saa.orchestrator :as saa]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.protocols.agent-bridge :as bridge]
            [hive-mcp.saa.registry :as registry]
            [hive-mcp.saa.support :as support]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each (fn [f]
                      (saa/clear-all-states!)
                      (support/reset+seed!)
                      (try (f)
                           (finally
                             (saa/clear-all-states!)
                             (registry/reset-for-test!)))))

;; =============================================================================
;; Ports resolve from the :saa/core seed alone (LSP, no addons)
;; =============================================================================

(deftest c13-default-ports-resolve-from-seed
  (testing "with only :saa/core seeded, the orchestrator injects the LSP defaults"
    (let [orch   (saa/->saa-orchestrator {:shout? false})
          config (:config orch)]
      (is (satisfies? psaa/IPhaseProvider (:phase-provider config))
          "phase-provider port resolves to a DefaultPhaseProvider")
      (is (satisfies? psaa/IObservationScorer (:scorer config))
          "scorer port resolves to a DefaultObservationScorer")
      (is (satisfies? psaa/IPlanSynthesizer (:planner config))
          "planner port resolves to a NoopPlanSynthesizer")
      (is (= "DefaultPhaseProvider"
             (-> (:phase-provider config) class .getSimpleName)))
      (is (= "DefaultObservationScorer"
             (-> (:scorer config) class .getSimpleName)))
      (is (= "NoopPlanSynthesizer"
             (-> (:planner config) class .getSimpleName))))))

;; =============================================================================
;; C13 — run-silence! streams the legacy envelope addon-free
;; =============================================================================

(deftest c13-run-silence-live-path
  (testing "run-silence! through the default provider yields legacy envelopes"
    (let [orch    (saa/->saa-orchestrator {:shout? false})
          session (support/->mock-session
                   [{:type :message :content "found file A"}
                    {:type :message :content "found pattern B"}])
          msgs    (support/drain (bridge/run-silence! orch session "explore" {}))
          last-msg (last msgs)]
      (is (pos? (count msgs)))
      (is (every? #(= :silence (:saa-phase %)) msgs)
          "every streamed envelope is stamped :saa-phase :silence")
      (is (= :phase-complete (:type last-msg)))
      (is (= :silence (:saa-phase last-msg)))
      (is (vector? (:observations last-msg)))
      (is (= 2 (:observation-count last-msg))
          "default provider streamed both observations through pm->raw-envelope"))))

;; =============================================================================
;; C13 — run-abstract! streams the legacy envelope addon-free
;; =============================================================================

(deftest c13-run-abstract-live-path
  (testing "run-abstract! synthesizes a plan through the default ports"
    (let [orch (saa/->saa-orchestrator {:shout? false})]
      (support/drain (bridge/run-silence!
                      orch
                      (support/->mock-session [{:type :message :content "obs"}])
                      "task" {}))
      (let [session (support/->mock-session
                     [{:type :message :content "Step 1: fix auth.clj"}
                      {:type :message :content "Step 2: update tests"}])
            msgs    (support/drain
                     (bridge/run-abstract! orch session ["obs1" "obs2"] {}))
            last-msg (last msgs)]
        (is (pos? (count msgs)))
        (is (= :phase-complete (:type last-msg)))
        (is (= :abstract (:saa-phase last-msg)))
        (is (string? (:plan last-msg))
            "NoopPlanSynthesizer returns nil → plan joined from streamed content")))))

;; =============================================================================
;; C13 — run-act! streams the legacy envelope addon-free
;; =============================================================================

(deftest c13-run-act-live-path
  (testing "run-act! executes a plan through the default provider"
    (let [orch (saa/->saa-orchestrator {:shout? false})]
      (support/drain (bridge/run-silence!
                      orch
                      (support/->mock-session [{:type :message :content "obs"}])
                      "task" {}))
      (let [session (support/->mock-session
                     [{:type :message :content "Changed auth.clj line 42"}
                      {:type :message :content "Tests pass"}])
            msgs    (support/drain
                     (bridge/run-act! orch session "Step 1\nStep 2" {}))
            last-msg (last msgs)]
        (is (pos? (count msgs)))
        (is (= :phase-complete (:type last-msg)))
        (is (= :act (:saa-phase last-msg)))
        (is (map? (:result last-msg)))
        (is (pos? (:message-count (:result last-msg))))
        (is (= :complete (saa/agent-saa-phase "mock-saa-session"))
            "act transitions state to :complete")))))

;; =============================================================================
;; C13 — full cycle is behavior-preserving addon-free
;; =============================================================================

(deftest c13-run-full-saa-live-path
  (testing "run-full-saa! chains the three port-routed phases to :saa-complete"
    (let [orch    (saa/->saa-orchestrator {:shout? false})
          session (support/->mock-session [{:type :message :content "x"}])
          msgs    (support/drain (bridge/run-full-saa! orch session "task" {}))
          last-msg (last msgs)]
      (is (pos? (count msgs)))
      (is (= :saa-complete (:type last-msg)))
      (is (= "mock-saa-session" (:agent-id last-msg)))
      (is (number? (:elapsed-ms last-msg))))))

;; =============================================================================
;; C13 — NoopAgentSession backend: stream stays valid (no throw, no addons)
;; =============================================================================

(deftest c13-noop-session-stream-still-valid
  (testing "against a NoopAgentSession the live path still produces a valid stream"
    (let [orch    (saa/->saa-orchestrator {:shout? false})
          session (bridge/->noop-session "noop-live-path")
          msgs    (support/drain (bridge/run-silence! orch session "task" {}))
          last-msg (last msgs)]
      (is (pos? (count msgs)) "the stream is non-empty and closes")
      (is (every? #(= :silence (:saa-phase %)) msgs)
          "every envelope is stamped with the phase")
      (is (= :phase-complete (:type last-msg))
          "the Noop backend's :error message streams as a normal envelope; the
           phase still completes rather than throwing")
      (is (= :silence (:saa-phase last-msg))))))
