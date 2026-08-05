(ns hive-schemas.hooks.test
  "clj-kondo hooks for schema-driven macros that synthesize named test vars."
  (:require [clj-kondo.hooks-api :as api]))

(defn- token-like [node value]
  (with-meta (api/token-node value) (meta node)))

(defn- def-node [name-node]
  (api/list-node
   [(api/token-node 'def) name-node (api/token-node nil)]))

(defn- do-node [nodes]
  (api/list-node (list* (api/token-node 'do) nodes)))

(defn- opts-sexpr [node]
  (let [x (api/sexpr node)]
    (if (map? x) x {})))

(defn- suffixed-defs [name-node suffixes]
  (let [base (api/sexpr name-node)]
    (mapv (fn [suffix]
            (def-node
             (token-like name-node (symbol (str base suffix)))))
          suffixes)))

(defn- base-suffixes [opts]
  (cond-> ["-conformance"]
    (contains? opts :rel)          (conj "-relation")
    (true? (:idempotent? opts))    (conj "-idempotent")
    (contains? opts :contract)     (conj "-contract")
    (not= false (:mutation opts))  (conj "-mutants-present" "-mutations")
    (contains? opts :golden-path)  (conj "-golden")))

(defn deftrifecta-from-schema
  "Register every statically selected facet emitted from the options map."
  [{:keys [node]}]
  (let [[_ name-node subject-node opts-node] (:children node)
        opts (opts-sexpr opts-node)
        defs (suffixed-defs name-node (base-suffixes opts))]
    {:node (do-node
            (conj defs (do-node [subject-node opts-node])))}))

(defn deftrifecta-predicate
  "Register the generated positive and negative predicate facets."
  [{:keys [node]}]
  (let [[_ name-node subject-node opts-node] (:children node)]
    {:node (do-node
            (conj (suffixed-defs name-node ["-positive" "-negative"])
                  (do-node [subject-node opts-node])))}))

(defn deftriad-from-schema
  "Register base facets plus optional proof and model-check facets."
  [{:keys [node]}]
  (let [[_ name-node subject-node opts-node] (:children node)
        opts     (opts-sexpr opts-node)
        suffixes (cond-> (base-suffixes opts)
                   (contains? opts :prove)       (conj "-proof")
                   (contains? opts :model-check) (conj "-model-check"))]
    {:node (do-node
            (conj (suffixed-defs name-node suffixes)
                  (do-node [subject-node opts-node])))}))
