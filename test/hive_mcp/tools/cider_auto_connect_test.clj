(ns hive-mcp.tools.cider-auto-connect-test
  "TDD tests for CIDER auto-connect fallback feature.

   When cider_eval_silent/explicit receive 'CIDER not connected' error,
   the system should:
   1. List available sessions
   2. If sessions exist, switch to one
   3. If no sessions, spawn a new 'auto' session
   4. Retry the eval

   This prevents agents from getting stuck on 'not connected' errors."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.tools.cider :as cider]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.test.stub.elisp :as elisp-stub]))

;; The eval path calls eval-elisp-with-timeout; route it through whatever
;; eval-elisp each test mocks (var resolved at call time).
(use-fixtures :each
  elisp-stub/with-elisp-builders
  (fn [t]
    (with-redefs [ec/eval-elisp-with-timeout (fn [elisp & _] (ec/eval-elisp elisp))]
      (t))))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn mock-ec-calls
  "Creates a stateful mock that returns different values based on call count.
   Takes a map of {call-pattern response} where call-pattern is a keyword
   like :first, :second or a string pattern to match in the elisp."
  [responses]
  (let [call-count (atom 0)]
    (fn [elisp]
      (let [n (swap! call-count inc)]
        (cond
          ;; Match by call number
          (and (= n 1) (:first responses))
          (:first responses)

          (and (= n 2) (:second responses))
          (:second responses)

          (and (= n 3) (:third responses))
          (:third responses)

          ;; Match by elisp content
          (and (str/includes? elisp "eval-silent") (:eval-silent responses))
          (:eval-silent responses)

          (and (str/includes? elisp "list-sessions") (:list-sessions responses))
          (:list-sessions responses)

          (and (str/includes? elisp "spawn-session") (:spawn-session responses))
          (:spawn-session responses)

          (and (str/includes? elisp "cider-status") (:cider-status responses))
          (:cider-status responses)

          :else
          {:success true :result "nil" :duration-ms 10})))))

(defn mock-not-connected
  "Returns error response for 'CIDER not connected'."
  []
  {:success false :error "CIDER not connected" :duration-ms 10})

(defn mock-success
  "Returns success response with given result."
  [result]
  {:success true :result result :duration-ms 10})

;; =============================================================================
;; Auto-Connect Fallback Tests
;; =============================================================================

(deftest eval-silent-triggers-auto-connect-on-not-connected-test
  (testing "eval-silent attempts auto-connect when CIDER not connected"
    (let [calls (atom [])
          session-spawned (atom false)]
      (with-redefs [ec/eval-elisp (fn [elisp]
                                    (swap! calls conj elisp)
                                    (cond
                                       ;; First eval fails - not connected
                                      (and (str/includes? elisp "eval-silent")
                                           (not @session-spawned))
                                      (mock-not-connected)

                                       ;; List sessions returns empty initially, then connected after spawn
                                      (str/includes? elisp "list-sessions")
                                      (if @session-spawned
                                        (mock-success "[{\"name\":\"auto\",\"port\":7920,\"status\":\"connected\"}]")
                                        (mock-success "[]"))

                                       ;; Spawn session succeeds
                                      (str/includes? elisp "spawn-session")
                                      (do (reset! session-spawned true)
                                          (mock-success "{\"name\":\"auto\",\"port\":7920,\"status\":\"starting\"}"))

                                       ;; Retry eval succeeds (after session spawned)
                                      (str/includes? elisp "eval-silent")
                                      (mock-success "42")

                                      :else
                                      (mock-success "nil")))]
        (let [result (cider/handle-cider-eval-silent {:code "(+ 1 41)"})]
          ;; Should succeed after auto-connect
          (is (= "text" (:type result)))
          (is (= "42" (:text result)))
          (is (nil? (:isError result)))
          ;; Verify the flow: eval -> list -> spawn -> list (wait) -> eval
          (is (>= (count @calls) 4) "Should have multiple calls for auto-connect flow"))))))

(deftest eval-silent-uses-existing-session-test
  (testing "eval-silent uses existing session when available"
    (let [calls (atom [])]
      (with-redefs [ec/eval-elisp (fn [elisp]
                                    (swap! calls conj elisp)
                                    (cond
                                       ;; First eval fails - not connected
                                      (and (str/includes? elisp "eval-silent")
                                           (= 1 (count (filter #(str/includes? % "eval-silent") @calls))))
                                      (mock-not-connected)

                                       ;; List sessions returns existing session
                                      (str/includes? elisp "list-sessions")
                                      (mock-success "[{\"name\":\"existing\",\"port\":7920,\"status\":\"connected\"}]")

                                       ;; Eval-in-session succeeds
                                      (str/includes? elisp "eval-in-session")
                                      (mock-success "42")

                                       ;; Fallback retry eval succeeds
                                      (str/includes? elisp "eval-silent")
                                      (mock-success "42")

                                      :else
                                      (mock-success "nil")))]
        (let [result (cider/handle-cider-eval-silent {:code "(+ 1 41)"})]
          ;; Should succeed using existing session
          (is (= "text" (:type result)))
          (is (nil? (:isError result))))))))

(deftest eval-silent-no-retry-loop-test
  (testing "eval-silent doesn't infinite loop on persistent failure"
    (let [call-count (atom 0)]
      (with-redefs [ec/eval-elisp (fn [_elisp]
                                    (swap! call-count inc)
                                     ;; Always return not connected
                                    (mock-not-connected))]
        (let [result (cider/handle-cider-eval-silent {:code "(+ 1 1)"})]
          ;; Should eventually give up with error
          (is (true? (:isError result)))
          (is (str/includes? (:text result) "Error"))
          ;; Should not make excessive calls (auto-connect has one retry attempt)
          (is (< @call-count 10) "Should not retry excessively"))))))

(deftest eval-explicit-also-triggers-auto-connect-test
  (testing "eval-explicit also attempts auto-connect when not connected"
    (let [auto-connect-attempted (atom false)]
      (with-redefs [ec/eval-elisp (fn [elisp]
                                    (when (str/includes? elisp "list-sessions")
                                      (reset! auto-connect-attempted true))
                                    (cond
                                       ;; First eval fails
                                      (str/includes? elisp "eval-explicit")
                                      (if @auto-connect-attempted
                                        (mock-success "Sent to REPL")
                                        (mock-not-connected))

                                       ;; List sessions
                                      (str/includes? elisp "list-sessions")
                                      (mock-success "[{\"name\":\"auto\",\"status\":\"connected\"}]")

                                      :else
                                      (mock-success "nil")))]
        (let [result (cider/handle-cider-eval-explicit {:code "(println \"test\")"})]
          (is @auto-connect-attempted "Should attempt auto-connect")
          (is (nil? (:isError result))))))))

(deftest auto-connect-spawn-session-naming-test
  (testing "Auto-spawned session uses 'auto' as name"
    (let [spawn-name (atom nil)
          session-spawned (atom false)]
      (with-redefs [ec/eval-elisp (fn [elisp]
                                    (when (str/includes? elisp "spawn-session")
                                       ;; Extract the session name from elisp
                                      (reset! spawn-name (when (str/includes? elisp "\"auto\"") "auto"))
                                      (reset! session-spawned true))
                                    (cond
                                      (str/includes? elisp "eval-silent")
                                      (if @session-spawned
                                        (mock-success "42")
                                        (mock-not-connected))

                                      (str/includes? elisp "list-sessions")
                                      (if @session-spawned
                                        (mock-success "[{\"name\":\"auto\",\"port\":7920,\"status\":\"connected\"}]")
                                        (mock-success "[]"))

                                      (str/includes? elisp "spawn-session")
                                      (mock-success "{\"name\":\"auto\",\"port\":7920,\"status\":\"starting\"}")

                                      :else
                                      (mock-success "nil")))]
        (cider/handle-cider-eval-silent {:code "(+ 1 1)"})
        (is (= "auto" @spawn-name) "Should spawn session named 'auto'")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest already-connected-no-auto-connect-test
  (testing "No auto-connect attempt when already connected"
    (let [list-sessions-called (atom false)]
      (with-redefs [ec/eval-elisp (fn [elisp]
                                    (when (str/includes? elisp "list-sessions")
                                      (reset! list-sessions-called true))
                                     ;; Always succeed (already connected)
                                    (mock-success "42"))]
        (cider/handle-cider-eval-silent {:code "(+ 1 41)"})
        (is (not @list-sessions-called) "Should not check sessions when already connected")))))

(deftest auto-connect-preserves-original-error-on-failure-test
  (testing "Returns meaningful error when auto-connect fails completely"
    (with-redefs [ec/eval-elisp (fn [_elisp]
                                   ;; Everything fails
                                  {:success false :error "Complete CIDER failure" :duration-ms 10})]
      (let [result (cider/handle-cider-eval-silent {:code "(+ 1 1)"})]
        (is (true? (:isError result)))
        ;; Should mention the failure, not hide it
        (is (string? (:text result)))))))

(deftest auto-connect-waits-for-session-ready-test
  (testing "Auto-connect waits for spawned session to be ready"
    (let [eval-count (atom 0)
          list-sessions-count (atom 0)
          session-spawned (atom false)]
      (with-redefs [ec/eval-elisp (fn [elisp]
                                    (cond
                                       ;; Initial eval fails if session not spawned
                                      (str/includes? elisp "eval-silent")
                                      (let [n (swap! eval-count inc)]
                                        (if (and (= n 1) (not @session-spawned))
                                          (mock-not-connected)
                                          (mock-success "result")))

                                       ;; List sessions - returns empty first, then connected after 2nd call
                                      (str/includes? elisp "list-sessions")
                                      (let [n (swap! list-sessions-count inc)]
                                        (cond
                                          ;; First call: no sessions
                                          (= n 1)
                                          (mock-success "[]")
                                          ;; After spawn, session becomes connected
                                          @session-spawned
                                          (mock-success "[{\"name\":\"auto\",\"port\":7920,\"status\":\"connected\"}]")
                                          ;; Default: still empty
                                          :else
                                          (mock-success "[]")))

                                       ;; Spawn succeeds
                                      (str/includes? elisp "spawn-session")
                                      (do (reset! session-spawned true)
                                          (mock-success "{\"name\":\"auto\",\"port\":7920,\"status\":\"starting\"}"))

                                      :else
                                      (mock-success "nil")))]
        (let [result (cider/handle-cider-eval-silent {:code "(+ 1 1)"})]
          ;; Should eventually succeed
          (is (nil? (:isError result))))))))
