(ns hive-mcp.workflows.wrap-session
  "hive-events FSM spec for the Wrap Session (crystallization) workflow.

   The wrap workflow crystallizes session learnings into long-term memory:
     ::fsm/start -> ::gather -> ::crystallize -> ::kg-edges -> ::notify -> ::evict -> ::end

   This is the Clojure handler implementation matching resources/fsm/wrap-session.edn.
   The EDN spec uses keyword handlers (:start, :gather, :crystallize, etc.) that are
   resolved to these functions at compile time via the handler-map.

   Design constraints (same as forge-belt):
   - Handlers are PURE functions: (resources, data) -> data'
   - Side effects flow through the resources map (territory)
   - The FSM is the map -- deterministic state transitions
   - Dispatch predicates are pure functions of state data
   - The degrade-continue envelope + evict + trace-log + always seams live in
     hive-mcp.workflows.support (boundary-step / continue / handle-evict /
     trace-log-enter / always / default-handle-error).

   Resources map (injected at run time):
     :harvest-fn     -- (fn [directory] -> harvested-data)
     :crystallize-fn -- (fn [harvested] -> {:summary-id str, :stats map, ...})
     :kg-edge-fn     -- (fn [summary-id source-ids project-id agent-id] -> {:created-count N})
     :notify-fn      -- (fn [agent-id session-id project-id stats] -> nil)
     :evict-fn       -- (fn [agent-id] -> {:evicted N})
     :scope-fn       -- (fn [directory] -> project-id)
     :source-ids-fn  -- (fn [harvested] -> [string])
     :directory      -- string (working directory for project scoping)
     :agent-id       -- string (ling's slave-id for attribution)

   State data shape:
     {:agent-id       string   ;; ling identity for attribution
      :directory      string   ;; working directory
      :project-id     string   ;; derived from directory via scope-fn
      :harvested      map      ;; crystal harvest result
      :crystal-result map      ;; crystallize result (:summary-id, :stats)
      :source-ids     [string] ;; memory entry IDs for KG edges
      :kg-result      map      ;; KG edge creation result
      :notify-sent?   bool     ;; wrap_notify dispatched
      :eviction       map      ;; context eviction result
      :error          any}     ;; error info if in error state"

  (:require [hive.events.fsm :as fsm]
            [hive-mcp.workflows.support :as support]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Dispatch Predicates (pure functions of state data)
;; =============================================================================

(defn harvested?
  "Check if harvest/gather produced data."
  [data]
  (some? (:harvested data)))

(defn crystallized?
  "Check if crystallization succeeded (no error in result).
   Degraded/skipped results are treated as non-fatal: the flow continues."
  [data]
  (and (some? (:crystal-result data))
       (or (:degraded (:crystal-result data))
           (:skipped (:crystal-result data))
           (not (get-in data [:crystal-result :error])))))

(defn crystal-error?
  "Check if crystallization returned a FATAL error.
   A result marked :degraded or :skipped is NOT treated as a fatal error —
   the wrap flow continues in degraded mode (see 'Git Commit Optional'
   decision 20260213005110)."
  [data]
  (let [cr (:crystal-result data)]
    (and (some? (:error cr))
         (not (:degraded cr))
         (not (:skipped cr)))))

(def always
  "Dispatch predicate — always true. Shared seam (support/always)."
  support/always)

;; =============================================================================
;; Handlers (pure functions: resources x data -> data')
;;
;; EDN handler-map keys: :start, :gather, :crystallize, :kg-edges,
;;                       :notify, :evict, :end, :error
;; =============================================================================

(defn handle-start
  "Initialize a wrap session.
   Resolves agent-id and directory, derives project-id.
   EDN handler key: :start"
  [resources data]
  (let [{:keys [agent-id directory project-id]}
        (support/resolve-session-identity resources data)]
    (assoc data
           :agent-id agent-id
           :directory directory
           :project-id project-id
           :error nil)))

(defn handle-gather
  "Harvest session data for crystallization.
   EDN handler key: :gather

   Degraded mode: if harvest-fn throws (e.g. nREPL/HTTP down), logs a
   warning, marks :harvested as {:degraded true}, and attaches an empty
   placeholder so the FSM can continue."
  [resources data]
  (let [harvest-fn (:harvest-fn resources)
        directory (:directory data)]
    (support/boundary-step data
      {:run   (fn [d] (assoc d :harvested (harvest-fn directory)))
       :policy support/continue
       :spec  {:log-msg "wrap-session: harvest failed — continuing in degraded mode:"
               :delta (fn [t] {:harvested {:degraded true
                                           :error (ex-message t)
                                           :progress-notes []
                                           :completed-tasks []
                                           :git-commits []
                                           :summary {}}})}})))

(defn handle-crystallize
  "Crystallize harvested session data into long-term memory.
   EDN handler key: :crystallize

   Degraded mode: if crystallize-fn throws, logs a warning and produces a
   :crystal-result marked :skipped/:degraded so the wrap still succeeds."
  [resources data]
  (let [crystallize-fn (:crystallize-fn resources)
        source-ids-fn (or (:source-ids-fn resources) (constantly []))
        harvested (:harvested data)]
    (support/boundary-step data
      {:run   (fn [d]
                (let [result (crystallize-fn harvested)
                      source-ids (source-ids-fn harvested)]
                  (assoc d
                         :crystal-result result
                         :source-ids source-ids)))
       :policy support/continue
       :spec  {:log-msg "wrap-session: crystallize failed — continuing in degraded mode:"
               :delta (fn [t] {:crystal-result {:skipped true
                                                :degraded true
                                                :error (ex-message t)
                                                :stats {}}
                               :source-ids []})}})))

(defn handle-kg-edges
  "Create :derived-from KG edges linking summary to source entries.
   EDN handler key: :kg-edges

   Degraded mode: if kg-edge-fn throws, marks :kg-result as
   {:skipped true :degraded true} and keeps the flow going."
  [resources data]
  (let [{:keys [project-id agent-id]} data
        summary-id (get-in data [:crystal-result :summary-id])
        source-ids (:source-ids data)
        kg-edge-fn (:kg-edge-fn resources)]
    (support/boundary-step data
      {:present? (boolean (and kg-edge-fn summary-id (seq source-ids)))
       :run    (fn [d] (assoc d :kg-result (kg-edge-fn summary-id source-ids project-id agent-id)))
       :absent (fn [d] (assoc d :kg-result {:created-count 0 :skipped true}))
       :policy support/continue
       :spec   {:log-msg "wrap-session: kg-edges failed — continuing in degraded mode:"
                :delta (fn [t] {:kg-result {:created-count 0
                                            :skipped true
                                            :degraded true
                                            :error (ex-message t)}})}})))

(defn handle-notify
  "Emit wrap_notify event for hivemind permeation.
   EDN handler key: :notify

   Degraded mode: if notify-fn throws, :notify-sent? is false and
   :notify-error captures the reason; wrap still continues."
  [resources data]
  (let [notify-fn (:notify-fn resources)
        {:keys [agent-id project-id crystal-result]} data
        session-id (:session crystal-result)
        stats (if (map? (:stats crystal-result)) (:stats crystal-result) {})]
    (support/boundary-step data
      {:present? (some? notify-fn)
       :run    (fn [d] (notify-fn agent-id session-id project-id stats)
                 (assoc d :notify-sent? true))
       :absent (fn [d] (assoc d :notify-sent? true))
       :policy support/continue
       :spec   {:log-msg "wrap-session: notify failed — continuing in degraded mode:"
                :delta (fn [t] {:notify-sent? false
                                :notify-degraded true
                                :notify-error (ex-message t)})}})))

(defn handle-evict
  "Evict context-store entries for the completing agent.
   EDN handler key: :evict

   Degraded mode (continue policy): if evict-fn throws, marks :eviction as
   {:skipped true :degraded true}; wrap continues to :end."
  [resources data]
  (support/handle-evict resources data support/continue))

(defn handle-end
  "Terminal state handler. Returns final wrap summary.
   EDN handler key: :end

   Includes :notify-degraded/:notify-error when the notify step ran in
   degraded mode (see 'Git Commit Optional' decision 20260213005110)."
  [_resources {:keys [data]}]
  (select-keys data [:agent-id :project-id :crystal-result
                     :kg-result :notify-sent? :notify-degraded :notify-error
                     :eviction]))

(def handle-error
  "Error state handler. Captures error context and throws.
   EDN handler key: :error"
  (support/default-handle-error "Wrap session workflow error"
                                [:crystal-result :error]))

;; =============================================================================
;; Handler Map (for EDN spec registration in workflow registry)
;; =============================================================================

(def handler-map
  "Maps EDN keyword handlers to implementation functions.
   Used by registry/register-handlers! for EDN spec compilation."
  {:start       handle-start
   :gather      handle-gather
   :crystallize handle-crystallize
   :kg-edges    handle-kg-edges
   :notify      handle-notify
   :evict       handle-evict
   :end         handle-end
   :error       handle-error})

;; =============================================================================
;; In-Code FSM Spec (inline functions, no EDN needed)
;; =============================================================================

(def wrap-session-spec
  "hive-events FSM spec for the wrap session workflow.
   Uses inline functions -- no handler-map needed at compile time.

   State graph:
   ```
   ::fsm/start --> ::gather --> ::crystallize -+--> ::kg-edges --> ::notify --> ::evict --> ::end
                                               |
                                               +--> ::error (crystal error)
   ```"
  {:fsm
   {::fsm/start
    {:handler    handle-start
     :dispatches [[::gather (fn [data] (and (:agent-id data)
                                            (not (:error data))))]
                  [::fsm/error (fn [data] (some? (:error data)))]]}

    ::gather
    {:handler    handle-gather
     :dispatches [[::crystallize harvested?]
                  [::fsm/error always]]}

    ::crystallize
    {:handler    handle-crystallize
     :dispatches [[::fsm/error crystal-error?]
                  [::kg-edges always]]}

    ::kg-edges
    {:handler    handle-kg-edges
     :dispatches [[::notify always]]}

    ::notify
    {:handler    handle-notify
     :dispatches [[::evict always]]}

    ::evict
    {:handler    handle-evict
     :dispatches [[::fsm/end always]]}

    ::fsm/end
    {:handler handle-end}

    ::fsm/error
    {:handler handle-error}}

   :opts
   {:max-trace 50

    :pre support/trace-log-enter}})

;; =============================================================================
;; Compilation & Execution API
;; =============================================================================

(defn compile-wrap
  "Compile the wrap session FSM spec. Call once, reuse the compiled FSM."
  []
  (fsm/compile wrap-session-spec))

(defn run-wrap
  "Execute a compiled wrap session FSM.

   Args:
     compiled-fsm -- Result of compile-wrap
     resources    -- Map of side-effect functions and config
     opts         -- Optional initial data overrides

   Returns:
     Final data map with wrap results."
  ([compiled-fsm resources]
   (run-wrap compiled-fsm resources {}))
  ([compiled-fsm resources opts]
   (fsm/run compiled-fsm
            resources
            {:data (merge {:agent-id nil
                           :directory nil
                           :project-id nil}
                          opts)})))

(defn run-wrap-session
  "Convenience: compile and run a single wrap session.

   Example:
   ```clojure
   (run-wrap-session
     {:harvest-fn     (fn [dir] (collect/harvest-all {:directory dir}))
      :crystallize-fn (fn [h] (synthesis/synthesize h))
      :kg-edge-fn     create-derived-from-edges!
      :notify-fn      emit-wrap-notify!
      :evict-fn       evict-agent-context!
      :source-ids-fn  extract-source-ids
      :scope-fn       scope/get-current-project-id
      :directory      \"/home/user/project\"
      :agent-id       \"swarm-worker-123\"})
   ```"
  ([resources]
   (run-wrap-session resources {}))
  ([resources opts]
   (run-wrap (compile-wrap) resources opts)))
