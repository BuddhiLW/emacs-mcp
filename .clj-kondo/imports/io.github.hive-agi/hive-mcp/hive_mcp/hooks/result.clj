(ns hive-mcp.hooks.result
  "clj-kondo hooks for hive-mcp.dns.result macro re-exports."
  (:require [clj-kondo.hooks-api :as api]))

(defn- do-node [nodes]
  (api/list-node (list* (api/token-node 'do) nodes)))

(defn guard [{:keys [node]}]
  (let [[_catch-class & args] (rest (:children node))]
    {:node (do-node args)}))

(defn rescue [{:keys [node]}]
  {:node (do-node (rest (:children node)))})

(defn try-effect [{:keys [node]}]
  {:node (do-node (rest (:children node)))})

(defn try-effect* [{:keys [node]}]
  (let [[_category & body] (rest (:children node))]
    {:node (do-node body)}))

(defn rescue-log [{:keys [node]}]
  {:node (do-node (rest (:children node)))})

(defn rescue-interrupt [{:keys [node]}]
  {:node (do-node (rest (:children node)))})

(defn let-ok [{:keys [node]}]
  (let [[_ binding-vec & body] (:children node)
        flat (loop [bs (seq (:children binding-vec)) acc []]
               (if (empty? bs)
                 acc
                 (if (= :let (api/sexpr (first bs)))
                   (recur (drop 2 bs) (into acc (:children (second bs))))
                   (recur (drop 2 bs)
                          (conj acc (first bs) (second bs))))))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node flat)
                   body))}))

(defn- thread-unwrapped [thread-sym {:keys [node]}]
  (let [[_ expr & forms] (:children node)
        sym (api/token-node (gensym "ok"))]
    {:node (api/list-node
            [(api/token-node 'do)
             expr
             (api/list-node
              [(api/token-node 'fn)
               (api/vector-node [sym])
               (api/list-node
                (list* (api/token-node thread-sym) sym forms))])])}))

(defn ok-> [ctx]
  (thread-unwrapped '-> ctx))

(defn ok->> [ctx]
  (thread-unwrapped '->> ctx))
