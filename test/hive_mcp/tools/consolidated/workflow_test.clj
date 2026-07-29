(ns hive-mcp.tools.consolidated.workflow-test
  "Tests for forge spark's ling-ready poll mechanism.

   Validates that wait-for-ling-ready correctly polls both DataScript
   registration AND CLI readiness before dispatching.

   Two-phase readiness:
     Phase 1: DataScript slave entry exists (usually instant after spawn!)
     Phase 2: CLI ready — mode-specific (vterm prompt marker, headless stdout)

   Since commit 508a7e8 the readiness module also:
     - picks the timeout per spawn-mode (`timeout-ms-for-spawn-mode`):
       vterm/claude -> `vterm-ling-ready-timeout-ms`,
       everything else -> config `[:services :forge :readiness-timeout-ms]`
       falling back to `default-ling-ready-timeout-ms`;
     - retries the whole poll ONCE after `readiness-retry-wait-ms` on timeout,
       reporting the CUMULATIVE elapsed-ms across both windows.
   The old single knob `ling-ready-timeout-ms` no longer exists — tests below
   drive `vterm-ling-ready-timeout-ms` (all poll tests use :claude mode).

   CLARITY: T - Telemetry (test) validates behavioral correctness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as d]
            [hive-mcp.config.core :as config]
            [hive-mcp.tools.consolidated.workflow.readiness :as readiness]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Access private functions via var
(def wait-for-ling-ready @#'readiness/wait-for-ling-ready)
(def timeout-ms-for-spawn-mode @#'readiness/timeout-ms-for-spawn-mode)

;; Keep the retry window tiny so timeout tests stay fast; production default is 2s.
(def ^:const test-retry-wait-ms 20)

(defn- settle!
  "Blocks up to timeout-ms for future f, cancelling it if still pending.
   Guarantees f is settled — done or cancelled — by the time this returns."
  [f timeout-ms]
  (when (= ::pending (deref f timeout-ms ::pending))
    (future-cancel f))
  nil)

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-conn* nil)

(defn isolated-ds-fixture
  "Each test gets a fresh DataScript connection.
   Swaps the value inside the private conn atom, preserving production conn."
  [f]
  (let [conn-atom  @#'conn/conn          ;; The atom itself (defonce ^:private conn (atom nil))
        test-conn  (d/create-conn schema/schema)
        saved-conn @conn-atom]            ;; Save current DS connection
    (reset! conn-atom test-conn)
    (binding [*test-conn* test-conn]
      (try
        (f)
        (finally
          (reset! conn-atom saved-conn))))))

(use-fixtures :each isolated-ds-fixture)

;; =============================================================================
;; Phase 1: DataScript Registration Tests
;; =============================================================================

(deftest test-ds-timeout-when-slave-missing
  (testing "Returns :ds-timeout when slave never appears in DataScript"
    (with-redefs [readiness/vterm-ling-ready-timeout-ms 150
                  readiness/ling-ready-poll-ms 10
                  readiness/readiness-retry-wait-ms test-retry-wait-ms]
      (let [result (wait-for-ling-ready "nonexistent-ling" :claude)]
        (is (not (:ready? result)) "Should not be ready")
        (is (>= (:attempts result) 2) "Should have polled multiple times")
        (is (= :ds-timeout (:phase result)) "Should be DS timeout phase")
        (is (nil? (:slave result)) "Should have no slave data")
        ;; Both poll windows (150ms each) plus the retry wait are accounted for.
        (is (>= (:elapsed-ms result) 300)
            "Should report cumulative elapsed across the initial poll + retry")))))

(deftest test-ds-delayed-registration
  (testing "Finds slave after delayed DataScript registration + CLI ready"
    (with-redefs [readiness/vterm-ling-ready-timeout-ms 5000
                  readiness/ling-ready-poll-ms 10
                  readiness/ling-cli-ready? (constantly true)]
      ;; Schedule slave registration after ~30ms
      (let [registrar (future
                        (Thread/sleep 30)
                        (ds-lings/add-slave! "delayed-ling" {:status :idle :depth 1 :cwd "/tmp"}))]
        (try
          (let [result (wait-for-ling-ready "delayed-ling" :claude)]
            (is (:ready? result) "Should eventually find the slave")
            (is (> (:attempts result) 1) "Should take more than one attempt")
            (is (some? (:slave result)) "Should include slave data")
            (is (= :cli-ready (:phase result)) "Should reach cli-ready phase"))
          (finally
            (settle! registrar 1000)))))))

;; =============================================================================
;; Phase 2: CLI Readiness Tests
;; =============================================================================

(deftest test-ready-when-ds-and-cli-both-pass
  (testing "Returns ready when DS has slave AND CLI readiness passes"
    (ds-lings/add-slave! "test-ling-001" {:status :idle :depth 1 :cwd "/tmp"})

    (with-redefs [readiness/ling-cli-ready? (constantly true)]
      (let [result (wait-for-ling-ready "test-ling-001" :claude)]
        (is (:ready? result) "Should be ready")
        (is (= 1 (:attempts result)) "Should find on first attempt")
        (is (< (:elapsed-ms result) 200) "Should be near-instant")
        (is (= :cli-ready (:phase result)) "Should report cli-ready phase")))))

(deftest test-cli-timeout-when-ds-exists-but-cli-not-ready
  (testing "Returns :cli-timeout when DS has slave but CLI never becomes ready"
    (ds-lings/add-slave! "stuck-ling" {:status :idle :depth 1 :cwd "/tmp"})

    (with-redefs [readiness/vterm-ling-ready-timeout-ms 150
                  readiness/ling-ready-poll-ms 10
                  readiness/readiness-retry-wait-ms test-retry-wait-ms
                  readiness/ling-cli-ready? (constantly false)]
      (let [result (wait-for-ling-ready "stuck-ling" :claude)]
        (is (not (:ready? result)) "Should not be ready")
        (is (>= (:attempts result) 2) "Should have polled multiple times")
        (is (= :cli-timeout (:phase result)) "Should be CLI timeout")
        (is (some? (:slave result)) "Should have slave data (DS found it)")))))

(deftest test-cli-delayed-readiness
  (testing "Polls until CLI becomes ready (DS present from start)"
    (ds-lings/add-slave! "slow-cli-ling" {:status :idle :depth 1 :cwd "/tmp"})

    (let [call-count (atom 0)]
      (with-redefs [readiness/vterm-ling-ready-timeout-ms 5000
                    readiness/ling-ready-poll-ms 10
                    ;; Guarded by agent-id: with-redefs replaces the var root
                    ;; process-wide, so a concurrent poll for another ling would
                    ;; otherwise advance this counter.
                    readiness/ling-cli-ready? (fn [agent-id _mode]
                                                ;; Ready after 3rd CLI check
                                                (and (= "slow-cli-ling" agent-id)
                                                     (>= (swap! call-count inc) 3)))]
        (let [result (wait-for-ling-ready "slow-cli-ling" :claude)]
          (is (:ready? result) "Should eventually be ready")
          (is (= 3 (:attempts result)) "Should take 3 attempts")
          (is (= 3 @call-count) "One CLI check per attempt, none from other agents")
          (is (= :cli-ready (:phase result))))))))

;; =============================================================================
;; Retry-After-Timeout (added in 508a7e8)
;; =============================================================================

(deftest test-retry-after-timeout-can-still-succeed
  (testing "A ling that registers after the first poll window is caught by the retry"
    (with-redefs [readiness/vterm-ling-ready-timeout-ms 150
                  readiness/ling-ready-poll-ms 10
                  readiness/readiness-retry-wait-ms 300
                  readiness/ling-cli-ready? (constantly true)]
      ;; Registers at ~250ms: too late for the initial 150ms window,
      ;; but well inside the retry window that starts at ~450ms.
      (let [registrar (future
                        (Thread/sleep 250)
                        (ds-lings/add-slave! "late-ling" {:status :idle :depth 1 :cwd "/tmp"}))]
        (try
          (let [result (wait-for-ling-ready "late-ling" :claude)]
            (is (:ready? result) "Should become ready on the retry pass")
            (is (= :cli-ready (:phase result)) "Should reach cli-ready phase")
            (is (some? (:slave result)) "Should include slave data")
            (is (>= (:elapsed-ms result) 400)
                "Elapsed should include the timed-out window + retry wait"))
          (finally
            (settle! registrar 1000)))))))

;; =============================================================================
;; Spawn-Mode Timeout Selection (added in 508a7e8)
;; =============================================================================

(deftest test-timeout-is-spawn-mode-aware
  (testing "vterm/claude use the short vterm timeout; other modes use configured/default"
    (with-redefs [readiness/vterm-ling-ready-timeout-ms 111
                  readiness/default-ling-ready-timeout-ms 222
                  ;; No config file in CI — resolve to the supplied :default.
                  config/get-service-value (fn [_service _field & {:keys [default]}] default)]
      (is (= 111 (timeout-ms-for-spawn-mode :claude)) "claude -> vterm timeout")
      (is (= 111 (timeout-ms-for-spawn-mode :vterm)) "vterm -> vterm timeout")
      (is (= 222 (timeout-ms-for-spawn-mode :headless)) "headless -> default timeout")
      (is (= 222 (timeout-ms-for-spawn-mode :agent-sdk)) "agent-sdk -> default timeout")))

  (testing "config [:services :forge :readiness-timeout-ms] overrides the default"
    (with-redefs [readiness/vterm-ling-ready-timeout-ms 111
                  config/get-service-value (fn [_service _field & _opts] 4242)]
      (is (= 4242 (timeout-ms-for-spawn-mode :headless)) "configured value wins")
      (is (= 111 (timeout-ms-for-spawn-mode :claude)) "vterm timeout is not configurable"))))

;; =============================================================================
;; Mode-Specific Dispatch Tests
;; =============================================================================

(deftest test-openrouter-always-cli-ready
  (testing "OpenRouter mode is always CLI-ready (API-based, no CLI startup)"
    (is (true? (readiness/ling-cli-ready? "or-ling" :openrouter))
        "ling-cli-ready? short-circuits to true for :openrouter")

    (ds-lings/add-slave! "or-ling" {:status :idle :depth 1 :cwd "/tmp"})

    ;; No mocking needed — openrouter returns true by default
    (let [result (wait-for-ling-ready "or-ling" :openrouter)]
      (is (:ready? result) "OpenRouter should be immediately ready")
      (is (= 1 (:attempts result)) "Should pass on first attempt"))))

(deftest test-agent-sdk-delegates-to-agent-sdk-ready
  ;; NOTE: :agent-sdk is NOT "always ready" any more — ling-cli-ready? dispatches
  ;; to agent-sdk-ready? (session :idle + live event-loop thread). Asserting
  ;; unconditional readiness here would just hang on the 60s default timeout.
  (testing "Agent SDK / Claude SDK modes dispatch to agent-sdk-ready?"
    (with-redefs [readiness/agent-sdk-ready? (constantly true)]
      (is (true? (readiness/ling-cli-ready? "sdk-ling" :agent-sdk)))
      (is (true? (readiness/ling-cli-ready? "sdk-ling" :claude-sdk))))
    (with-redefs [readiness/agent-sdk-ready? (constantly false)]
      (is (false? (readiness/ling-cli-ready? "sdk-ling" :agent-sdk)))
      (is (false? (readiness/ling-cli-ready? "sdk-ling" :claude-sdk)))))

  (testing "An agent-sdk ling with a live session is ready on the first poll"
    (ds-lings/add-slave! "sdk-ling" {:status :idle :depth 1 :cwd "/tmp"})

    (with-redefs [readiness/agent-sdk-ready? (constantly true)]
      (let [result (wait-for-ling-ready "sdk-ling" :agent-sdk)]
        (is (:ready? result) "Agent SDK should be ready once its session is idle")
        (is (= 1 (:attempts result)) "Should pass on first attempt")))))

(deftest test-headless-mode-dispatches-to-headless-ready
  (testing "Headless / claude-process modes dispatch to headless-ready?"
    (with-redefs [readiness/headless-ready? (constantly true)]
      (is (true? (readiness/ling-cli-ready? "hl-ling" :headless)))
      (is (true? (readiness/ling-cli-ready? "hl-ling" :claude-process))))
    (with-redefs [readiness/headless-ready? (constantly false)]
      (is (false? (readiness/ling-cli-ready? "hl-ling" :headless))))))

(deftest test-claude-mode-dispatches-to-vterm-ready
  (testing "Claude / vterm modes dispatch to vterm-ready?"
    (with-redefs [readiness/vterm-ready? (constantly true)]
      (is (true? (readiness/ling-cli-ready? "claude-ling" :claude)))
      (is (true? (readiness/ling-cli-ready? "claude-ling" :vterm))))
    (with-redefs [readiness/vterm-ready? (constantly false)]
      (is (false? (readiness/ling-cli-ready? "claude-ling" :claude))))))

(deftest test-claude-mode-calls-vterm-ready
  (testing "Claude mode delegates to vterm-ready? via ling-cli-ready?"
    (ds-lings/add-slave! "claude-ling" {:status :idle :depth 1 :cwd "/tmp"})

    (let [checked-modes (atom [])]
      (with-redefs [readiness/ling-cli-ready? (fn [_id mode]
                                                (swap! checked-modes conj mode)
                                                true)]
        (wait-for-ling-ready "claude-ling" :claude)
        (is (= [:claude] @checked-modes) "Should call ling-cli-ready? with :claude")))))

(deftest test-headless-mode-calls-headless-ready
  (testing "Headless mode delegates to headless-ready? via ling-cli-ready?"
    (ds-lings/add-slave! "headless-ling" {:status :idle :depth 1 :cwd "/tmp"})

    (let [checked-modes (atom [])]
      (with-redefs [readiness/ling-cli-ready? (fn [_id mode]
                                                (swap! checked-modes conj mode)
                                                true)]
        (wait-for-ling-ready "headless-ling" :headless)
        (is (= [:headless] @checked-modes) "Should call ling-cli-ready? with :headless")))))

;; =============================================================================
;; Poll Timing Tests
;; =============================================================================

(deftest test-poll-timing
  (testing "Poll intervals are fixed at ling-ready-poll-ms (no backoff)"
    (with-redefs [readiness/vterm-ling-ready-timeout-ms 200
                  readiness/ling-ready-poll-ms 50
                  readiness/readiness-retry-wait-ms test-retry-wait-ms
                  readiness/ling-cli-ready? (constantly false)]
      ;; Register slave so we hit CLI check (not DS timeout)
      (ds-lings/add-slave! "timing-ling" {:status :idle :depth 1 :cwd "/tmp"})

      (let [start (System/currentTimeMillis)
            result (wait-for-ling-ready "timing-ling" :claude)
            elapsed (- (System/currentTimeMillis) start)]
        ;; Fixed 50ms interval against a 200ms timeout => ~5 attempts per window.
        ;; Exponential backoff would bail out in far fewer.
        (is (<= 3 (:attempts result) 8)
            "Should poll at a fixed interval (~timeout/poll attempts), not back off")
        ;; Two 200ms windows + the retry wait => ~420ms.
        (is (>= elapsed 400) "Should have spent time polling both windows")
        (is (<= elapsed 1200) "Should not overshoot timeout by too much")
        (is (not (:ready? result)) "Should have timed out")))))
