(ns hive-mcp.workflows.strategy-registry
  "method-keyword -> IDispatchStrategy registry-of-records.

   The :method dispatch seam. Addon `(hooks)` map entries whose key namespace is
   \"wf\" route here via register-by-key! / deregister-by-owner! (addons.core).
   Each entry is a WorkflowStrategyEntry ADT carrying {:method :strategy :owner}.

   Owner = addon-id keyword (or :wf/core for the boot-seeded :wf/default Noop).
   Resolvers ALWAYS return an IDispatchStrategy (LSP): the boot-seeded
   :wf/default backs every lookup, so a caller cannot tell a default from an
   addon contribution and never gets nil.

   Conflict policy (mirrors saa.registry.planners):
     same method + same owner      -> idempotent silent replace
     same method + different owner -> :wf/registry-conflict warn-log, first-write-wins"
  (:require [hive-mcp.workflows.strategy :as strategy :refer [WorkflowStrategyEntry]]
            [hive-dsl.adt :refer [adt-case]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-id :wf/default)
(def ^:private core-owner :wf/core)

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state
  (atom {:by-id    {}    ;; :method -> {:strategy rec :owner kw :registered-at inst}
         :by-owner {}})) ;; owner-kw -> #{:method}

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  "Register an IDispatchStrategy under owner, keyed by method.
   Returns :ok | :replaced | :conflict. entry shape: {:strategy <IDispatchStrategy>}."
  [owner method entry]
  (let [now     (java.time.Instant/now)
        v       (assoc entry :owner owner :registered-at now)
        outcome (atom :ok)]
    (swap! state
           (fn [{:keys [by-id by-owner]}]
             (let [existing (get by-id method)]
               (cond
                 (nil? existing)
                 (do (reset! outcome :ok)
                     {:by-id    (assoc by-id method v)
                      :by-owner (update by-owner owner (fnil conj #{}) method)})

                 (= owner (:owner existing))
                 (do (reset! outcome :replaced)
                     {:by-id    (assoc by-id method v)
                      :by-owner by-owner})

                 :else
                 (do (reset! outcome :conflict)
                     {:by-id by-id :by-owner by-owner})))))
    (when (= :conflict @outcome)
      (log/warn "[workflows.strategy-registry] :wf/registry-conflict"
                {:method         method
                 :existing-owner (:owner (get-in @state [:by-id method]))
                 :rejected-owner owner}))
    @outcome))

(defn deregister-by-owner!
  "Remove every strategy registered by `owner`. Returns set of removed methods."
  [owner]
  (let [removed (atom #{})]
    (swap! state
           (fn [{:keys [by-id by-owner]}]
             (let [ids (get by-owner owner #{})]
               (reset! removed ids)
               {:by-id    (apply dissoc by-id ids)
                :by-owner (dissoc by-owner owner)})))
    @removed))

;; =============================================================================
;; Hook-key facade — routed by addons.core "wf" branch
;; =============================================================================

(defn register-by-key!
  "Route \"wf\" addon `(hooks)` entries into the registry.

   `entries` is a WorkflowStrategyEntry ADT value or a vector of them. Each is
   validated via adt-case exhaustiveness; owner is the addon-id. `k` is accepted
   for hook-walk symmetry; routing is by the entry's :method.

   Returns a vector of per-entry outcomes (:ok | :replaced | :conflict | :ignored)."
  [owner k entries]
  (let [entries (cond (sequential? entries) entries
                      (map? entries)        [entries]
                      :else                 nil)]
    (when (nil? entries)
      (log/warn "[workflows.strategy-registry] non-entry value for hook key — skipping"
                {:owner owner :key k}))
    (mapv (fn [entry]
            (if (strategy/workflow-strategy-entry? entry)
              (adt-case WorkflowStrategyEntry entry
                :wf/strategy (register! owner (:method entry) {:strategy (:strategy entry)}))
              (do (log/warn "[workflows.strategy-registry] non-WorkflowStrategyEntry value — ignored"
                            {:owner owner :key k :entry entry})
                  :ignored)))
          (or entries []))))

;; =============================================================================
;; Resolvers — ALWAYS return an IDispatchStrategy (LSP)
;; =============================================================================

(defn lookup
  "Return the registered entry for a method, or nil."
  [method]
  (get-in @state [:by-id method]))

(defn lookup-strategy-or-default
  "Return the IDispatchStrategy for `method`, else the :wf/default entry, else a
   freshly-constructed NoopDispatchStrategy. Never nil (LSP) — seed-independent."
  ([] (lookup-strategy-or-default default-id))
  ([method]
   (or (some-> (lookup method) :strategy)
       (some-> (lookup default-id) :strategy)
       (strategy/->NoopDispatchStrategy))))

(defn all-methods
  "Sorted vector of all registered method keywords."
  []
  (vec (sort (keys (:by-id @state)))))

(defn snapshot
  "Immutable snapshot of the registry. :version is a hash callers can stamp."
  []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test!
  "Clear the registry. Test-only."
  []
  (reset! state {:by-id {} :by-owner {}}))

;; =============================================================================
;; Bootstrap: seed :wf/default Noop before the first addon registration arrives
;; =============================================================================

(defonce ^:private __wf-default-seeded__
  (register! core-owner default-id {:strategy (strategy/->NoopDispatchStrategy)}))
