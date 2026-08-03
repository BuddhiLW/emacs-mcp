(ns hive-dsl.hooks.macros
  "clj-kondo analyze-call hooks for hive-dsl binding/body macros whose call
   shape has no `:lint-as` equivalent."
  (:require [clj-kondo.hooks-api :as api]))

(defn- do-node
  "Wrap nodes in a `do` so kondo analyzes each as an expression."
  [nodes]
  (api/list-node (list* (api/token-node 'do) nodes)))

(defn- thread-unwrapped
  "Rewrite (ok-> expr & forms) into (do expr (fn [g] (-> g & forms))) so the
   threaded value keeps an unknown type — `expr` is a Result, the forms see
   its unwrapped ok value — while arity checks on the forms still apply."
  [thread-sym {:keys [node]}]
  (let [[_ expr & forms] (:children node)
        sym (api/token-node (gensym "ok"))]
    {:node (api/list-node
            [(api/token-node 'do)
             expr
             (api/list-node
              [(api/token-node 'fn)
               (api/vector-node [sym])
               (api/list-node (list* (api/token-node thread-sym) sym forms))])])}))

(defn ok->
  "Hook for (ok-> expr & forms)."
  [ctx]
  (thread-unwrapped '-> ctx))

(defn ok->>
  "Hook for (ok->> expr & forms)."
  [ctx]
  (thread-unwrapped '->> ctx))

(defn expr-then-body
  "Hook for (with-gate g & body) / (with-gate-result g & body)."
  [{:keys [node]}]
  {:node (do-node (rest (:children node)))})

(defn adt-case
  "Hook for (adt-case type-ref expr & clauses). Preserve type-ref as a real var
   reference, then model variant/body pairs as case clauses."
  [{:keys [node]}]
  (let [[_ type-ref expr & clauses] (:children node)]
    {:node (api/list-node
            [(api/token-node 'let)
             (api/vector-node [(api/token-node '_) type-ref])
             (api/list-node
              (list* (api/token-node 'case) expr clauses))])}))

(defn with-scope
  "Hook for (with-scope [scope-sym] & body) — binds scope-sym over body."
  [{:keys [node]}]
  (let [[_ binding-vec & body] (:children node)
        sym (first (:children binding-vec))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [sym (api/token-node nil)])
                   body))}))

(defn with-go-loop
  "Hook for (with-go-loop [sym handler-fn opts] & body) — binds sym over body,
   analyzes handler-fn and opts as expressions."
  [{:keys [node]}]
  (let [[_ binding-vec & body] (:children node)
        [sym & exprs] (:children binding-vec)]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [sym (do-node exprs)])
                   body))}))
