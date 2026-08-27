(ns hive-mcp.meta-piggyback-test
  "TDD tests for _meta piggyback communication.

   CLARITY: Telemetry first - verify the communication channel works.
   DDD: Testing the domain behavior of hivemind messaging.
   
   Updated for per-agent cursor tracking (no global coordinator-last-seen-ts)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.tools.core :as tools-core]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-dsl.bounded-atom :refer [bclear!]]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture [f]
  (bclear! hivemind/agent-registry)
  (piggyback/reset-all-cursors!)
  (f))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Unit Tests - Piggyback Module (SRP: Single Responsibility)
;; =============================================================================

(deftest test-get-messages-returns-new-messages
  (testing "get-messages returns messages added after last call for that agent"
    ;; Add a message via shout
    (hivemind/shout! "test-agent" :progress {:task "test" :message "hello"})

    ;; Get piggyback for coordinator - should return the message
    (let [piggyback (piggyback/get-messages "coordinator")]
      (is (seq piggyback) "Should return non-empty messages")
      (is (= "test-agent" (:a (first piggyback))) "Should include agent-id")
      (is (= "progress" (:e (first piggyback))) "Should include event type")
      (is (= "hello" (:m (first piggyback))) "Should include message"))))

(deftest test-get-messages-per-agent-cursors
  (testing "Each agent has independent cursor - messages aren't repeated"
    ;; Both readers are coordinator lanes: since the audience filter landed
    ;; (hive-mcp.channel.audience) an arbitrary ling id receives nothing at
    ;; all, so probing the CURSOR through one would measure routing instead.
    ;; Routing itself is covered by hive-mcp.channel.audience-test.
    (hivemind/shout! "worker" :progress {:message "first"})

    ;; Agent A reads
    (let [read-a1 (piggyback/get-messages "coordinator-A")]
      (is (seq read-a1) "Agent A should get messages"))

    ;; Agent A reads again - should be empty (already read)
    (let [read-a2 (piggyback/get-messages "coordinator-A")]
      (is (nil? read-a2) "Agent A should get nil on second read"))

    ;; Agent B reads - should still see the message (own cursor)
    (let [read-b1 (piggyback/get-messages "coordinator-B")]
      (is (seq read-b1) "Agent B should get messages (independent cursor)"))))

(deftest test-fifo-ordering
  (testing "Messages are returned in FIFO order"
    ;; Add messages with small delays to ensure ordering.
    ;; Three DIFFERENT agents, so the progress digest has nothing to collapse
    ;; and ordering is what is under test.
    (hivemind/shout! "a" :started {:message "first"})
    (Thread/sleep 10)
    (hivemind/shout! "b" :progress {:message "second"})
    (Thread/sleep 10)
    (hivemind/shout! "c" :completed {:message "third"})

    (let [messages (piggyback/get-messages "coordinator-reader")]
      (is (= 3 (count messages)) "Should have 3 messages")
      (is (= "first" (:m (nth messages 0))) "First message correct")
      (is (= "second" (:m (nth messages 1))) "Second message correct")
      (is (= "third" (:m (nth messages 2))) "Third message correct"))))

(deftest test-fetch-history-doesnt-advance-cursor
  (testing "fetch-history returns messages without marking as read"
    (hivemind/shout! "agent" :progress {:message "test"})

    ;; Fetch history (doesn't advance cursor)
    (let [history (piggyback/fetch-history)]
      (is (seq history) "History should have messages"))

    ;; get-messages should still return the message. Reader is a coordinator
    ;; lane so the audience filter is not what is under test here.
    (let [messages (piggyback/get-messages "coordinator-reader")]
      (is (seq messages) "Messages should still be available"))))

;; =============================================================================
;; Integration Tests - make-tool Wrapper (OCP: Open for extension)
;; =============================================================================

(deftest test-make-tool-no-meta-when-no-piggyback
  (testing "make-tool wrapper omits :_meta when no piggyback"
    ;; No messages added - create wrapped tool
    (let [test-tool {:name "test-tool"
                     :description "test"
                     :inputSchema {}
                     :handler (fn [_] {:type "text" :text "result"})}
          wrapped (routes/make-tool test-tool)
          handler (:handler wrapped)]

      ;; First call consumes any existing messages (from other tests)
      (handler {})

      ;; Second call should have no piggyback
      (let [result (handler {})]
        (is (map? result) "Result should be a map")
        (is (contains? result :content) "Should have :content")
        (is (not (contains? result :_meta)) "Should NOT have :_meta when no piggyback")))))

;; =============================================================================
;; SDK Integration Tests - coerce-tool-response (LSP: Substitution)
;; =============================================================================

(deftest test-sdk-preserves-meta-field
  (testing "SDK coerce-tool-response converts :_meta to string key \"_meta\""
    (require '[io.modelcontext.clojure-sdk.server :as sdk-server])

    ;; Handler returns keyword :_meta, SDK converts to string "_meta"
    ;; for jsonrpc4clj serialization (camelCase strips underscore from keywords)
    (let [tool {:name "test"}
          response {:content [{:type "text" :text "hi"}]
                    :_meta {:hm [{:a "agent" :e "progress" :m "msg"}]}}
          coerced ((resolve 'io.modelcontext.clojure-sdk.server/coerce-tool-response)
                   tool response)]
      (is (contains? coerced "_meta") "SDK should convert :_meta to string \"_meta\"")
      (is (= {:hm [{:a "agent" :e "progress" :m "msg"}]}
             (get coerced "_meta")) "Meta content should be unchanged"))))

;; =============================================================================
;; End-to-End Test (DIP: Depends on abstractions)
;; =============================================================================

;; =============================================================================
;; Instruction Queue Tests
;; =============================================================================

(deftest test-instruction-queue-push-and-drain
  (testing "Instructions can be pushed and drained per agent"
    ;; Push instructions
    (piggyback/push-instruction! "ling-1" {:type "flow" :action "pause"})
    (piggyback/push-instruction! "ling-1" {:type "priority" :level "urgent"})
    (piggyback/push-instruction! "ling-2" {:type "context" :file "test.clj"})

    ;; Drain ling-1 - should get both, in order
    (let [drained (piggyback/drain-instructions! "ling-1")]
      (is (= 2 (count drained)) "Ling-1 should have 2 instructions")
      (is (= "pause" (get-in drained [0 :action])) "First instruction correct")
      (is (= "urgent" (get-in drained [1 :level])) "Second instruction correct"))

    ;; Drain ling-1 again - should be empty
    (let [drained2 (piggyback/drain-instructions! "ling-1")]
      (is (empty? drained2) "Second drain should be empty"))

    ;; Ling-2 should still have its instruction
    (let [drained-2 (piggyback/drain-instructions! "ling-2")]
      (is (= 1 (count drained-2)) "Ling-2 should have 1 instruction"))))
