(ns hive-weave.hooks.async
  "clj-kondo analyze-call hooks for hive-weave.async's macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn- leading-then-body
  "A `let` binding each of `lead` to `_`, with `body` in tail position.
   Analyzes every leading argument without placing it in a `do` statement
   position."
  [lead body]
  (api/list-node
   (list* (api/token-node 'let)
          (api/vector-node (vec (mapcat (fn [a] [(api/token-node '_) a]) lead)))
          body)))

(defn safe-go
  "Hook for (safe-go opts & body)."
  [{:keys [node]}]
  (let [[opts & body] (rest (:children node))]
    {:node (leading-then-body [opts] body)}))

(defn safe-go-loop
  "Hook for (safe-go-loop opts bindings & body). Rewrites to a plain `loop` so
   the bindings and `recur` resolve."
  [{:keys [node]}]
  (let [[opts bindings & body] (rest (:children node))]
    {:node (leading-then-body
            [opts]
            [(api/list-node
              (list* (api/token-node 'loop) bindings body))])}))
