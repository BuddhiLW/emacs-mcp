(ns hive-mcp.agent.drone.tool-allowlist-test
  "Tests for drone tool allowlist enforcement.

   CLARITY-I: Verifies that tool calls are properly filtered
   by the allowlist before reaching the executor."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.drone.tool-allowlist :as allowlist]))

;;; ============================================================
;;; Test Helpers
;;; ============================================================

(defn make-tool-call
  "Create a tool call map for testing."
  ([name] (make-tool-call name {}))
  ([name args]
   {:id (str "call-" name "-" (rand-int 10000))
    :name name
    :arguments args}))

;;; ============================================================
;;; resolve-allowlist tests
;;; ============================================================

(deftest resolve-allowlist-explicit-override
  (testing "Explicit :tool-allowlist takes highest priority"
    (let [custom #{"read_file" "grep"}
          result (allowlist/resolve-allowlist {:tool-allowlist custom
                                               :task-type :testing})]
      (is (= custom result))
      (is (contains? result "read_file"))
      (is (not (contains? result "bash"))))))

(deftest resolve-allowlist-task-type
  (testing "Task-type profile used when no explicit allowlist"
    (let [result (allowlist/resolve-allowlist {:task-type :documentation})]
      ;; :documentation is the one narrowed profile — a fixed literal set,
      ;; not the discovered toolset.
      (is (contains? result "read_file"))
      (is (contains? result "file_write"))
      (is (contains? result "glob_files"))
      (is (contains? result "grep"))
      ;; Narrower than the full toolset: no shell, no eval, no editing surface
      (is (not (contains? result "bash")))
      (is (not (contains? result "clojure")))
      (is (not (contains? result "code")))
      (is (not (contains? result "edit"))))))

(deftest resolve-allowlist-default-fallback
  (testing "Default allowlist used when no options provided"
    (let [result (allowlist/resolve-allowlist {})]
      (is (= (allowlist/default-allowlist) result))
      ;; Only assert the registry-independent member — see
      ;; default-allowlist-contains-required-tools for why.
      (is (contains? result "bash")))))

(deftest resolve-allowlist-nil-opts
  (testing "nil task-type and nil allowlist returns default"
    (let [result (allowlist/resolve-allowlist {:tool-allowlist nil
                                               :task-type nil})]
      (is (= (allowlist/default-allowlist) result)))))

;;; ============================================================
;;; tool-allowed? tests
;;; ============================================================

(deftest tool-allowed-present
  (testing "Tool on allowlist returns true"
    (is (true? (allowlist/tool-allowed? "read_file" #{"read_file" "grep"})))))

(deftest tool-allowed-absent
  (testing "Tool not on allowlist returns false"
    (is (false? (allowlist/tool-allowed? "bash" #{"read_file" "grep"})))))

(deftest tool-allowed-empty-allowlist
  (testing "Empty allowlist rejects everything"
    (is (false? (allowlist/tool-allowed? "read_file" #{})))))

;;; ============================================================
;;; reject-tool-call tests
;;; ============================================================

(deftest reject-tool-call-format
  (testing "Rejection result has correct format"
    (let [result (allowlist/reject-tool-call "call-1" "bash" #{"read_file"})]
      (is (= "tool" (:role result)))
      (is (= "call-1" (:tool_call_id result)))
      (is (= "bash" (:name result)))
      (is (string? (:content result)))
      (is (.contains (:content result) "TOOL REJECTED"))
      (is (.contains (:content result) "bash"))
      (is (.contains (:content result) "read_file")))))

;;; ============================================================
;;; enforce-allowlist tests
;;; ============================================================

(deftest enforce-allowlist-all-allowed
  (testing "All tools on allowlist pass through"
    (let [calls [(make-tool-call "read_file" {:path "/src/foo.clj"})
                 (make-tool-call "grep" {:pattern "defn"})]
          result (allowlist/enforce-allowlist calls #{"read_file" "grep"})]
      (is (= 2 (count (:allowed result))))
      (is (empty? (:rejected result))))))

(deftest enforce-allowlist-all-rejected
  (testing "All tools not on allowlist are rejected"
    (let [calls [(make-tool-call "bash" {:command "rm -rf /"})
                 (make-tool-call "magit_push" {})]
          result (allowlist/enforce-allowlist calls #{"read_file"})]
      (is (empty? (:allowed result)))
      (is (= 2 (count (:rejected result))))
      ;; Verify rejection format
      (doseq [r (:rejected result)]
        (is (= "tool" (:role r)))
        (is (.contains (:content r) "TOOL REJECTED"))))))

(deftest enforce-allowlist-mixed
  (testing "Mixed batch: some allowed, some rejected"
    (let [calls [(make-tool-call "read_file" {:path "/src/foo.clj"})
                 (make-tool-call "bash" {:command "ls"})
                 (make-tool-call "grep" {:pattern "TODO"})
                 (make-tool-call "magit_push" {})]
          al #{"read_file" "grep"}
          result (allowlist/enforce-allowlist calls al)]
      (is (= 2 (count (:allowed result))))
      (is (= 2 (count (:rejected result))))
      ;; Allowed should be read_file and grep
      (is (= #{"read_file" "grep"}
             (set (map :name (:allowed result)))))
      ;; Rejected should be bash and magit_push
      (is (= #{"bash" "magit_push"}
             (set (map :name (:rejected result))))))))

(deftest enforce-allowlist-empty-calls
  (testing "Empty tool calls returns empty results"
    (let [result (allowlist/enforce-allowlist [] #{"read_file"})]
      (is (empty? (:allowed result)))
      (is (empty? (:rejected result))))))

(deftest enforce-allowlist-preserves-call-data
  (testing "Allowed calls preserve original data"
    (let [call (make-tool-call "read_file" {:path "/src/foo.clj"})
          result (allowlist/enforce-allowlist [call] #{"read_file"})
          allowed-call (first (:allowed result))]
      (is (= (:id call) (:id allowed-call)))
      (is (= (:name call) (:name allowed-call)))
      (is (= (:arguments call) (:arguments allowed-call))))))

;;; ============================================================
;;; Default allowlist coverage
;;; ============================================================

(deftest default-allowlist-contains-required-tools
  (testing "Default allowlist is the discovered toolset, not empty"
    (let [al (allowlist/default-allowlist)]
      (is (seq al))
      (is (every? string? al))
      ;; `bash` is the one guaranteed member: drone-extra-tools adds it
      ;; unconditionally because the drone tool proxy provides it, so it does
      ;; not depend on which addons happen to be registered.
      (is (contains? al "bash"))))
  (testing "everything else is registry-dependent, so assert the RULE not the roster"
    ;; The discovered set differs between a cold JVM (agent registry only) and a
    ;; live server (extension registry populated by addons) — a cold run has no
    ;; read_file/grep/edit at all. Pinning names here is what made this suite
    ;; pass hot and fail cold; the invariant the ns actually owns is the
    ;; blacklist, asserted in default-allowlist-excludes-dangerous-tools.
    (let [al (allowlist/default-allowlist)]
      (is (= al (allowlist/default-allowlist)) "discovery is stable within a run"))))

(deftest default-allowlist-excludes-dangerous-tools
  (testing "Default allowlist excludes the recursive-spawn and coordinator-only tools"
    (let [al (allowlist/default-allowlist)]
      ;; Recursive self-call / resource amplification
      (is (not (contains? al "agent")))
      (is (not (contains? al "wave")))
      (is (not (contains? al "workflow")))
      (is (not (contains? al "delegate")))
      ;; Bypass vector — routes to everything else
      (is (not (contains? al "multi")))
      ;; Coordinator-only surfaces
      (is (not (contains? al "olympus")))
      (is (not (contains? al "emacs")))
      (is (not (contains? al "config")))
      ;; Destructive
      (is (not (contains? al "migration")))))
  (testing "deprecated fine-grained names are gone from the registry entirely"
    ;; These were the old assertions. They pass trivially now — the shims no
    ;; longer exist — so they prove nothing about the blacklist. Kept only to
    ;; catch a regression that re-registers them.
    (let [al (allowlist/default-allowlist)]
      (is (not (contains? al "swarm_spawn")))
      (is (not (contains? al "delegate_drone")))
      (is (not (contains? al "magit_push"))))))
