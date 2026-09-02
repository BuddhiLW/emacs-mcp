(ns hive-mcp.events.effects-test
  "Tests for effect implementations - TDD for POC-05/06/07/08/09/10.

   Validates effect handlers work correctly. Uses set-memory-write-handler!
   API instead of with-redefs for memory tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.core :as ev]
            [hive-mcp.channel.core]
            [hive-mcp.swarm.coordinator]))

;; =============================================================================
;; Test fixture: Reset registration before each test
;; =============================================================================

(defn reset-effects-fixture [f]
  (effects/reset-registration!)
  (f))

(use-fixtures :each reset-effects-fixture)

;; =============================================================================
;; POC-05: :channel-publish effect tests
;; =============================================================================

(deftest channel-publish-effect-test
  (testing ":channel-publish effect is registered and callable"
    (effects/register-effects!)
    (let [emitted (atom nil)]
      (with-redefs [hive-mcp.channel.core/emit-event! (fn [type data]
                                                        (reset! emitted {:type type :data data})
                                                        {:emitted true})]
        (let [handler (ev/get-fx-handler :channel-publish)]
          (is (fn? handler) "Handler should be a function")
          (handler {:event-type :test-event :data {:key "value"}})
          (is (= :test-event (:type @emitted)) "Should emit correct event type")
          (is (= {:key "value"} (:data @emitted)) "Should pass data correctly"))))))

(deftest channel-publish-effect-skips-nil-event-type
  (testing ":channel-publish does nothing when event-type is nil"
    (effects/register-effects!)
    (let [emitted (atom nil)]
      (with-redefs [hive-mcp.channel.core/emit-event! (fn [type data]
                                                        (reset! emitted {:type type :data data}))]
        (let [handler (ev/get-fx-handler :channel-publish)]
          (handler {:event-type nil :data {:foo "bar"}})
          (is (nil? @emitted) "Should not emit when event-type is nil"))))))

(deftest channel-publish-effect-defaults-empty-data
  (testing ":channel-publish defaults data to empty map"
    (effects/register-effects!)
    (let [emitted (atom nil)]
      (with-redefs [hive-mcp.channel.core/emit-event! (fn [type data]
                                                        (reset! emitted {:type type :data data}))]
        (let [handler (ev/get-fx-handler :channel-publish)]
          (handler {:event-type :some-event})
          (is (= {} (:data @emitted)) "Should default to empty map"))))))

;; =============================================================================
;; POC-06: :memory-write effect tests
;; =============================================================================

(deftest memory-write-effect-test
  (testing ":memory-write effect is registered and callable"
    (effects/register-effects!)
    (let [added (atom nil)]
      ;; Configure the memory write handler (mimics server initialization)
      (effects/set-memory-write-handler!
       (fn [data]
         (reset! added data)
         {:success true :id "mem-123"}))
      (let [handler (ev/get-fx-handler :memory-write)]
        (is (fn? handler) "Handler should be a function")
        (handler {:type "note" :content "Test content" :tags ["test"]})
        (is (= "note" (:type @added)) "Should pass type")
        (is (= "Test content" (:content @added)) "Should pass content")
        (is (= ["test"] (:tags @added)) "Should pass tags")))))

(deftest memory-write-effect-skips-when-missing-required-fields
  (testing ":memory-write does nothing when type or content missing"
    (effects/register-effects!)
    (let [added (atom nil)]
      ;; Configure the memory write handler
      (effects/set-memory-write-handler!
       (fn [data] (reset! added data)))
      (let [handler (ev/get-fx-handler :memory-write)]
        ;; Missing content
        (handler {:type "note"})
        (is (nil? @added) "Should not call add when content missing")

        ;; Missing type
        (handler {:content "Some content"})
        (is (nil? @added) "Should not call add when type missing")))))

(deftest memory-write-effect-handles-exception
  (testing ":memory-write catches and logs exceptions"
    (effects/register-effects!)
    ;; Configure handler that throws
    (effects/set-memory-write-handler!
     (fn [_data] (throw (Exception. "Chroma unavailable"))))
    (let [handler (ev/get-fx-handler :memory-write)]
      ;; Should not throw - graceful failure
      (is (nil? (handler {:type "note" :content "Will fail"}))
          "Should handle exception gracefully"))))

;; =============================================================================
;; POC-07: :dispatch-task effect tests
;; =============================================================================

(deftest dispatch-task-effect-test
  (testing ":dispatch-task effect is registered and callable"
    (effects/register-effects!)
    (let [dispatched (atom nil)]
      (with-redefs [hive-mcp.swarm.coordinator/dispatch-or-queue!
                    (fn [task-spec]
                      (reset! dispatched task-spec)
                      {:action :dispatch :files (:files task-spec)})]
        (let [handler (ev/get-fx-handler :dispatch-task)]
          (is (fn? handler) "Handler should be a function")
          (handler {:slave-id "swarm-worker-1"
                    :prompt "Fix the bug"
                    :files ["src/core.clj"]})
          (is (= "swarm-worker-1" (:slave-id @dispatched)))
          (is (= "Fix the bug" (:prompt @dispatched)))
          (is (= ["src/core.clj"] (:files @dispatched))))))))

(deftest dispatch-task-effect-skips-when-missing-slave-id
  (testing ":dispatch-task does nothing when slave-id missing"
    (effects/register-effects!)
    (let [dispatched (atom nil)]
      (with-redefs [hive-mcp.swarm.coordinator/dispatch-or-queue!
                    (fn [task-spec] (reset! dispatched task-spec))]
        (let [handler (ev/get-fx-handler :dispatch-task)]
          (handler {:prompt "Do something" :files ["a.clj"]})
          (is (nil? @dispatched) "Should not dispatch without slave-id"))))))

(deftest dispatch-task-effect-skips-when-missing-prompt
  (testing ":dispatch-task does nothing when prompt missing"
    (effects/register-effects!)
    (let [dispatched (atom nil)]
      (with-redefs [hive-mcp.swarm.coordinator/dispatch-or-queue!
                    (fn [task-spec] (reset! dispatched task-spec))]
        (let [handler (ev/get-fx-handler :dispatch-task)]
          (handler {:slave-id "worker-1" :files ["a.clj"]})
          (is (nil? @dispatched) "Should not dispatch without prompt"))))))

(deftest dispatch-task-effect-handles-optional-files
  (testing ":dispatch-task works with empty files list"
    (effects/register-effects!)
    (let [dispatched (atom nil)]
      (with-redefs [hive-mcp.swarm.coordinator/dispatch-or-queue!
                    (fn [task-spec]
                      (reset! dispatched task-spec)
                      {:action :dispatch})]
        (let [handler (ev/get-fx-handler :dispatch-task)]
          (handler {:slave-id "worker-2" :prompt "Explore code"})
          (is (some? @dispatched) "Should dispatch even without files")
          (is (nil? (:files @dispatched)) "Files should be nil"))))))

;; =============================================================================
;; POC-08: :now coeffect tests
;; =============================================================================

(deftest now-coeffect-test
  (testing ":now coeffect injects current timestamp in milliseconds"
    (effects/register-effects!)
    (let [cofx-handler (ev/get-cofx-handler :now)
          before (System/currentTimeMillis)
          result (cofx-handler {})
          after (System/currentTimeMillis)]
      (is (fn? cofx-handler) "Handler should be a function")
      (is (number? (:now result)) "Should return a number")
      (is (>= (:now result) before) "Timestamp should be >= start time")
      (is (<= (:now result) after) "Timestamp should be <= end time"))))

;; =============================================================================
;; POC-09: :agent-context coeffect tests
;; =============================================================================

(deftest agent-context-coeffect-test
  (testing ":agent-context coeffect injects agent environment info"
    (effects/register-effects!)
    (let [cofx-handler (ev/get-cofx-handler :agent-context)
          result (cofx-handler {})]
      (is (fn? cofx-handler) "Handler should be a function")
      (is (map? (:agent-context result)) "Should return a map")
      (is (contains? (:agent-context result) :agent-id) "Should have :agent-id key")
      (is (contains? (:agent-context result) :cwd) "Should have :cwd key")
      ;; :cwd should always be set (user.dir is always available)
      (is (string? (:cwd (:agent-context result))) ":cwd should be a string"))))

;; =============================================================================
;; POC-10: :db-snapshot coeffect tests
;; =============================================================================

(deftest db-snapshot-coeffect-test
  (testing ":db-snapshot coeffect injects DataScript database"
    (effects/register-effects!)
    (let [cofx-handler (ev/get-cofx-handler :db-snapshot)
          result (cofx-handler {})]
      (is (fn? cofx-handler) "Handler should be a function")
      ;; Coeffect injects as :db-snapshot, not :db (per effects.clj:439-442)
      (is (contains? result :db-snapshot) "Should have :db-snapshot key")
      ;; The db should be a DataScript database value (dereferenced)
      (is (some? (:db-snapshot result)) ":db-snapshot should be non-nil"))))
