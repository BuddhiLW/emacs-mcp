(ns hive-mcp.channel.piggyback-cursor-test
  "Tests for hivemind piggyback cursor stability.

   Pins down the bug where different agent-ids in tool args created
   spurious cursor keys, causing ALL accumulated shouts to be
   re-delivered from timestamp 0 on every dispatch to a new target.

   See: CURSOR IDENTITY FIX in server/routes.clj"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.channel.piggyback :as pb]
            [hive-mcp.delivery.channels :as channels]
            [hive-mcp.protocols.delivery-channel :as dc]))

;; The :each fixture is registered after `with-clean-pb-state` is defined,
;; below. `use-fixtures :each` REPLACES the fixture list rather than appending,
;; so this namespace must register exactly once.

;; =============================================================================
;; Cursor Key Stability — The Core Bug
;; =============================================================================

(deftest different-agent-ids-create-independent-cursors-test
  (testing "REGRESSION: Different agent-ids get independent cursors (the underlying behavior)"
    ;; This test documents WHY the bug existed:
    ;; piggyback/get-messages uses [agent-id project-id] as cursor key.
    ;; When middleware passes different agent-ids (from dispatch targets),
    ;; each creates an independent cursor starting from 0.
    ;;
    ;; Both readers here are COORDINATOR lanes. Since the audience filter
    ;; landed (hive-mcp.channel.audience) a reader that is neither the
    ;; shouter's spawner nor a coordinator receives nothing at all, so
    ;; probing cursor independence with an arbitrary ling id would now
    ;; measure routing rather than the cursor.
    (let [messages (atom [{:agent-id "ling-1"
                           :event-type :progress
                           :message "working"
                           :timestamp 1000
                           :project-id "proj-A"}])]
      (pb/register-message-source! (fn [] @messages))

      ;; First read with coordinator-proj-A: gets the message, advances cursor
      (let [r1 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (= 1 (count r1)) "coordinator reads 1 message"))

      ;; Same coordinator reads again: nothing new
      (let [r2 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (nil? r2) "same cursor key returns nil — already read"))

      ;; DIFFERENT agent-id reads: gets the message AGAIN (fresh cursor!)
      ;; This is the ROOT CAUSE — if middleware uses target agent-id,
      ;; every dispatch to a new target re-reads all messages.
      (let [r3 (pb/get-messages "coordinator-other" :project-id "proj-A")]
        (is (= 1 (count r3)) "different agent-id = fresh cursor = re-delivery")))))

(deftest stable-cursor-key-prevents-re-delivery-test
  (testing "Using the SAME agent-id for all reads prevents re-delivery"
    (let [messages (atom [{:agent-id "ling-1"
                           :event-type :progress
                           :message "task 1 progress"
                           :timestamp 1000
                           :project-id "proj-A"}])]
      (pb/register-message-source! (fn [] @messages))

      ;; Read 1: coordinator sees 1 message
      (let [r1 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (= 1 (count r1))))

      ;; Simulate: new shout arrives
      (swap! messages conj {:agent-id "ling-2"
                            :event-type :completed
                            :message "task 2 done"
                            :timestamp 2000
                            :project-id "proj-A"})

      ;; Read 2: same cursor key — only the NEW message
      (let [r2 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (= 1 (count r2)) "only 1 new message, not 2")
        (is (= "task 2 done" (:m (first r2)))))

      ;; Read 3: nothing new
      (let [r3 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (nil? r3) "cursor advanced, nothing to deliver")))))

(deftest ever-growing-piggyback-regression-test
  (testing "REGRESSION: Simulates the exact bug — dispatching to N agents re-delivers all N times"
    ;; Scenario: coordinator dispatches to 3 different lings.
    ;; With the bug, each dispatch used the TARGET agent-id as cursor key,
    ;; so each got ALL accumulated shouts from timestamp 0.
    ;;
    ;; The three "wrong behavior" readers are coordinator-lane ids so this
    ;; keeps measuring the CURSOR. Routing is a separate axis, covered by
    ;; hive-mcp.channel.audience-test.
    (let [shouts (atom [{:agent-id "ling-scout" :event-type :progress
                         :message "scouting" :timestamp 100
                         :project-id "hive-mcp"}
                        {:agent-id "ling-worker" :event-type :started
                         :message "started" :timestamp 200
                         :project-id "hive-mcp"}
                        {:agent-id "ling-tester" :event-type :completed
                         :message "tests pass" :timestamp 300
                         :project-id "hive-mcp"}])]
      (pb/register-message-source! (fn [] @shouts))

      ;; WRONG behavior (before fix): each target-id creates fresh cursor
      ;; Dispatch 1: using target "coordinator-orchestrator" as cursor key
      (let [r1 (pb/get-messages "coordinator-orchestrator" :project-id "hive-mcp")]
        (is (= 3 (count r1)) "fresh cursor → all 3 shouts"))

      ;; Dispatch 2: using target "coordinator-permissions" as cursor key
      (let [r2 (pb/get-messages "coordinator-permissions" :project-id "hive-mcp")]
        (is (= 3 (count r2)) "DIFFERENT cursor key → all 3 AGAIN"))

      ;; Dispatch 3: using target "coordinator-session" as cursor key
      (let [r3 (pb/get-messages "coordinator-session" :project-id "hive-mcp")]
        (is (= 3 (count r3)) "ANOTHER cursor key → all 3 AGAIN"))

      ;; CORRECT behavior: using STABLE coordinator ID for all reads
      (pb/reset-all-cursors!)

      ;; Read 1: coordinator-hive-mcp reads all 3 (expected on first read)
      (let [r1 (pb/get-messages "coordinator-hive-mcp" :project-id "hive-mcp")]
        (is (= 3 (count r1)) "first read: all 3 shouts"))

      ;; Read 2: same coordinator key — nothing new
      (let [r2 (pb/get-messages "coordinator-hive-mcp" :project-id "hive-mcp")]
        (is (nil? r2) "no re-delivery with stable cursor key")))))

;; =============================================================================
;; Cross-Session Stale Messages
;; =============================================================================

(deftest stale-messages-from-previous-session-test
  (testing "Messages from previous sessions are delivered once on first read, then cursor advances"
    (let [;; Old messages from previous session (lower timestamps)
          old-shouts [{:agent-id "old-ling" :event-type :completed
                       :message "old work done" :timestamp 1000
                       :project-id "proj-A"}
                      {:agent-id "old-ling-2" :event-type :error
                       :message "old error" :timestamp 2000
                       :project-id "proj-A"}]
          ;; New messages from current session
          new-shout {:agent-id "new-ling" :event-type :started
                     :message "new work started" :timestamp 5000
                     :project-id "proj-A"}
          all-messages (atom (vec (conj old-shouts new-shout)))]
      (pb/register-message-source! (fn [] @all-messages))

      ;; First read: gets ALL messages (old + new), expected
      (let [r1 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (= 3 (count r1)) "first read includes old + new"))

      ;; Cursor now at timestamp 5000
      ;; Second read: nothing new
      (let [r2 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (nil? r2) "cursor advanced past all messages"))

      ;; New message arrives at timestamp 6000
      (swap! all-messages conj {:agent-id "new-ling" :event-type :progress
                                :message "progressing" :timestamp 6000
                                :project-id "proj-A"})

      ;; Third read: only the truly new message
      (let [r3 (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (= 1 (count r3)))
        (is (= "progressing" (:m (first r3))))))))

;; =============================================================================
;; Cursor Isolation Between Projects
;; =============================================================================

(deftest project-cursor-isolation-test
  (testing "coordinator-proj-A cursor is independent of coordinator-proj-B"
    (let [messages (atom [{:agent-id "ling-1" :event-type :progress
                           :message "A work" :timestamp 1000
                           :project-id "proj-A"}
                          {:agent-id "ling-2" :event-type :progress
                           :message "B work" :timestamp 1000
                           :project-id "proj-B"}])]
      (pb/register-message-source! (fn [] @messages))

      ;; proj-A coordinator reads proj-A messages
      (let [ra (pb/get-messages "coordinator-proj-A" :project-id "proj-A")]
        (is (= 1 (count ra)))
        (is (= "A work" (:m (first ra)))))

      ;; proj-B coordinator reads proj-B messages (not affected by proj-A cursor)
      (let [rb (pb/get-messages "coordinator-proj-B" :project-id "proj-B")]
        (is (= 1 (count rb)))
        (is (= "B work" (:m (first rb)))))

      ;; Both cursors advanced — re-read returns nil
      (is (nil? (pb/get-messages "coordinator-proj-A" :project-id "proj-A")))
      (is (nil? (pb/get-messages "coordinator-proj-B" :project-id "proj-B"))))))

;; =============================================================================
;; Multi-Coordinator Instance Isolation (bb-mcp instance-id)
;; =============================================================================

(deftest multi-coordinator-instance-isolation-test
  (testing "Two coordinator instances with different instance-ids get independent cursors"
    ;; Simulates the real scenario: two bb-mcp processes both have
    ;; CLAUDE_SWARM_SLAVE_ID="coordinator", but each appends a unique
    ;; instance-id (e.g. "coordinator:a1b2c3d4" vs "coordinator:e5f6g7h8").
    ;; Neither should consume the other's messages.
    (let [messages (atom [{:agent-id "ling-worker" :event-type :progress
                           :message "building widgets" :timestamp 1000
                           :project-id "proj-X"}
                          {:agent-id "ling-tester" :event-type :completed
                           :message "tests pass" :timestamp 2000
                           :project-id "proj-X"}])
          coord-a "coordinator:a1b2c3d4"
          coord-b "coordinator:e5f6g7h8"]
      (pb/register-message-source! (fn [] @messages))

      ;; Coordinator A reads — gets both messages
      (let [ra (pb/get-messages coord-a :project-id "proj-X")]
        (is (= 2 (count ra)) "coord-A gets all messages on first read"))

      ;; Coordinator B reads — also gets both (independent cursor)
      (let [rb (pb/get-messages coord-b :project-id "proj-X")]
        (is (= 2 (count rb)) "coord-B gets all messages independently"))

      ;; Both re-read — nothing new for either
      (is (nil? (pb/get-messages coord-a :project-id "proj-X"))
          "coord-A cursor advanced, nothing new")
      (is (nil? (pb/get-messages coord-b :project-id "proj-X"))
          "coord-B cursor advanced, nothing new")

      ;; New message arrives
      (swap! messages conj {:agent-id "ling-deployer" :event-type :started
                            :message "deploying" :timestamp 3000
                            :project-id "proj-X"})

      ;; Both coordinators independently see the new message
      (let [ra2 (pb/get-messages coord-a :project-id "proj-X")]
        (is (= 1 (count ra2)) "coord-A sees only the new message")
        (is (= "deploying" (:m (first ra2)))))

      (let [rb2 (pb/get-messages coord-b :project-id "proj-X")]
        (is (= 1 (count rb2)) "coord-B sees only the new message")
        (is (= "deploying" (:m (first rb2))))))))

;; =============================================================================
;; Dual-Path Shape Dedup — PiggybackChannel must thread :shout-id
;; =============================================================================
;;
;; Regression: PiggybackChannel.deliver! was destructuring the event payload
;; without :shout-id, so buffer-backbone-event! saw shout-id=nil. The atom
;; path kept :shout-id (uuid string); the backbone path lost it and fell
;; back to the composite key [agent-id timestamp event-type]. The two keys
;; never matched → both shapes survived dedup → the ---HIVEMIND--- block
;; showed the same shout twice (once with :t, once without).

(defn- with-clean-pb-state [f]
  (let [original-source @pb/message-source-fn]
    (pb/reset-all-cursors!)
    (pb/clear-backbone-buffer!)
    (try (f)
         (finally
           (pb/reset-all-cursors!)
           (pb/clear-backbone-buffer!)
           (pb/register-message-source! original-source)))))

(use-fixtures :each with-clean-pb-state)

(deftest piggyback-channel-preserves-shout-id-test
  (with-clean-pb-state
    (fn []
      (testing "REGRESSION: PiggybackChannel.deliver! must thread :shout-id into backbone-buffer"
        (let [shout-id (str (random-uuid))
              payload  {:agent-id   "ling-shape"
                        :event-type :progress
                        :message    "w"
                        :task       "t"
                        :timestamp  (System/currentTimeMillis)
                        :project-id "proj-shape"
                        :shout-id   shout-id}]
          (dc/deliver! (channels/create-piggyback-channel) payload)
          (let [[buf] @pb/backbone-buffer]
            (is (some? buf) "buffer should receive one entry")
            (is (= shout-id (:shout-id buf))
                "PiggybackChannel must forward :shout-id — otherwise dedup key collapses to composite")))))))

(deftest atom-and-backbone-shape-mismatch-dedups-to-one-test
  (with-clean-pb-state
    (fn []
      (testing "REGRESSION: atom projection (no :task, keyword event-type) +
                backbone projection (with :task, string event-type) +
                same :shout-id → exactly one message after dedup"
        (let [shout-id (str (random-uuid))
              now      (System/currentTimeMillis)
              ;; Atom path — mirrors all-hivemind-messages projection
              atom-msg {:agent-id   "ling-shape"
                        :event-type :started
                        :message    "Add X"
                        :task       "Add X"          ;; normalized projection now carries :task
                        :timestamp  now
                        :project-id "proj-shape"
                        :shout-id   shout-id}
              ;; Backbone path — via PiggybackChannel, string event-type after JSON roundtrip
              backbone-payload {:agent-id   "ling-shape"
                                :event-type "started"
                                :message    "Add X"
                                :task       "Add X"
                                :timestamp  now
                                :project-id "proj-shape"
                                :shout-id   shout-id}]
          (pb/register-message-source! (constantly [atom-msg]))
          (dc/deliver! (channels/create-piggyback-channel) backbone-payload)
          (let [msgs (pb/get-messages "coordinator-shape" :project-id "proj-shape")]
            (is (= 1 (count msgs))
                "shape mismatch must not defeat :shout-id dedup")
            ;; Surviving copy must carry :t so downstream renderers see the task
            (is (= "Add X" (:t (first msgs)))
                "surviving projection must retain :task → :t")))))))

(deftest dedup-fallback-survives-missing-shout-id-test
  (with-clean-pb-state
    (fn []
      (testing "Defense in depth: if shout-id is missing, fallback composite key still dedups"
        ;; This pins the fallback path — if a future regression drops :shout-id
        ;; again, the composite key [agent-id timestamp event-type-name] must
        ;; still collapse atom + backbone copies of the same shout.
        (let [now      (System/currentTimeMillis)
              atom-msg {:agent-id   "ling-fallback"
                        :event-type :completed
                        :message    "done"
                        :timestamp  now
                        :project-id "proj-fallback"}
              backbone-payload {:agent-id   "ling-fallback"
                                :event-type "completed"   ;; stringified, no shout-id
                                :message    "done"
                                :timestamp  now
                                :project-id "proj-fallback"}]
          (pb/register-message-source! (constantly [atom-msg]))
          (dc/deliver! (channels/create-piggyback-channel) backbone-payload)
          (let [msgs (pb/get-messages "coordinator-fallback" :project-id "proj-fallback")]
            (is (= 1 (count msgs))
                "composite fallback must dedup keyword vs string event-type")))))))
