(ns hive-mcp.channel-test
  "Tests for bidirectional channel infrastructure."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! timeout alts!! go]]
            [hive-mcp.channel.core :as ch]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn with-clean-server [f]
  "Ensure server is stopped before and after each test."
  (ch/stop-server!)
  (Thread/sleep 100)
  (f)
  (ch/stop-server!)
  (Thread/sleep 100))

(use-fixtures :each with-clean-server)

;; =============================================================================
;; Protocol Tests (Client API - NOT YET IMPLEMENTED)
;; =============================================================================
;;
;; Note: These tests were written for a planned client-side channel API
;; that was never implemented. The channel module currently only provides
;; server-side functionality.
;;
;; TODO: Implement client-side API or remove these tests permanently

;; (deftest tcp-channel-create-test
;;   (testing "TCP channel creation"
;;     (let [ch (ch/tcp-channel "localhost" 9998)]
;;       (is (some? ch))
;;       (is (= "localhost" (:host ch)))
;;       (is (= 9998 (:port ch)))
;;       (is (not (ch/connected? ch))))))
;;
;; (deftest unix-channel-create-test
;;   (testing "Unix channel creation"
;;     (let [ch (ch/unix-channel "/tmp/test.sock")]
;;       (is (some? ch))
;;       (is (= "/tmp/test.sock" (:path ch)))
;;       (is (not (ch/connected? ch))))))

;; =============================================================================
;; Event Bus Tests
;; =============================================================================

(deftest event-publish-subscribe-test
  (testing "Event pub/sub works"
    (let [received (atom nil)
          sub-ch (ch/subscribe! :test-event)]
      ;; Start consumer in background
      (go
        (when-let [event (<!! sub-ch)]
          (reset! received event)))
      ;; Publish event
      (ch/publish! {:type :test-event :data "hello"})
      ;; Wait for delivery
      (Thread/sleep 100)
      (is (= "hello" (:data @received)))
      (ch/unsubscribe! :test-event sub-ch))))

(deftest event-requires-type-test
  (testing "Events without :type throw exception"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ch/publish! {:data "no type"})))))

(deftest multiple-subscribers-test
  (testing "Multiple subscribers receive events"
    (let [received1 (atom nil)
          received2 (atom nil)
          sub1 (ch/subscribe! :multi-test)
          sub2 (ch/subscribe! :multi-test)]
      ;; Start consumers
      (go (when-let [e (<!! sub1)] (reset! received1 e)))
      (go (when-let [e (<!! sub2)] (reset! received2 e)))
      ;; Publish
      (ch/publish! {:type :multi-test :data "broadcast"})
      (Thread/sleep 100)
      (is (= "broadcast" (:data @received1)))
      (is (= "broadcast" (:data @received2)))
      (ch/unsubscribe! :multi-test sub1)
      (ch/unsubscribe! :multi-test sub2))))

;; =============================================================================
;; Server Tests
;; =============================================================================

(deftest start-stop-unix-server-test
  (testing "Unix server start/stop"
    (let [path "/tmp/hive-mcp-test.sock"
          state (ch/start-server! {:type :unix :path path})]
      (is (some? state))
      (is (= :unix (:type state)))
      (is (= path (:path state)))
      (is @(:running state))
      ;; Socket file should exist
      (is (.exists (java.io.File. path)))
      ;; Stop
      (ch/stop-server!)
      ;; Socket file should be cleaned up
      (Thread/sleep 100)
      (is (not (.exists (java.io.File. path)))))))

(deftest start-stop-tcp-server-test
  (testing "TCP server start/stop"
    (let [port (+ 20000 (rand-int 1000))
          state (ch/start-server! {:type :tcp :port port})]
      (is (some? state))
      (is (= :tcp (:type state)))
      (is (= port (:port state)))
      (is @(:running state))
      (ch/stop-server!))))

(deftest server-already-running-test
  (testing "Starting server when already running returns existing state"
    (let [port1 (+ 21000 (rand-int 1000))]
      (ch/start-server! {:type :tcp :port port1})
      (let [state2 (ch/start-server! {:type :tcp :port (+ port1 1)})]
        ;; Should return first server's state, not create new one
        (is (= port1 (:port state2)))))))

;; =============================================================================
;; Emit Event Tests
;; =============================================================================

(deftest emit-event-adds-timestamp-test
  (testing "emit-event! adds timestamp"
    (let [received (promise)
          ;; Envelope keys are owned by the source, never restated here.
          ts-key (:timestamp ch/envelope-keys)
          type-key (:type ch/envelope-keys)
          sub (ch/subscribe! :timestamped)]
      ;; Start async receiver first
      (async/go
        (when-let [e (async/<! sub)]
          (deliver received e)))
      ;; Brief pause to ensure go block is waiting
      (Thread/sleep 10)
      ;; Emit event
      (ch/emit-event! :timestamped {:data "test"})
      ;; Wait for result with timeout
      (let [result (deref received 1000 nil)]
        (is (some? result) "Should receive event")
        (when result
          (is (number? (get result ts-key)))
          (is (> (get result ts-key) 0))
          (is (= "timestamped" (get result type-key)))
          (is (= "test" (get result "data")))))
      (ch/unsubscribe! :timestamped sub))))

;; =============================================================================
;; Bencode Tests (INTERNAL FUNCTIONS NOT EXPOSED)
;; =============================================================================
;;
;; Note: encode-msg and decode-msg were refactored to transport layer.
;; These tests reference private functions that no longer exist in this ns.

;; (deftest bencode-roundtrip-test
;;   (testing "Bencode encode/decode roundtrip"
;;     (let [msg {:op "event" :type "test" :data "hello"}
;;           encoded (#'ch/encode-msg msg)
;;           decoded (let [in (java.io.PushbackInputStream.
;;                             (java.io.ByteArrayInputStream. encoded))]
;;                     (#'ch/decode-msg in))]
;;       (is (= "event" (get decoded "op")))
;;       (is (= "test" (get decoded "type")))
;;       (is (= "hello" (get decoded "data"))))))

;; =============================================================================
;; Integration Test: Client-Server Communication (NOT YET IMPLEMENTED)
;; =============================================================================
;;
;; Note: These tests require the client-side API that was never implemented.
;; Server-side functionality (start-server!, broadcast!) works, but client-side
;; functions (unix-channel, connect!, send!, recv!, disconnect!) do not exist.

;; (deftest client-server-integration-test
;;   (testing "Client can connect to server and exchange messages"
;;     (let [path "/tmp/hive-mcp-integration-test.sock"
;;           received-by-server (atom nil)]
;;       (ch/start-server! {:type :unix :path path})
;;       (let [sub (ch/subscribe! :client-msg)]
;;         (go (when-let [e (<!! sub)]
;;               (reset! received-by-server e)))
;;         (let [client (ch/unix-channel path)]
;;           (is (ch/connect! client))
;;           (is (ch/connected? client))
;;           (is (ch/send! client {:type "client-msg" :data "from-client"}))
;;           (Thread/sleep 200)
;;           (ch/disconnect! client)
;;           (is (not (ch/connected? client))))
;;         (ch/unsubscribe! :client-msg sub)))))
;;
;; (deftest broadcast-to-clients-test
;;   (testing "Server can broadcast to connected clients"
;;     (let [path "/tmp/hive-mcp-broadcast-test.sock"
;;           client-received (atom nil)]
;;       (ch/start-server! {:type :unix :path path})
;;       (let [client (ch/unix-channel path)]
;;         (is (ch/connect! client))
;;         (future
;;           (when-let [msg (ch/recv! client)]
;;             (reset! client-received msg)))
;;         (Thread/sleep 100)
;;         (ch/broadcast! {:type "server-broadcast" :data "hello-all"})
;;         (Thread/sleep 200)
;;         (is (= "server-broadcast" (get @client-received "type")))
;;         (is (= "hello-all" (get @client-received "data")))
;;         (ch/disconnect! client)))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'hive-mcp.channel-test))
