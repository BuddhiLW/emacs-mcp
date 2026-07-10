(ns hive-mcp.knowledge-graph.slots.breaker-trifecta-test
  "Trifecta coverage for the pure breaker state-machine transitions
   (ENGINE-L1.1 anchor for ENGINE-L1.2a fix).

   `decision` and `on-failure` are pure `(map → map)` transitions —
   ideal targets for golden + property + mutation testing. The breaker
   guards the LMDB slot the conn-init fix protects, so its transitions
   must remain correct under refactor pressure."
  (:require [clojure.test.check.generators :as gen]
            [hive-mcp.knowledge-graph.slots.breaker :as breaker]
            [hive-test.trifecta :refer [deftrifecta]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Adapters — wrap multi-arg / record-shaped APIs for trifecta's single-input shape.
;; -----------------------------------------------------------------------------

(defn run-decision
  "Trifecta-shaped wrapper for `breaker/decision`."
  [breaker-map]
  (breaker/decision breaker-map))

(defn run-on-failure
  "Trifecta-shaped wrapper for `breaker/on-failure`. Takes a case map so the
   generator and the cases share one schema."
  [{:keys [breaker policy ts]}]
  (breaker/on-failure breaker policy ts))

;; -----------------------------------------------------------------------------
;; Predicates — trifecta property assertions.
;; -----------------------------------------------------------------------------

(def ^:private valid-decisions #{:pass :block})

(def ^:private valid-states #{:closed :open :half-open})

(defn decision-keyword? [k]
  (contains? valid-decisions k))

(defn well-formed-breaker? [b]
  (and (map? b)
       (contains? valid-states (:state b))
       (integer? (:failures b))
       (nat-int? (:failures b))
       (or (nil? (:opened-at b)) (integer? (:opened-at b)))
       (integer? (:cooldown-ms b))))

;; -----------------------------------------------------------------------------
;; Generators
;; -----------------------------------------------------------------------------

(def ^:private gen-state
  (gen/elements valid-states))

(def ^:private gen-breaker
  (gen/let [state       gen-state
            failures    gen/nat
            opened-at   (gen/one-of [(gen/return nil) gen/large-integer])
            cooldown-ms (gen/large-integer* {:min 0 :max 600000})]
    {:state state
     :failures failures
     :opened-at opened-at
     :cooldown-ms cooldown-ms}))

(def ^:private gen-policy
  (gen/let [max-failures   (gen/large-integer* {:min 1 :max 10})
            initial-cd     (gen/large-integer* {:min 1000 :max 60000})
            max-cd-factor  (gen/large-integer* {:min 1 :max 100})]
    {:max-failures max-failures
     :initial-cooldown-ms initial-cd
     :max-cooldown-ms (* initial-cd max-cd-factor)}))

(def ^:private gen-on-failure-case
  (gen/let [breaker gen-breaker
            policy  gen-policy
            ts      gen/large-integer]
    {:breaker breaker :policy policy :ts ts}))

;; -----------------------------------------------------------------------------
;; Trifecta: decision — :closed | :half-open → :pass, :open → :block
;; -----------------------------------------------------------------------------

(deftrifecta breaker-decision
  hive-mcp.knowledge-graph.slots.breaker-trifecta-test/run-decision
  {:golden-path "test/golden/hive-mcp/breaker-decision.edn"
   :cases       {:closed    {:state :closed    :failures 0 :opened-at nil :cooldown-ms 0}
                 :open      {:state :open      :failures 3 :opened-at 1000 :cooldown-ms 30000}
                 :half-open {:state :half-open :failures 3 :opened-at 1000 :cooldown-ms 30000}}
   :gen         gen-breaker
   :pred        decision-keyword?
   :num-tests   200
   :mutations   [["always-pass"  (fn [_] :pass)]
                 ["always-block" (fn [_] :block)]
                 ["inverted"     (fn [b] (if (= :open (:state b)) :pass :block))]]})

;; -----------------------------------------------------------------------------
;; Trifecta: on-failure — counts failures, trips :closed→:open at threshold,
;;                        :half-open→:open on any failure (with cooldown step)
;; -----------------------------------------------------------------------------

(def ^:private default-policy
  ;; Mirrors breaker/default-policy — kept inline so the golden snapshot is
  ;; stable across policy tuning.
  {:max-failures 3
   :initial-cooldown-ms 30000
   :max-cooldown-ms 600000})

(deftrifecta breaker-on-failure
  hive-mcp.knowledge-graph.slots.breaker-trifecta-test/run-on-failure
  {:golden-path "test/golden/hive-mcp/breaker-on-failure.edn"
   :cases       {:closed-below-threshold
                 {:breaker {:state :closed :failures 0 :opened-at nil :cooldown-ms 0}
                  :policy  default-policy
                  :ts      1000}

                 :closed-hits-threshold
                 {:breaker {:state :closed :failures 2 :opened-at nil :cooldown-ms 0}
                  :policy  default-policy
                  :ts      2000}

                 :half-open-reverts-to-open
                 {:breaker {:state :half-open :failures 3 :opened-at 1000 :cooldown-ms 30000}
                  :policy  default-policy
                  :ts      5000}

                 :open-stays-open-with-cooldown-bump
                 {:breaker {:state :open :failures 3 :opened-at 1000 :cooldown-ms 30000}
                  :policy  default-policy
                  :ts      9000}}
   :gen         gen-on-failure-case
   :pred        well-formed-breaker?
   :apply?      false
   :num-tests   200
   :mutations   [["no-trip" (fn [{:keys [breaker]}]
                              ;; Never trips — failures increment but state stays.
                              (update breaker :failures (fnil inc 0)))]
                 ["instant-trip" (fn [{:keys [breaker ts policy]}]
                                   ;; Trips on first failure regardless of threshold.
                                   (assoc breaker
                                          :state :open
                                          :failures (inc (or (:failures breaker) 0))
                                          :opened-at ts
                                          :cooldown-ms (:initial-cooldown-ms policy)))]
                 ["no-cooldown-on-trip" (fn [{:keys [breaker policy ts]}]
                                          ;; Trips but forgets cooldown — half-open will
                                          ;; recover immediately, defeating the breaker.
                                          (assoc breaker
                                                 :state :open
                                                 :failures (inc (or (:failures breaker) 0))
                                                 :opened-at ts
                                                 :cooldown-ms 0))]]})
