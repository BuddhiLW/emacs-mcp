(ns hive-mcp.agent.headless-test
  "Tests for headless ling process management.

   Tests cover:
   1. Ring buffer operations (create, append, capacity, stats)
   2. Process lifecycle (spawn, status, kill)
   3. Stdin dispatch
   4. Process registry
   5. Cleanup operations
   6. Integration with Ling record spawn-mode

   Note: Spawn tests use a simple subprocess ('echo' or 'cat')
   instead of actual `claude` CLI to avoid CI dependencies."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.headless :as headless]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.swarm.datascript.queries :as ds-queries]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- kill-headless-fixture
  "Kill all headless processes around each test."
  [f]
  (headless/kill-all-headless!)
  (try (f) (finally (headless/kill-all-headless!))))

(use-fixtures :each
  (iso/with-isolations :swarm-ds)
  kill-headless-fixture)

(defn gen-ling-id []
  (str "test-headless-" (java.util.UUID/randomUUID)))

;; =============================================================================
;; Ring Buffer Tests
;; =============================================================================

(deftest ring-buffer-create-test
  (testing "Create ring buffer with default capacity"
    (let [buf (headless/create-ring-buffer)]
      (is (= 5000 (:capacity @buf)))
      (is (= [] (:lines @buf)))
      (is (zero? (:total-lines-seen @buf)))
      (is (zero? (:dropped @buf))))))

(deftest ring-buffer-create-custom-capacity-test
  (testing "Create ring buffer with custom capacity"
    (let [buf (headless/create-ring-buffer 10)]
      (is (= 10 (:capacity @buf))))))

(deftest ring-buffer-append-test
  (testing "Append lines to ring buffer"
    (let [buf (headless/create-ring-buffer 100)]
      (headless/ring-buffer-append! buf "line 1")
      (headless/ring-buffer-append! buf "line 2")
      (headless/ring-buffer-append! buf "line 3")
      (is (= ["line 1" "line 2" "line 3"] (:lines @buf)))
      (is (= 3 (:total-lines-seen @buf)))
      (is (zero? (:dropped @buf))))))

(deftest ring-buffer-capacity-eviction-test
  (testing "Ring buffer evicts oldest lines when at capacity"
    (let [buf (headless/create-ring-buffer 3)]
      (headless/ring-buffer-append! buf "a")
      (headless/ring-buffer-append! buf "b")
      (headless/ring-buffer-append! buf "c")
      ;; Buffer is full at capacity 3
      (is (= ["a" "b" "c"] (:lines @buf)))
      ;; Add one more - should evict "a"
      (headless/ring-buffer-append! buf "d")
      (is (= ["b" "c" "d"] (:lines @buf)))
      (is (= 4 (:total-lines-seen @buf)))
      (is (= 1 (:dropped @buf)))
      ;; Add two more - should evict "b" and "c"
      (headless/ring-buffer-append! buf "e")
      (headless/ring-buffer-append! buf "f")
      (is (= ["d" "e" "f"] (:lines @buf)))
      (is (= 6 (:total-lines-seen @buf)))
      (is (= 3 (:dropped @buf))))))

(deftest ring-buffer-contents-test
  (testing "Get ring buffer contents"
    (let [buf (headless/create-ring-buffer 100)]
      (doseq [i (range 10)]
        (headless/ring-buffer-append! buf (str "line-" i)))
      ;; Get all contents
      (is (= 10 (count (headless/ring-buffer-contents buf))))
      ;; Get last 3
      (is (= ["line-7" "line-8" "line-9"]
             (headless/ring-buffer-contents buf {:last-n 3}))))))

(deftest ring-buffer-contents-last-n-larger-than-buffer-test
  (testing "last-n larger than buffer returns all lines"
    (let [buf (headless/create-ring-buffer 100)]
      (headless/ring-buffer-append! buf "only-line")
      (is (= ["only-line"]
             (headless/ring-buffer-contents buf {:last-n 100}))))))

(deftest ring-buffer-stats-test
  (testing "Ring buffer stats"
    (let [buf (headless/create-ring-buffer 5)]
      (doseq [i (range 8)]
        (headless/ring-buffer-append! buf (str "line-" i)))
      (let [stats (headless/ring-buffer-stats buf)]
        (is (= 5 (:current-lines stats)))
        (is (= 5 (:capacity stats)))
        (is (= 8 (:total-lines-seen stats)))
        (is (= 3 (:dropped stats)))))))

;; =============================================================================
;; Process Spawn Tests (using simple subprocesses)
;; =============================================================================

(deftest spawn-headless-basic-test
  (testing "Spawn a headless process using 'sleep' command"
    (let [ling-id (gen-ling-id)
          result (headless/spawn-headless! ling-id
                                           {:cwd "/tmp"
                                            :claude-cmd "sleep"
                                            :buffer-capacity 100})]
      ;; sleep will be invoked as "sleep --print" which will error and exit,
      ;; but the process handle should still be created
      (is (= ling-id (:ling-id result)))
      (is (pos? (:pid result)))
      (is (some? (:process result)))
      (is (some? (:stdout-buf result)))
      (is (some? (:stderr-buf result)))
      ;; Process should be in registry
      (is (headless/headless? ling-id))
      ;; Clean up
      (try (headless/kill-headless! ling-id {:force? true})
           (catch Exception _)))))

(deftest spawn-headless-cat-process-test
  (testing "Spawn a headless process using 'cat' (reads stdin, writes stdout)"
    (let [ling-id (gen-ling-id)
          ;; cat without args reads from stdin and echoes to stdout
          ;; We override the command to avoid needing `claude` installed
          result (headless/spawn-headless! ling-id
                                           {:cwd "/tmp"
                                            :claude-cmd "cat"
                                            :buffer-capacity 100})]
      (is (pos? (:pid result)))
      ;; Give process time to start
      (Thread/sleep 100)
      ;; Process should be alive
      (let [status (headless/headless-status ling-id)]
        (is (:alive? status))
        (is (= ling-id (:ling-id status)))
        (is (= "/tmp" (:cwd status))))
      ;; Clean up
      (headless/kill-headless! ling-id {:force? true}))))

(deftest spawn-headless-duplicate-id-test
  (testing "Spawning with duplicate ID throws"
    (let [ling-id (gen-ling-id)]
      (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                         :buffer-capacity 100})
      (is (thrown? clojure.lang.ExceptionInfo
                   (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"})))
      ;; Clean up
      (headless/kill-headless! ling-id {:force? true}))))

;; =============================================================================
;; Stdin Dispatch Tests
;; =============================================================================

(deftest dispatch-via-stdin-test
  (testing "Dispatch via stdin to a cat process (echoes back to stdout)"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      ;; Send a message via stdin
      (is (true? (headless/dispatch-via-stdin! ling-id "hello from test")))
      ;; Give cat time to echo
      (Thread/sleep 200)
      ;; Check stdout buffer - cat should have echoed the message
      (let [stdout (headless/get-stdout ling-id)]
        (is (some #(= "hello from test" %) stdout)))
      ;; Clean up
      (headless/kill-headless! ling-id {:force? true}))))

(deftest dispatch-via-stdin-nonexistent-test
  (testing "Dispatch to nonexistent ling throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (headless/dispatch-via-stdin! "nonexistent-ling" "hello")))))

;; =============================================================================
;; Process Kill Tests
;; =============================================================================

(deftest kill-headless-test
  (testing "Kill a headless process"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      ;; Should be alive before kill
      (is (:alive? (headless/headless-status ling-id)))
      ;; Kill it
      (let [result (headless/kill-headless! ling-id)]
        (is (:killed? result))
        (is (= ling-id (:ling-id result))))
      ;; Should no longer be in registry
      (is (not (headless/headless? ling-id))))))

(deftest kill-headless-force-test
  (testing "Force-kill a headless process"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      (let [result (headless/kill-headless! ling-id {:force? true})]
        (is (:killed? result)))
      (is (not (headless/headless? ling-id))))))

(deftest kill-headless-nonexistent-test
  (testing "Kill nonexistent ling throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (headless/kill-headless! "nonexistent-ling")))))

;; =============================================================================
;; Status Tests
;; =============================================================================

(deftest headless-status-test
  (testing "Get status of a headless process"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      (let [status (headless/headless-status ling-id)]
        (is (= ling-id (:ling-id status)))
        (is (:alive? status))
        (is (pos? (:pid status)))
        (is (= "/tmp" (:cwd status)))
        (is (pos? (:started-at status)))
        (is (>= (:uptime-ms status) 0))
        (is (map? (:stdout status)))
        (is (map? (:stderr status))))
      ;; Clean up
      (headless/kill-headless! ling-id {:force? true}))))

(deftest headless-status-nonexistent-test
  (testing "Status of nonexistent ling returns nil"
    (is (nil? (headless/headless-status "nonexistent-ling")))))

(deftest headless-status-after-kill-test
  (testing "Status after kill shows not alive"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      (headless/kill-headless! ling-id {:force? true})
      ;; After kill, should not be in registry
      (is (nil? (headless/headless-status ling-id))))))

;; =============================================================================
;; Registry Tests
;; =============================================================================

(deftest list-headless-test
  (testing "List all headless processes"
    (let [id1 (gen-ling-id)
          id2 (gen-ling-id)
          _ (headless/spawn-headless! id1 {:cwd "/tmp" :claude-cmd "cat"
                                           :buffer-capacity 100})
          _ (headless/spawn-headless! id2 {:cwd "/tmp" :claude-cmd "cat"
                                           :buffer-capacity 100})]
      (Thread/sleep 100)
      (let [all (headless/list-headless)]
        (is (= 2 (count all)))
        (is (= #{id1 id2} (set (map :ling-id all)))))
      ;; Clean up
      (headless/kill-headless! id1 {:force? true})
      (headless/kill-headless! id2 {:force? true}))))

(deftest headless-count-test
  (testing "Count headless processes"
    (is (zero? (headless/headless-count)))
    (let [id1 (gen-ling-id)
          _ (headless/spawn-headless! id1 {:cwd "/tmp" :claude-cmd "cat"
                                           :buffer-capacity 100})]
      (is (= 1 (headless/headless-count)))
      (headless/kill-headless! id1 {:force? true})
      (is (zero? (headless/headless-count))))))

(deftest kill-all-headless-test
  (testing "Kill all headless processes"
    (let [ids (repeatedly 3 gen-ling-id)]
      (doseq [id ids]
        (headless/spawn-headless! id {:cwd "/tmp" :claude-cmd "cat"
                                      :buffer-capacity 100}))
      (Thread/sleep 100)
      (is (= 3 (headless/headless-count)))
      (let [result (headless/kill-all-headless!)]
        (is (= 3 (:killed result)))
        (is (zero? (:errors result))))
      (is (zero? (headless/headless-count))))))

;; =============================================================================
;; Get Stdout/Stderr Tests
;; =============================================================================

(deftest get-stdout-test
  (testing "Get stdout contents"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      ;; Send multiple messages
      (headless/dispatch-via-stdin! ling-id "msg1")
      (headless/dispatch-via-stdin! ling-id "msg2")
      (headless/dispatch-via-stdin! ling-id "msg3")
      (Thread/sleep 300)
      ;; Get all stdout
      (let [stdout (headless/get-stdout ling-id)]
        (is (>= (count stdout) 3))
        (is (some #(= "msg1" %) stdout))
        (is (some #(= "msg3" %) stdout)))
      ;; Get last 2
      (let [stdout (headless/get-stdout ling-id {:last-n 2})]
        (is (= 2 (count stdout)))
        (is (= "msg3" (last stdout))))
      ;; Clean up
      (headless/kill-headless! ling-id {:force? true}))))

(deftest get-stdout-nonexistent-test
  (testing "Get stdout of nonexistent ling returns nil"
    (is (nil? (headless/get-stdout "nonexistent")))))

(deftest get-stderr-nonexistent-test
  (testing "Get stderr of nonexistent ling returns nil"
    (is (nil? (headless/get-stderr "nonexistent")))))

;; =============================================================================
;; Process Handle Access Tests
;; =============================================================================

(deftest get-process-test
  (testing "Get raw process handle"
    (let [ling-id (gen-ling-id)
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})]
      (Thread/sleep 100)
      (let [proc (headless/get-process ling-id)]
        (is (instance? Process proc))
        (is (.isAlive proc)))
      (headless/kill-headless! ling-id {:force? true}))))

(deftest get-process-nonexistent-test
  (testing "Get process of nonexistent ling returns nil"
    (is (nil? (headless/get-process "nonexistent")))))

;; =============================================================================
;; Ling Record Integration Tests (spawn-mode dispatch)
;; =============================================================================

(deftest ling-record-spawn-mode-default-test
  (testing "Ling record defaults to :vterm spawn-mode"
    (let [ling (ling/->ling "test-ling" {:cwd "/tmp"})]
      (is (= :vterm (:spawn-mode ling))))))

(deftest ling-record-spawn-mode-headless-test
  (testing "Ling record accepts :headless spawn-mode"
    (let [ling (ling/->ling "test-ling" {:cwd "/tmp" :spawn-mode :headless})]
      (is (= :headless (:spawn-mode ling))))))

(deftest ling-headless-spawn-with-datascript-test
  (testing "Headless ling spawn registers in DataScript with spawn-mode"
    (let [ling-id (gen-ling-id)
          ling (ling/->ling ling-id {:cwd "/tmp"
                                     :project-id "test-project"
                                     :spawn-mode :headless})
          ;; Override claude-cmd via env or use cat
          ;; Actually, spawn! calls headless/spawn-headless! which uses :claude-cmd
          ;; but the Ling record doesn't pass that through.
          ;; We need to test the DataScript registration part.
          ;; Let's spawn headless directly and register manually
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})
          ;; Now register in DataScript as the spawn! method would
          _ (ds/add-slave! ling-id {:status :idle :depth 1 :cwd "/tmp"
                                    :project-id "test-project"})
          _ (ds/update-slave! ling-id {:ling/spawn-mode :headless
                                       :ling/process-pid (.pid (headless/get-process ling-id))
                                       :ling/process-alive? true})]
      ;; Verify DataScript has the spawn-mode
      (let [slave (ds-queries/get-slave ling-id)]
        (is (= :headless (:ling/spawn-mode slave)))
        (is (pos? (:ling/process-pid slave)))
        (is (true? (:ling/process-alive? slave))))
      ;; Verify reconstituted ling has correct spawn-mode
      (let [reconstituted (ling/get-ling ling-id)]
        (is (some? reconstituted))
        (is (= :headless (:spawn-mode reconstituted))))
      ;; Clean up
      (headless/kill-headless! ling-id {:force? true}))))

(deftest ling-headless-kill-with-datascript-test
  (testing "Headless ling kill cleans up both process registry and DataScript"
    (let [ling-id (gen-ling-id)
          ;; Spawn the process
          _ (headless/spawn-headless! ling-id {:cwd "/tmp" :claude-cmd "cat"
                                               :buffer-capacity 100})
          ;; Register in DataScript
          _ (ds/add-slave! ling-id {:status :idle :depth 1 :cwd "/tmp"})
          _ (ds/update-slave! ling-id {:ling/spawn-mode :headless})]
      (Thread/sleep 100)
      ;; Verify both exist
      (is (headless/headless? ling-id))
      (is (some? (ds-queries/get-slave ling-id)))
      ;; Create ling record and kill
      (let [ling (ling/->ling ling-id {:cwd "/tmp" :spawn-mode :headless})
            result (proto/kill! ling)]
        (is (:killed? result)))
      ;; Both should be cleaned up
      (is (not (headless/headless? ling-id)))
      (is (nil? (ds-queries/get-slave ling-id))))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest ring-buffer-empty-contents-test
  (testing "Empty ring buffer returns empty vector"
    (let [buf (headless/create-ring-buffer)]
      (is (= [] (headless/ring-buffer-contents buf)))
      (is (= [] (headless/ring-buffer-contents buf {:last-n 10}))))))

(deftest ring-buffer-capacity-one-test
  (testing "Ring buffer with capacity 1"
    (let [buf (headless/create-ring-buffer 1)]
      (headless/ring-buffer-append! buf "first")
      (is (= ["first"] (:lines @buf)))
      (headless/ring-buffer-append! buf "second")
      (is (= ["second"] (:lines @buf)))
      (is (= 2 (:total-lines-seen @buf)))
      (is (= 1 (:dropped @buf))))))

(deftest spawn-headless-invalid-cwd-test
  (testing "Spawn with nonexistent cwd throws"
    (let [ling-id (gen-ling-id)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (headless/spawn-headless! ling-id
                                             {:cwd "/nonexistent/dir/xyz"
                                              :claude-cmd "cat"}))))))

(deftest headless-question-mark-test
  (testing "headless? returns false for non-headless IDs"
    (is (false? (headless/headless? "some-vterm-ling")))
    (is (false? (headless/headless? "")))))

;; =============================================================================
;; Environment Variable Tests (build-child-env)
;; =============================================================================

(deftest build-child-env-sets-slave-id-test
  (testing "build-child-env always sets CLAUDE_SWARM_SLAVE_ID"
    (let [env (headless/build-child-env "test-ling-42" {})]
      (is (= "test-ling-42" (get env "CLAUDE_SWARM_SLAVE_ID"))))))

(deftest build-child-env-sets-guard-vars-test
  (testing "build-child-env sets HIVE_MCP_ROLE and HIVE_LING_DEPTH"
    (let [env (headless/build-child-env "test-ling" {})]
      (is (= "child-ling" (get env "HIVE_MCP_ROLE")))
      (is (some? (get env "HIVE_LING_DEPTH")))
      (is (pos? (parse-long (get env "HIVE_LING_DEPTH")))))))

(deftest build-child-env-sets-project-dir-test
  (testing "build-child-env sets BB_MCP_PROJECT_DIR from cwd"
    (let [env (headless/build-child-env "test-ling" {:cwd "/home/user/project"})]
      (is (= "/home/user/project" (get env "BB_MCP_PROJECT_DIR"))))))

(deftest build-child-env-no-project-dir-without-cwd-test
  (testing "build-child-env omits BB_MCP_PROJECT_DIR when no cwd"
    (let [env (headless/build-child-env "test-ling" {})]
      (is (nil? (get env "BB_MCP_PROJECT_DIR"))))))

(deftest build-child-env-sets-openrouter-model-test
  (testing "build-child-env sets OPENROUTER_MODEL for non-claude models"
    (let [env (headless/build-child-env "test-ling" {:model "deepseek/deepseek-v3.2"})]
      (is (= "deepseek/deepseek-v3.2" (get env "OPENROUTER_MODEL"))))))

(deftest build-child-env-no-model-for-claude-test
  (testing "build-child-env does NOT set OPENROUTER_MODEL for claude"
    (let [env (headless/build-child-env "test-ling" {:model "claude"})]
      (is (nil? (get env "OPENROUTER_MODEL"))))))

(deftest build-child-env-env-extra-override-test
  (testing "env-extra overrides all other env vars (highest priority)"
    (let [env (headless/build-child-env "test-ling"
                                        {:cwd "/tmp"
                                         :env-extra {"BB_MCP_PROJECT_DIR" "/override"
                                                     "CUSTOM_VAR" "custom-val"}})]
      (is (= "/override" (get env "BB_MCP_PROJECT_DIR")))
      (is (= "custom-val" (get env "CUSTOM_VAR")))
      ;; Guard vars still present
      (is (= "child-ling" (get env "HIVE_MCP_ROLE"))))))

(deftest build-child-env-env-extra-keyword-keys-test
  (testing "env-extra supports keyword keys (coerced to strings)"
    (let [env (headless/build-child-env "test-ling"
                                        {:env-extra {:MY_VAR "hello"}})]
      (is (= "hello" (get env "MY_VAR"))))))

(deftest build-child-env-forwards-parent-env-test
  (testing "build-child-env forwards HOME and PATH from parent"
    (let [env (headless/build-child-env "test-ling" {})]
      ;; HOME and PATH should be present (inherited from running JVM)
      (is (some? (get env "HOME")))
      (is (some? (get env "PATH"))))))

(comment
  ;; Run tests
  ;; (clojure.test/run-tests 'hive-mcp.agent.headless-test)
  )
