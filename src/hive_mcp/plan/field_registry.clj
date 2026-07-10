(ns hive-mcp.plan.field-registry
  "OCP registry for addon-contributed plan/step domain fields.

   hive-mcp core declares ZERO workflow-specific plan fields. Addons (e.g.
   hive-workflows) contribute field specs via IAddon `hooks` under the
   `:plan/*` keyword namespace; `hive-mcp.addons.core` routes them here. The
   plan schema, parser, normalizer, and kanban projection CONSULT this
   registry so fields like :method are never hardcoded in core.

   Mirrors hive-mcp.saa.registry / hive-mcp.multi.registry: a generic core
   registry an addon populates and cleans up by owner. Relies on malli maps
   being open by default, so unregistered fields validate without schema edits.

   Field spec (map):
     :scope        :step | :plan       (required — which map the field rides)
     :key          keyword             (required — the field key, e.g. :method)
     :schema       malli schema        (optional — documentation/strict validation)
     :normalize    (fn [v] -> v')      (optional — value coercion; only applied
                                        when the key is present)
     :default-from :plan-key           (optional, :step scope — plan-level key that
                                        supplies a per-step default when absent)
     :project?     boolean             (optional — carry into plan->task-specs)
     :validate     (fn [v] -> nil|err) (optional — field-value check, run only
                                        when the key is present; truthy return is
                                        the error surfaced by the plan gate)
     :owner        keyword             (stamped at registration — addon id)"
  (:require [clojure.tools.logging :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private registry (atom {:step {} :plan {}}))

(defn reset-registry!
  "Clear all registered fields. Test/shutdown hook."
  []
  (reset! registry {:step {} :plan {}}))

;; =============================================================================
;; Registration (populated by addons via IAddon hooks -> addons.core)
;; =============================================================================

(defn- valid-spec?
  [{:keys [scope key]}]
  (and (contains? #{:step :plan} scope) (keyword? key)))

(defn register-field!
  "Register one field spec. Returns the field :key, or nil if the spec is
   invalid (logs a warn). Last-write-wins per [scope key]."
  [{:keys [scope key] :as spec}]
  (if (valid-spec? spec)
    (do (swap! registry assoc-in [scope key] spec) key)
    (do (log/warn "[plan-field-registry] invalid field spec — need :scope :step|:plan + keyword :key"
                  {:spec spec})
        nil)))

(defn register-by-key!
  "IAddon `hooks` entry point. `k` is the :plan/* hook keyword (routed here by
   addons.core); `spec` is the field-spec map whose :scope/:key drive placement.
   `owner` (addon id) is stamped for per-owner deregistration."
  [owner _k spec]
  (register-field! (assoc spec :owner owner)))

(defn deregister-by-owner!
  "Remove every field registered by `owner` (addon shutdown). Idempotent."
  [owner]
  (swap! registry
         (fn [r]
           (reduce (fn [acc scope]
                     (update acc scope
                             (fn [fields]
                               (into {} (remove (fn [[_ s]] (= owner (:owner s))) fields)))))
                   r
                   [:step :plan])))
  nil)

;; =============================================================================
;; Queries
;; =============================================================================

(defn step-fields [] (:step @registry))
(defn plan-fields [] (:plan @registry))
(defn step-field-keys [] (set (keys (:step @registry))))
(defn plan-field-keys [] (set (keys (:plan @registry))))

;; =============================================================================
;; Consult helpers (pure — called by plan.schema / parser / util)
;; =============================================================================

(defn apply-normalizers
  "Apply registered :normalize fns for `scope` (:step|:plan) to the keys present
   in map `m`. Absent keys are untouched; specs without :normalize are skipped."
  [m scope]
  (reduce-kv (fn [acc k {:keys [normalize]}]
               (if (and normalize (contains? acc k))
                 (update acc k normalize)
                 acc))
             m
             (get @registry scope)))

(defn validate-fields
  "Apply each registered field's :validate fn (for `scope` :step|:plan) to the
   keys present in map `m`. Returns a vector of {:scope :key :value :error} for
   every field whose :validate returned a truthy error. Fields without :validate,
   and keys absent from `m`, contribute nothing. Field-agnostic — core never
   names a specific field."
  [m scope]
  (reduce-kv (fn [acc k {:keys [validate]}]
               (if (and validate (contains? m k))
                 (if-let [err (validate (get m k))]
                   (conj acc {:scope scope :key k :value (get m k) :error err})
                   acc)
                 acc))
             []
             (get @registry scope)))

(defn resolve-step-defaults
  "For each registered :step field carrying :default-from, fill the value on
   every step that lacks it from the plan-level source key (coerced via the
   field's :normalize). Pure; returns the plan unchanged when nothing applies."
  [plan]
  (let [defaults (into {}
                       (for [[k {:keys [default-from normalize]}] (:step @registry)
                             :when default-from
                             :let [pv (get plan default-from)
                                   pv (if (and normalize (some? pv)) (normalize pv) pv)]
                             :when (some? pv)]
                         [k pv]))]
    (if (empty? defaults)
      plan
      (update plan :steps
              (fn [steps]
                (mapv (fn [step]
                        (reduce-kv (fn [acc k dv]
                                     (if (some? (get acc k)) acc (assoc acc k dv)))
                                   step
                                   defaults))
                      steps))))))

(defn project-keys
  "Registered :step field keys flagged :project? — carried into kanban task
   specs by plan.parser.util/plan->task-specs."
  []
  (->> (:step @registry)
       (filter (fn [[_ spec]] (:project? spec)))
       (map key)
       set))
