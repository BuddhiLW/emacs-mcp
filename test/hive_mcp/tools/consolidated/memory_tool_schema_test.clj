(ns hive-mcp.tools.consolidated.memory-tool-schema-test
  "Drift guard for the ADVERTISED `memory` tool schema (KG-P2-RELATES-DEAD).

   The `memory` supertool is the only agent-visible KG-edge surface
   (command=\"kg edge\"). Its `relation` enum is registry-backed, so it must be
   derived at ADVERTISEMENT time — a static literal freezes at ns-load and
   drifts from what the KG store actually accepts (this is exactly how the
   core :relates relation became a dead capability).

   These assertions run against the tool list the server actually publishes
   (routes/build-server-spec → make-tool, post schema-extension merge), NOT
   against a tool-def fn in isolation — a test on the fn alone would pass even
   if the registry never wired it up."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.knowledge-graph.schema :as kg-schema]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- advertised-memory-props
  "Properties of the `memory` tool as the server advertises it."
  []
  (->> (:tools (routes/build-server-spec))
       (filter #(= "memory" (:name %)))
       first
       :inputSchema
       :properties))

(defn- advertised-relations []
  (set (get-in (advertised-memory-props) ["relation" :enum])))

(deftest memory-tool-advertises-open-relates
  (testing "relates is advertised (the dead-capability regression)"
    (is (contains? (advertised-relations) "relates")))

  (testing "predicate param is advertised (hybrid open-relation model)"
    (is (contains? (advertised-memory-props) "predicate")))

  (testing "enum exactly mirrors the live accepted relation set — drift guard"
    (is (= (set (map name (kg-schema/relation-types)))
           (advertised-relations)))))

(deftest memory-tool-relation-enum-resolves-at-advertisement-time
  (testing "an addon relation registered AFTER ns-load is advertised"
    (let [probe :kg-p2-advert-probe
          exts  @(requiring-resolve 'hive-mcp.knowledge-graph.schema/relation-type-extensions)]
      (try
        (kg-schema/register-relation-type! probe)
        (is (contains? (advertised-relations) "kg-p2-advert-probe")
            "relation enum is frozen in a static def — it cannot mirror the registry")
        (finally
          (swap! exts disj probe))))))
