(ns hooks.hive-mcp.test-stub
  "clj-kondo analyze-call hooks for the test stub macros.

   `with-captured-logs` binds a bare symbol (not a vector) and
   `with-decay-store` binds the symbol carried by its `:bind` option, so
   neither can be modelled with :lint-as. `def-stub-store` defines a record
   from a name argument. Without these hooks every reference to the bound
   symbol or the generated record symbols is an unresolved symbol."
  (:require [clj-kondo.hooks-api :as api]))

(defn- let-node
  "Build (let [binding-sym (atom [])] body...), preserving `loc` on the
   binding. The bound value must not be nil: clj-kondo narrows the
   binding's type from it, and a nil binding makes every `@sink` in the
   body a `deref, received: nil` error. An atom matches what
   with-captured-logs really binds and stays deref-safe for the rest."
  [binding-sym loc body]
  (let [token (fn [s] (with-meta (api/token-node s) loc))]
    (api/list-node
     (list* (token 'let)
            (api/vector-node
             [(with-meta (api/token-node binding-sym) loc)
              (api/list-node [(token 'atom) (api/vector-node [])])])
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

(defn def-stub-store
  "(def-stub-store Nm) => (defrecord Nm [delegate])

   The real macro also implements IKGStore against the record's `delegate`
   field; the expansion only has to define Nm / ->Nm / map->Nm so callers
   of the generated constructors resolve."
  [{:keys [node]}]
  (let [[_ nm] (:children node)
        loc    (meta nm)]
    (when-not (simple-symbol? (api/sexpr nm))
      (throw (ex-info "def-stub-store expects a record name symbol" {})))
    {:node (api/list-node
            [(with-meta (api/token-node 'defrecord) loc)
             nm
             (api/vector-node [(with-meta (api/token-node 'delegate) loc)])])}))
