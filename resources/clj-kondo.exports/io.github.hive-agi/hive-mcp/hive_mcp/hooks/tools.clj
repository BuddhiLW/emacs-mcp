(ns hive-mcp.hooks.tools
  "clj-kondo hooks for public hive-mcp tool macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn- coercion-expr [sym config-node]
  (api/list-node
   [(api/token-node 'or)
    sym
    (or (second (:children config-node))
        (api/token-node nil))]))

(defn with-coerced-params
  "Model the outer destructure and each coerced local as nested lets."
  [{:keys [node]}]
  (let [[_ spec & body] (:children node)
        [bindings params & coercions] (:children spec)
        coerced-bindings
        (mapcat (fn [[sym config]]
                  [sym (coercion-expr sym config)])
                (partition 2 coercions))
        inner (api/list-node
               (list* (api/token-node 'let)
                      (api/vector-node (vec coerced-bindings))
                      body))]
    {:node (api/list-node
            [(api/token-node 'let)
             (api/vector-node [bindings params])
             inner])}))
