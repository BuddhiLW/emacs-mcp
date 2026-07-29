(ns hive-dsl.hooks.result
  "clj-kondo hooks for hive-dsl.result macros.

   Transforms macro calls into equivalent core forms so kondo
   can analyze bindings, body expressions, and detect errors."
  (:require [clj-kondo.hooks-api :as api]))

(defn guard
  "Hook for (guard catch-class fallback & body).
   Skips catch-class, analyzes fallback + body."
  [{:keys [node]}]
  (let [[_catch-class & rest-args] (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) rest-args))}))

(defn rescue
  "Hook for (rescue fallback & body).
   Analyzes all args (fallback is an expression too)."
  [{:keys [node]}]
  (let [args (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) args))}))

(defn try-effect
  "Hook for (try-effect & body).
   Analyzes body expressions."
  [{:keys [node]}]
  (let [body (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) body))}))

(defn try-effect*
  "Hook for (try-effect* :category & body).
   Skips category keyword, analyzes body."
  [{:keys [node]}]
  (let [[_category & body] (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) body))}))

(defn rescue-log
  "Hook for (rescue-log label fallback & body).
   Analyzes label + fallback + body as expressions."
  [{:keys [node]}]
  (let [args (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) args))}))

(defn rescue-interrupt
  "Hook for (rescue-interrupt label fallback & body).
   Same shape as rescue-log."
  [{:keys [node]}]
  (let [args (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) args))}))

(defn let-ok
  "Hook for (let-ok [sym expr ... :let [normal-bindings] ...] & body).
   Railway-oriented let: each `sym expr` pair binds the unwrapped ok value,
   and a `:let [..]` entry splices ordinary let bindings. `:lint-as let`
   can't model the interleaved `:let`, so its bound symbols read as
   unresolved. Rewrite to a plain `let` with every binding flattened so
   kondo resolves them and still analyzes the body + binding exprs."
  [{:keys [node]}]
  (let [[_ binding-vec & body] (:children node)
        flat (loop [bs (seq (:children binding-vec)) acc []]
               (if (empty? bs)
                 acc
                 (if (= :let (api/sexpr (first bs)))
                   ;; :let [a 1 b 2] — splice the inner vector's bindings
                   (recur (drop 2 bs) (into acc (:children (second bs))))
                   ;; sym expr pair (sym may be a destructure form)
                   (recur (drop 2 bs) (conj acc (first bs) (second bs))))))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node flat)
                   body))}))
