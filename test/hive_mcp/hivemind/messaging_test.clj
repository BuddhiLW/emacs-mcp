(ns hive-mcp.hivemind.messaging-test
  "Cap on the :message / :task strings carried in shouts.

   Why: a single oversized shout fans out across (per-agent ring × backbone
   subscribers × N consumers). Observed: a kanban-list dump (~50 entries
   serialized as JSON) emitted as a drone error message bloated every
   downstream context window. Bound at canonical ingestion in shout!."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.hivemind.messaging :as msg]
            [hive-mcp.hivemind.state :as state]
            [hive-dsl.bounded-atom :refer [bget]]))

(defn- read-back-message
  "Pull the most recent :message string the shout! pipeline persisted to the
   per-agent ring."
  [agent-id]
  (let [entry (bget state/agent-registry agent-id)
        last-msg (last (:messages entry))]
    (:message last-msg)))

(defn- big-string [n]
  (apply str (repeat n "x")))

(deftest cap-oversized-message
  (testing "5KB :message stored ≤ ~700 chars with truncation marker"
    (let [agent-id (str "test-shout-cap-" (random-uuid))
          payload-size 5000
          _ (msg/shout! agent-id :progress {:message (big-string payload-size)})
          stored (read-back-message agent-id)]
      (is (some? stored))
      (is (<= (count stored) 700)
          (str "stored len=" (count stored) ", expected ≤ 700"))
      (is (re-find #"truncated, dropped \d+ chars" stored)))))

(deftest short-message-untouched
  (testing "Sub-cap :message passes through verbatim"
    (let [agent-id (str "test-shout-short-" (random-uuid))
          msg "small payload"
          _ (msg/shout! agent-id :progress {:message msg})
          stored (read-back-message agent-id)]
      (is (= msg stored)))))

(deftest cap-oversized-task
  (testing "Oversized :task is also capped"
    (let [agent-id (str "test-shout-task-cap-" (random-uuid))
          _ (msg/shout! agent-id :started {:task (big-string 4000)})
          entry (bget state/agent-registry agent-id)
          last-msg (last (:messages entry))
          stored-task (:task last-msg)]
      (is (<= (count stored-task) 700))
      (is (re-find #"truncated" stored-task)))))

(deftest non-string-message-bounded
  (testing "Accidental coll-shaped :message is pr-str'd then capped"
    (let [agent-id (str "test-shout-coll-" (random-uuid))
          payload (vec (repeat 500 {:title "x" :status "todo" :id "y"}))
          _ (msg/shout! agent-id :error {:message payload})
          stored (read-back-message agent-id)]
      (is (some? stored))
      (is (<= (count stored) 700)))))

(deftest nil-message-stays-absent
  (testing "Missing :message key does not synthesize a stored message"
    (let [agent-id (str "test-shout-nil-" (random-uuid))
          _ (msg/shout! agent-id :progress {:task "task only"})
          entry (bget state/agent-registry agent-id)
          last-msg (last (:messages entry))]
      (is (nil? (:message last-msg)))
      (is (= "task only" (:task last-msg))))))
