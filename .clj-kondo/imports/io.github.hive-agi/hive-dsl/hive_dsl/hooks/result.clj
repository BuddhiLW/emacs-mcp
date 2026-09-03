(ns hive-dsl.hooks.result
  "clj-kondo hooks for hive-dsl.result macros.

   Transforms macro calls into equivalent core forms so kondo
   can analyze bindings, body expressions, and detect errors."
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

(defn guard
  "Hook for (guard catch-class fallback & body).
   Skips catch-class, analyzes fallback + body."
  [{:keys [node]}]
  (let [[_catch-class fallback & body] (rest (:children node))]
    {:node (leading-then-body [fallback] body)}))

(defn rescue
  "Hook for (rescue fallback & body).
   Analyzes all args (fallback is an expression too)."
  [{:keys [node]}]
  (let [[fallback & body] (rest (:children node))]
    {:node (leading-then-body [fallback] body)}))

(defn try-effect
  "Hook for (try-effect & body).
   Analyzes body expressions."
  [{:keys [node]}]
  (let [body (rest (:children node))]
    {:node (api/list-node
            (list* (api/token-node 'do) body))}))

(defn try-effect*
  "Hook for (try-effect* category & body).
   Analyzes category as an expression + body."
  [{:keys [node]}]
  (let [[category & body] (rest (:children node))]
    {:node (leading-then-body [category] body)}))

(defn rescue-log
  "Hook for (rescue-log label fallback & body).
   Analyzes label + fallback + body as expressions."
  [{:keys [node]}]
  (let [[label fallback & body] (rest (:children node))]
    {:node (leading-then-body [label fallback] body)}))

(defn rescue-interrupt
  "Hook for (rescue-interrupt label fallback & body).
   Same shape as rescue-log."
  [{:keys [node]}]
  (let [[label fallback & body] (rest (:children node))]
    {:node (leading-then-body [label fallback] body)}))

(defn let-ok
  "Hook for (let-ok [sym expr ... :let [normal-bindings] ...] & body).
   Railway-oriented let: each `sym expr` pair binds the unwrapped ok value,
   and a `:let [..]` entry splices ordinary let bindings. `:lint-as let`
   can't model the interleaved `:let`, so its bound symbols read as
   unresolved. Rewrite to a plain `let` with every binding flattened so
   kondo resolves them and still analyzes the body + binding exprs.

   Each ok-binding expr is rewritten to `(:ok expr)`, which is what the macro
   binds at runtime. Binding the Result expression directly gives the symbol
   the Result's type, and every downstream use of the payload then reports a
   mismatch against it."
  [{:keys [node]}]
  (let [unwrap (fn [expr]
                 (api/list-node [(api/keyword-node :ok) expr]))
        [_ binding-vec & body] (:children node)
        flat (loop [bs (seq (:children binding-vec)) acc []]
               (if (empty? bs)
                 acc
                 (if (= :let (api/sexpr (first bs)))
                   ;; :let [a 1 b 2] — splice the inner vector's bindings
                   (recur (drop 2 bs) (into acc (:children (second bs))))
                   ;; sym expr pair (sym may be a destructure form)
                   (recur (drop 2 bs)
                          (conj acc (first bs) (unwrap (second bs)))))))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node flat)
                   body))}))
