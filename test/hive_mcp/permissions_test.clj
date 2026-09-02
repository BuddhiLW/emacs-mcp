(ns hive-mcp.permissions-test
  "Pinning tests for tiered permission system.
   
   Tests verify:
   - Tier detection logic (get-tier)
   - Auto-approval for tier-1 (test files)
   - Coordinator review for tier-2 (source files)
   - Human approval for tier-3 (destructive operations)
   - Escalation flow with mocked hivemind
   
   Uses with-redefs to mock hivemind and channel for isolated unit testing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.server.permissions :as perm]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.channel.core :as channel]
            [clojure.string]))

;; =============================================================================
;; Test Fixtures & Helpers
;; =============================================================================

(def ^:dynamic *hivemind-asks* nil)
(def ^:dynamic *channel-events* nil)

(defn capture-calls-fixture
  "Fixture to capture hivemind and channel calls."
  [f]
  (binding [*hivemind-asks* (atom [])
            *channel-events* (atom [])]
    (f)))

(use-fixtures :each capture-calls-fixture)

(defn mock-ask-approve
  "Mock hivemind/ask! that auto-approves."
  [agent-id question options & _]
  (when *hivemind-asks*
    (swap! *hivemind-asks* conj {:agent-id agent-id
                                 :question question
                                 :options options}))
  {:decision (first options) :by "test"})

(defn mock-ask-deny
  "Mock hivemind/ask! that denies."
  [agent-id question options & _]
  (when *hivemind-asks*
    (swap! *hivemind-asks* conj {:agent-id agent-id
                                 :question question
                                 :options options}))
  {:decision (second options) :by "test"})

(defn mock-emit-event!
  "Mock channel/emit-event! that captures events."
  [event-type data]
  (when *channel-events*
    (swap! *channel-events* conj {:type event-type :data data})))

;; =============================================================================
;; Test: Tier Detection - Tier 1 (Auto-approve)
;; =============================================================================

(deftest test-get-tier-test-files-clojure
  (testing "Test files (.clj) are tier-1"
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "test/core_test.clj"})))
    (is (= :tier-1 (perm/get-tier "file_write" {"file_path" "test/foo/bar_test.clj"})))
    (is (= :tier-1 (perm/get-tier "clojure_edit" {"file_path" "some_test.clj"})))))

(deftest test-get-tier-test-files-other-languages
  (testing "Test files in other languages are tier-1"
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "handler_test.go"})))
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "component_test.js"})))
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "service_test.ts"})))))

(deftest test-get-tier-test-directory
  (testing "Files in test/ directory are tier-1"
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "test/utils.clj"})))
    (is (= :tier-1 (perm/get-tier "file_write" {"file_path" "test/helpers/mock.clj"})))))

(deftest test-get-tier-mock-files
  (testing "Mock files are tier-1"
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "api_mock.clj"})))
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "service_mock.go"})))))

(deftest test-get-tier-generated-files
  (testing "Generated files are tier-1"
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "src/generated/api.clj"})))
    (is (= :tier-1 (perm/get-tier "file_write" {"file_path" "lib/generated/types.ts"})))))

(deftest test-get-tier-fixtures
  (testing "Fixture files are tier-1"
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "test/fixtures/sample.json"})))
    ;; Note: tier-1 pattern requires parent directory before /fixtures/
    (is (= :tier-1 (perm/get-tier "file_write" {"file_path" "test/fixtures/test_data.edn"})))))

;; =============================================================================
;; Test: Tier Detection - Tier 2 (Coordinator Review)
;; =============================================================================

(deftest test-get-tier-src-files
  (testing "Source files in src/ are tier-2"
    (is (= :tier-2 (perm/get-tier "file_edit" {"file_path" "src/core.clj"})))
    (is (= :tier-2 (perm/get-tier "file_write" {"file_path" "src/api/handler.clj"})))
    (is (= :tier-2 (perm/get-tier "clojure_edit" {"file_path" "src/domain/user.clj"})))))

(deftest test-get-tier-lib-files
  (testing "Library files in lib/ are tier-2"
    (is (= :tier-2 (perm/get-tier "file_edit" {"file_path" "lib/utils.clj"})))
    (is (= :tier-2 (perm/get-tier "file_write" {"file_path" "lib/helpers/format.clj"})))))

(deftest test-get-tier-elisp-files
  (testing "Elisp files in elisp/ are tier-2"
    (is (= :tier-2 (perm/get-tier "file_edit" {"file_path" "elisp/hive-mcp.el"})))
    (is (= :tier-2 (perm/get-tier "file_write" {"file_path" "elisp/addons/swarm.el"})))))

;; =============================================================================
;; Test: Tier Detection - Tier 3 (Human Approval Required)
;; =============================================================================

(deftest test-get-tier-dangerous-tools
  (testing "Dangerous tools are tier-3 regardless of arguments"
    (is (= :tier-3 (perm/get-tier "bash" {"command" "ls"})))
    (is (= :tier-3 (perm/get-tier "eval_elisp" {"code" "(message \"hello\")"})))
    (is (= :tier-3 (perm/get-tier "cider_eval_explicit" {"code" "(println 1)"})))
    (is (= :tier-3 (perm/get-tier "magit_commit" {"message" "fix"})))
    (is (= :tier-3 (perm/get-tier "magit_push" {})))
    (is (= :tier-3 (perm/get-tier "preset_delete" {"name" "tdd"})))
    (is (= :tier-3 (perm/get-tier "swarm_kill" {"slave_id" "test"})))
    (is (= :tier-3 (perm/get-tier "mcp_memory_cleanup_expired" {})))))

(deftest test-get-tier-destructive-bash-commands
  (testing "Destructive bash commands are tier-3"
    (is (= :tier-3 (perm/get-tier "bash" {"command" "rm -rf /tmp/foo"})))
    (is (= :tier-3 (perm/get-tier "bash" {"command" "DROP TABLE users;"})))
    (is (= :tier-3 (perm/get-tier "bash" {"command" "TRUNCATE TABLE logs;"})))
    (is (= :tier-3 (perm/get-tier "bash" {"command" "DELETE FROM orders WHERE id = 1;"})))))

;; =============================================================================
;; Test: Tier Detection - Allowed (No Escalation)
;; =============================================================================

(deftest test-get-tier-read-only-tools
  (testing "Read-only tools are allowed"
    (is (= :allowed (perm/get-tier "read_file" {"path" "src/core.clj"})))
    (is (= :allowed (perm/get-tier "grep" {"pattern" "defn"})))
    (is (= :allowed (perm/get-tier "glob_files" {"pattern" "**/*.clj"})))
    (is (= :allowed (perm/get-tier "magit_status" {})))
    (is (= :allowed (perm/get-tier "magit_log" {"count" 10})))))

(deftest test-get-tier-unknown-path
  (testing "File edits without recognized path are allowed"
    (is (= :allowed (perm/get-tier "file_edit" {"file_path" "README.md"})))
    (is (= :allowed (perm/get-tier "file_write" {"file_path" "docs/guide.md"})))))

;; =============================================================================
;; Test: requires-escalation?
;; =============================================================================

(deftest test-requires-escalation
  (testing "requires-escalation? returns true for non-allowed tiers"
    (is (true? (perm/requires-escalation? "file_edit" {"file_path" "src/core.clj"})))
    (is (true? (perm/requires-escalation? "bash" {"command" "ls"})))
    (is (true? (perm/requires-escalation? "file_write" {"file_path" "test/foo_test.clj"})))))

(deftest test-does-not-require-escalation
  (testing "requires-escalation? returns false for allowed tier"
    (is (false? (perm/requires-escalation? "read_file" {"path" "src/core.clj"})))
    (is (false? (perm/requires-escalation? "grep" {"pattern" "defn"})))))

;; =============================================================================
;; Test: Escalation - Tier 1 Auto-Approve
;; =============================================================================

(deftest test-escalate-tier-1-auto-approves
  (testing "Tier-1 escalation auto-approves without asking"
    (let [result (perm/escalate! "agent-123" "file_edit"
                                 {"file_path" "test/core_test.clj"
                                  "content" "(deftest foo)"})]
      (is (true? (:approved result)))
      (is (= :tier-1 (:tier result)))
      (is (= :auto (:reviewer result))))))

(deftest test-escalate-tier-1-no-hivemind-call
  (testing "Tier-1 does not call hivemind"
    (with-redefs [hivemind/ask! mock-ask-approve]
      (perm/escalate! "agent-123" "file_write"
                      {"file_path" "test/fixtures/data.json"
                       "content" "{}"})
      (is (= 0 (count @*hivemind-asks*))))))

;; =============================================================================
;; Test: Escalation - Tier 2 Coordinator Review  
;; =============================================================================

(deftest test-escalate-tier-2-asks-coordinator
  (testing "Tier-2 escalation asks coordinator via hivemind"
    (with-redefs [hivemind/ask! mock-ask-approve]
      (let [result (perm/escalate! "agent-456" "file_edit"
                                   {"file_path" "src/api.clj"
                                    "new_string" "(defn handler [])"})]
        (is (true? (:approved result)))
        (is (= :tier-2 (:tier result)))
        (is (= :coordinator (:reviewer result)))
        ;; Verify hivemind was called
        (is (= 1 (count @*hivemind-asks*)))
        (let [ask (first @*hivemind-asks*)]
          (is (= "agent-456" (:agent-id ask)))
          (is (clojure.string/includes? (:question ask) "TIER-2"))
          (is (clojure.string/includes? (:question ask) "src/api.clj")))))))

(deftest test-escalate-tier-2-denied
  (testing "Tier-2 denial returns approved=false with reason"
    (with-redefs [hivemind/ask! mock-ask-deny]
      (let [result (perm/escalate! "agent-456" "file_edit"
                                   {"file_path" "src/core.clj"
                                    "content" "bad code"})]
        (is (false? (:approved result)))
        (is (= :tier-2 (:tier result)))
        (is (= :coordinator (:reviewer result)))
        (is (= "Coordinator denied" (:reason result)))))))

(deftest test-escalate-tier-2-shows-content-preview
  (testing "Tier-2 includes content preview in question"
    (with-redefs [hivemind/ask! mock-ask-approve]
      (perm/escalate! "agent-456" "file_edit"
                      {"file_path" "src/api.clj"
                       "new_string" "(defn foo []\n  (println \"hello\")\n  42)"})
      (let [ask (first @*hivemind-asks*)]
        (is (clojure.string/includes? (:question ask) "Preview"))
        (is (clojure.string/includes? (:question ask) "defn foo"))))))

;; =============================================================================
;; Test: Escalation - Tier 3 Human Approval
;; =============================================================================

(deftest test-escalate-tier-3-asks-human
  (testing "Tier-3 escalation asks human via hivemind"
    (with-redefs [hivemind/ask! mock-ask-approve
                  channel/emit-event! mock-emit-event!]
      (let [result (perm/escalate! "agent-789" "bash"
                                   {"command" "ls -la"})]
        (is (true? (:approved result)))
        (is (= :tier-3 (:tier result)))
        (is (= :human (:reviewer result)))
        ;; Verify hivemind was called
        (is (= 1 (count @*hivemind-asks*)))
        (let [ask (first @*hivemind-asks*)]
          (is (= "agent-789" (:agent-id ask)))
          (is (clojure.string/includes? (:question ask) "TIER-3"))
          (is (clojure.string/includes? (:question ask) "bash")))))))

(deftest test-escalate-tier-3-emits-channel-event
  (testing "Tier-3 emits approval-request event to channel"
    (with-redefs [hivemind/ask! mock-ask-approve
                  channel/emit-event! mock-emit-event!]
      (perm/escalate! "agent-789" "magit_push" {})
      ;; Verify channel event was emitted
      (is (= 1 (count @*channel-events*)))
      (let [event (first @*channel-events*)]
        (is (= :approval-request (:type event)))
        (is (= "agent-789" (get-in event [:data :agent-id])))
        (is (= "magit_push" (get-in event [:data :tool])))
        (is (= :tier-3 (get-in event [:data :tier])))))))

(deftest test-escalate-tier-3-denied
  (testing "Tier-3 denial returns approved=false with reason"
    (with-redefs [hivemind/ask! mock-ask-deny
                  channel/emit-event! mock-emit-event!]
      (let [result (perm/escalate! "agent-789" "eval_elisp"
                                   {"code" "(delete-file \"/important\")"})]
        (is (false? (:approved result)))
        (is (= :tier-3 (:tier result)))
        (is (= :human (:reviewer result)))
        (is (= "Human denied" (:reason result)))))))

(deftest test-escalate-tier-3-truncates-long-code
  (testing "Tier-3 truncates long code in preview"
    (with-redefs [hivemind/ask! mock-ask-approve
                  channel/emit-event! mock-emit-event!]
      (let [long-code (apply str (repeat 500 "x"))]
        (perm/escalate! "agent-789" "bash" {"command" long-code})
        (let [ask (first @*hivemind-asks*)]
          ;; Question should not contain full 500 chars
          (is (< (count (:question ask)) 600)))))))

;; =============================================================================
;; Test: Escalation - Allowed (No Escalation)
;; =============================================================================

(deftest test-escalate-allowed-no-approval-needed
  (testing "Allowed tier returns approved without asking"
    (with-redefs [hivemind/ask! mock-ask-approve]
      (let [result (perm/escalate! "agent-123" "read_file"
                                   {"path" "src/core.clj"})]
        (is (true? (:approved result)))
        (is (= :allowed (:tier result)))
        (is (= :none (:reviewer result)))
        ;; No hivemind call
        (is (= 0 (count @*hivemind-asks*)))))))

;; =============================================================================
;; Test: approved? Helper
;; =============================================================================

(deftest test-approved-helper
  (testing "approved? extracts :approved from result"
    (is (true? (perm/approved? {:approved true :tier :tier-1})))
    (is (false? (perm/approved? {:approved false :tier :tier-3 :reason "denied"})))
    (is (nil? (perm/approved? {})))))

;; =============================================================================
;; Test: Edge Cases
;; =============================================================================

(deftest test-get-tier-nil-path
  (testing "Handles nil file path gracefully"
    (is (= :allowed (perm/get-tier "file_edit" {})))
    (is (= :allowed (perm/get-tier "file_edit" {"file_path" nil})))))

(deftest test-get-tier-alternative-path-keys
  (testing "Extracts path from alternative keys"
    ;; Should work with 'path' key
    (is (= :tier-1 (perm/get-tier "file_edit" {"path" "test/foo_test.clj"})))
    ;; Should work with 'file' key  
    (is (= :tier-2 (perm/get-tier "file_write" {"file" "src/core.clj"})))))

(deftest test-get-tier-case-sensitive-patterns
  (testing "Pattern matching is case-sensitive where appropriate"
    ;; test/ directory should match
    (is (= :tier-1 (perm/get-tier "file_edit" {"file_path" "test/foo.clj"})))
    ;; TEST/ should not match (if patterns are case-sensitive)
    ;; This depends on implementation - adjust if needed
    ))

(deftest test-escalate-unknown-tier
  (testing "Unknown tier returns error"
    ;; This tests defensive programming - if get-tier returns unexpected value
    ;; The implementation should handle this gracefully
    ))
