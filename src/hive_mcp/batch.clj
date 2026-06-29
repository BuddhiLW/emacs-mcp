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
            [hive-mcp.extensions.delegate :refer [delegate-or-noop]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Extension delegation
;; =============================================================================

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

(defn- creation-tool?
  "Tools whose primary effect is producing a new addressable artifact.
   A creation op that returns `:id nil` has effectively failed even when
   the call did not throw — downstream $ref to its id resolves to nil
   and any dependent op runs against a phantom reference."
  [tool]
  (contains? #{"memory" "kanban" "kg"} (str tool)))

(defn enrich-op-result
  "Enrich an execute-op result with `:data` (parsed handler result) and
   compose a cross-layer `:success` signal.

   Cross-layer composition: a handler may return a structured result
   whose data carries an explicit failure signal — typical when a tool
   call did not throw but its underlying side-effect (memory add, kanban
   create, kg edge) failed. The composed signal honours those:

   - `(:success data)` is `false`              → wrapper :success false
   - creation-tool with `(:id data)` literally `nil` → wrapper :success false

   Without composition, batch summaries reported `:success true` for ops
   the user observed as failed (e.g. `{id: null, success: null}` from a
   degraded memory backend), and dependent `$ref`-using ops dispatched
   against null endpoints — silent dangling KG edges in wrap ceremonies.

   Never upgrades `:success`; only downgrades. An op that already
   threw (`:success false`) keeps its original `:error`."
  [{:keys [tool result success] :as op-result}]
  (let [data                  (extract-result-data result)
        inner-success-false?  (and (map? data)
                                   (contains? data :success)
                                   (false? (:success data)))
        ;; Explicit error flags: mcp-error envelopes carry {:isError true};
        ;; hive-dsl Result failures carry {:ok false}. Neither was inspected
        ;; before, so in-band tool errors surfaced in summaries as failed:0.
        explicit-error-flag?  (and (map? data)
                                   (or (true? (:isError data))
                                       (false? (:ok data))))
        ;; Bare error map with no :success key — e.g. handle-kg-add-edge's
        ;; validation failures return {:error "relation is required"} directly.
        bare-error?           (and (map? data)
                                   (some? (:error data))
                                   (not (contains? data :success)))
        null-id-on-create?    (and (creation-tool? tool)
                                   (map? data)
                                   (or (and (contains? data :id) (nil? (:id data)))
                                       ;; kg edge handler returns :edge-id, not :id
                                       (and (contains? data :edge-id) (nil? (:edge-id data)))))
        downgrade?            (and success
                                   (or inner-success-false?
                                       explicit-error-flag?
                                       bare-error?
                                       null-id-on-create?))
        downgrade-msg         (cond
                                inner-success-false?
                                (or (some-> data :errors first)
                                    (some-> data :error str)
                                    "tool reported failure (inner :success false)")
                                bare-error?
                                (some-> data :error str)
                                explicit-error-flag?
                                (or (some-> data :error str)
                                    (some-> data :text str)
                                    "tool reported failure (:isError/:ok false)")
                                null-id-on-create?
                                (str "creation tool returned nil id — degraded backend?")
                                :else nil)]
    (cond-> (assoc op-result :data data)
      downgrade? (-> (assoc :success false)
                     (assoc :error downgrade-msg)))))

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

   Returns {:id op-id :tool tool-name :command cmd :success bool :result map}
        or {:id op-id :tool tool-name :command cmd :success false :error string}.

   `:tool` and `:command` are echoed back to the caller so downstream
   stages (enrich-op-result, FX emitters, format-results) can reason
   about the op without re-joining against the input. Without this,
   `enrich-op-result`'s creation-tool classifier sees `nil` for `tool`
   and fails to downgrade memory/kanban/kg ops that returned `:id nil`."
  [resolve-handler {:keys [id tool command] :as op}]
  (try
    (let [handler (resolve-handler tool)]
      (if-not handler
        {:id id :tool tool :command command :success false
         :error (str "Tool not found: " tool)}
        (let [meta-keys #{:id :tool :depends_on :wave}
              handler-args (-> (apply dissoc op meta-keys)
                               (update :command #(if (keyword? %) (name %) %)))
              result (handler handler-args)]
          {:id id :tool tool :command command :success true :result result})))
    (catch Exception e
      (log/error {:event :op-execution-error
                  :op-id id
                  :tool  tool
                  :error (ex-message e)})
      {:id id :tool tool :command command :success false
       :error (ex-message e)})))

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

(defn- classify-op-refs
  "After `resolve-op-refs` has run, walk the ORIGINAL op's params to
   spot every `$ref:...` string and look up how it actually resolved
   against `results-by-id`. A ref is `:broken` when:

     - the source op-id is missing from results (`ref-not-found`), OR
     - the resolved value is literally `nil`.

   The second case catches the wrap-ceremony footgun where a creation
   op timed out, was already downgraded to `:success false` by
   `enrich-op-result`, and its `:data.id` is nil — a dependent k>'s
   `:from $ref:src.data.id` would otherwise create a phantom edge.

   Returns `nil` when all refs OK (or no refs); otherwise
   `{:broken-refs [{:ref str :reason kw} ...]}`."
  [original-op results-by-id]
  (let [meta-keys #{:id :tool :command :depends_on :wave}
        refs (atom [])
        walk! (fn walk! [v]
                (cond
                  (ref? v)
                  (when-let [parsed (parse-ref v)]
                    (let [resolved (resolve-ref parsed results-by-id)]
                      (cond
                        (identical? resolved ref-not-found)
                        (swap! refs conj {:ref v :reason :unresolved})
                        (nil? resolved)
                        (swap! refs conj {:ref v :reason :nil-resolved}))))

                  (map? v)
                  (run! walk! (vals v))

                  (sequential? v)
                  (run! walk! v)

                  :else nil))]
    (doseq [[k v] original-op]
      (when-not (contains? meta-keys k)
        (walk! v)))
    (when (seq @refs)
      {:broken-refs @refs})))

(defn- broken-ref-skip
  "Build a skip result for an op whose ref(s) resolved to nil/missing.
   Mirrors the dependency-skip shape so consumers (format-results,
   wave summary counters) treat it identically."
  [op {:keys [broken-refs]}]
  (enrich-op-result
    {:id      (:id op)
     :tool    (:tool op)
     :command (:command op)
     :success false
     :error   (str "Skipped: broken-ref — "
                   (str/join ", " (mapv (fn [{:keys [ref reason]}]
                                          (str ref " (" (name reason) ")"))
                                        broken-refs)))}))

(defn- normalize-exec-result
  "Defensive: bounded-pmap-style executors can return `nil` on worker
   timeout; pair each input op with its result by index and synthesise
   a failed result for any nil position. Without this, the wave-result
   reduction `(swap! all-results assoc nil {...})` collapses every
   timed-out op under the nil key, dropping all but one and producing
   the off-by-one summary count observed during ceremony smoke tests."
  [op exec-result]
  (cond
    (nil? exec-result)
    {:id      (:id op)
     :tool    (:tool op)
     :command (:command op)
     :success false
     :error   "executor returned nil — likely worker timeout"}

    (nil? (:id exec-result))
    (assoc exec-result :id (:id op))

    :else exec-result))

(defn- execute-and-collect-wave
  "Execute one wave, skipping ops with failed deps OR broken refs.
   Resolves $ref strings before execution and enriches results with
   `:data` for downstream refs.

   Skip semantics:
   - Failed dep (depends_on entry's :success false) → skip with
     'dependencies failed' error.
   - Broken ref (a $ref that resolved to nil or ref-not-found) → skip
     with 'broken-ref' error. This is the fix for dangling KG edges
     when a source op produced :id nil under a degraded backend.

   Defensive: a nil result from execute-wave (worker-pool timeout)
   is normalized into a failed op-result keyed by the input op's id so
   the wave summary count stays exact."
  [resolve-handler wave-ops all-results]
  (let [{deps-ok true deps-failed false}
        (group-by #(:ok (check-deps-satisfied % all-results)) wave-ops)

        dep-skip-results
        (mapv (fn [op]
                (let [{:keys [failed-deps]} (check-deps-satisfied op all-results)]
                  (enrich-op-result
                    {:id      (:id op)
                     :tool    (:tool op)
                     :command (:command op)
                     :success false
                     :error   (str "Skipped: dependencies failed — "
                                   (str/join ", " failed-deps))})))
              (or deps-failed []))

        ;; For each dep-ok op, classify against the current results:
        ;; broken-ref ops skip immediately; the rest go to the executor
        ;; with refs resolved.
        ref-classified
        (mapv (fn [op]
                (if-let [broken (classify-op-refs op all-results)]
                  [:broken op broken]
                  [:ok op]))
              (or deps-ok []))

        broken-ref-results
        (->> ref-classified
             (filter #(= :broken (first %)))
             (mapv (fn [[_ op broken]] (broken-ref-skip op broken))))

        executable-ops
        (->> ref-classified
             (filter #(= :ok (first %)))
             (mapv (fn [[_ op]] op)))

        resolved-ops (mapv #(resolve-op-refs % all-results) executable-ops)
        raw-results  (execute-wave resolve-handler resolved-ops)
        ;; Pair input ops with executor outputs by index so a nil result
        ;; (worker timeout) is synthesized into a failed entry carrying
        ;; the input op's id — preserves :total invariant.
        exec-results (mapv (fn [op raw]
                             (enrich-op-result (normalize-exec-result op raw)))
                           executable-ops raw-results)]
    (into (into dep-skip-results broken-ref-results) exec-results)))

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
