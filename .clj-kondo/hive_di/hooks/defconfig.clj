(ns hive-di.hooks.defconfig
  "clj-kondo analyze-call hook for hive-di.core/defconfig.

   defconfig expands into:
     - (defadt <Name> …)        — handled by hive-dsl.hooks.defadt
     - <Name>-fields            — field registry def
     - <Name>-schema            — Malli schema def
     - resolve-<Name>           — resolver fn (0/1/2 arity)

   Without this hook clj-kondo flags every reference to the generated
   `<Name>-fields` / `resolve-<Name>` symbols as unresolved."
  (:require [clj-kondo.hooks-api :as api]))

(defn defconfig
  [{:keys [node]}]
  (let [[_ type-sym & body] (:children node)
        type-name (api/sexpr type-sym)]
    (when-not (symbol? type-name)
      (throw (ex-info "defconfig expects a symbol type name" {})))
    (let [name-str    (name type-name)
          fields-sym  (symbol (str name-str "-fields"))
          schema-sym  (symbol (str name-str "-schema"))
          resolve-sym (symbol (str "resolve-" name-str))
          loc         (meta type-sym)
          mk-token    (fn [s] (with-meta (api/token-node s) loc))
          ;; Keep field-spec exprs visible so referenced symbols (env, literal)
          ;; still get analyzed.
          field-uses  (api/list-node
                       (list* (mk-token 'do)
                              (map (fn [c]
                                     (api/list-node
                                      [(mk-token 'do) c]))
                                   body)))
          rewritten
          (api/list-node
           [(mk-token 'do)
            (api/list-node
             [(mk-token 'def) (mk-token type-name) (mk-token nil)])
            (api/list-node
             [(mk-token 'def) (mk-token fields-sym) (mk-token nil)])
            (api/list-node
             [(mk-token 'def) (mk-token schema-sym) (mk-token nil)])
            (api/list-node
             [(mk-token 'defn) (mk-token resolve-sym)
              (api/vector-node [(mk-token '&) (mk-token '_args)])
              (mk-token nil)])
            field-uses])]
      {:node rewritten})))
