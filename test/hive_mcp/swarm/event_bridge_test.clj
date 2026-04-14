(ns hive-mcp.swarm.event-bridge-test
  "Tests for the swarm event bridge (NATS ↔ in-process channel.core).

   Uses the hive-test trifecta macro (deftest-facets) with :expr golden
   facets so each stateful scenario captures a deterministic view of what
   was recorded on both sides of the bridge."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-test.trifecta :refer [deftest-facets]]
            [hive-mcp.swarm.event-bridge :as bridge]
            [hive-mcp.protocols.event-backbone :as eb]
            [hive-mcp.channel.core :as channel]))

;; =============================================================================
;; FakeBackbone — records every call, pretends to be connected when asked.
;; =============================================================================

(defrecord FakeBackbone [id connected?-atom subs published]
  eb/IEventBackbone
  (backbone-id  [_] id)
  (connected?   [_] @connected?-atom)
  (publish!     [_ subject payload]
    (swap! published conj [subject payload])
    nil)
  (subscribe!   [_ subject handler-fn]
    (swap! subs assoc subject handler-fn)
    ::sub-handle)
  (unsubscribe! [_ subject]
    (swap! subs dissoc subject)
    nil))

(defn- make-fake-backbone
  ([] (make-fake-backbone true))
  ([connected?]
   (->FakeBackbone :fake (atom connected?) (atom {}) (atom []))))

;; =============================================================================
;; Capture helper: tap channel.core for the duration of a scenario so we
;; can assert which events were published locally.
;; =============================================================================

(defn- capture-local!
  "Subscribe to an event-type and drain everything published while the
   thunk runs. Returns [result events-seen]."
  [event-type thunk]
  (let [ch (channel/subscribe! event-type)
        acc (atom [])
        stop? (atom false)
        pump (future
               (while (not @stop?)
                 (let [v #_{:clj-kondo/ignore [:unresolved-symbol]}
                       (clojure.core.async/alt!!
                         ch ([x] x)
                         (clojure.core.async/timeout 20) :none)]
                   (when (and (not= v :none) (some? v))
                     (swap! acc conj v)))))]
    (try
      (Thread/sleep 30) ;; let subscribe take effect
      (let [r (thunk)]
        (Thread/sleep 80) ;; let pump drain
        [r @acc])
      (finally
        (reset! stop? true)
        (try (clojure.core.async/close! ch) (catch Exception _ nil))))))

;; =============================================================================
;; Scenario runner — installs a FakeBackbone, runs body, tears down.
;; Ensures bridge is stopped and backbone cleared on the way out.
;; =============================================================================

(defn- with-fake-backbone
  [connected? body-fn]
  (let [bb (make-fake-backbone connected?)]
    (try
      (eb/set-backbone! bb)
      (body-fn bb)
      (finally
        (try (bridge/stop-nats-bridge!) (catch Exception _ nil))
        (eb/clear-backbone!)))))

(defn- normalize-published
  "Drop payload bodies we don't care about in snapshots — keep only the
   subject pattern and event :type/:slave-id."
  [pubs]
  (mapv (fn [[subject payload]]
          [subject (select-keys payload [:type :slave-id :via])])
        pubs))

(defn- normalize-local
  [events]
  (mapv #(select-keys % [:type :slave-id :via]) events))

;; =============================================================================
;; Scenario 1 — start-nats-bridge! is a no-op when backbone disconnected
;; =============================================================================

(deftest-facets start-bridge-noop-when-disconnected
  identity
  {:type :golden
   :path "test/golden/swarm/event-bridge/start-noop-when-disconnected.edn"
   :expr (hive-mcp.swarm.event-bridge-test/with-fake-backbone
           false
           (fn [bb]
             {:start-result (bridge/start-nats-bridge!)
              :subscribed   (keys @(:subs bb))
              :bridge-status (select-keys (bridge/bridge-status) [:running])}))})

;; =============================================================================
;; Scenario 2 — start-nats-bridge! subscribes hive.v1.slave.> when connected
;; =============================================================================

(deftest-facets start-bridge-subscribes-slave-wildcard
  identity
  {:type :golden
   :path "test/golden/swarm/event-bridge/start-subscribes-wildcard.edn"
   :expr (hive-mcp.swarm.event-bridge-test/with-fake-backbone
           true
           (fn [bb]
             {:start-result (bridge/start-nats-bridge!)
              :subscribed   (vec (sort (keys @(:subs bb))))
              :bridge-status (select-keys (bridge/bridge-status) [:running :backbone-id])}))})

;; =============================================================================
;; Scenario 3 — republish-from-nats! re-emits into channel.core with :via
;;              :nats-bridge. Exercised end-to-end via the fake backbone's
;;              stored handler.
;; =============================================================================

(deftest-facets republish-from-nats-tags-via-bridge
  identity
  {:type :golden
   :path "test/golden/swarm/event-bridge/republish-tags-via-bridge.edn"
   :expr (hive-mcp.swarm.event-bridge-test/with-fake-backbone
           true
           (fn [bb]
             (bridge/start-nats-bridge!)
             (let [handler (get @(:subs bb) "hive.v1.slave.>")
                   [_ seen] (hive-mcp.swarm.event-bridge-test/capture-local!
                              :slave-spawned
                              (fn []
                                (handler {:type :slave-spawned
                                          :slave-id "ling-nats-1"
                                          :name "from-nats"})))]
               {:handler-present? (some? handler)
                :local-events     (hive-mcp.swarm.event-bridge-test/normalize-local seen)})))})

;; =============================================================================
;; Scenario 4 — publish-slave-event! mirrors to BOTH channel.core and NATS
;;              on a normal (no :via) event.
;; =============================================================================

(deftest-facets publish-slave-event-mirrors-both-sides
  identity
  {:type :golden
   :path "test/golden/swarm/event-bridge/publish-mirrors-both.edn"
   :expr (hive-mcp.swarm.event-bridge-test/with-fake-backbone
           true
           (fn [bb]
             (let [[_ seen] (hive-mcp.swarm.event-bridge-test/capture-local!
                              :slave-spawned
                              (fn []
                                (bridge/publish-slave-event!
                                  {:type :slave-spawned
                                   :slave-id "ling-out-1"
                                   :name "outbound"})))]
               {:local-events (hive-mcp.swarm.event-bridge-test/normalize-local seen)
                :nats-publishes (hive-mcp.swarm.event-bridge-test/normalize-published
                                  @(:published bb))})))})

;; =============================================================================
;; Scenario 5 — publish-slave-event! LOOP GUARD: events already tagged
;;              :via :nats-bridge must NOT be republished outbound.
;; =============================================================================

(deftest-facets publish-slave-event-loop-guard
  identity
  {:type :golden
   :path "test/golden/swarm/event-bridge/publish-loop-guard.edn"
   :expr (hive-mcp.swarm.event-bridge-test/with-fake-backbone
           true
           (fn [bb]
             (let [[_ seen] (hive-mcp.swarm.event-bridge-test/capture-local!
                              :slave-spawned
                              (fn []
                                (bridge/publish-slave-event!
                                  {:type :slave-spawned
                                   :slave-id "ling-loop-1"
                                   :via :nats-bridge})))]
               {:local-events (hive-mcp.swarm.event-bridge-test/normalize-local seen)
                :nats-publishes (hive-mcp.swarm.event-bridge-test/normalize-published
                                  @(:published bb))})))})

;; =============================================================================
;; Scenario 6 — stop-nats-bridge! is idempotent
;; =============================================================================

(deftest-facets stop-bridge-idempotent
  identity
  {:type :golden
   :path "test/golden/swarm/event-bridge/stop-idempotent.edn"
   :expr (hive-mcp.swarm.event-bridge-test/with-fake-backbone
           true
           (fn [bb]
             (bridge/start-nats-bridge!)
             {:before (select-keys (bridge/bridge-status) [:running])
              :stop-1 (do (bridge/stop-nats-bridge!) :ok)
              :stop-2 (do (bridge/stop-nats-bridge!) :ok)
              :after  (select-keys (bridge/bridge-status) [:running])}))})
