(ns hive-mcp.test.stub.batch-extensions
  "Batch extension seams (:bx/*) for driver-free tests.

   hive-mcp.batch delegates cycle detection and wave assignment to
   `delegate-or-noop` extension keys. hive-mcp core ships no implementation —
   on a cold run every op lands in wave 1 and cycles pass validation. The
   registry is the seam; this ns installs implementations in it rather than
   letting a test assert the absence of the collaborator.

   The implementations are hive.events.multi's, a real hive-mcp dependency
   already on the :test-unit classpath — not a hand-rolled fake, so a test
   using them is pinned to an independent implementation of the contract.

   API:
     (install!)            register :bx/h and :bx/i, returns the key vector
     with-batch-extensions clojure.test :each fixture (snapshot + restore)

   Contracts mirrored from hive-mcp.batch:
     :bx/h detect-cycles => vector of error strings, empty when acyclic
     :bx/i assign-waves  => ops, each carrying an integer :wave"
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
  "\"$ref:<op-id>.<seg>.<seg>\" => {:op-id \"<op-id>\" :path [:seg :seg]}.
   nil when S is not a $ref or carries no path."
  [s]
  (when (and (string? s) (str/starts-with? s ref-prefix))
    (let [[op-id & path] (str/split (subs s (count ref-prefix)) #"\.")]
      (when (and (seq op-id) (seq path))
        {:op-id op-id :path (mapv keyword path)}))))

(defn extract-result-data
  "The payload of a handler result: an MCP text response's JSON body parsed
   with keyword keys, or the result itself when it carries no such body."
  [handler-result]
  (let [text (or (:text handler-result)
                 (some :text (:content handler-result)))]
    (if (string? text)
      (try (json/read-str text :key-fn keyword)
           (catch Exception _ handler-result))
      handler-result)))

(defn resolve-ref
  "The value PARSED-REF designates in RESULTS-BY-ID, or batch/ref-not-found
   when the source op produced no result."
  [{:keys [op-id path]} results-by-id]
  (if-let [op-result (get results-by-id op-id)]
    (get-in op-result path)
    batch/ref-not-found))

(defn resolve-refs-in-value
  "V with every $ref string it contains replaced by its resolved value. An
   unresolvable ref is left as the original string so the caller's broken-ref
   classification still sees it."
  [v results-by-id]
  (cond
    (batch/ref? v)
    (if-let [parsed (parse-ref v)]
      (let [resolved (resolve-ref parsed results-by-id)]
        (if (identical? resolved batch/ref-not-found) v resolved))
      v)

    (map? v)
    (into (empty v) (map (fn [[k x]] [k (resolve-refs-in-value x results-by-id)])) v)

    (sequential? v)
    (into (empty v) (map #(resolve-refs-in-value % results-by-id)) v)

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
  (let [ids   (volatile! #{})
        walk! (fn walk! [v]
                (cond
                  (batch/ref? v)  (when-let [{:keys [op-id]} (parse-ref v)]
                                    (vswap! ids conj op-id))
                  (map? v)        (run! walk! (vals v))
                  (sequential? v) (run! walk! v)
                  :else nil))]
    (doseq [[_ v] (pd/ref-walkable-entries op)] (walk! v))
    @ids))

(defn validate-ref-deps
  "Error strings for refs naming an op that is not in OPS."
  [ops]
  (let [id-set (set (map :id ops))]
    (into []
          (mapcat (fn [op]
                    (for [ref-id (collect-ref-op-ids op)
                          :when  (not (contains? id-set ref-id))]
                      (str "Operation '" (:id op)
                           "' references non-existent operation '" ref-id "'"))))
          ops)))

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
