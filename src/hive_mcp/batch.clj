(ns hive-mcp.batch
  "Pure batch-runner for cross-tool operations. Extracted from
   `hive-mcp.tools.multi` (T13 Phase 1) so that:

   - The runner is a bounded context with zero tool-specific knowledge.
   - `hive-mcp.tools.multi` becomes a thin hive-mcp-flavored wrapper that
     supplies handler resolution + FX emission.
   - Downstream (T13 Phase 2) a `Batchable` protocol will wrap this runner
     so any consolidated tool can opt into batch/dsl/collect semantics.

   This namespace owns:
   - Operation normalization (string->keyword keys, id auto-gen, deps coercion)
   - Validation pipeline (required fields, unique ids, dep references, cycles, ref-deps)
   - Wave assignment (delegated to extension :bx/i)
   - $ref resolution (delegates :bx/a–:bx/g)
   - Single-op execution with error isolation (handler injected)
   - Wave execution (delegates :bx/j)
   - Top-level `run-operations` orchestrator

   Zero behavior change versus pre-extraction `tools.multi/run-multi`.
   Extension hooks (:bx/*) are preserved verbatim."
  (:require [clojure.string :as str]
            [hive-mcp.batch.protocol :as proto]
            [hive-mcp.dns.result :as result]
            [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Extension delegation
;; =============================================================================

(defn- delegate-or-noop
  "Delegate to extension if available, fall back to default value."
  [ext-key default-val args]
  (if-let [f (ext/get-extension ext-key)]
    (apply f args)
    (do
      (log/debug "Extension not available, returning default for" ext-key)
      default-val)))

;; =============================================================================
;; Operation normalization
;; =============================================================================

(defn normalize-op
  "Normalize a single operation map from MCP JSON format.
   Converts string keys to keywords. Ensures :id and :tool are present."
  [op]
  (let [normalized (into {} (map (fn [[k v]] [(keyword k) v]) op))]
    (cond-> normalized
      (str/blank? (:id normalized))
      (assoc :id (str "op-" (java.util.UUID/randomUUID)))

      (:depends_on normalized)
      (update :depends_on (fn [deps]
                            (cond
                              (string? deps) [deps]
                              (sequential? deps) (vec deps)
                              :else []))))))

;; =============================================================================
;; Reference resolution (delegated to extensions)
;; =============================================================================

(def ref-not-found
  "Sentinel for unresolvable reference. Intentionally keyed under the
   legacy `hive-mcp.tools.multi` namespace to preserve the pre-extraction
   public contract (tests compare against this exact keyword)."
  :hive-mcp.tools.multi/ref-not-found)

(defn ref?
  "Predicate: is this value a $ref string?"
  [v]
  (and (string? v) (str/starts-with? v "$ref:")))

(defn parse-ref
  "Delegate to extension for reference parsing."
  [s]
  (delegate-or-noop :bx/a nil [s]))

(defn extract-result-data
  "Delegate to extension for result data extraction."
  [handler-result]
  (delegate-or-noop :bx/b handler-result [handler-result]))

(defn enrich-op-result
  "Enrich an execute-op result with :data (parsed handler result)."
  [{:keys [result] :as op-result}]
  (assoc op-result :data (extract-result-data result)))

(defn resolve-ref
  "Delegate to extension for reference resolution."
  [parsed-ref results-by-id]
  (delegate-or-noop :bx/c ref-not-found [parsed-ref results-by-id]))

(defn resolve-refs-in-value
  "Delegate to extension for recursive reference resolution."
  [v results-by-id]
  (delegate-or-noop :bx/d v [v results-by-id]))

(defn resolve-op-refs
  "Delegate to extension for operation reference resolution."
  [op results-by-id]
  (delegate-or-noop :bx/e op [op results-by-id]))

(defn collect-ref-op-ids
  "Delegate to extension for reference collection."
  [op]
  (delegate-or-noop :bx/f #{} [op]))

(defn- validate-ref-deps
  "Delegate to extension for reference-dependency validation."
  [ops]
  (delegate-or-noop :bx/g [] [ops]))

;; =============================================================================
;; Validation sub-validators
;; =============================================================================

(defn- validate-required-fields
  "Check all ops have non-blank :id and :tool. Returns error vector."
  [ops]
  (into []
        (mapcat (fn [{:keys [id tool] :as op}]
                  (cond-> []
                    (str/blank? id)
                    (conj (str "Operation missing :id — " (pr-str (select-keys op [:tool :command]))))
                    (str/blank? tool)
                    (conj (str "Operation '" id "' missing :tool")))))
        ops))

(defn- validate-unique-ids
  "Check for duplicate operation IDs. Returns error vector."
  [ops]
  (into []
        (comp (filter (fn [[_id cnt]] (> cnt 1)))
              (map (fn [[id cnt]]
                     (str "Duplicate operation ID: '" id "' (appears " cnt " times)"))))
        (frequencies (map :id ops))))

(defn- validate-dep-references
  "Check deps reference existing ops, no self-deps. Returns error vector."
  [ops]
  (let [id-set (set (map :id ops))]
    (into []
          (mapcat (fn [{:keys [id depends_on]}]
                    (when (seq depends_on)
                      (mapcat (fn [dep]
                                (cond-> []
                                  (= dep id)
                                  (conj (str "Operation '" id "' depends on itself"))
                                  (not (contains? id-set dep))
                                  (conj (str "Operation '" id "' depends on non-existent '" dep "'"))))
                              depends_on))))
          ops)))

(defn- detect-cycles
  "Delegate to extension for cycle detection."
  [ops]
  (delegate-or-noop :bx/h [] [ops]))

(defn validate-ops
  "Validate an operations vector.
   Returns {:valid true} or {:valid false :errors [...]}."
  [ops]
  (let [basic-errors (into (validate-required-fields ops)
                           (concat (validate-unique-ids ops)
                                   (validate-dep-references ops)))]
    (if (seq basic-errors)
      {:valid false :errors basic-errors}
      (let [all-errors (into (detect-cycles ops)
                             (validate-ref-deps ops))]
        (if (seq all-errors)
          {:valid false :errors all-errors}
          {:valid true})))))

;; =============================================================================
;; Wave assignment
;; =============================================================================

(defn assign-waves
  "Assign operations to execution waves. Delegates to extension.
   Noop: all ops assigned to wave 1 (sequential execution)."
  [ops]
  (delegate-or-noop :bx/i (mapv #(assoc % :wave 1) ops) [ops]))

;; =============================================================================
;; Single-op execution (handler injected)
;; =============================================================================

(defn execute-op
  "Execute a single operation with error isolation, using an injected
   `resolve-handler` fn (tool-name -> handler-fn-or-nil).

   Returns {:id op-id :success bool :result map}
        or {:id op-id :success false :error string}."
  [resolve-handler {:keys [id tool] :as op}]
  (try
    (let [handler (resolve-handler tool)]
      (if-not handler
        {:id id :success false :error (str "Tool not found: " tool)}
        (let [meta-keys #{:id :tool :depends_on :wave}
              handler-args (-> (apply dissoc op meta-keys)
                               (update :command #(if (keyword? %) (name %) %)))
              result (handler handler-args)]
          {:id id :success true :result result})))
    (catch Exception e
      (log/error {:event :op-execution-error
                  :op-id id
                  :tool  tool
                  :error (ex-message e)})
      {:id id :success false :error (ex-message e)})))

;; =============================================================================
;; Wave execution
;; =============================================================================

(defn- execute-wave
  "Execute all operations in a single wave. Delegates to extension :bx/j.
   Noop: sequential execution via mapv."
  [resolve-handler wave-ops]
  (let [exec-one (partial execute-op resolve-handler)]
    (if-let [f (ext/get-extension :bx/j)]
      (f wave-ops exec-one)
      (mapv exec-one wave-ops))))

(defn- check-deps-satisfied
  "Check if all dependencies for an op have succeeded.
   Returns {:ok true} or {:ok false :failed-deps [ids]}."
  [{:keys [depends_on]} results-by-id]
  (if (empty? depends_on)
    {:ok true}
    (let [failed (filterv (fn [dep-id]
                            (let [r (get results-by-id dep-id)]
                              (or (nil? r) (not (:success r)))))
                          depends_on)]
      (if (empty? failed)
        {:ok true}
        {:ok false :failed-deps failed}))))

(defn- execute-and-collect-wave
  "Execute one wave, skipping ops with failed deps. Resolves $ref strings
   before execution, enriches results with :data for downstream refs."
  [resolve-handler wave-ops all-results]
  (let [{executable true skipped false}
        (group-by #(:ok (check-deps-satisfied % all-results)) wave-ops)

        skip-results (mapv (fn [op]
                             (let [{:keys [failed-deps]} (check-deps-satisfied op all-results)]
                               (enrich-op-result
                                {:id (:id op) :success false
                                 :error (str "Skipped: dependencies failed — "
                                             (str/join ", " failed-deps))})))
                           (or skipped []))

        resolved-ops (mapv #(resolve-op-refs % all-results) (or executable []))
        exec-results (mapv enrich-op-result (execute-wave resolve-handler resolved-ops))]
    (into skip-results exec-results)))

;; =============================================================================
;; Pipeline
;; =============================================================================

(defn- compile-batch
  "Normalize → validate → assign-waves. Returns Result.
   Ok:  {:waved-ops [...] :wave-groups {1 [...] 2 [...]}}
   Err: :multi/validation-failed with :errors and :total."
  [ops]
  (let [normalized (mapv normalize-op ops)
        validation (validate-ops normalized)]
    (if-not (:valid validation)
      (result/err :multi/validation-failed
                  {:errors (:errors validation) :total (count ops)})
      (let [waved (assign-waves normalized)]
        (result/ok {:waved-ops   waved
                    :wave-groups (group-by :wave waved)})))))

(defn- build-dry-run-response
  "Build dry-run plan response from wave groups."
  [wave-groups total-count]
  {:success true
   :dry-run true
   :waves   (into (sorted-map)
                  (map (fn [[w ops]]
                         [w {:ops (mapv #(select-keys % [:id :tool :command :depends_on]) ops)}])
                       wave-groups))
   :summary {:total total-count :success 0 :failed 0 :waves (count wave-groups)}})

(defn- noop-emit-fx
  "Default FX emitter: discard. Callers override via :emit-fx option."
  [_fx-id _fx-data])

(defn- execute-all-waves
  "Execute all waves sequentially, collect results, emit FX via injected fn."
  [resolve-handler emit-fx wave-groups total-count]
  (let [wave-count  (count wave-groups)
        all-results (atom {})
        wave-log    (atom (sorted-map))]
    (doseq [wave-num (sort (keys wave-groups))]
      (let [wave-ops (get wave-groups wave-num)
            wave-all (execute-and-collect-wave resolve-handler wave-ops @all-results)]
        (doseq [r wave-all]
          (swap! all-results assoc (:id r) r))
        (swap! wave-log assoc wave-num
               {:ops     (mapv #(select-keys % [:id :tool :command]) wave-ops)
                :results wave-all})
        ;; Emit observability FX via injected callback
        (let [op-count (count wave-all)
              success-count (count (filter :success wave-all))
              failed-count (- op-count success-count)]
          (emit-fx :multi/wave-complete
                   {:wave-num      wave-num
                    :op-count      op-count
                    :success-count success-count
                    :failed-count  failed-count
                    :total-waves   wave-count}))
        (doseq [{:keys [id error] :as r} wave-all
                :when (and (not (:success r)) error)]
          (emit-fx :multi/op-error
                   {:op-id    id
                    :tool     (:tool r)
                    :command  (:command r)
                    :error    error
                    :wave-num wave-num}))))

    (let [results     (vals @all-results)
          success-cnt (count (filter :success results))
          failed-cnt  (count (remove :success results))]
      {:success (zero? failed-cnt)
       :waves   @wave-log
       :summary {:total   total-count
                 :success success-cnt
                 :failed  failed-cnt
                 :waves   wave-count}})))

(defn run-operations
  "Execute a vector of operations with dependency ordering.

   Pipeline: normalize → validate → assign-waves → execute-per-wave

   Required options:
     :resolve-handler  (fn [tool-name] handler-fn-or-nil)

   Optional options:
     :dry-run?  bool — validate and plan only, don't execute
     :emit-fx   (fn [fx-id fx-data]) — observability hook (default: noop)

   Returns:
     {:success bool
      :waves   {1 {:ops [...] :results [...]} ...}
      :summary {:total N :success M :failed F :waves W}
      :errors  [...] (validation errors if any)}"
  [ops {:keys [resolve-handler dry-run? emit-fx]
        :or   {emit-fx noop-emit-fx}}]
  (assert (ifn? resolve-handler) "run-operations requires :resolve-handler fn")
  (let [compiled (compile-batch ops)]
    (if (result/err? compiled)
      {:success false
       :errors  (:errors compiled)
       :summary {:total (or (:total compiled) (count ops)) :success 0 :failed 0 :waves 0}}
      (let [{:keys [wave-groups]} (:ok compiled)]
        (if dry-run?
          (build-dry-run-response wave-groups (count ops))
          (execute-all-waves resolve-handler emit-fx wave-groups (count ops)))))))

;; =============================================================================
;; Default Batchable reference implementation (T13 Phase 2)
;; =============================================================================

(def ^:private default-batch-schema
  "JSONSchema `:properties` map exposed by the default batch runner.
   Consolidated tools that opt into Batchable via `make-default-runner`
   inherit this schema; custom runners may override `batch-schema`."
  {:operations {:type        "array"
                :description "Vector of operation maps; each requires :tool and :command."
                :items       {:type "object"}}
   :dry_run    {:type        "boolean"
                :description "Validate + plan waves without executing handlers."
                :default     false}})

(defn- coerce-batch-opts
  "Merge caller opts onto the runner's configured defaults. Opts keys
   `:resolve-handler` / `:emit-fx` passed at call-time win; falling back
   to whatever was baked into the runner record."
  [{:keys [resolve-handler emit-fx]} opts]
  (cond-> (or opts {})
    (and resolve-handler (not (contains? opts :resolve-handler)))
    (assoc :resolve-handler resolve-handler)
    (and emit-fx (not (contains? opts :emit-fx)))
    (assoc :emit-fx emit-fx)))

(defn- safe-run-operations
  "`run-operations` wrapped so the Batchable contract (never-throws) holds
   even if a caller forgets `:resolve-handler`. Any unexpected exception
   becomes an `{:success false :errors [...]}` payload."
  [ops opts]
  (try
    (if (ifn? (:resolve-handler opts))
      (run-operations ops opts)
      {:success false
       :errors  ["Batchable requires :resolve-handler fn in opts or runner"]
       :summary {:total (count ops) :success 0 :failed 0 :waves 0}
       :waves   {}})
    (catch Throwable t
      {:success false
       :errors  [(str "batch-execute crashed: " (ex-message t))]
       :summary {:total (count (or ops [])) :success 0 :failed 0 :waves 0}
       :waves   {}})))

(defrecord DefaultBatchRunner [resolve-handler emit-fx]
  proto/Batchable
  (batch-execute [this ops opts]
    (safe-run-operations ops (coerce-batch-opts this opts)))
  (batch-schema [_this]
    default-batch-schema)

  proto/DAGBatchable
  (batch-with-deps [this ops opts]
    ;; The default runner already understands :depends_on — no behavior
    ;; change vs batch-execute; this arm documents the capability.
    (safe-run-operations ops (coerce-batch-opts this opts)))

  proto/StreamingBatchable
  (batch-stream [this ops opts on-event]
    ;; Minimal bridge: route :emit-fx into the caller-supplied on-event
    ;; so existing observability FX surface as stream events.
    (let [merged (coerce-batch-opts this opts)
          wrapped (fn [fx-id fx-data]
                    (try
                      (when on-event (on-event fx-id fx-data))
                      (catch Throwable _ nil))
                    (when-let [prior (:emit-fx opts)]
                      (try (prior fx-id fx-data)
                           (catch Throwable _ nil))))]
      (safe-run-operations ops (assoc merged :emit-fx wrapped)))))

(defn make-default-runner
  "Construct a `DefaultBatchRunner` satisfying `Batchable` / `DAGBatchable`
   / `StreamingBatchable`. Both keys are optional; callers can also pass
   `:resolve-handler` / `:emit-fx` inside opts at each `batch-execute`
   call, and per-call values win over baked-in ones.

   Example:
     (def runner (make-default-runner
                    {:resolve-handler resolve-tool-handler
                     :emit-fx         fire-fx!}))
     (proto/batch-execute runner ops {:dry-run? true})"
  [{:keys [resolve-handler emit-fx] :as _cfg}]
  (->DefaultBatchRunner resolve-handler emit-fx))
