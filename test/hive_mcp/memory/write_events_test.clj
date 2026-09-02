(ns hive-mcp.memory.write-events-test
  "The write vocabulary round-trips, the bridge table covers every op, and
   notify! lands on the channel bus."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.events.bridge :as bridge]
            [hive-mcp.memory.write-events :as we]))

(deftest vocabulary-round-trips-test
  (doseq [op (keys we/op->channel-type)]
    (testing (str op)
      (is (= op (we/channel-type->op (we/op->channel-type op))))
      (is (= {:op op :id "x" :memory-type "decision"}
             (we/event->write (we/->event op {:id "x" :memory-type "decision" :content "dropped"}))))))
  (is (nil? (we/event->write {:type :something-else :id "x"}))
      "a non-write event is not a write"))

(deftest bridge-table-covers-every-op-test
  (doseq [[op t] we/op->channel-type]
    (testing (str op)
      (let [[ev-id payload] (bridge/hook->event t {:id "x"})]
        (is (= "memory" (namespace ev-id)))
        (is (= {:id "x"} payload))))))

(deftest notify-publishes-on-channel-test
  (let [ch (channel/subscribe! :memory-updated)]
    (try
      (we/notify! :updated {:id "e1" :memory-type "decision" :fields [:tags]})
      (let [[v _] (async/alts!! [ch (async/timeout 2000)])]
        (is (= {:type :memory-updated :id "e1" :memory-type "decision" :fields [:tags]} v)))
      (finally
        (channel/unsubscribe! :memory-updated ch)))))

(deftest listener-runs-synchronously-before-notify-returns-test
  (let [seen (atom [])]
    (try
      (we/register-listener! ::t (fn [w] (swap! seen conj w)))
      (we/register-listener! ::t (fn [w] (swap! seen conj (assoc w :second true))))
      (we/notify! :deleted {:id "gone" :memory-type "note"})
      (is (= [{:op :deleted :id "gone" :memory-type "note" :second true}] @seen)
          "same key replaces; the listener ran before notify! returned")
      (finally
        (we/unregister-listener! ::t)))
    (is (not (contains? (we/listener-keys) ::t)))))

(deftest throwing-listener-does-not-break-notify-test
  (try
    (we/register-listener! ::boom (fn [_] (throw (ex-info "boom" {}))))
    (is (nil? (we/notify! :added {:id "x" :memory-type "decision"})))
    (finally
      (we/unregister-listener! ::boom))))

(deftest notify-unknown-op-is-a-noop-test
  (is (nil? (we/notify! :exploded {:id "x"}))))
