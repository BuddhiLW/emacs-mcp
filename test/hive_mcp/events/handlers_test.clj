(ns hive-mcp.events.handlers-test
  "Tests for event handlers - TDD for Phase 5 migration.

   Tests handler functions directly without full dispatch machinery."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.events.handlers.task :as task]
            [hive-mcp.events.handlers.session :as session]
            [hive-mcp.events.handlers.kanban :as kanban]))

;; =============================================================================
;; P5-1: :task/shout-complete handler tests
;; =============================================================================

(deftest test-handle-task-shout-complete-produces-shout-effect
  (testing ":task/shout-complete produces :shout effect with message"
    (let [event [:task/shout-complete {:task-id "task-123"
                                       :title "Fix the bug"
                                       :agent-id "ling-worker-1"}]
          coeffects {}
          result (task/handle-task-shout-complete coeffects event)]
      (is (contains? result :shout) "Should produce :shout effect")
      (is (= :completed (get-in result [:shout :event-type]))
          "Event type should be :completed")
      (is (string? (get-in result [:shout :message]))
          "Should include a message string"))))

(deftest test-handle-task-shout-complete-message-includes-title
  (testing ":task/shout-complete message includes task title"
    (let [event [:task/shout-complete {:task-id "task-456"
                                       :title "Implement feature X"
                                       :agent-id "ling-impl"}]
          result (task/handle-task-shout-complete {} event)]
      (is (re-find #"Implement feature X" (get-in result [:shout :message]))
          "Message should contain task title"))))

(deftest test-handle-task-shout-complete-with-missing-title
  (testing ":task/shout-complete falls back when title missing"
    (let [event [:task/shout-complete {:task-id "task-789"
                                       :agent-id "ling-worker"}]
          result (task/handle-task-shout-complete {} event)]
      (is (contains? result :shout) "Should still produce :shout")
      (is (string? (get-in result [:shout :message]))
          "Should have fallback message"))))

(deftest test-handle-task-shout-complete-includes-data
  (testing ":task/shout-complete includes structured data"
    (let [event [:task/shout-complete {:task-id "task-999"
                                       :title "Test task"
                                       :agent-id "ling-tester"
                                       :project "hive-mcp"}]
          result (task/handle-task-shout-complete {} event)]
      (is (= "task-999" (get-in result [:shout :data :task-id])))
      (is (= "ling-tester" (get-in result [:shout :data :agent-id])))
      (is (= "hive-mcp" (get-in result [:shout :data :project]))))))

;; =============================================================================
;; P5-3: :session/wrap handler tests
;; =============================================================================

(deftest test-handle-session-wrap-produces-wrap-crystallize-effect
  (testing ":session/wrap produces :wrap-crystallize effect (per FIX comment session.clj:66-67 — :run-workflow was never registered)"
    (let [event [:session/wrap {:session-id "session-abc"
                                :project "hive-mcp"}]
          coeffects {}
          result (session/handle-session-wrap coeffects event)]
      (is (contains? result :wrap-crystallize) "Should produce :wrap-crystallize effect")
      (is (= "session-abc" (get-in result [:wrap-crystallize :session-id])))
      (is (= "hive-mcp" (get-in result [:wrap-crystallize :project]))))))

(deftest test-handle-session-wrap-includes-params
  (testing ":session/wrap passes params into :wrap-crystallize effect"
    (let [event [:session/wrap {:session-id "sess-123"
                                :project "test-project"
                                :start-time "2024-01-01T10:00:00Z"}]
          result (session/handle-session-wrap {} event)]
      (is (= "sess-123" (get-in result [:wrap-crystallize :session-id])))
      (is (= "test-project" (get-in result [:wrap-crystallize :project]))))))

(deftest test-handle-session-wrap-generates-session-id-if-missing
  (testing ":session/wrap synthesizes effective-session-id when none provided"
    (let [event [:session/wrap {:project "my-project"}]
          coeffects {:agent-context {:agent-id "ling-123"}}
          result (session/handle-session-wrap coeffects event)]
      (is (contains? result :wrap-crystallize))
      (is (string? (get-in result [:wrap-crystallize :session-id]))
          "Should synthesize a session-id from agent-id and date")
      (is (clojure.string/includes? (get-in result [:wrap-crystallize :session-id]) "ling-123")
          "synthesized session-id must include the resolved agent-id"))))

(deftest test-handle-session-wrap-produces-log-effect
  (testing ":session/wrap logs the wrap trigger"
    (let [event [:session/wrap {:session-id "sess-456"
                                :project "hive-mcp"}]
          result (session/handle-session-wrap {} event)]
      (is (contains? result :log) "Should produce :log effect")
      (is (= :info (get-in result [:log :level]))))))

;; =============================================================================
;; P5-2: :git/commit-modified handler tests
;; =============================================================================

(deftest test-handle-git-commit-modified-returns-empty-when-no-files
  (testing ":git/commit-modified returns empty map when no files modified"
    (let [coeffects {}
          event [:git/commit-modified {:task-id "task-123"
                                       :modified-files []}]
          result (task/handle-git-commit-modified coeffects event)]
      (is (= {} result)))))

(deftest test-handle-git-commit-modified-returns-effects-when-files-present
  (testing ":git/commit-modified returns :log and :git-commit effects"
    (let [coeffects {}
          event [:git/commit-modified {:task-id "task-123"
                                       :title "Add login feature"
                                       :modified-files ["src/auth.clj" "test/auth_test.clj"]}]
          result (task/handle-git-commit-modified coeffects event)]
      (is (map? result))
      (is (contains? result :log))
      (is (contains? result :git-commit)))))

(deftest test-handle-git-commit-modified-includes-files-in-effect
  (testing ":git-commit effect includes all modified files"
    (let [coeffects {}
          event [:git/commit-modified {:task-id "task-456"
                                       :title "Fix bug"
                                       :modified-files ["src/a.clj" "src/b.clj"]}]
          result (task/handle-git-commit-modified coeffects event)]
      (is (= ["src/a.clj" "src/b.clj"] (get-in result [:git-commit :files]))))))

(deftest test-handle-git-commit-modified-uses-conventional-commit-feat
  (testing "commit message uses 'feat:' prefix for non-fix titles"
    (let [coeffects {}
          event [:git/commit-modified {:task-id "task-789"
                                       :title "Add new feature"
                                       :modified-files ["src/core.clj"]}]
          result (task/handle-git-commit-modified coeffects event)]
      (is (re-find #"^feat:" (get-in result [:git-commit :message]))))))

(deftest test-handle-git-commit-modified-uses-conventional-commit-fix
  (testing "commit message uses 'fix:' prefix for fix titles"
    (let [coeffects {}
          event [:git/commit-modified {:task-id "task-abc"
                                       :title "Fix authentication bug"
                                       :modified-files ["src/auth.clj"]}]
          result (task/handle-git-commit-modified coeffects event)]
      (is (re-find #"^fix:" (get-in result [:git-commit :message]))))))

(deftest test-handle-git-commit-modified-handles-missing-title
  (testing ":git/commit-modified generates message when title missing"
    (let [coeffects {}
          event [:git/commit-modified {:task-id "task-def"
                                       :modified-files ["src/core.clj"]}]
          result (task/handle-git-commit-modified coeffects event)]
      (is (string? (get-in result [:git-commit :message])))
      (is (not (empty? (get-in result [:git-commit :message])))))))

;; =============================================================================
;; P5-4: :kanban/sync handler tests
;; =============================================================================

(deftest test-handle-kanban-sync-returns-effects-map
  (testing ":kanban/sync returns :log and :kanban-sync effects"
    (let [coeffects {:agent-context {:project "hive-mcp"}}
          event [:kanban/sync {}]
          result (kanban/handle-kanban-sync coeffects event)]
      (is (map? result))
      (is (contains? result :log))
      (is (contains? result :kanban-sync)))))

(deftest test-handle-kanban-sync-includes-project-from-coeffects
  (testing ":kanban-sync effect includes project from coeffects"
    (let [coeffects {:agent-context {:project "test-project"}}
          event [:kanban/sync {}]
          result (kanban/handle-kanban-sync coeffects event)]
      (is (= "test-project" (get-in result [:kanban-sync :project]))))))

(deftest test-handle-kanban-sync-uses-bidirectional-direction
  (testing ":kanban-sync specifies bidirectional sync"
    (let [coeffects {:agent-context {:project "proj"}}
          event [:kanban/sync {}]
          result (kanban/handle-kanban-sync coeffects event)]
      (is (= :bidirectional (get-in result [:kanban-sync :direction]))))))

(deftest test-handle-kanban-sync-allows-project-override-in-event
  (testing ":kanban/sync allows project override in event data"
    (let [coeffects {:agent-context {:project "default-proj"}}
          event [:kanban/sync {:project "override-proj"}]
          result (kanban/handle-kanban-sync coeffects event)]
      (is (= "override-proj" (get-in result [:kanban-sync :project]))))))

;; =============================================================================
;; WRAP-NOTIFY handler tests (FIX: agent_id attribution + defensive stats)
;; =============================================================================

(deftest test-handle-crystal-wrap-notify-with-map-stats
  (testing ":crystal/wrap-notify handles normal map stats"
    (let [event [:crystal/wrap-notify {:agent-id "ling-123"
                                       :session-id "session:2026-01-16:ling-123"
                                       :created-ids ["note-1" "note-2"]
                                       :stats {:decisions 3 :conventions 2}}]
          result ((requiring-resolve 'hive-mcp.events.handlers.crystal/handle-crystal-wrap-notify)
                  {} event)]
      (is (contains? result :wrap-notify) "Should produce :wrap-notify effect")
      (is (contains? result :shout) "Should produce :shout effect")
      (is (= "ling-123" (get-in result [:shout :agent-id])) "Should use passed agent-id")
      (is (= :wrap_notify (get-in result [:shout :event-type]))
          "Shout :event-type is the :wrap_notify keyword (intentional — see crystal.clj:193)")
      (is (re-find #"3 decisions" (get-in result [:shout :data :message]))
          "Message should include decision count"))))

(deftest test-handle-crystal-wrap-notify-with-nil-stats
  (testing ":crystal/wrap-notify handles nil stats gracefully (CLARITY-Y)"
    (let [event [:crystal/wrap-notify {:agent-id "ling-456"
                                       :session-id "session:2026-01-16:ling-456"
                                       :created-ids []
                                       :stats nil}]
          result ((requiring-resolve 'hive-mcp.events.handlers.crystal/handle-crystal-wrap-notify)
                  {} event)]
      (is (contains? result :wrap-notify) "Should produce :wrap-notify effect")
      (is (= {} (get-in result [:wrap-notify :stats])) "Should use empty map for nil stats")
      (is (re-find #"0 decisions" (get-in result [:shout :data :message]))
          "Message should default to 0 decisions"))))

(deftest test-handle-crystal-wrap-notify-with-empty-stats
  (testing ":crystal/wrap-notify handles empty stats map"
    (let [event [:crystal/wrap-notify {:agent-id "ling-789"
                                       :session-id "session:2026-01-16:ling-789"
                                       :stats {}}]
          result ((requiring-resolve 'hive-mcp.events.handlers.crystal/handle-crystal-wrap-notify)
                  {} event)]
      (is (contains? result :wrap-notify) "Should produce :wrap-notify effect")
      (is (re-find #"0 decisions" (get-in result [:shout :data :message]))
          "Message should default to 0 decisions"))))

(deftest test-handle-crystal-wrap-notify-preserves-agent-id
  (testing ":crystal/wrap-notify uses explicitly passed agent-id for attribution"
    (let [event [:crystal/wrap-notify {:agent-id "swarm-my-ling-worker-123"
                                       :session-id "session:test"
                                       :stats {:decisions 1}}]
          result ((requiring-resolve 'hive-mcp.events.handlers.crystal/handle-crystal-wrap-notify)
                  {} event)]
      (is (= "swarm-my-ling-worker-123" (get-in result [:wrap-notify :agent-id]))
          "wrap-notify should use explicit agent-id")
      (is (= "swarm-my-ling-worker-123" (get-in result [:shout :agent-id]))
          "shout should use explicit agent-id"))))
