(ns hive-mcp.tools.swarm-test
  "Unit tests for swarm tool bug fixes (ADR-001 Phase 1).

   Bug 1: handle-lings-available should fallback to elisp when registry empty
   Bug 2: handle-swarm-status should preserve all slaves-detail entries

   TDD approach: These tests are written to fail first, then fixes applied."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.tools.swarm :as swarm]
            [hive-mcp.tools.swarm.core :as swarm-core]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-dsl.bounded-atom :refer [bput! bget bclear!]]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each (iso/with-isolations :swarm-ds :agent-registry))

;; =============================================================================
;; Bug 1: handle-lings-available Elisp Fallback
;; =============================================================================

(deftest lings-available-registry-only-test
  (testing "Returns lings from registry when populated"
    ;; Setup: Register a ling in the Clojure registry
    (swarm/register-ling! "test-ling-1" {:name "worker-1"
                                         :presets ["tdd"]
                                         :cwd "/home/user/project"})

    (let [result (swarm/handle-lings-available {})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (= "text" (:type result)))
      (is (= 1 (:count parsed)))
      (is (contains? (:lings parsed) :test-ling-1))
      (is (= "worker-1" (get-in parsed [:lings :test-ling-1 :name]))))))

(deftest lings-available-elisp-fallback-when-empty-test
  (testing "Falls back to elisp query when registry is empty but elisp has lings"
    ;; Registry is empty (via fixture)
    ;; Mock elisp to return lings
    (let [elisp-lings [{:slave-id "elisp-ling-1"
                        :name "elisp-worker"
                        :presets ["reviewer"]
                        :cwd "/tmp/project"
                        :status "idle"}
                       {:slave-id "elisp-ling-2"
                        :name "elisp-tester"
                        :presets ["tdd"]
                        :cwd "/tmp/test"
                        :status "working"}]
          elisp-json (json/write-str elisp-lings)]
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [elisp _timeout]
                      ;; Check it's calling the lings list function
                      (if (re-find #"hive-mcp-swarm-list-lings" elisp)
                        {:success true :result elisp-json :timed-out false}
                        {:success true :result "t" :timed-out false}))]
        (let [result (swarm/handle-lings-available {})
              parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "text" (:type result)))
          (is (= 2 (:count parsed))
              "Should return 2 lings from elisp fallback")
          ;; The fallback should populate the response with elisp data
          (is (some #(= "elisp-worker" (:name %)) (vals (:lings parsed)))
              "Should include elisp-worker from fallback"))))))

(deftest lings-available-elisp-fallback-graceful-degradation-test
  (testing "Returns empty gracefully when both registry and elisp have no lings"
    (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                  ec/eval-elisp-with-timeout
                  (fn [_elisp _timeout]
                    {:success true :result "[]" :timed-out false})]
      (let [result (swarm/handle-lings-available {})
            parsed (json/read-str (:text result) :key-fn keyword)]
        (is (= "text" (:type result)))
        (is (= 0 (:count parsed)))
        (is (empty? (:lings parsed)))))))

(deftest lings-available-elisp-fallback-error-handling-test
  (testing "Handles elisp fallback errors gracefully"
    ;; Registry is empty, elisp fails
    (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                  ec/eval-elisp-with-timeout
                  (fn [elisp _timeout]
                    (if (re-find #"hive-mcp-swarm-list-lings" elisp)
                      {:success false :error "Emacs not responding" :timed-out true}
                      {:success true :result "t" :timed-out false}))]
      (let [result (swarm/handle-lings-available {})
            parsed (json/read-str (:text result) :key-fn keyword)]
        ;; Should return empty (from registry) rather than error
        (is (= "text" (:type result)))
        (is (= 0 (:count parsed))
            "Should return empty count, not error, when fallback fails")))))

;; =============================================================================
;; Bug 2: handle-swarm-status Merge Logic
;; =============================================================================

(deftest swarm-status-preserves-all-slaves-vector-test
  (testing "Preserves all slaves when slaves-detail is a vector"
    (let [status-json (json/write-str
                       {:slaves-count 3
                        :slaves-detail [{:slave-id "slave-1" :name "worker-1" :status "idle"}
                                        {:slave-id "slave-2" :name "worker-2" :status "busy"}
                                        {:slave-id "slave-3" :name "worker-3" :status "idle"}]})]
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [_elisp _timeout]
                      {:success true :result status-json :timed-out false})]
        (let [result (swarm/handle-swarm-status {})
              parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "text" (:type result)))
          (is (= 3 (count (:slaves-detail parsed)))
              "All 3 slaves should be preserved in output")
          (is (= #{"slave-1" "slave-2" "slave-3"}
                 (set (map :slave-id (:slaves-detail parsed))))
              "All slave IDs should be present"))))))

(deftest swarm-status-merge-with-hivemind-preserves-all-test
  (testing "Merging hivemind status preserves all slaves"
    ;; Setup: Add hivemind status for some agents
    (bput! hivemind/agent-registry
           "slave-1" {:status :progress :task "Working on tests"})
    (bput! hivemind/agent-registry
           "slave-3" {:status :completed :task "Done with review"})

    (let [status-json (json/write-str
                       {:slaves-count 3
                        :slaves-detail [{:slave-id "slave-1" :name "worker-1" :status "idle"}
                                        {:slave-id "slave-2" :name "worker-2" :status "idle"}
                                        {:slave-id "slave-3" :name "worker-3" :status "idle"}]})]
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [_elisp _timeout]
                      {:success true :result status-json :timed-out false})]
        (let [result (swarm/handle-swarm-status {})
              parsed (json/read-str (:text result) :key-fn keyword)
              slaves-by-id (into {} (map (juxt :slave-id identity)
                                         (:slaves-detail parsed)))]
          (is (= 3 (count (:slaves-detail parsed)))
              "All 3 slaves should be preserved after merge")
          ;; Check hivemind status was merged
          (is (= "working" (name (:status (get slaves-by-id "slave-1"))))
              "slave-1 should have hivemind 'working' status")
          (is (= "idle" (name (:status (get slaves-by-id "slave-3"))))
              "slave-3 should have hivemind 'idle' status (completed)")
          ;; slave-2 has no hivemind entry, should retain original
          (is (some? (get slaves-by-id "slave-2"))
              "slave-2 should still be present"))))))

(deftest swarm-status-handles-nil-slaves-detail-test
  (testing "Handles nil slaves-detail gracefully"
    (let [status-json (json/write-str {:slaves-count 0})]
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [_elisp _timeout]
                      {:success true :result status-json :timed-out false})]
        (let [result (swarm/handle-swarm-status {})
              parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "text" (:type result)))
          (is (nil? (:slaves-detail parsed))
              "Should handle nil slaves-detail without error"))))))

(deftest swarm-status-handles-empty-slaves-detail-test
  (testing "Handles empty slaves-detail vector gracefully"
    (let [status-json (json/write-str {:slaves-count 0 :slaves-detail []})]
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [_elisp _timeout]
                      {:success true :result status-json :timed-out false})]
        (let [result (swarm/handle-swarm-status {})
              parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "text" (:type result)))
          (is (empty? (:slaves-detail parsed))
              "Should handle empty slaves-detail"))))))

(deftest swarm-status-large-slave-count-test
  (testing "Preserves all slaves with larger slave counts (regression)"
    (let [many-slaves (mapv (fn [i]
                              {:slave-id (str "slave-" i)
                               :name (str "worker-" i)
                               :status "idle"})
                            (range 10))
          status-json (json/write-str {:slaves-count 10
                                       :slaves-detail many-slaves})]
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [_elisp _timeout]
                      {:success true :result status-json :timed-out false})]
        (let [result (swarm/handle-swarm-status {})
              parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= 10 (count (:slaves-detail parsed)))
              "All 10 slaves should be preserved - no truncation"))))))

;; =============================================================================
;; Helper Function Tests
;; =============================================================================

(deftest get-slave-working-status-test
  (testing "Maps hivemind event types to working status"
    ;; Setup various agent states
    (bput! hivemind/agent-registry
           "agent-started" {:status :started})
    (bput! hivemind/agent-registry
           "agent-progress" {:status :progress})
    (bput! hivemind/agent-registry
           "agent-completed" {:status :completed})
    (bput! hivemind/agent-registry
           "agent-error" {:status :error})
    (bput! hivemind/agent-registry
           "agent-blocked" {:status :blocked})

    (is (= "working" (swarm/get-slave-working-status "agent-started")))
    (is (= "working" (swarm/get-slave-working-status "agent-progress")))
    (is (= "idle" (swarm/get-slave-working-status "agent-completed")))
    (is (= "idle" (swarm/get-slave-working-status "agent-error")))
    (is (= "blocked" (swarm/get-slave-working-status "agent-blocked")))
    (is (nil? (swarm/get-slave-working-status "nonexistent-agent")))))

(deftest register-unregister-ling-test
  (testing "Register and unregister lings correctly"
    (swarm/register-ling! "ling-1" {:name "worker" :presets ["tdd"] :cwd "/tmp"})

    ;; ADR-002: Use DataScript queries instead of deprecated atom
    (let [lings (swarm/get-available-lings)]
      (is (contains? lings "ling-1"))
      (is (= "worker" (:name (get lings "ling-1"))))
      (is (number? (:spawned-at (get lings "ling-1")))))

    (swarm/unregister-ling! "ling-1")
    (let [lings-after (swarm/get-available-lings)]
      (is (not (contains? lings-after "ling-1"))))))

;; =============================================================================
;; Bug Fix: swarm_kill should clean up agents from hivemind_status (task 9871bcf4)
;; =============================================================================

(deftest swarm-kill-cleans-up-hivemind-agents-test
  (testing "swarm_kill removes agent from hivemind_status :agents map"
    ;; Setup: Register a ling in both registries (simulating real spawn)
    (let [slave-id "test-ling-cleanup"
          metadata {:name "cleanup-worker" :presets ["tdd"] :cwd "/tmp/test"}]
      ;; Register in swarm registry (DataScript)
      (swarm/register-ling! slave-id metadata)
      ;; Also register in hivemind (as event-driven spawn does)
      (hivemind/register-agent! slave-id metadata)

      ;; Verify agent appears in hivemind_status
      (let [status-before (hivemind/get-status)
            agents-before (:agents status-before)]
        (is (contains? agents-before slave-id)
            "Agent should be in hivemind_status before kill"))

      ;; Kill the ling via the handler (mocking elisp call success)
      ;; Note: Must mock swarm-core/swarm-addon-available? since with-swarm macro uses it
      (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                    ec/eval-elisp-with-timeout
                    (fn [_elisp _timeout]
                      {:success true :result "{\"killed\": true}" :timed-out false})]
        (swarm/handle-swarm-kill {:slave_id slave-id}))

      ;; Verify agent is removed from hivemind_status
      (let [status-after (hivemind/get-status)
            agents-after (:agents status-after)]
        (is (not (contains? agents-after slave-id))
            "Agent should NOT be in hivemind_status after kill - BUG: stale entries remain")))))

(deftest swarm-kill-all-cleans-up-hivemind-agents-test
  (testing "swarm_kill with 'all' removes all agents from hivemind_status"
    ;; Setup: Register multiple lings
    (doseq [i (range 3)]
      (let [slave-id (str "test-ling-" i)
            metadata {:name (str "worker-" i) :presets ["tdd"] :cwd "/tmp"}]
        (swarm/register-ling! slave-id metadata)
        (hivemind/register-agent! slave-id metadata)))

    ;; Verify all agents appear in hivemind_status
    (let [status-before (hivemind/get-status)
          agents-before (:agents status-before)]
      (is (= 3 (count agents-before))
          "All 3 agents should be in hivemind_status before kill"))

    ;; Kill all via the handler
    ;; Note: Must mock swarm-core/swarm-addon-available? since with-swarm macro uses it
    (with-redefs [swarm-core/swarm-addon-available? (constantly true)
                  ec/eval-elisp-with-timeout
                  (fn [_elisp _timeout]
                    {:success true :result "{\"killed\": 3}" :timed-out false})]
      (swarm/handle-swarm-kill {:slave_id "all"}))

    ;; Verify all agents are removed from hivemind_status
    (let [status-after (hivemind/get-status)
          agents-after (:agents status-after)]
      (is (empty? agents-after)
          "No agents should be in hivemind_status after kill all - BUG: stale entries remain"))))
