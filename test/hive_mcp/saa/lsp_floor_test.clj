(ns hive-mcp.saa.lsp-floor-test
  "Regression: lookup-*-or-default must return a satisfying record even when
   the registry is completely empty (core-seed not fired). Guards the
   nil-provider failure observed via hivemind piggyback
   ([SAA:silence] FAILED: No implementation of method: :build-options ...
   found for class: nil)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.saa.registry :as reg]
            [hive-mcp.saa.registry.phase-providers :as rp]
            [hive-mcp.saa.registry.scorers :as rs]
            [hive-mcp.saa.registry.planners :as rpl]
            [hive-mcp.protocols.saa :as psaa]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest lsp-floor-holds-with-empty-registry
  (testing "resolvers never return nil even when :saa/default is absent"
    (rp/reset-for-test!) (rs/reset-for-test!) (rpl/reset-for-test!)
    (is (empty? (rp/all-ids)) "precondition: phase-provider registry cleared")
    (let [pp (reg/lookup-phase-provider-or-default :anything)
          sc (reg/lookup-scorer-or-default :anything)
          pl (reg/lookup-planner-or-default :anything)]
      (is (some? pp) "phase-provider non-nil on empty registry")
      (is (satisfies? psaa/IPhaseProvider pp))
      (is (map? (psaa/phase-config pp :silence)))
      (is (some? (psaa/build-options pp :silence {}))
          "build-options works on the hard-default provider (the piggyback bug)")
      (is (some? sc))
      (is (satisfies? psaa/IObservationScorer sc))
      (is (vector? (psaa/score sc [{:data "a bug fix"}])))
      (is (some? pl))
      (is (satisfies? psaa/IPlanSynthesizer pl))
      (is (nil? (psaa/synthesize pl [] "task"))))))
