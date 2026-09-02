(ns hive-mcp.server.hivemind-piggyback-e2e-test
  "End-to-end tests for hivemind shout -> piggyback delivery pipeline.

   Verifies the full path: shout! stores in agent-registry ->
   all-hivemind-messages reads from it -> piggyback/get-messages
   returns them -> routes/wrap-handler-piggybacks appends ---HIVEMIND--- blocks.

   (The old hivemind-only `wrap-handler-piggyback` was merged into the unified
   `wrap-handler-piggybacks` in hive-mcp.server.routes.middleware, which drains
   TOOLRESULT / MEMORY / catchup / HIVEMIND in a single pass. Its HIVEMIND leg
   is exactly what these tests assert on.)

   These tests complement piggyback_middleware_test.clj (which uses
   mock message sources) by testing the real hivemind integration.

   Regression test for: kanban 20260207222318-18674b30
   'Bug: Hivemind shouts not delivered via piggyback ---HIVEMIND--- blocks'"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.channel.piggyback :as pb]
            [hive-mcp.agent.context :as ctx]
            [hive-dsl.bounded-atom :refer [bput! bget bclear!]]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private test-dir "/home/lages/PP/hive/hive-mcp")
(def ^:private test-project "hive-mcp")

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- reset-state [f]
  ;; Save original message-source-fn (hivemind.clj registers it at load time)
  (let [original-source @pb/message-source-fn]
    ;; Reset state
    (pb/reset-all-cursors!)
    (bclear! hivemind/agent-registry)
    ;; Re-register the real message source (tests may have overwritten it)
    (pb/register-message-source! original-source)
    (f)
    ;; Cleanup
    (pb/reset-all-cursors!)
    (bclear! hivemind/agent-registry)
    ;; Restore original source
    (pb/register-message-source! original-source)))

(use-fixtures :each reset-state)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- dummy-handler
  "Handler that returns normalized content (vector of text items)."
  [_args]
  [{:type "text" :text "{\"ok\": true}"}])

(defn- extract-hivemind-block
  "Extract ---HIVEMIND--- block content string from handler response."
  [content]
  (some (fn [{:keys [text]}]
          (when text
            (second (re-find #"---HIVEMIND---\n([\s\S]*?)\n---/HIVEMIND---" text))))
        content))

(defn- shout-directly!
  "Store a shout directly in agent-registry (bypasses DataScript/channels).
   This is the minimal path that shout! takes for message storage."
  [agent-id event-type message project-id]
  (let [now (System/currentTimeMillis)
        msg {:event-type event-type
             :message message
             :timestamp now
             :project-id (or project-id "global")}
        current (or (bget hivemind/agent-registry agent-id)
                    {:messages [] :last-seen nil})
        messages (or (:messages current) [])
        new-messages (vec (take-last 10 (conj messages msg)))]
    (bput! hivemind/agent-registry agent-id
           {:messages new-messages
            :last-seen now})))

(defn- call-with-context
  "Call a wrapped handler with proper request context binding.
   Simulates what wrap-handler-context does in production."
  [wrapped args]
  (ctx/with-request-context {:project-id test-project
                              :directory test-dir}
    (wrapped (assoc args :directory test-dir))))

;; =============================================================================
;; End-to-End Tests: Shout -> Piggyback Delivery
;; =============================================================================

(deftest e2e-ling-shout-delivered-to-coordinator-via-piggyback-test
  (testing "Ling shout stored in agent-registry is delivered via ---HIVEMIND--- block"
    ;; Step 1: Ling shouts (store directly in registry, bypassing DataScript)
    (shout-directly! "swarm-test-ling-1" :progress "Found the bug!" test-project)

    ;; Step 2: Coordinator calls any tool -> piggyback should include the shout
    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)
          result (call-with-context wrapped {})]
      (is (some? (extract-hivemind-block result))
          "Coordinator should see ling's shout in ---HIVEMIND--- block")
      (let [block-text (extract-hivemind-block result)]
        (is (.contains block-text "swarm-test-ling-1")
            "Block should contain the ling's agent-id")
        (is (.contains block-text "Found the bug!")
            "Block should contain the shout message")))))

(deftest e2e-multiple-ling-shouts-delivered-in-order-test
  (testing "Multiple ling shouts are delivered in FIFO order"
    ;; Ling 1 shouts first
    (shout-directly! "ling-alpha" :started "Starting task" test-project)
    (Thread/sleep 2) ; Ensure different timestamps
    ;; Ling 2 shouts second
    (shout-directly! "ling-beta" :progress "Halfway done" test-project)
    (Thread/sleep 2)
    ;; Ling 1 shouts again
    (shout-directly! "ling-alpha" :completed "Task done" test-project)

    ;; Coordinator reads all shouts via piggyback
    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)
          result (call-with-context wrapped {})
          block-text (extract-hivemind-block result)]
      (is (some? block-text) "Should have ---HIVEMIND--- block")
      ;; Parse the EDN to verify order
      (let [msgs (clojure.edn/read-string block-text)]
        (is (= 3 (count msgs)) "Should have all 3 shouts")
        ;; Verify FIFO order by checking message content sequence
        (is (= "Starting task" (:m (first msgs)))
            "First message should be ling-alpha's start")
        (is (= "Halfway done" (:m (second msgs)))
            "Second message should be ling-beta's progress")
        (is (= "Task done" (:m (nth msgs 2)))
            "Third message should be ling-alpha's completion")))))

(deftest e2e-cursor-advances-no-redelivery-test
  (testing "Shouts are delivery-once: cursor advances, no re-delivery"
    ;; Shout
    (shout-directly! "ling-once" :progress "Delivered once" test-project)

    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
      ;; First call: delivered
      (let [r1 (call-with-context wrapped {})]
        (is (some? (extract-hivemind-block r1))
            "First call should deliver the shout"))

      ;; Second call: NOT re-delivered (cursor advanced)
      (let [r2 (call-with-context wrapped {})]
        (is (nil? (extract-hivemind-block r2))
            "Second call should NOT re-deliver (cursor advanced)")))))

(deftest e2e-new-shouts-after-cursor-advance-test
  (testing "New shouts after cursor advance are delivered on next call"
    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
      ;; Old shout
      (shout-directly! "ling-old" :progress "Old message" test-project)

      ;; Consume it
      (call-with-context wrapped {})

      ;; NEW shout arrives
      (Thread/sleep 2) ; Ensure timestamp > cursor
      (shout-directly! "ling-new" :completed "New message" test-project)

      ;; Should see only the new shout
      (let [result (call-with-context wrapped {})
            block-text (extract-hivemind-block result)]
        (is (some? block-text) "Should deliver new shout")
        (is (.contains block-text "ling-new")
            "Should contain new ling's agent-id")
        (is (not (.contains block-text "ling-old"))
            "Should NOT contain old ling's agent-id (already delivered)")))))

(deftest e2e-project-scoped-delivery-test
  (testing "Coordinator only sees shouts from its own project + global"
    ;; Shout with correct project
    (shout-directly! "ling-same-project" :progress "Same project" test-project)
    ;; Shout with different project
    (shout-directly! "ling-other-project" :progress "Other project" "other-project")
    ;; Shout with global scope
    (shout-directly! "ling-global" :progress "Global shout" "global")

    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)
          result (call-with-context wrapped {})
          block-text (extract-hivemind-block result)]
      (is (some? block-text) "Should have ---HIVEMIND--- block")
      ;; Parse and check
      (let [msgs (clojure.edn/read-string block-text)]
        ;; Should see same-project and global, NOT other-project
        (is (some #(= "ling-same-project" (:a %)) msgs)
            "Should include same-project shout")
        (is (some #(= "ling-global" (:a %)) msgs)
            "Should include global shout")
        (is (not (some #(= "ling-other-project" (:a %)) msgs))
            "Should NOT include other-project shout")))))

(deftest e2e-shout-fields-correctly-formatted-test
  (testing "Piggyback messages have correct compact format {:a :e :m}"
    (shout-directly! "ling-format" :completed "All done" test-project)

    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)
          result (call-with-context wrapped {})
          block-text (extract-hivemind-block result)
          msgs (clojure.edn/read-string block-text)
          msg (first msgs)]
      (is (= "ling-format" (:a msg)) ":a should be agent-id")
      (is (= "completed" (:e msg)) ":e should be event-type as string")
      (is (= "All done" (:m msg)) ":m should be message text"))))

(deftest e2e-ring-buffer-eviction-test
  (testing "Ring buffer (max 10) evicts oldest messages per agent"
    ;; Shout 12 times from same agent
    (doseq [i (range 12)]
      (shout-directly! "ling-rapid" :progress (str "Message " i) test-project)
      (Thread/sleep 2)) ; Ensure unique timestamps

    ;; Agent-registry should have only last 10
    ;; bounded-atom: bget returns unwrapped :data
    (let [agent-data (bget hivemind/agent-registry "ling-rapid")
          stored-count (count (:messages agent-data))]
      (is (= 10 stored-count)
          "Ring buffer should keep only last 10 messages"))

    ;; The coordinator receives the surviving 10 as ONE digested :progress row:
    ;; :n counts the rows it stands for, :m carries the newest message.
    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)
          result (call-with-context wrapped {})
          block-text (extract-hivemind-block result)
          msgs (clojure.edn/read-string block-text)]
      (is (= 1 (count msgs))
          "Piggyback should deliver one digested row for the burst")
      (is (= 10 (:n (first msgs)))
          "Digest should stand for 10 messages (0 and 1 evicted by ring buffer)")
      (is (= "Message 11" (:m (first msgs)))
          "Digest row should carry the newest message"))))

(deftest e2e-empty-registry-no-hivemind-block-test
  (testing "No ---HIVEMIND--- block when no shouts exist"
    ;; No shouts at all
    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)
          result (call-with-context wrapped {})]
      (is (nil? (extract-hivemind-block result))
          "Should NOT append ---HIVEMIND--- when no messages"))))

(deftest e2e-message-source-fn-integration-test
  (testing "all-hivemind-messages source function reads from agent-registry correctly"
    ;; This test verifies the DIP: hivemind.clj registers its message source,
    ;; and piggyback.clj reads from it. If source function is nil or returns
    ;; wrong format, piggyback delivery silently fails.
    (is (some? @pb/message-source-fn)
        "Message source function should be registered (by hivemind.clj)")

    ;; Store a shout
    (shout-directly! "ling-source-test" :started "Source test" test-project)

    ;; Verify source function returns the shout
    (let [all-msgs (@pb/message-source-fn)]
      (is (seq all-msgs) "Source function should return messages")
      (let [msg (first (filter #(= "ling-source-test" (:agent-id %)) all-msgs))]
        (is (some? msg) "Should find our test shout")
        (is (= :started (:event-type msg)) "Event type should be keyword")
        (is (= "Source test" (:message msg)) "Message should match")
        (is (= test-project (:project-id msg)) "Project-id should match")
        (is (number? (:timestamp msg)) "Timestamp should be a number")))))
