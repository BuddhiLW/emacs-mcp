(ns hooks.hive-mcp.test-stub
  "clj-kondo analyze-call hooks for the test stub binding macros.

   `with-captured-logs` binds a bare symbol (not a vector) and
   `with-decay-store` binds the symbol carried by its `:bind` option, so
   neither can be modelled with :lint-as. Without these hooks every
   reference to the bound symbol is reported as an unresolved symbol."
  (:require [clj-kondo.hooks-api :as api]))

(defn- let-node
  "Build (let [binding-sym nil] body...), preserving `loc` on the binding."
  [binding-sym loc body]
  (let [token (fn [s] (with-meta (api/token-node s) loc))]
    (api/list-node
     (list* (token 'let)
            (api/vector-node [(with-meta (api/token-node binding-sym) loc)
                              (token nil)])
            body))))

(defn with-captured-logs
  "(with-captured-logs sink-sym body...) => (let [sink-sym nil] body...)"
  [{:keys [node]}]
  (let [[_ sink-sym & body] (:children node)
        sym (api/sexpr sink-sym)]
    (when-not (simple-symbol? sym)
      (throw (ex-info "with-captured-logs expects a symbol sink" {})))
    {:node (let-node sym (meta sink-sym) body)}))

(defn- bind-node
  "The value node of the `:bind` key in an option map node, or nil."
  [opts]
  (->> (partition 2 (:children opts))
       (some (fn [[k v]] (when (= :bind (api/sexpr k)) v)))))

(defn with-decay-store
  "(with-decay-store {:bind store ...} body...)
   => (let [store nil] {:bind store ...} body...)

   The option map is kept in the expansion so symbols referenced by the
   other options stay analyzed. When `:bind` is absent the macro gensyms
   its own binding, so emit the body with no binding at all."
  [{:keys [node]}]
  (let [[_ opts & body] (:children node)
        bind (when (api/map-node? opts) (bind-node opts))
        sym  (some-> bind api/sexpr)]
    {:node (if (simple-symbol? sym)
             (let-node sym (meta bind) (cons opts body))
             (api/list-node (list* (api/token-node 'do) opts body)))}))
