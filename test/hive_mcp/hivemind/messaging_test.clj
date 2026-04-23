(ns hive-mcp.hivemind.messaging-test
  "Integration: cap on :message / :task strings carried in shouts.

   Why: a single oversized shout fans out across (per-agent ring × backbone
   subscribers × N consumers). Observed: a kanban-list dump (~50 entries
   serialized as JSON) emitted as a drone error message bloated every
   downstream context window. Bound at canonical ingestion in shout!.

   Unit-level contract for `cap-message` is covered in cap_test.clj;
   property-level invariants in cap_property_test.clj. This file spies on
   the per-agent ring bucket to verify the bound is enforced at the shout!
   boundary, not just in the pure helper."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.hivemind.messaging :as msg]
            [hive-mcp.hivemind.state :as state]
            [hive-dsl.bounded-atom :refer [bget]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private ellipsis "…")

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
  (testing "Oversized :message stored ≤ configured cap with ellipsis suffix"
    (let [agent-id (str "test-shout-cap-" (random-uuid))
          cap msg/default-shout-message-cap
          payload-size (* 3 cap)
          _ (msg/shout! agent-id :progress {:message (big-string payload-size)})
          stored (read-back-message agent-id)]
      (is (some? stored))
      (is (<= (count stored) cap)
          (str "stored len=" (count stored) ", expected ≤ " cap))
      (is (.endsWith ^String stored ellipsis)
          "truncated shout payload must end with ellipsis marker"))))

(deftest short-message-untouched
  (testing "Sub-cap :message passes through verbatim"
    (let [agent-id (str "test-shout-short-" (random-uuid))
          payload "small payload"
          _ (msg/shout! agent-id :progress {:message payload})
          stored (read-back-message agent-id)]
      (is (= payload stored)))))

(deftest cap-oversized-task
  (testing "Oversized :task is also capped"
    (let [agent-id (str "test-shout-task-cap-" (random-uuid))
          cap msg/default-shout-message-cap
          _ (msg/shout! agent-id :started {:task (big-string (* 2 cap))})
          entry (bget state/agent-registry agent-id)
          last-msg (last (:messages entry))
          stored-task (:task last-msg)]
      (is (<= (count stored-task) cap))
      (is (.endsWith ^String stored-task ellipsis)))))

(deftest non-string-message-bounded
  (testing "Accidental coll-shaped :message is pr-str'd then capped"
    (let [agent-id (str "test-shout-coll-" (random-uuid))
          cap msg/default-shout-message-cap
          payload (vec (repeat 500 {:title "x" :status "todo" :id "y"}))
          _ (msg/shout! agent-id :error {:message payload})
          stored (read-back-message agent-id)]
      (is (some? stored))
      (is (<= (count stored) cap)))))

(deftest nil-message-stays-absent
  (testing "Missing :message key does not synthesize a stored message"
    (let [agent-id (str "test-shout-nil-" (random-uuid))
          _ (msg/shout! agent-id :progress {:task "task only"})
          entry (bget state/agent-registry agent-id)
          last-msg (last (:messages entry))]
      (is (nil? (:message last-msg)))
      (is (= "task only" (:task last-msg))))))
