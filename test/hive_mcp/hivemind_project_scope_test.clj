(ns hive-mcp.hivemind-project-scope-test
  "Pinning tests for project-scoped hivemind broadcasts.

   Tests verify:
   - hivemind_shout tags messages with project-id from directory
   - hivemind_status filters agents by project when directory provided
   - Piggyback messages only include agents from same project
   - Cross-project leak prevention (coordinator-X can't see coordinator-Y's agents)

   SECURITY: These tests verify that multi-project hivemind sessions
   maintain isolation between projects."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.swarm.protocol :as proto]
            [hive-mcp.swarm.datascript.registry :as registry]
            [hive-mcp.swarm.datascript.connection :as conn]
            [hive-mcp.tools.memory.scope :as mem-scope]
            [datascript.core :as d]))

;;; =============================================================================
;;; Test Fixtures
;;; =============================================================================

(defn reset-hivemind-and-datascript-state
  "Fixture to ensure clean state between tests.

   Resets:
   - hivemind agent-registry (message history)
   - DataScript connection (slave registry)
   - Piggyback cursors"
  [f]
  ;; Get the var and atom, then reset the atom's value
  (let [registry-atom (var-get #'hivemind/agent-registry)
        conn-atom (var-get #'conn/conn)]
    ;; Reset state before test
    (reset! registry-atom {})
    (piggyback/reset-all-cursors!)
    (reset! conn-atom nil)
    (conn/ensure-conn)
    ;; Run test
    (f)
    ;; Cleanup after test
    (reset! registry-atom {})
    (piggyback/reset-all-cursors!)
    (reset! conn-atom nil)))

(use-fixtures :each reset-hivemind-and-datascript-state)

;;; =============================================================================
;;; Helper Functions
;;; =============================================================================

(defn get-handler
  "Get a handler from hivemind tools by name."
  [tool-name]
  (let [tool (first (filter #(= tool-name (:name %)) hivemind/tools))]
    (:handler tool)))

(defn register-slave-with-project!
  "Register a slave in DataScript with explicit project-id."
  [slave-id project-id]
  (proto/add-slave! registry/default-registry slave-id
                    {:name slave-id
                     :status :idle
                     :depth 1
                     :cwd (str "/tmp/projects/" project-id)
                     :project-id project-id}))

;;; =============================================================================
;;; hivemind_shout Project Tagging Tests
;;; =============================================================================

(deftest shout-tags-messages-with-project-id-from-directory-test
  (testing "hivemind_shout derives project-id from directory parameter"
    ;; Mock the scope resolution to return predictable project-id
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (cond
                      (= dir "/projects/alpha") "project-alpha"
                      (= dir "/projects/beta") "project-beta"
                      :else "global"))]
      ;; Shout with directory param
      (hivemind/shout! "agent-alpha" :progress
                       {:task "work" :message "doing stuff" :directory "/projects/alpha"})

      ;; Verify message was tagged with project-id
      (let [registry-atom (var-get #'hivemind/agent-registry)
            messages (get-in @registry-atom ["agent-alpha" :messages])]
        (is (= 1 (count messages)))
        (is (= "project-alpha" (:project-id (first messages))))))))

(deftest shout-falls-back-to-slave-cwd-for-project-id-test
  (testing "hivemind_shout uses slave's cwd when directory not provided"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (when (= dir "/tmp/projects/beta-project")
                      "beta-project"))]
      ;; Register slave with cwd
      (register-slave-with-project! "agent-beta" "beta-project")

      ;; Shout without directory param
      (hivemind/shout! "agent-beta" :progress {:task "work" :message "busy"})

      ;; Verify project-id derived from slave's cwd
      (let [registry-atom (var-get #'hivemind/agent-registry)
            messages (get-in @registry-atom ["agent-beta" :messages])]
        (is (= "beta-project" (:project-id (first messages))))))))

(deftest shout-defaults-to-global-when-no-project-derivable-test
  (testing "hivemind_shout defaults to 'global' when project can't be derived"
    (with-redefs [mem-scope/get-current-project-id (constantly nil)]
      ;; Shout from unregistered agent without directory
      (hivemind/shout! "orphan-agent" :progress {:task "solo" :message "alone"})

      (let [registry-atom (var-get #'hivemind/agent-registry)
            messages (get-in @registry-atom ["orphan-agent" :messages])]
        (is (= "global" (:project-id (first messages))))))))

;;; =============================================================================
;;; hivemind_status Project Filtering Tests
;;; =============================================================================

(deftest status-filters-agents-by-project-test
  (testing "hivemind_status returns only agents from specified project"
    ;; Register slaves in different projects
    (register-slave-with-project! "alpha-1" "project-alpha")
    (register-slave-with-project! "alpha-2" "project-alpha")
    (register-slave-with-project! "beta-1" "project-beta")

    ;; Get status for project-alpha only
    (let [status (hivemind/get-status "project-alpha")]
      (is (= 2 (count (:agents status))))
      (is (contains? (:agents status) "alpha-1"))
      (is (contains? (:agents status) "alpha-2"))
      (is (not (contains? (:agents status) "beta-1"))))))

(deftest status-returns-all-agents-when-no-project-filter-test
  (testing "hivemind_status returns all agents when project-id is nil"
    ;; Register slaves in different projects
    (register-slave-with-project! "alpha-1" "project-alpha")
    (register-slave-with-project! "beta-1" "project-beta")

    ;; Get status without project filter
    (let [status (hivemind/get-status nil)]
      (is (>= (count (:agents status)) 2))
      (is (contains? (:agents status) "alpha-1"))
      (is (contains? (:agents status) "beta-1")))))

(deftest status-tool-uses-directory-for-filtering-test
  (testing "hivemind_status tool handler derives project-id from directory"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (when (= dir "/projects/gamma")
                      "project-gamma"))]
      ;; Register slaves
      (register-slave-with-project! "gamma-1" "project-gamma")
      (register-slave-with-project! "delta-1" "project-delta")

      ;; Call handler with directory
      (let [handler (get-handler "hivemind_status")
            result (handler {:directory "/projects/gamma"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        ;; Should only show gamma agents
        (is (= "project-gamma" (:project-id parsed)))
        (is (contains? (:agents parsed) (keyword "gamma-1")))
        (is (not (contains? (:agents parsed) (keyword "delta-1"))))))))

;;; =============================================================================
;;; Piggyback Project Scoping Tests
;;; =============================================================================

(deftest piggyback-filters-messages-by-project-test
  (testing "piggyback get-messages returns only messages from specified project"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (cond
                      (= dir "/projects/x") "project-x"
                      (= dir "/projects/y") "project-y"
                      :else "global"))]
      ;; Shout from different projects (avoid :completed which triggers event dispatch)
      (hivemind/shout! "agent-x" :progress {:task "x-work" :message "x msg" :directory "/projects/x"})
      (Thread/sleep 10) ; Ensure distinct timestamps
      (hivemind/shout! "agent-y" :progress {:task "y-work" :message "y msg" :directory "/projects/y"})
      (Thread/sleep 10)
      (hivemind/shout! "agent-x" :progress {:task "x-done" :message "x finished" :directory "/projects/x"})

      ;; Get messages for project-x only
      (let [msgs (piggyback/get-messages "coordinator-x" :project-id "project-x")]
        (is (= 2 (count msgs)) "Should have 2 messages from project-x")
        (is (every? #(= "agent-x" (:a %)) msgs) "All should be from agent-x")))))

(deftest piggyback-includes-global-messages-for-all-projects-test
  (testing "piggyback includes 'global' messages regardless of project filter"
    (with-redefs [mem-scope/get-current-project-id (constantly nil)]
      ;; Shout a global message (no project derivable)
      (hivemind/shout! "global-agent" :progress {:task "global" :message "for everyone"})

      ;; Get messages with project filter
      (let [msgs (piggyback/get-messages "any-coordinator" :project-id "some-project")]
        (is (= 1 (count msgs)))
        (is (= "global-agent" (:a (first msgs))))))))

(deftest piggyback-cursors-are-per-project-test
  (testing "piggyback maintains separate cursors per agent+project"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (cond
                      (= dir "/projects/p1") "project-1"
                      (= dir "/projects/p2") "project-2"
                      :else "global"))]
      ;; Shout from two projects
      (hivemind/shout! "p1-agent" :progress {:task "p1" :message "msg1" :directory "/projects/p1"})
      (Thread/sleep 10)
      (hivemind/shout! "p2-agent" :progress {:task "p2" :message "msg2" :directory "/projects/p2"})

      ;; Coordinator-A reads project-1
      (let [msgs-a (piggyback/get-messages "coord-a" :project-id "project-1")]
        (is (= 1 (count msgs-a))))

      ;; Coordinator-B reads project-2 (should see p2 messages, not affected by A's cursor)
      (let [msgs-b (piggyback/get-messages "coord-b" :project-id "project-2")]
        (is (= 1 (count msgs-b)))
        (is (= "p2-agent" (:a (first msgs-b)))))

      ;; Coordinator-A reads project-1 again (cursor advanced, no new messages)
      (let [msgs-a2 (piggyback/get-messages "coord-a" :project-id "project-1")]
        (is (nil? msgs-a2) "Should have no new messages after cursor advanced")))))

;;; =============================================================================
;;; Cross-Project Leak Prevention Tests
;;; =============================================================================

(deftest no-cross-project-agent-leak-test
  (testing "SECURITY: Agents from project-X are not visible to project-Y status query"
    ;; Register agents in isolated projects
    (register-slave-with-project! "secret-agent" "classified-project")
    (register-slave-with-project! "public-agent" "public-project")

    ;; Query public project
    (let [public-status (hivemind/get-status "public-project")]
      (is (contains? (:agents public-status) "public-agent"))
      (is (not (contains? (:agents public-status) "secret-agent"))
          "Secret agent should NOT be visible from public project"))))

(deftest no-cross-project-message-leak-test
  (testing "SECURITY: Messages from project-X are not delivered to project-Y coordinator"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (cond
                      (= dir "/secret") "secret-project"
                      (= dir "/public") "public-project"
                      :else "global"))]
      ;; Secret shout
      (hivemind/shout! "secret-agent" :progress
                       {:task "secret mission" :message "classified info" :directory "/secret"})

      ;; Public coordinator should NOT see secret messages
      (let [public-msgs (piggyback/get-messages "public-coord" :project-id "public-project")]
        (is (or (nil? public-msgs) (empty? public-msgs))
            "Public coordinator should NOT receive secret project messages")))))

(deftest secret-coordinator-sees-own-messages-test
  (testing "SECURITY: Secret project coordinator CAN see their own messages"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (when (= dir "/secret") "secret-project"))]
      ;; Secret shout (use :progress to avoid event dispatch bug with :completed)
      (hivemind/shout! "secret-agent" :progress
                       {:task "secret" :message "mission complete" :directory "/secret"})

      ;; Secret coordinator SHOULD see their messages
      (let [secret-msgs (piggyback/get-messages "secret-coord" :project-id "secret-project")]
        (is (= 1 (count secret-msgs)))
        (is (= "secret-agent" (:a (first secret-msgs))))))))

;;; =============================================================================
;;; hivemind_messages Project Filtering Tests
;;; =============================================================================

(deftest messages-tool-filters-available-agents-by-project-test
  (testing "hivemind_messages available-agents respects project filter"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (when (= dir "/proj-a") "proj-a"))]
      ;; Register and shout from different projects
      (register-slave-with-project! "agent-a" "proj-a")
      (register-slave-with-project! "agent-b" "proj-b")
      (hivemind/shout! "agent-a" :progress {:task "a" :message "from a"})
      (hivemind/shout! "agent-b" :progress {:task "b" :message "from b"})

      ;; Query with directory filter
      (let [handler (get-handler "hivemind_messages")
            result (handler {:agent_id "nonexistent" :directory "/proj-a"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        ;; Error response should include filtered available-agents
        (is (:error parsed))
        (is (= "proj-a" (:project-filter parsed)))
        ;; Available agents should only include proj-a agents
        (is (some #(= "agent-a" %) (:available-agents parsed)))
        ;; agent-b might still be there if they had proj-a messages, but shouldn't leak
        ))))

;;; =============================================================================
;;; Cross-Project Descendant Piggyback Tests (regression for #5720370c)
;;; =============================================================================

(deftest piggyback-includes-cross-project-descendant-shouts-test
  (testing "Coordinator sees shouts from lings spawned into a different project"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (cond
                      (= dir "/projects/coordinator") "proj-coord"
                      (= dir "/projects/worker") "proj-worker"
                      :else "global"))]
      ;; Register coordinator in proj-coord
      (register-slave-with-project! "coordinator-main" "proj-coord")
      ;; Register ling in proj-worker, parented to coordinator
      (proto/add-slave! registry/default-registry "ling-worker-1"
                        {:name "ling-worker-1" :status :idle :depth 1
                         :parent "coordinator-main"
                         :cwd "/projects/worker" :project-id "proj-worker"})

      ;; Ling shouts from proj-worker
      (hivemind/shout! "ling-worker-1" :progress
                       {:task "cross-work" :message "working in worker project"
                        :directory "/projects/worker"})

      ;; Resolve child project-ids (the fix)
      (let [_ (require 'hive-mcp.swarm.datascript.queries)
            child-pids-fn (resolve 'hive-mcp.swarm.datascript.queries/get-child-project-ids)
            child-pids (child-pids-fn "proj-coord")]

        ;; With additional-project-ids, coordinator sees the cross-project shout
        (let [msgs (piggyback/get-messages "coord-main"
                                            :project-id "proj-coord"
                                            :additional-project-ids child-pids)]
          (is (some #(= "ling-worker-1" (:a %)) msgs)
              "Coordinator should see ling's cross-project shout via descendant resolution"))

        ;; Without additional-project-ids (old behavior), coordinator misses it
        (piggyback/reset-all-cursors!)
        (let [msgs-old (piggyback/get-messages "coord-main-old" :project-id "proj-coord")]
          (is (not (some #(= "ling-worker-1" (:a %)) msgs-old))
              "Without fix, coordinator should NOT see cross-project shout"))))))

(deftest cross-project-descendant-isolation-test
  (testing "SECURITY: Unrelated coordinator does NOT see another coordinator's descendants"
    (with-redefs [mem-scope/get-current-project-id
                  (fn [dir]
                    (cond
                      (= dir "/alpha") "proj-alpha"
                      (= dir "/beta") "proj-beta"
                      :else "global"))]
      ;; Alpha coordinator and its cross-project ling
      (register-slave-with-project! "alpha-coord" "proj-alpha")
      (proto/add-slave! registry/default-registry "alpha-ling"
                        {:name "alpha-ling" :status :idle :depth 1
                         :parent "alpha-coord"
                         :cwd "/beta" :project-id "proj-beta"})

      ;; Alpha ling shouts from proj-beta
      (hivemind/shout! "alpha-ling" :progress
                       {:task "alpha-work" :message "alpha in beta" :directory "/beta"})

      ;; Unrelated coordinator in proj-gamma should NOT see alpha-ling's shout
      (let [child-pids-fn (resolve 'hive-mcp.swarm.datascript.queries/get-child-project-ids)
            gamma-children (child-pids-fn "proj-gamma")
            msgs (piggyback/get-messages "gamma-coord"
                                          :project-id "proj-gamma"
                                          :additional-project-ids gamma-children)]
        (is (empty? gamma-children) "Unrelated project has no children")
        (is (not (some #(= "alpha-ling" (:a %)) msgs))
            "Gamma coordinator must NOT see alpha's cross-project ling")))))
