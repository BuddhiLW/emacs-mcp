(ns hive-mcp.test.stub.batch-extensions
  "Batch extension seams (:bx/*) for driver-free tests.

   hive-mcp.batch delegates $ref parsing/resolution, ref-dependency
   validation, cycle detection and wave assignment to `delegate-or-noop`
   extension keys. hive-mcp core ships no implementation — on a cold run
   every $ref stays unresolved, every op lands in wave 1 and cycles pass
   validation. The registry is the seam; this ns installs implementations in
   it rather than letting a test assert the absence of the collaborator.

   Provenance, per key — stated exactly, because a stub that drifts from the
   provider it stands in for turns green into a lie:

     :bx/h :bx/i  delegate to `hive.events.multi`, a committed hive-mcp
                  dependency: an independent implementation, not a fake.
     :bx/a-:bx/g  are a PORT of `hive-knowledge.agent.multi-batch` (the
                  production provider, registered by hive-knowledge's
                  init.clj). hive-knowledge is NOT in hive-mcp's committed
                  deps.edn, so cold CI has no provider at all and a port is
                  the only way to exercise the seam.

   The port is CHECKED, not claimed: `hive-mcp.batch.ref-contract` states the
   seam contract once (malli value objects + the shared case corpus) and
   `hive-mcp.batch.ref-conformance-test` runs that corpus against every
   provider on the classpath — this port always, the real hive-knowledge
   provider whenever local deps put it there.

   API:
     (install!)            register :bx/a-:bx/i, returns the key vector
     with-batch-extensions clojure.test :each fixture (snapshot + restore)"
  (:require [clojure.string :as str]
            [hive-mcp.extensions.registry :as ext]
            [hive.events.multi :as hem]
            [clojure.data.json :as json]
            [hive-mcp.batch :as batch]
            [hive-mcp.dsl.param-domain :as pd]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private ext-keys
  [:bx/a :bx/b :bx/c :bx/d :bx/e :bx/f :bx/g :bx/h :bx/i])

(def ^:private ref-prefix "$ref:")

(defn parse-ref
  "\"$ref:<op-id>.<seg>.<seg>\" => {:op-id \"<op-id>\" :path [\"<seg>\" \"<seg>\"]};
   \"$ref:<op-id>\" => {:op-id \"<op-id>\" :path []}; nil when S is not a $ref.

   Path segments stay STRINGS: keywordisation is :bx/c's job, on lookup."
  [s]
  (when (batch/ref? s)
    (let [ref-body (subs s (count ref-prefix))
          dot-idx  (str/index-of ref-body ".")]
      (if dot-idx
        {:op-id (subs ref-body 0 dot-idx)
         :path  (str/split (subs ref-body (inc dot-idx)) #"\.")}
        {:op-id ref-body
         :path  []}))))

(defn extract-result-data
  "The payload of a handler result: the JSON body of an MCP text response
   parsed with keyword keys, or the result itself when it carries no such
   body or the body is not JSON. nil in, nil out."
  [handler-result]
  (letfn [(parse [text fallback]
            (if (string? text)
              (try (json/read-str text :key-fn keyword)
                   (catch Exception _ fallback))
              fallback))]
    (cond
      (nil? handler-result)
      nil

      (and (map? handler-result)
           (sequential? (:content handler-result)))
      (if-let [item (first (filter #(= "text" (:type %)) (:content handler-result)))]
        (parse (:text item) handler-result)
        handler-result)

      (and (map? handler-result)
           (= "text" (:type handler-result)))
      (parse (:text handler-result) handler-result)

      :else
      handler-result)))

(defn resolve-ref
  "The value PARSED-REF designates in RESULTS-BY-ID: an empty path designates
   the whole op-result, otherwise the string path segments are keywordised
   and walked. batch/ref-not-found when the op-id is absent from
   RESULTS-BY-ID; a present op whose path holds nil resolves to nil."
  [{:keys [op-id path]} results-by-id]
  (if (contains? results-by-id op-id)
    (let [op-result (get results-by-id op-id)]
      (if (empty? path)
        op-result
        (get-in op-result (mapv keyword path))))
    batch/ref-not-found))

(defn resolve-refs-in-value
  "V with every $ref string it contains replaced by its resolved value. An
   unresolvable ref is left as the original string so the caller's broken-ref
   classification still sees it. Maps keep their keys; sequentials become
   vectors."
  [v results-by-id]
  (cond
    (batch/ref? v)
    (if-let [parsed (parse-ref v)]
      (let [resolved (resolve-ref parsed results-by-id)]
        (if (identical? batch/ref-not-found resolved) v resolved))
      v)

    (string? v) v

    (map? v)
    (reduce-kv (fn [m k x] (assoc m k (resolve-refs-in-value x results-by-id)))
               {}
               v)

    (sequential? v)
    (mapv #(resolve-refs-in-value % results-by-id) v)

    :else v))

(defn resolve-op-refs
  "OP with its ref-walkable params resolved against RESULTS-BY-ID. Prose
   params are left untouched — a $ref inside prose is quotation."
  [op results-by-id]
  (reduce (fn [acc [k v]]
            (assoc acc k (resolve-refs-in-value v results-by-id)))
          op
          (pd/ref-walkable-entries op)))

(defn collect-ref-op-ids
  "The set of op-ids OP's ref-walkable params reference."
  [op]
  (letfn [(walk [v]
            (cond
              (batch/ref? v)  (if-let [{:keys [op-id]} (parse-ref v)] #{op-id} #{})
              (map? v)        (reduce into #{} (map walk (vals v)))
              (sequential? v) (reduce into #{} (map walk v))
              :else           #{}))]
    (reduce into #{} (map (fn [[_ v]] (walk v)) (pd/ref-walkable-entries op)))))

(defn validate-ref-deps
  "Error strings for every $ref whose target op-id the referencing op does
   not declare in :depends_on. A $ref is an ordering edge, not just a name:
   an undeclared target may not have run when the ref is resolved."
  [ops]
  (into []
        (mapcat (fn [{:keys [id depends_on] :as op}]
                  (let [ref-ids (collect-ref-op-ids op)
                        dep-set (set (or depends_on []))]
                    (keep (fn [ref-id]
                            (when-not (contains? dep-set ref-id)
                              (str "Operation '" id "' has $ref to '" ref-id
                                   "' but doesn't declare it in depends_on")))
                          ref-ids))))
        ops))

(defn detect-cycles
  "Cycle errors for OPS, empty when acyclic. Non-cycle findings of
   hem/validate-ops are dropped — hive-mcp.batch has already made them."
  [ops]
  (let [{:keys [valid errors]} (hem/validate-ops ops)]
    (if valid
      []
      (into [] (filter #(str/includes? (str %) "Circular dependency")) errors))))

(defn install!
  "Register every batch extension. Returns the registered key vector."
  []
  (ext/register-many! {:bx/a parse-ref
                       :bx/b extract-result-data
                       :bx/c resolve-ref
                       :bx/d resolve-refs-in-value
                       :bx/e resolve-op-refs
                       :bx/f collect-ref-op-ids
                       :bx/g validate-ref-deps
                       :bx/h detect-cycles
                       :bx/i hem/assign-waves})
  ext-keys)

(defn with-batch-extensions
  "clojure.test fixture: install the batch extensions for the test, then
   restore whatever was registered under those keys before."
  [f]
  (let [prior (into {} (map (juxt identity ext/get-extension)) ext-keys)]
    (try
      (install!)
      (f)
      (finally
        (doseq [[k v] prior]
          (if v
            (ext/register! k v)
            (ext/deregister! k)))))))
