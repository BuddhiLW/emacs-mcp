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

(defn- base-suffixes
  "Facet suffixes `opts` selects, in emission order. Mirrors
   hive-schemas.plan/opts->facets: every selector reads TRUTHINESS, not presence."
  [opts]
  (cond-> ["-conformance"]
    (:rel opts)                   (conj "-relation")
    (true? (:idempotent? opts))   (conj "-idempotent")
    (:contract opts)              (conj "-contract")
    (not= false (:mutation opts)) (conj "-mutants-present" "-mutations")
    (:golden-path opts)           (conj "-golden")
    (:strict-in opts)             (conj "-input-strength")))

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

(defn- multi-suffixes
  "Facet suffixes `opts` selects for a dispatch seam, in emission order. Mirrors
   deftrifecta-from-multi: :total? defaults to TRUE, so only an explicit false
   drops the no-default facet."
  [opts]
  (cond-> ["-is-a-dispatch-seam"
           "-vocabulary-is-closed"
           "-covers-the-vocabulary"]
    (not= false (:total? opts)) (conj "-has-no-default-method")
    :always                     (conj "-dispatch-stays-in-vocabulary"
                                      "-args-reach-the-vocabulary")
    (:out opts)                 (conj "-conformance")))

(defn deftrifecta-from-multi
  "Register the generated dispatch-seam facets."
  [{:keys [node]}]
  (let [[_ name-node subject-node opts-node] (:children node)]
    {:node (do-node
            (conj (suffixed-defs name-node (multi-suffixes (opts-sexpr opts-node)))
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

(defn deftriad-from-plan
  "Mark the plan form as used. The emitted var names come from the plan value,
   which is only known at expansion, so none are registered."
  [{:keys [node]}]
  (let [[_ plan-node] (:children node)]
    {:node (do-node [plan-node])}))

(defn deftrifecta-wire
  "Register the boundary facets the options map statically selects."
  [{:keys [node]}]
  (let [[_ name-node schema-node opts-node] (:children node)
        opts     (opts-sexpr opts-node)
        suffixes (cond-> ["-coercion-identity" "-explain-total"]
                   (:roundtrip opts)             (conj "-wire-roundtrip")
                   (not= false (:json-schema opts)) (conj "-json-schema"))]
    {:node (do-node
            (conj (suffixed-defs name-node suffixes)
                  (do-node [schema-node opts-node])))}))

(defn deftest-port-contract
  "Register the non-vacuity facet plus one facet per method the literal spec
   declares."
  [{:keys [node]}]
  (let [[_ name-node spec-node instance-node] (:children node)
        spec     (opts-sexpr spec-node)
        methods  (keys (:port/methods spec))
        suffixes (into ["-methods-present"] (map #(str "-" (name %))) methods)]
    {:node (do-node
            (conj (suffixed-defs name-node suffixes)
                  (do-node (remove nil? [spec-node instance-node]))))}))
