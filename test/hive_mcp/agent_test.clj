(ns hive-mcp.agent-test
  "Pinning tests for agent delegation tool restrictions.
   
   Ensures drone agents can only access safe tools and are blocked from
   dangerous operations like file writes, bash execution, and git commits."
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [hive-mcp.agent.core]
            [hive-mcp.agent.drone.tools :as drone-tools]
            [hive-mcp.server.permissions :as permissions]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def expected-tier-3-tools
  "Tier-3 tools that require human approval (from permissions module)."
  #{"bash" "magit_commit" "magit_push" "eval_elisp" "cider_eval_explicit"
    "preset_delete" "swarm_kill" "mcp_memory_cleanup_expired"})

(def coordinator-only-tools
  "Consolidated tool names a drone must never reach.
   Mirrors the blacklist in hive-mcp.agent.drone.tools; kept here so the two
   are cross-checked rather than derived from one another."
  #{"agent" "wave" "workflow" "multi" "delegate"
    "olympus" "emacs" "migration" "config"})

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- fresh-tool-cache-fixture
  "Re-discover the drone toolset around each test.

   `drone-tools/full-toolset` memoizes into a process-global atom, so in a
   whole-suite run another namespace can populate it while the tool registry
   is mid-reset — leaving a truncated set cached. Arranging a fresh cache is
   the test's job; without it these assertions read an accident of test order."
  [f]
  (drone-tools/invalidate-tool-cache!)
  (try (f) (finally (drone-tools/invalidate-tool-cache!))))

(use-fixtures :each fresh-tool-cache-fixture)

;; =============================================================================
;; Pinning Tests - Tool Definitions
;; =============================================================================

(deftest drone-toolset-is-blacklist-filtered
  (testing "the drone toolset is the discovered set minus the blacklist"
    (let [toolset (set (drone-tools/full-toolset))]
      (is (seq toolset) "drones must have some tools")
      (is (empty? (set/intersection toolset coordinator-only-tools))
          (str "SECURITY VIOLATION: drone toolset must exclude coordinator-only "
               "consolidated tools. Found: "
               (set/intersection toolset coordinator-only-tools))))))

(deftest drone-cannot-spawn-or-amplify
  (testing "recursive-spawn and amplification tools are excluded"
    (let [toolset (set (drone-tools/full-toolset))]
      (doseq [t ["agent" "wave" "workflow" "multi" "delegate"]]
        (is (not (contains? toolset t))
            (str t " must not be reachable by a drone"))))))

(deftest drone-shell-access-is-pattern-gated
  (testing "bash is available but every command passes validate-bash-command"
    (is (contains? (set (drone-tools/full-toolset)) "bash")
        "bash is provided by the drone tool proxy")
    (testing "benign commands are allowed"
      (doseq [cmd ["ls -la" "git status" "grep -rn foo src/"]]
        (is (:allowed? (drone-tools/validate-bash-command cmd))
            (str "should allow: " cmd))))
    (testing "destructive commands are blocked with a reason"
      (doseq [cmd ["rm -rf /" "sudo rm -rf /var" ":(){ :|:& };:"]]
        (let [{:keys [allowed? reason]} (drone-tools/validate-bash-command cmd)]
          (is (false? allowed?) (str "should block: " cmd))
          (is (string? reason) "a block must carry a reason"))))))

(deftest dangerous-tool-predicate-exists
  (testing "permissions/dangerous-tool? is defined"
    (is (fn? permissions/dangerous-tool?)
        "permissions/dangerous-tool? must be defined")))

(deftest tier-3-tools-match-expected
  (testing "permissions/dangerous-tool? returns true for tier-3 tools"
    (doseq [tool expected-tier-3-tools]
      (is (permissions/dangerous-tool? tool)
          (str tool " should be marked as dangerous"))))
  (testing "permissions/dangerous-tool? returns false for safe tools"
    (doseq [tool ["read_file" "grep" "glob_files"]]
      (is (not (permissions/dangerous-tool? tool))
          (str tool " should not be marked as dangerous")))))

(deftest drone-toolset-is-cached-and-invalidatable
  (testing "full-toolset caches, invalidate-tool-cache! forces re-discovery"
    (let [a (drone-tools/full-toolset)
          b (drone-tools/full-toolset)]
      (is (= a b) "repeated calls return the cached set")
      (drone-tools/invalidate-tool-cache!)
      (is (= (set a) (set (drone-tools/full-toolset)))
          "re-discovery yields the same set for an unchanged registry"))))

;; =============================================================================
;; Integration Test - delegate-drone! Uses Agentic Path
;; =============================================================================

(deftest delegate-drone-exists-and-callable
  (testing "delegate-drone! is defined and callable"
    (is (fn? @#'hive-mcp.agent.core/delegate-drone!)
        "delegate-drone! must be a function")
    (is (some? @#'hive-mcp.agent.core/delegate-drone!)
        "delegate-drone! should be defined")))

(deftest delegate-drone-routes-to-agentic
  (testing "delegate-drone! correctly routes through drone/delegate-agentic!"
    ;; delegate-drone! now routes through the agentic path (no external delegate-fn)
    (let [captured-args (atom nil)
          mock-delegate-agentic! (fn [opts]
                                   (reset! captured-args opts)
                                   {:status :completed :result "mocked"})]
      (with-redefs [hive-mcp.agent.drone/delegate-agentic! mock-delegate-agentic!]
        (let [result (hive-mcp.agent.core/delegate-drone! {:task "test task"
                                                           :files ["foo.clj"]})]
          ;; Verify the call went through
          (is (= :completed (:status result))
              "Should return the mocked result")
          ;; Verify opts were passed
          (is (= "test task" (:task @captured-args))
              "Task should be passed to drone/delegate-agentic!")
          (is (= ["foo.clj"] (:files @captured-args))
              "Files should be passed to drone/delegate-agentic!"))))))

(comment
  ;; Run tests in REPL
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.agent-test)

  ;; Run specific test
  (drone-toolset-is-blacklist-filtered))
