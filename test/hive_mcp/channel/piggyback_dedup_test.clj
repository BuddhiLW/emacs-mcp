(ns hive-mcp.channel.piggyback-dedup-test
  "Regression tests for dual-path dedup in merged-messages.

   Root cause (kanban 54741a74): when NATS backbone is connected, shout!
   writes to both the atom (message-source-fn path) AND publishes to NATS
   (backbone-buffer path). JSON roundtrip converts keyword event-types to
   strings, so the old dedup key [agent-id timestamp :progress] didn't
   match [agent-id timestamp \"progress\"] — producing duplicates.

   Fix: stable :shout-id (UUID) at emit time enables cross-path dedup
   regardless of keyword/string mismatch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.channel.piggyback :as pb]))

;; Reset all state between tests
(use-fixtures :each
  (fn [f]
    (let [original-source @pb/message-source-fn]
      (pb/reset-all-cursors!)
      (pb/clear-backbone-buffer!)
      (f)
      (pb/reset-all-cursors!)
      (pb/clear-backbone-buffer!)
      (pb/register-message-source! original-source))))

;; =============================================================================
;; Dual-Path Dedup — The Core Bug
;; =============================================================================

(deftest shout-id-dedup-across-atom-and-backbone-test
  (testing "REGRESSION: same shout-id from atom + backbone path produces exactly 1 message"
    (let [shout-id (str (random-uuid))
          now (System/currentTimeMillis)
          ;; Atom path: event-type is keyword (Clojure-native)
          atom-msg {:agent-id "ling-dedup-1"
                    :event-type :progress
                    :message "Working on X"
                    :timestamp now
                    :project-id "test-project"
                    :shout-id shout-id}
          ;; Backbone path: event-type is string (JSON roundtrip)
          backbone-msg {:agent-id "ling-dedup-1"
                        :event-type "progress"
                        :message "Working on X"
                        :timestamp now
                        :project-id "test-project"
                        :shout-id shout-id}]

      ;; Simulate atom path
      (pb/register-message-source! (constantly [atom-msg]))

      ;; Simulate backbone path
      (pb/buffer-backbone-event! backbone-msg)

      ;; Read messages — should see exactly 1
      (let [msgs (pb/get-messages "coordinator:test"
                                   :project-id "test-project")]
        (is (= 1 (count msgs))
            "Same shout-id from both paths should produce exactly 1 message")
        (is (= "ling-dedup-1" (:a (first msgs))))))))

(deftest keyword-vs-string-event-type-dedup-without-shout-id-test
  (testing "Fallback dedup normalizes event-type even without shout-id"
    (let [now (System/currentTimeMillis)
          ;; Legacy message without shout-id, keyword event-type
          atom-msg {:agent-id "ling-legacy"
                    :event-type :completed
                    :message "Done"
                    :timestamp now
                    :project-id "global"}
          ;; Same message, string event-type (as from JSON roundtrip)
          backbone-msg {:agent-id "ling-legacy"
                        :event-type "completed"
                        :message "Done"
                        :timestamp now
                        :project-id "global"}]

      (pb/register-message-source! (constantly [atom-msg]))
      (pb/buffer-backbone-event! backbone-msg)

      (let [msgs (pb/get-messages "coordinator:test"
                                   :project-id "global")]
        (is (= 1 (count msgs))
            "Normalized fallback key should dedup keyword vs string event-type")))))

(deftest distinct-shout-ids-not-deduped-test
  (testing "Messages with different shout-ids are NOT deduped (distinct shouts)"
    (let [now (System/currentTimeMillis)
          msg-a {:agent-id "ling-a"
                 :event-type :progress
                 :message "First shout"
                 :timestamp now
                 :project-id "test-project"
                 :shout-id (str (random-uuid))}
          msg-b {:agent-id "ling-a"
                 :event-type :progress
                 :message "Second shout"
                 :timestamp (inc now)
                 :project-id "test-project"
                 :shout-id (str (random-uuid))}]

      (pb/register-message-source! (constantly [msg-a msg-b]))

      (let [msgs (pb/get-messages "coordinator:test"
                                   :project-id "test-project")]
        (is (= 2 (count msgs))
            "Distinct shout-ids should NOT be deduped")))))

(deftest backbone-buffer-preserves-shout-id-test
  (testing "buffer-backbone-event! preserves :shout-id through normalization"
    (let [shout-id (str (random-uuid))]
      (pb/clear-backbone-buffer!)
      (pb/buffer-backbone-event! {:agent-id "ling-buf"
                                   :event-type "started"
                                   :message "Hello"
                                   :timestamp (System/currentTimeMillis)
                                   :project-id "test-project"
                                   :shout-id shout-id})
      (let [buffered @pb/backbone-buffer]
        (is (= 1 (count buffered)))
        (is (= shout-id (:shout-id (first buffered)))
            "shout-id should survive normalization")))))

(deftest triple-delivery-still-deduped-test
  (testing "Even 3 copies of same shout (atom + 2x backbone) dedup to 1"
    (let [shout-id (str (random-uuid))
          now (System/currentTimeMillis)
          msg {:agent-id "ling-triple"
               :event-type :progress
               :message "Triple threat"
               :timestamp now
               :project-id "test-project"
               :shout-id shout-id}]

      ;; Atom delivers it
      (pb/register-message-source! (constantly [msg]))
      ;; Backbone delivers it twice (e.g. NATS retry)
      (pb/buffer-backbone-event! (assoc msg :event-type "progress"))
      (pb/buffer-backbone-event! (assoc msg :event-type "progress"))

      (let [msgs (pb/get-messages "coordinator:test"
                                   :project-id "test-project")]
        (is (= 1 (count msgs))
            "3 copies with same shout-id should collapse to 1")))))
