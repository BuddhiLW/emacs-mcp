(ns hive-mcp.server.piggyback-middleware-test
  "Tests for piggyback middleware cursor identity stability.

   Pins down the fix where the MEMORY channel uses _caller_id only
   (session-scoped) for buffer keys, ensuring enqueue and drain always use the
   same key regardless of project-id resolution differences.

   The HIVEMIND channel STILL uses project-scoped identity for cross-project
   shout filtering — different semantics.

   NOTE: the per-channel wrappers (wrap-handler-piggyback,
   wrap-handler-memory-piggyback) were merged into the single
   `routes/wrap-handler-piggybacks` (hive-mcp.server.routes.middleware), which
   drains all four channels — TOOLRESULT, MEMORY, catchup, HIVEMIND — in one
   pass. Both semantics above are preserved inside it, so the tests below
   exercise the unified wrapper and assert on the individual blocks."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.channel.piggyback :as pb]
            [hive-mcp.channel.memory-piggyback :as mp]
            [hive-mcp.agent.context :as ctx]))

;; The project-id that extract-project-id resolves for our test directory.
;; Used for hivemind piggyback (still project-scoped).
(def ^:private test-dir "/home/lages/PP/hive/hive-mcp")
(def ^:private test-project "hive-mcp")

;; Reset all state between tests
(use-fixtures :each
  (fn [f]
    (pb/reset-all-cursors!)
    (mp/reset-all!)
    (f)
    (pb/reset-all-cursors!)
    (mp/reset-all!)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- dummy-handler
  "Handler that returns normalized content (vector of text items)."
  [_args]
  [{:type "text" :text "{\"success\": true}"}])

(defn- extract-hivemind-block
  "Extract ---HIVEMIND--- block content from handler response."
  [content]
  (some (fn [{:keys [text]}]
          (when text
            (second (re-find #"---HIVEMIND---\n([\s\S]*?)\n---/HIVEMIND---" text))))
        content))

(defn- extract-memory-block
  "Extract ---MEMORY--- block content from handler response."
  [content]
  (some (fn [{:keys [text]}]
          (when text
            (second (re-find #"---MEMORY---\n([\s\S]*?)\n---/MEMORY---" text))))
        content))

(defn- call-wrapped
  "Call a wrapped handler with args, binding ctx from the same args.
   Simulates the full context binding that wrap-handler-context does."
  [wrapped args]
  (let [agent-id (:agent_id args)
        project-id test-project]
    (ctx/with-request-context (cond-> {:project-id project-id
                                       :directory test-dir}
                                agent-id (assoc :agent-id agent-id))
      (wrapped (assoc args :directory test-dir)))))

;; =============================================================================
;; Hivemind Piggyback Middleware — Cursor Identity (STILL project-scoped)
;; =============================================================================

(deftest hivemind-piggyback-stable-cursor-across-dispatch-targets-test
  (testing "HIVEMIND channel uses stable cursor regardless of agent_id in args"
    (let [shouts (atom [{:agent-id "ling-1" :event-type :progress
                         :message "working" :timestamp 1000
                         :project-id test-project}])]
      (pb/register-message-source! (fn [] @shouts))

      (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
        ;; Call 1: simulate `agent dispatch agent_id="swarm-target-A"`
        (let [r1 (call-wrapped wrapped {:agent_id "swarm-target-A"})]
          (is (some? (extract-hivemind-block r1))
              "first call: shouts delivered"))

        ;; Call 2: DIFFERENT target — cursor should be the SAME coordinator key
        (let [r2 (call-wrapped wrapped {:agent_id "swarm-target-B"})]
          (is (nil? (extract-hivemind-block r2))
              "second call with different target: NO re-delivery (cursor stable)"))

        ;; Call 3: yet another target
        (let [r3 (call-wrapped wrapped {:agent_id "swarm-target-C"})]
          (is (nil? (extract-hivemind-block r3))
              "third call: still no re-delivery"))))))

(deftest hivemind-piggyback-delivers-new-shouts-after-cursor-advance-test
  (testing "New shouts are still delivered after cursor advances"
    (let [shouts (atom [{:agent-id "ling-1" :event-type :started
                         :message "started" :timestamp 1000
                         :project-id test-project}])]
      (pb/register-message-source! (fn [] @shouts))

      (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
        ;; First call: drains existing shout
        (call-wrapped wrapped {})

        ;; New shout arrives
        (swap! shouts conj {:agent-id "ling-2" :event-type :completed
                            :message "done" :timestamp 2000
                            :project-id test-project})

        ;; Second call: new shout delivered
        (let [r2 (call-wrapped wrapped {})]
          (is (some? (extract-hivemind-block r2))
              "new shout delivered after cursor advance"))))))

;; =============================================================================
;; Memory Piggyback Middleware — Session-Scoped (caller-id only)
;; =============================================================================

(deftest memory-piggyback-session-scoped-drain-test
  (testing "MEMORY channel drains by _caller_id, not project-id"
    ;; Enqueue with raw caller-id "coordinator" (matches middleware's key)
    (mp/enqueue! "coordinator"
                 [{:id "ax-1" :type "axiom" :content "Rule 1" :tags ["axiom"]}
                  {:id "cv-1" :type "convention" :content "Conv 1" :tags ["convention"]}])

    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
      ;; Call 1: with target agent_id in args — uses _caller_id (nil → "coordinator")
      (let [r1 (call-wrapped wrapped {:agent_id "swarm-target-X"})]
        (is (some? (extract-memory-block r1))
            "first call: memory entries drained for coordinator key"))

      ;; Call 2: different target — buffer already drained
      (let [r2 (call-wrapped wrapped {:agent_id "swarm-target-Y"})]
        (is (nil? (extract-memory-block r2))
            "second call: buffer already drained, no re-delivery")))))

(deftest memory-piggyback-no-args-agent-id-uses-coordinator-test
  (testing "Tools without agent_id in args still drain correctly (coordinator default)"
    (mp/enqueue! "coordinator"
                 [{:id "n-1" :type "note" :content "A note" :tags []}])

    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
      ;; Call without agent_id in args (e.g., memory query)
      (let [r1 (call-wrapped wrapped {})]
        (is (some? (extract-memory-block r1))
            "drains using coordinator default when no agent_id in args")))))

(deftest memory-piggyback-with-caller-id-test
  (testing "Explicit _caller_id is used for buffer key"
    (let [caller "coordinator:abc123"]
      (mp/enqueue! caller
                   [{:id "ax-1" :type "axiom" :content "With caller" :tags []}])

      (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
        ;; Pass _caller_id in args — middleware uses it directly
        (let [r1 (call-wrapped wrapped {:_caller_id caller})]
          (is (some? (extract-memory-block r1))
              "drains using explicit _caller_id"))))))

;; =============================================================================
;; Mixed Scenario — Full Middleware Simulation
;; =============================================================================

(deftest mixed-tool-calls-stable-cursor-test
  (testing "Interleaved dispatch and non-dispatch calls share the same cursor"
    (let [shouts (atom [{:agent-id "ling-1" :event-type :started
                         :message "ling started" :timestamp 1000
                         :project-id test-project}])]
      (pb/register-message-source! (fn [] @shouts))

      (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
        ;; Call 1: memory query (no agent_id in args) — reads shout
        (let [r1 (call-wrapped wrapped {})]
          (is (some? (extract-hivemind-block r1))
              "memory query: reads shout"))

        ;; Call 2: agent dispatch to target A — cursor already advanced
        (let [r2 (call-wrapped wrapped {:agent_id "swarm-target-A"})]
          (is (nil? (extract-hivemind-block r2))
              "dispatch target-A: no re-delivery"))

        ;; Call 3: another memory query — still nothing
        (let [r3 (call-wrapped wrapped {})]
          (is (nil? (extract-hivemind-block r3))
              "second memory query: cursor still advanced"))

        ;; New shout arrives
        (swap! shouts conj {:agent-id "ling-2" :event-type :completed
                            :message "ling done" :timestamp 2000
                            :project-id test-project})

        ;; Call 4: dispatch to target B — picks up only the NEW shout
        (let [r4 (call-wrapped wrapped {:agent_id "swarm-target-B"})]
          (is (some? (extract-hivemind-block r4))
              "dispatch target-B: only NEW shout delivered"))))))

;; =============================================================================
;; REGRESSION: Catchup Enqueue Key Alignment
;; =============================================================================

(deftest catchup-enqueue-key-aligns-with-middleware-drain-test
  (testing "FIX: Buffer enqueued with raw caller-id is drained by middleware"
    ;; Enqueue with raw "coordinator" — matches middleware's _caller_id fallback
    (mp/enqueue! "coordinator"
                 [{:id "ax-fix" :type "axiom" :content "Delivered axiom" :tags ["axiom"]}])

    (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
      ;; Middleware uses (or (:_caller_id args) "coordinator") → "coordinator"
      (let [r1 (call-wrapped wrapped {})]
        (is (some? (extract-memory-block r1))
            "buffer key aligned: enqueue and drain both use raw caller-id"))))

  (testing "FIX: Explicit _caller_id aligns enqueue and drain"
    (let [caller-id "coordinator:instance-42"]
      ;; Enqueue with the same _caller_id the middleware will see
      (mp/enqueue! caller-id
                   [{:id "ax-explicit" :type "axiom" :content "Explicit caller" :tags ["axiom"]}])

      (let [wrapped (routes/wrap-handler-piggybacks dummy-handler)]
        (let [r1 (call-wrapped wrapped {:_caller_id caller-id})]
          (is (some? (extract-memory-block r1))
              "explicit _caller_id: enqueue and drain keys match"))))))
