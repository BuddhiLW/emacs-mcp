;; PROPRIETARY - Copyright 2026 BuddhiLW. All Rights Reserved.

(ns hive-mcp.delivery.channels-trifecta-test
  "Trifecta coverage for the E2E-2 delivery-channel surface:
   - enabled-channel-ids (private) — env/explicit precedence
   - channel-factories (private) — DI invariant: every factory id matches
     its produced channel-id (LSP / contract)
   - register-default-channels! — at least the always-available impls
     (:piggyback, :file-tail) come up regardless of editor backends.

   Tests are pure where possible; the `register-default-channels!`
   assertion is a single deftest (side-effectful) outside the trifecta
   to keep the property/golden/mutation triad clean."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.delivery.channels :as ch]
            [hive-mcp.protocols.delivery-channel :as dc]))

;; =============================================================================
;; Subjects under test — public wrappers around the private targets
;; =============================================================================

(defn run-enabled-ids
  "Wrapper around the private `enabled-channel-ids` so trifecta can target it."
  [ids]
  (@#'ch/enabled-channel-ids ids))

(defn run-factory-channel-id
  "For a given factory key, instantiate the channel and read its channel-id.
   The DI contract is: factory key MUST equal the channel's reported id."
  [factory-key]
  (let [factory (get @#'ch/channel-factories factory-key)]
    (dc/channel-id (factory))))

;; =============================================================================
;; Predicates + generators
;; =============================================================================

(def ^:private all-factory-ids
  "Source of truth: the static DI table keyset."
  (set (keys @#'ch/channel-factories)))

(defn set-of-keywords? [x]
  (and (set? x) (every? keyword? x)))

(defn subset-of-factories? [x]
  (and (set-of-keywords? x)
       (every? all-factory-ids x)))

(def ^:private gen-ids-arg
  "Either nil (env/default fallback) or an explicit non-empty subset."
  (gen/one-of
   [(gen/return nil)
    (gen/such-that seq
                   (gen/set (gen/elements (vec all-factory-ids))
                            {:min-elements 0 :max-elements (count all-factory-ids)}))]))

(def ^:private gen-factory-key
  (gen/elements (vec all-factory-ids)))

;; =============================================================================
;; Trifecta: enabled-channel-ids precedence
;; =============================================================================

(deftrifecta enabled-channel-ids-precedence
  hive-mcp.delivery.channels-trifecta-test/run-enabled-ids
  {:gen   gen-ids-arg
   :pred  set-of-keywords?
   :num-tests 100})

(deftest enabled-channel-ids-cases
  (testing "Explicit ids set wins"
    (is (= #{:nats} (run-enabled-ids #{:nats})))
    (is (= #{:nats :file-tail :piggyback}
           (run-enabled-ids #{:nats :file-tail :piggyback})))
    (is (= #{} (run-enabled-ids #{}))))
  (testing "Nil arg returns full factory keyset (env unset)"
    (is (= all-factory-ids (run-enabled-ids nil)))))

;; =============================================================================
;; Trifecta: channel-factories LSP/contract — factory id == channel-id
;; =============================================================================

(deftrifecta factory-id-matches-channel-id
  hive-mcp.delivery.channels-trifecta-test/run-factory-channel-id
  {:gen   gen-factory-key
   :pred  keyword?
   :num-tests 50})

(deftest factory-id-roundtrip-cases
  (testing "Every factory key matches its produced channel-id (LSP contract)"
    (doseq [k all-factory-ids]
      (is (= k (run-factory-channel-id k))
          (str "factory " k " must return a channel reporting itself")))))

;; =============================================================================
;; Side-effect: register-default-channels! brings up the agnostic impls
;; =============================================================================

(deftest register-default-channels-headless-agnostic
  (testing "Even with no editor frontend, register-default-channels! brings up
            the OS-native channels (piggyback + file-tail) and NATS-capable channel."
    (let [registered (ch/register-default-channels! #{:piggyback :file-tail :nats})
          ids        (set (mapv dc/channel-id (dc/get-channels)))]
      (is (= #{:piggyback :file-tail :nats} (set registered))
          "Selection set is honored — only the chosen impls register.")
      (is (contains? ids :piggyback)
          ":piggyback is always available in-process.")
      (is (contains? ids :file-tail)
          ":file-tail is always available (file-system fallback).")
      (is (contains? ids :nats)
          ":nats registers even without backbone (available? gates delivery, not registration)."))))

;; =============================================================================
;; ENGINE-L0.1: Nil-safe event-type guard
;; =============================================================================

(defn run-event-name
  "Public wrapper around the private `event-name` helper for testing the
   nil-safe :event-type guard (ENGINE-L0.1, incident 2026-05-11)."
  [event-type channel-id event]
  (@#'ch/event-name event-type channel-id event))

(deftest event-name-nil-safe-guard
  (testing "nil :event-type returns nil instead of throwing NPE"
    (is (nil? (run-event-name nil :websocket {:agent-id "a" :message "m"}))
        "nil event-type must not throw — refusing delivery beats NPE inside debug catch"))
  (testing "keyword event-type round-trips to string name"
    (is (= "spawn" (run-event-name :spawn :olympus {:event-type :spawn}))))
  (testing "string event-type returned as-is"
    (is (= "spawn" (run-event-name "spawn" :olympus {}))))
  (testing "deliver! survives nil :event-type on every channel (no exception bubbles)"
    (doseq [ch [(ch/->WebSocketChannel)
                (ch/->CoreAsyncChannel)
                (ch/->ChannelBroadcastChannel)
                (ch/->OlympusChannel)]]
      (is (nil? (dc/deliver! ch {:agent-id "a" :message "m"}))
          (str (dc/channel-id ch) " must silently drop events with nil :event-type")))))
