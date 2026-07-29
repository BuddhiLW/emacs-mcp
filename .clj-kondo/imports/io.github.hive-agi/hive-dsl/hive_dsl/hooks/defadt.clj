(ns hive-dsl.hooks.defadt
  "clj-kondo analyze-call hook for hive-dsl.adt/defadt.

   defadt expands into 4 top-level vars:
     - <TypeName>             — type metadata def
     - <type-name>            — constructor fn (kebab-case)
     - <type-name>?           — predicate fn
     - -><type-name>          — keyword coercer

   Without this hook clj-kondo sees only the macro call and reports
   `unresolved-symbol` for any reference to the generated names."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(defn- camel->kebab
  "Mirror of hive-dsl.adt/camel->kebab for the analyzer."
  [s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case))

(defn defadt
  "Rewrite (defadt TypeName ?docstring & variants) into a `do` block of
   four `def` forms so clj-kondo registers the generated names. Variants
   themselves aren't validated here — the runtime macro still does that."
  [{:keys [node]}]
  (let [[_ type-sym & body] (:children node)
        type-name (api/sexpr type-sym)]
    (when-not (symbol? type-name)
      (throw (ex-info "defadt expects a symbol type name" {})))
    (let [kebab            (camel->kebab (name type-name))
          ctor-sym         (symbol kebab)
          pred-sym         (symbol (str kebab "?"))
          coerce-sym       (symbol (str "->" kebab))
          loc              (meta type-sym)
          mk-token         (fn [s] (with-meta (api/token-node s) loc))
          ;; Walk body to keep variant exprs visible to kondo so any embedded
          ;; symbol/predicate references still get analyzed.
          variant-uses     (api/list-node
                            (list* (mk-token 'do)
                                   (mapv (fn [c]
                                           (api/list-node
                                            [(mk-token 'do) c]))
                                         body)))
          rewritten
          (api/list-node
           [(mk-token 'do)
            (api/list-node
             [(mk-token 'def) (mk-token type-name) (mk-token nil)])
            (api/list-node
             [(mk-token 'defn) (mk-token ctor-sym)
              (api/list-node
               [(api/vector-node [(mk-token '_variant)]) (mk-token nil)])
              (api/list-node
               [(api/vector-node [(mk-token '_variant) (mk-token '_data)])
                (mk-token nil)])])
            (api/list-node
             [(mk-token 'defn) (mk-token pred-sym)
              (api/vector-node [(mk-token '_x)])
              (mk-token nil)])
            (api/list-node
             [(mk-token 'defn) (mk-token coerce-sym)
              (api/vector-node [(mk-token '_kw)])
              (mk-token nil)])
            variant-uses])]
      {:node rewritten})))
