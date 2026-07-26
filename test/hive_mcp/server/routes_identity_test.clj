(ns hive-mcp.server.routes-identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.protocols.vessel :as vessel]
            [hive-mcp.server.routes.identity :as identity]
            [hive-mcp.server.routes.middleware :as middleware]
            [hive-mcp.tools.memory.scope :as scope]))

(deftest directory-derived-project-wins-over-vessel-project
  (testing "caller working directory outranks vessel project-id"
    (with-redefs [vessel/resolve-agent-context
                  (constantly {:cwd "/work/vessel" :project-id "stale-project"})
                  scope/get-current-project-id identity]
      (is (= "/work/caller"
             (identity/extract-project-id
              {:directory "/work/caller" :_caller_id "agent-1"}))))))

(deftest vessel-cwd-derives-project-before-vessel-project-id
  (testing "vessel cwd supplies HCR project when caller cwd is absent"
    (with-redefs [vessel/resolve-agent-context
                  (constantly {:cwd "/work/vessel" :project-id "stale-project"})
                  scope/get-current-project-id identity]
      (is (= "/work/vessel"
             (identity/extract-project-id {:_caller_id "agent-1"})))
      (is (= "/work/vessel"
             (identity/extract-directory {:_caller_id "agent-1"}))))))

(deftest handler-context-binds-resolved-directory
  (testing "request context binds vessel cwd instead of server cwd"
    (with-redefs [identity/extract-project-id (constantly "vessel-project")
                  identity/extract-directory (constantly "/work/vessel")
                  crystal/record-session-start! (constantly nil)]
      (let [handler (middleware/wrap-handler-context
                     (fn [_]
                       {:project-id (ctx/current-project-id)
                        :directory (ctx/current-directory)}))]
        (is (= {:project-id "vessel-project"
                :directory "/work/vessel"}
               (handler {:_caller_id "agent-1"})))))))
