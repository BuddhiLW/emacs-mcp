(ns hive-mcp.hivemind.conversation-round-trip-test
  "Integration: full handler-chain delivery for tell + ask/respond.

   Exercises events.handlers.conversation + events.effects.conversation +
   hivemind.conversation + channel.conversation-inbox end to end via
   ev/dispatch — no NATS required (publish fns no-op when bridge ns absent)."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.channel.conversation-inbox :as inbox]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.effects.conversation :as conv-fx]
            [hive-mcp.events.handlers.conversation :as conv-handlers]
            [hive-mcp.hivemind.conversation :as conv]
            [hive-test.isolation :as iso]
            [hive-mcp.isolation-methods]))

(defn- conversation-fixture
  [f]
  (inbox/reset-all!)
  (reset! conv/pending-asks {})
  (reset! @#'hive-mcp.events.handlers.conversation/*registered false)
  (reset! @#'hive-mcp.events.effects.conversation/*registered false)
  (conv-fx/register-conversation-effects!)
  (conv-handlers/register-handlers!)
  (f))

(use-fixtures :each
  (iso/with-isolations :events)
  conversation-fixture)

(deftest tell-pushes-envelope-to-receiver-inbox
  (testing ":conversation/tell delivers envelope into receiver's :tell section"
    (ev/dispatch [:conversation/tell
                  {:from "ling-a" :to "ling-b" :message "hi"}])
    (let [drained (inbox/drain! "ling-b")
          tell    (first (:tell drained))]
      (is (= 1 (count (:tell drained))))
      (is (= "ling-a" (:from tell)))
      (is (= "ling-b" (:to tell)))
      (is (= "hi"     (:message tell)))
      (is (= :conversation/tell (:event-type tell))))))

(deftest ask-registers-pending-and-pushes-inbox
  (testing ":conversation/ask registers promise-chan AND pushes envelope to receiver"
    (ev/dispatch [:conversation/ask
                  {:from "ling-a" :to "ling-b"
                   :ask-id "ask-fixed-1" :question "ready?"}])
    (is (some? (conv/pending-ask "ask-fixed-1"))
        "pending-ask registry holds the new ask-id")
    (let [drained (inbox/drain! "ling-b")
          ask     (first (:ask drained))]
      (is (= "ask-fixed-1" (:ask-id ask)))
      (is (= "ready?"      (:question ask)))
      (is (= :conversation/ask (:event-type ask))))))

(deftest ask-respond-round-trip-delivers-answer
  (testing "respond correlated by ask-id resolves the await-response! promise-chan"
    (ev/dispatch [:conversation/ask
                  {:from "ling-a" :to "ling-b"
                   :ask-id "ask-rt-1" :question "yes/no?"}])
    (let [{:keys [response-chan]} (conv/pending-ask "ask-rt-1")]
      (is (some? response-chan))
      (ev/dispatch [:conversation/respond
                    {:from "ling-b" :to "ling-a"
                     :ask-id "ask-rt-1" :answer "yes"}])
      (let [[v port] (async/alts!! [response-chan (async/timeout 500)])]
        (is (= response-chan port) "response-chan delivered before timeout")
        (is (= "yes" v)))
      (is (nil? (conv/pending-ask "ask-rt-1"))
          "deliver-response! cleans the pending entry"))))

(deftest respond-without-pending-ask-does-not-throw
  (testing "respond with unknown ask-id is logged + dropped; sender still gets inbox copy"
    (is (some? (ev/dispatch [:conversation/respond
                             {:from "ling-b" :to "ling-a"
                              :ask-id "ask-unknown" :answer "late"}])))
    (let [drained (inbox/drain! "ling-a")
          respond (first (:respond drained))]
      (is (= "ask-unknown" (:ask-id respond)))
      (is (= "late"        (:answer respond))))))
