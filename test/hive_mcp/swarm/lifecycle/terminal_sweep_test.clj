(ns hive-mcp.swarm.lifecycle.terminal-sweep-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.ling.spawn :as ling]
            [hive-mcp.agent.ling.terminal-registry :as terminal-reg]
            [hive-mcp.agent.protocol :as agent]
            [hive-mcp.swarm.datascript.lings :as ds-lings]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.swarm.lifecycle.terminal-sweep :as terminal-sweep]))

(defn- status-agent
  [status-map]
  (reify agent/IAgent
    (spawn! [_ _opts] nil)
    (dispatch! [_ _task-opts] nil)
    (kill! [_] nil)
    (status [_] status-map)
    (agent-type [_] :ling)
    (can-chain-tools? [_] true)
    (claims [_] [])
    (claim-files! [_ _files _task-id] nil)
    (release-claims! [_] nil)
    (upgrade! [_] nil)))

(deftest sweep-once-zombifies-dead-terminal-lings
  (let [txs (atom [])
        slave {:slave/id "vterm-1"
               :slave/depth 1
               :slave/status :working
               :slave/alive? true
               :ling/spawn-mode :vterm}]
    (with-redefs [terminal-reg/registered-terminals (fn [] #{:vterm})
                  queries/get-all-slaves (fn [& _opts] [slave])
                  ling/->ling (fn [_id _opts]
                                (status-agent {:slave/id "vterm-1"
                                               :slave/status :dead
                                               :elisp-alive? false}))
                  ds-lings/update-slave! (fn [slave-id updates]
                                           (swap! txs conj [slave-id updates]))]
      (let [result (terminal-sweep/sweep-once! 1234)]
        (is (= {:checked 1 :zombified 1 :alive 0 :errors []} result))
        (is (= [["vterm-1" {:slave/alive? false
                            :slave/status :zombie
                            :slave/status-changed-at 1234}]]
               @txs))))))

(deftest sweep-once-ignores-unregistered-terminal-modes
  (testing "Rows for unloaded addons are not probed or zombified"
    (let [txs (atom [])
          slave {:slave/id "vterm-1"
                 :slave/depth 1
                 :slave/status :working
                 :ling/spawn-mode :vterm}]
      (with-redefs [terminal-reg/registered-terminals (fn [] #{})
                    queries/get-all-slaves (fn [& _opts] [slave])
                    ds-lings/update-slave! (fn [slave-id updates]
                                             (swap! txs conj [slave-id updates]))]
        (is (= {:checked 0 :zombified 0 :alive 0 :errors []}
               (terminal-sweep/sweep-once! 1234)))
        (is (empty? @txs))))))
