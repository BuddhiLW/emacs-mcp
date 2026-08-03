;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.channel.activation-test
  "The activation seam and the drain telemetry ledger.

   Contract under test: with no `:memory/activation` provider registered the
   drain ctx is exactly the harvested cues, and any provider that misbehaves
   degrades to that same ctx rather than breaking the tool response."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.channel.activation :as activation]
            [hive-mcp.channel.drain-telemetry :as telemetry]
            [hive-mcp.extensions.registry :as ext]))

(defn- with-provider
  "Register `f` under the activation key for the duration of `thunk`,
   restoring whatever was registered before."
  [f thunk]
  (let [prior (ext/get-extension activation/extension-key)]
    (try
      (ext/register! activation/extension-key f)
      (thunk)
      (finally
        (if prior
          (ext/register! activation/extension-key prior)
          (ext/deregister! activation/extension-key))))))

(use-fixtures :each (fn [t] (telemetry/reset!) (t) (telemetry/reset!)))

;; =============================================================================
;; activation/drain-ctx — degraded mode is the contract, not the fallback
;; =============================================================================

(deftest extension-key-is-pinned
  (testing "the key is a published contract — an addon writes this literal, so
            renaming it here silently unregisters every provider"
    (is (= :memory/activation activation/extension-key))))

(deftest drain-ctx-without-a-provider-is-just-the-cues
  (testing "no provider => {:tokens cues} and nothing else"
    (let [prior (ext/get-extension activation/extension-key)]
      (try
        (ext/deregister! activation/extension-key)
        (is (= {:tokens #{"carto"}}
               (activation/drain-ctx {:tool-name "code" :cues #{"carto"}})))
        (is (= {:tokens #{}} (activation/drain-ctx {:tool-name "code"})))
        (finally
          (when prior (ext/register! activation/extension-key prior)))))))

(deftest drain-ctx-merges-a-well-behaved-provider
  (with-provider
    (fn [_] {:pins #{"mem-1" "mem-2"} :tokens #{"extra"} :floor-cap 3})
    (fn []
      (let [ctx (activation/drain-ctx {:tool-name "code" :cues #{"carto"}})]
        (testing "provider tokens are UNIONED onto the cues, never replacing them"
          (is (= #{"carto" "extra"} (:tokens ctx))))
        (testing "pins and floor-cap ride through"
          (is (= #{"mem-1" "mem-2"} (:pins ctx)))
          (is (= 3 (:floor-cap ctx))))))))

(deftest drain-ctx-survives-a-hostile-provider
  (testing "a throwing provider degrades to the cues"
    (with-provider
      (fn [_] (throw (ex-info "boom" {})))
      (fn [] (is (= {:tokens #{"carto"}}
                    (activation/drain-ctx {:tool-name "code" :cues #{"carto"}}))))))

  (testing "a provider returning garbage degrades to the cues"
    (doseq [answer [nil 42 "nope" [] {:pins "not-a-coll"}]]
      (with-provider
        (fn [_] answer)
        (fn [] (is (= {:tokens #{"carto"}}
                      (activation/drain-ctx {:tool-name "code" :cues #{"carto"}}))
                   (str "answer " (pr-str answer) " must degrade"))))))

  (testing "non-string pins and a non-positive cap are dropped, the rest survives"
    (with-provider
      (fn [_] {:pins ["ok" 42 nil] :floor-cap 0})
      (fn []
        (let [ctx (activation/drain-ctx {:tool-name "code" :cues #{}})]
          (is (= #{"ok"} (:pins ctx)))
          (is (nil? (:floor-cap ctx))))))))

(deftest provider-sees-allowlisted-cues-only
  (testing "the rule input carries the cue tokens, never the raw tool args"
    (let [seen (atom nil)]
      (with-provider
        (fn [ctx] (reset! seen ctx) nil)
        (fn []
          (activation/drain-ctx {:tool-name "code" :cues #{"carto"} :caller-id "c1"})
          (is (= {:tool-name "code" :cues #{"carto"} :caller-id "c1"} @seen))
          (is (not (contains? @seen :args))))))))

;; =============================================================================
;; drain-telemetry — the offers-vs-access measurement S4 tunes against
;; =============================================================================

(deftest telemetry-accumulates-offers-and-deliveries
  (telemetry/record! {:seq-num 1 :delivered-ids ["a"] :offered-ids ["b" "c"]})
  (telemetry/record! {:seq-num 2 :delivered-ids ["b"] :offered-ids ["c"]})
  (let [snap (telemetry/snapshot)]
    (testing "counters fold across drains"
      (is (= {:offers 0 :delivered 1 :last-seq 1} (get snap "a")))
      (is (= {:offers 1 :delivered 1 :last-seq 2} (get snap "b")))
      (is (= {:offers 2 :delivered 0 :last-seq 2} (get snap "c"))))
    (testing "summary aggregates the ledger"
      (is (= {:entries 3 :offers 3 :delivered 2 :offer-per-delivery 1.5}
             (telemetry/summary))))))

(deftest telemetry-tolerates-empty-and-nil-input
  (telemetry/record! {:seq-num 1 :delivered-ids nil :offered-ids nil})
  (is (= {} (telemetry/snapshot)))
  (is (= {:entries 0 :offers 0 :delivered 0 :offer-per-delivery nil}
         (telemetry/summary))))

(deftest shelf-report-ranks-pushed-but-unread-first
  (telemetry/record! {:seq-num 1 :delivered-ids ["read"] :offered-ids ["shelf"]})
  (telemetry/record! {:seq-num 2 :delivered-ids ["read"] :offered-ids ["shelf"]})
  (telemetry/record! {:seq-num 3 :offered-ids ["shelf"]})
  (let [lookup {"read" {:access-count 5 :helpful-count 1 :unhelpful-count 0}
                "shelf" {:access-count 0 :helpful-count 0 :unhelpful-count 0}}
        rows (telemetry/shelf-report lookup)]
    (testing "the entry offered three times and never read leads the report"
      (is (= "shelf" (:id (first rows))))
      (is (= 3 (:offers (first rows))))
      (is (= 0 (:access-count (first rows)))))
    (testing "a delivered-and-read entry scores negative — it is not shelf-ware"
      (let [read-row (first (filter #(= "read" (:id %)) rows))]
        (is (neg? (:shelf-score read-row)))
        (is (= 5 (:access-count read-row)))))
    (testing "a lookup that knows nothing is treated as zero access, never nil"
      (is (every? number? (map :access-count (telemetry/shelf-report (constantly nil))))))))
