(ns hive-mcp.saa.protocols-contract-test
  "W0 contract suite: the protocols.saa seam + DefaultPhaseProvider.

   C2 (FIX#6) every message DefaultPhaseProvider.execute-phase! emits is a
        valid PhaseMessage variant, and raw-msg->phase-message maps each raw
        :type to a :pm/* variant.
   Re-export identity: protocols.agent-bridge re-exports are identical? to
        the canonical protocols.saa vars."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.protocols.agent-bridge :as bridge]
            [hive-mcp.saa.adapters :as adapters]
            [hive-mcp.saa.types :as types]
            [hive-mcp.saa.support :as support]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each support/with-fresh-registry)

;; =============================================================================
;; C2 — execute-phase! emits only PhaseMessage variants (FIX#6)
;; =============================================================================

(def ^:private raw-fixture
  "One raw bridge message per recognized :type, plus one unknown shape."
  [{:type :message      :content "chunk-text"}
   {:type :complete     :data {:done true}}
   {:type :result       :data 42}
   {:type :error        :error "boom"}
   {:type :saa-complete :phases 3}
   {:type :weird-unknown :foo 1}])

(deftest execute-phase-emits-only-phase-messages
  (testing "every envelope on the out-ch is a PhaseMessage ADT value"
    (let [provider (adapters/->default-phase-provider)
          session  (support/->mock-session raw-fixture)
          out-ch   (psaa/execute-phase! provider session "prompt" {:phase :silence})
          msgs     (support/drain out-ch)]
      (is (= (count raw-fixture) (count msgs))
          "one PhaseMessage emitted per raw bridge message")
      (is (every? types/phase-message? msgs)
          "every emitted envelope satisfies phase-message?")
      (is (every? #{:pm/started :pm/chunk :pm/observation
                    :pm/phase-complete :pm/error :pm/saa-complete}
                  (map :adt/variant msgs))
          "every envelope variant is a declared :pm/* variant"))))

(deftest execute-phase-unknown-raw-degrades-to-chunk
  (testing "an unrecognized raw shape becomes an opaque :pm/chunk (never throws)"
    (let [provider (adapters/->default-phase-provider)
          session  (support/->mock-session [{:type :weird-unknown :foo 1}])
          [msg]    (support/drain
                    (psaa/execute-phase! provider session "p" {:phase :act}))]
      (is (types/phase-message? msg))
      (is (= :pm/chunk (:adt/variant msg)))
      (is (= :act (:phase msg)) "phase stamped from provider-options"))))

;; =============================================================================
;; C2 — raw-msg->phase-message maps each raw :type → :pm/* variant
;; =============================================================================

(deftest raw-msg-type-to-pm-variant-mapping
  (testing "each raw :type lifts to its canonical :pm/* variant"
    (doseq [[raw expected]
            [[{:type :message :content "c"} :pm/chunk]
             [{:type :complete}             :pm/phase-complete]
             [{:type :result}               :pm/phase-complete]
             [{:type :error :error "e"}     :pm/error]
             [{:type :saa-complete}         :pm/saa-complete]]]
      (let [pm (types/raw-msg->phase-message raw :silence)]
        (is (types/phase-message? pm)
            (str "raw " (pr-str raw) " did not lift to a PhaseMessage"))
        (is (= expected (:adt/variant pm))
            (str "raw :type " (:type raw) " expected " expected
                 " got " (:adt/variant pm)))))))

(deftest raw-msg-prefers-explicit-phase
  (testing ":saa-phase / :phase on the raw message override the fallback phase"
    (is (= :abstract
           (:phase (types/raw-msg->phase-message
                    {:type :message :content "x" :saa-phase :abstract} :silence))))
    (is (= :act
           (:phase (types/raw-msg->phase-message
                    {:type :message :content "x" :phase :act} :silence))))))

;; =============================================================================
;; Re-export identity — agent-bridge mirrors protocols.saa exactly
;; =============================================================================

(deftest agent-bridge-reexports-identical
  (testing "ISAAOrchestrator + run-* re-exports are identical? to psaa originals"
    (is (identical? psaa/ISAAOrchestrator bridge/ISAAOrchestrator))
    (is (identical? psaa/run-silence!   bridge/run-silence!))
    (is (identical? psaa/run-abstract!  bridge/run-abstract!))
    (is (identical? psaa/run-act!       bridge/run-act!))
    (is (identical? psaa/run-full-saa!  bridge/run-full-saa!))))

(deftest default-phase-provider-satisfies-protocol
  (testing "the zero-arg and bound DefaultPhaseProvider both satisfy IPhaseProvider"
    (is (satisfies? psaa/IPhaseProvider (adapters/->default-phase-provider)))
    (is (satisfies? psaa/IPhaseProvider (adapters/->default-phase-provider :backend)))))
