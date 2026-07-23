(ns hive-mcp.tools.kg.queries-health-test
  (:require [clojure.test :refer [deftest is]]
            [hive-mcp.knowledge-graph.connection :as connection]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.tools.core :as tools]
            [hive-mcp.tools.kg.queries :as queries]))

(deftest kg-stats-includes-backend-health-test
  (let [health {:status :healthy
                :backend :datahike
                :compatible? true}]
    (with-redefs [edges/edge-stats
                  (constantly {:total-edges 3
                               :by-relation {:implements 2}
                               :by-scope {"hive" 3}})
                  connection/backend-health (constantly health)
                  tools/mcp-json identity]
      (is (= {:success true
              :total-edges 3
              :by-relation {:implements 2}
              :by-scope {"hive" 3}
              :backend-health health}
             (queries/handle-kg-stats {}))))))
