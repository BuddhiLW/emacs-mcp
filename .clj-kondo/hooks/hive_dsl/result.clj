(ns hooks.hive-dsl.result
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
