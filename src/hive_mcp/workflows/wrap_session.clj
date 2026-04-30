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
            [taoensso.timbre :as log]))
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

(defn always [_data] true)

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
  (let [scope-fn (or (:scope-fn resources) (constantly nil))
        directory (or (:directory data) (:directory resources))
        agent-id (or (:agent-id data) (:agent-id resources))]
    (assoc data
           :agent-id agent-id
           :directory directory
           :project-id (when directory (scope-fn directory))
           :error nil)))

(defn handle-gather
  "Harvest session data for crystallization.
   EDN handler key: :gather

   Uses resources:
     :harvest-fn (fn [directory] -> harvested-data)

   Degraded mode: if harvest-fn throws (e.g. nREPL/HTTP down), logs a
   warning, marks :harvested as {:degraded true}, and attaches an empty
   placeholder so the FSM can continue. Mirrors the 'Git Commit Optional'
   decision (20260213005110)."
  [resources data]
  (let [harvest-fn (:harvest-fn resources)
        directory (:directory data)]
    (try
      (let [harvested (harvest-fn directory)]
        (assoc data :harvested harvested))
      (catch Throwable t
        (log/warn "wrap-session: harvest failed — continuing in degraded mode:"
                  (ex-message t))
        (assoc data :harvested {:degraded true
                                :error (ex-message t)
                                :progress-notes []
                                :completed-tasks []
                                :git-commits []
                                :summary {}})))))

(defn handle-crystallize
  "Crystallize harvested session data into long-term memory.
   EDN handler key: :crystallize

   Uses resources:
     :crystallize-fn (fn [harvested] -> {:summary-id str, :stats map, ...})
     :source-ids-fn  (fn [harvested] -> [string])

   Degraded mode: if crystallize-fn throws (e.g. nREPL down, HTTP failure
   to Chroma/embedding service), logs a warning and produces a
   :crystal-result marked :skipped/:degraded so the wrap still succeeds.
   Mirrors the 'Git Commit Optional' decision (20260213005110)."
  [resources data]
  (let [crystallize-fn (:crystallize-fn resources)
        source-ids-fn (or (:source-ids-fn resources) (constantly []))
        harvested (:harvested data)]
    (try
      (let [result (crystallize-fn harvested)
            source-ids (source-ids-fn harvested)]
        (assoc data
               :crystal-result result
               :source-ids source-ids))
      (catch Throwable t
        (log/warn "wrap-session: crystallize failed — continuing in degraded mode:"
                  (ex-message t))
        (assoc data
               :crystal-result {:skipped true
                                :degraded true
                                :error (ex-message t)
                                :stats {}}
               :source-ids [])))))

(defn handle-kg-edges
  "Create :derived-from KG edges linking summary to source entries.
   EDN handler key: :kg-edges

   Uses resources:
     :kg-edge-fn (fn [summary-id source-ids project-id agent-id] -> {:created-count N})

   Degraded mode: if kg-edge-fn throws (e.g. nREPL/HTTP down), marks
   :kg-result as {:skipped true :degraded true} and keeps the flow going."
  [resources data]
  (let [{:keys [project-id agent-id]} data
        summary-id (get-in data [:crystal-result :summary-id])
        source-ids (:source-ids data)
        kg-edge-fn (:kg-edge-fn resources)]
    (if (and kg-edge-fn summary-id (seq source-ids))
      (try
        (let [result (kg-edge-fn summary-id source-ids project-id agent-id)]
          (assoc data :kg-result result))
        (catch Throwable t
          (log/warn "wrap-session: kg-edges failed — continuing in degraded mode:"
                    (ex-message t))
          (assoc data :kg-result {:created-count 0
                                  :skipped true
                                  :degraded true
                                  :error (ex-message t)})))
      (assoc data :kg-result {:created-count 0 :skipped true}))))

(defn handle-notify
  "Emit wrap_notify event for hivemind permeation.
   EDN handler key: :notify

   Uses resources:
     :notify-fn (fn [agent-id session-id project-id stats] -> nil)

   Degraded mode: if notify-fn throws (e.g. NATS down, nREPL/HTTP down),
   :notify-sent? is false and :notify-error captures the reason; wrap
   still continues."
  [resources data]
  (let [notify-fn (:notify-fn resources)
        {:keys [agent-id project-id crystal-result]} data
        session-id (:session crystal-result)
        stats (if (map? (:stats crystal-result)) (:stats crystal-result) {})]
    (if notify-fn
      (try
        (notify-fn agent-id session-id project-id stats)
        (assoc data :notify-sent? true)
        (catch Throwable t
          (log/warn "wrap-session: notify failed — continuing in degraded mode:"
                    (ex-message t))
          (assoc data
                 :notify-sent? false
                 :notify-degraded true
                 :notify-error (ex-message t))))
      (assoc data :notify-sent? true))))

(defn handle-evict
  "Evict context-store entries for the completing agent.
   EDN handler key: :evict

   Uses resources:
     :evict-fn (fn [agent-id] -> {:evicted N})

   Degraded mode: if evict-fn throws, marks :eviction as
   {:skipped true :degraded true}; wrap continues to :end."
  [resources data]
  (let [evict-fn (:evict-fn resources)
        agent-id (:agent-id data)]
    (if evict-fn
      (try
        (let [result (evict-fn agent-id)]
          (assoc data :eviction result))
        (catch Throwable t
          (log/warn "wrap-session: evict failed — continuing in degraded mode:"
                    (ex-message t))
          (assoc data :eviction {:evicted 0
                                 :skipped true
                                 :degraded true
                                 :error (ex-message t)})))
      (assoc data :eviction {:evicted 0 :skipped true}))))

(defn handle-end
  "Terminal state handler. Returns final wrap summary.
   EDN handler key: :end

   Includes :notify-degraded/:notify-error when the notify step ran in
   degraded mode (see 'Git Commit Optional' decision 20260213005110)."
  [_resources {:keys [data]}]
  (select-keys data [:agent-id :project-id :crystal-result
                     :kg-result :notify-sent? :notify-degraded :notify-error
                     :eviction]))

(defn handle-error
  "Error state handler. Captures error context.
   EDN handler key: :error"
  [_resources {:keys [error data] :as _fsm}]
  (throw (ex-info "Wrap session workflow error"
                  {:agent-id (:agent-id data)
                   :data (select-keys data [:crystal-result :error])
                   :error error})))

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

    :pre
    (fn [{:keys [current-state-id] :as fsm} _resources]
      (update-in fsm [:data :trace-log] (fnil conj [])
                 {:state current-state-id
                  :at (str (java.time.Instant/now))
                  :direction :enter}))}})

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
     {:harvest-fn     (fn [dir] (crystal-hooks/harvest-all {:directory dir}))
      :crystallize-fn (fn [h] (crystal-hooks/crystallize-session h))
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
