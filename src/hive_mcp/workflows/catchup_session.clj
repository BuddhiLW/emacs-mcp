(ns hive-mcp.workflows.catchup-session
  "hive-events FSM handlers for the Catchup workflow.

   The catchup workflow restores session context from Chroma memory,
   runs optional addon hooks, maintenance tasks, and delivers entries
   via piggyback + context-store for pass-by-reference.

   This is the Clojure handler implementation matching resources/fsm/catchup.edn.
   The EDN spec uses keyword handlers (:start, :scope-resolve, :query-memory, etc.)
   that are resolved to these functions at compile time via the handler-map.

   Design constraints (same as forge-belt, wrap-session):
   - Handlers are PURE functions: (resources, data) -> data'
   - Side effects flow through the resources map (territory)
   - The FSM is the map -- deterministic state transitions
   - Dispatch predicates are pure functions of state data

   Resources map (injected at run time):
     :chroma-check-fn      -- () -> bool (embedding configured?)
     :scope-fn             -- (directory) -> project-id
     :project-name-fn      -- (directory) -> string
     :query-fn             -- (type, tags, project-id, limit) -> entries
     :query-axioms-fn      -- (project-id) -> entries
     :query-conventions-fn -- (project-id, axiom-ids, priority-ids) -> entries
     :query-expiring-fn    -- (project-id, limit) -> entries
     :git-fn               -- (directory) -> git-info
     :entry->meta-fns      -- {:axiom fn, :priority fn, :catchup fn}
     :addon-fn             -- optional, addon-provided
     :permeate-fn          -- (directory) -> {:permeated N :agents [...]}
     :tree-scan-fn         -- (directory) -> scan-result
     :disc-decay-fn        -- (project-id) -> decay-stats
     :piggyback-fn         -- (agent-id, project-id, entries, refs) -> nil
     :context-store-fn     -- (data, tags, ttl) -> ctx-id
     :build-scopes-fn      -- (project-name, project-id) -> scopes
     :build-response-fn    -- (data) -> response
     :error-response-fn    -- (error) -> response

   State data shape:
     {:directory            string    ;; working directory (input)
      :project-id           string    ;; resolved project scope
      :project-name         string    ;; display name
      :scopes               vector    ;; scope tags for display
      :chroma-configured?   bool      ;; prerequisite check

      ;; Raw Chroma entries
      :axioms               vector
      :priority-conventions vector
      :sessions             vector
      :decisions            vector
      :conventions          vector
      :snippets             vector
      :expiring             vector

      ;; Git context
      :git-info             map       ;; {:branch :uncommitted :last-commit}

      ;; Metadata transforms
      :axioms-meta          vector
      :priority-meta        vector
      :sessions-meta        vector
      :decisions-meta       vector
      :conventions-meta     vector
      :snippets-meta        vector
      :expiring-meta        vector

      ;; Addon-provided
      :addon-data           map

      ;; Maintenance results
      :permeation           map       ;; {:permeated N :agents [...]}
      :project-tree-scan    map
      :disc-decay           map

      ;; Delivery
      :context-refs         map       ;; category->ctx-id
      :piggyback-enqueued?  bool

      ;; Error
      :error                any}"

  (:require [hive.events.fsm :as fsm]
            [hive-mcp.dns.result :as result]
            [hive-mcp.concurrency.pool :as pool]
            [hive-dsl.context.identity :as ctx-id]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Resource Call Helpers (eliminate when-fn branching)
;; =============================================================================

(defn- call-resource
  "Call optional resource function from resources map.
   Returns nil if fn key not present in resources."
  [resources k & args]
  (when-let [f (get resources k)]
    (apply f args)))

(defn- safe-call
  "Call optional resource fn with error recovery.
   Returns fallback on nil fn or exception."
  [resources k args fallback]
  (if-let [f (get resources k)]
    (try (apply f args)
         (catch Exception _e fallback))
    fallback))

;; =============================================================================
;; Parallel Execution Helpers
;; =============================================================================

(def ^:private ^:const query-timeout-ms
  "Timeout for individual parallel query futures (matches production catchup.clj)."
  15000)

(defn- safe-deref
  "Deref a future with timeout-ms. Returns default on timeout or exception."
  [fut timeout-ms default]
  (try
    (let [result (deref fut timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do (future-cancel fut)
            (log/debug "catchup-fsm: parallel query timed out")
            default)
        result))
    (catch Exception e
      (log/debug "catchup-fsm: parallel deref failed:" (.getMessage e))
      default)))

;; =============================================================================
;; Data-Driven Specs
;; =============================================================================

(def ^:private standard-queries
  "Standard query-fn calls: [data-key type tags limit].
   These all use the generic :query-fn resource."
  [[:priority-conventions "convention" ["catchup-priority"] 50]
   [:sessions            "note"       ["session-summary"]  10]
   [:decisions           "decision"   nil                  50]
   [:snippets            "snippet"    nil                  20]])

(def ^:private context-categories
  "Categories to cache in context-store during delivery."
  [:axioms :priority-conventions :sessions :decisions :conventions :snippets])

(def ^:private meta-transforms
  "Entry metadata transforms: [target-key source-key meta-fn-key].
   Applied in handle-transform to produce *-meta keys."
  [[:axioms-meta      :axioms              :axiom]
   [:priority-meta    :priority-conventions :priority]
   [:sessions-meta    :sessions            :catchup]
   [:snippets-meta    :snippets            :catchup]
   [:expiring-meta    :expiring            :catchup]
   [:decisions-base   :decisions           :catchup]
   [:conventions-base :conventions         :catchup]])

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn- run-standard-queries
  "Run data-driven standard queries via :query-fn resource IN PARALLEL.
   Fires all 4 independent queries as futures and collects with timeout.
   Returns map of {category-key -> entries-vector}."
  [query-fn project-id]
  (if-not query-fn
    ;; No query-fn: return empty vectors for all categories
    (reduce (fn [acc [data-key _ _ _]] (assoc acc data-key []))
            {} standard-queries)
    ;; Fire all standard queries as parallel futures (bounded by IO pool)
    (let [futures (mapv (fn [[data-key type tags limit]]
                          [data-key (pool/with-io (or (query-fn type tags project-id limit) []))])
                        standard-queries)]
      (reduce (fn [acc [data-key fut]]
                (assoc acc data-key (safe-deref fut query-timeout-ms [])))
              {} futures))))

(defn- run-special-queries
  "Run queries that need dedicated resource fns, with parallelism for independent queries.

   Dependency graph:
   - axioms:      independent (fires as future)
   - expiring:    independent (fires as future)
   - conventions: DEPENDS on axiom-ids + priority-convention-ids (Wave 2, sequential)

   Axioms and expiring fire in parallel (Wave 1), then conventions runs
   after axiom results are available (Wave 2)."
  [resources project-id standard-results]
  (let [;; Wave 1: Fire independent queries in parallel (bounded by IO pool)
        f-axioms   (pool/with-io (or (call-resource resources :query-axioms-fn project-id) []))
        f-expiring (pool/with-io (or (call-resource resources :query-expiring-fn project-id 20) []))

        ;; Wave 1: Collect independent results
        axioms   (safe-deref f-axioms query-timeout-ms [])
        expiring (safe-deref f-expiring query-timeout-ms [])

        ;; Wave 2: Dependent query (needs axiom-ids + priority-convention-ids)
        axiom-ids   (set (map :id axioms))
        pc-ids      (set (map :id (:priority-conventions standard-results)))
        conventions (or (call-resource resources :query-conventions-fn
                                       project-id axiom-ids pc-ids)
                        [])]
    {:axioms axioms
     :conventions conventions
     :expiring expiring}))

;; =============================================================================
;; Transform Helpers
;; =============================================================================

(defn- apply-meta-transforms
  "Apply entry->meta transforms from resource fns, driven by meta-transforms spec.
   Each spec entry [target source meta-key] produces target from mapv(meta-fn, source)."
  [entry->meta-fns data]
  (reduce (fn [acc [target-key source-key meta-fn-key]]
            (let [meta-fn (or (get entry->meta-fns meta-fn-key) identity)]
              (assoc acc target-key (mapv meta-fn (get data source-key)))))
          {}
          meta-transforms))

;; =============================================================================
;; Delivery Helpers
;; =============================================================================

(defn- store-context-categories
  "Cache entry categories in context-store. Returns {category -> ctx-id} map.
   Uses reduce over context-categories spec instead of branching per category."
  [context-store-fn data project-id ttl]
  (when context-store-fn
    (try
      (reduce (fn [refs cat]
                (let [entries (get data cat)]
                  (if (seq entries)
                    (assoc refs cat
                           (context-store-fn entries
                                             #{"catchup" (name cat) (or project-id "global")}
                                             ttl))
                    refs)))
              {} context-categories)
      (catch Exception _e nil))))

;; =============================================================================
;; Handlers (pure functions: resources x data -> data')
;;
;; EDN handler-map keys: :start, :scope-resolve, :query-memory, :transform,
;;                       :addon-pass, :maintenance, :deliver, :end, :error
;; =============================================================================

(defn handle-start
  "Check prerequisites and initialize state.
   EDN handler key: :start

   Checks if Chroma is configured via :chroma-check-fn resource.
   Sets :chroma-configured? for the dispatch predicate."
  [resources data]
  (let [chroma-check-fn (or (:chroma-check-fn resources) (constantly false))
        directory       (or (:directory data) (:directory resources))]
    (assoc data
           :directory directory
           :chroma-configured? (boolean (chroma-check-fn))
           :error nil)))

(defn handle-scope-resolve
  "Resolve project scope from directory.
   EDN handler key: :scope-resolve

   Uses call-resource to eliminate nil-fn guards."
  [resources data]
  (let [directory    (:directory data)
        project-id   (when directory (call-resource resources :scope-fn directory))
        project-name (when directory (call-resource resources :project-name-fn directory))
        scopes       (call-resource resources :build-scopes-fn project-name project-id)]
    (assoc data
           :project-id project-id
           :project-name project-name
           :scopes scopes)))

(defn handle-query-memory
  "Query all Chroma memory categories with project scoping IN PARALLEL.
   EDN handler key: :query-memory

   Uses Result DSL: wraps all queries in try-effect* for railway error handling.
   Standard queries (4 categories via :query-fn) fire as parallel futures.
   Special queries (axioms, expiring) fire in parallel, then conventions
   runs sequentially (depends on axiom-ids + priority-convention-ids)."
  [resources data]
  (let [r (result/try-effect* :catchup/query-failed
                              (let [project-id (:project-id data)
                                    standard   (run-standard-queries (:query-fn resources) project-id)
                                    special    (run-special-queries resources project-id standard)]
                                (merge standard special)))]
    (if (result/ok? r)
      (merge data (:ok r) {:query-failed? false})
      (assoc data
             :query-failed? true
             :error (str "Query failed: " (:message r))))))

(defn handle-transform
  "Transform raw entries to metadata and fetch git info.
   EDN handler key: :transform"
  [resources data]
  (let [git-info   (when-let [dir (:directory data)]
                     (call-resource resources :git-fn dir))
        transforms (apply-meta-transforms (:entry->meta-fns resources) data)]
    (merge data transforms {:git-info git-info})))

(defn handle-addon-pass
  "Addon processing step. Delegates to :addon-fn if provided.
   EDN handler key: :addon-pass

   Gracefully degrades to pass-through when addon not loaded."
  [resources data]
  (if-let [f (:addon-fn resources)]
    (let [result (safe-deref
                  (pool/with-io (f data))
                  query-timeout-ms nil)]
      (if result
        (merge data result)
        data))
    data))

(defn handle-maintenance
  "Run maintenance tasks IN PARALLEL using futures for graceful error recovery.
   EDN handler key: :maintenance

   All three tasks (permeation, tree-scan, disc-decay) are independent.
   Fires all as futures, collects with timeout and fallback defaults."
  [resources data]
  (let [directory  (:directory data)
        project-id (:project-id data)
        ;; Fire all independent maintenance tasks in parallel (bounded by IO pool)
        f-permeation  (pool/with-io (safe-call resources :permeate-fn
                                               [directory]
                                               {:permeated 0 :error "permeation failed"}))
        f-tree-scan   (pool/with-io (safe-call resources :tree-scan-fn
                                               [(or directory ".")]
                                               {:scanned false :error "tree scan failed"}))
        f-disc-decay  (pool/with-io (safe-call resources :disc-decay-fn
                                               [project-id]
                                               {:updated 0 :skipped 0 :errors 1}))]
    (assoc data
           :permeation (or (safe-deref f-permeation query-timeout-ms
                                       {:permeated 0 :error "permeation timed out"})
                           {:permeated 0})
           :project-tree-scan (safe-deref f-tree-scan query-timeout-ms
                                          {:scanned false :error "tree scan timed out"})
           :disc-decay (safe-deref f-disc-decay query-timeout-ms
                                   {:updated 0 :skipped 0 :errors 1}))))

(defn handle-deliver
  "Enqueue piggyback entries and cache in context-store.
   EDN handler key: :deliver

   Uses store-context-categories helper for data-driven context caching.
   Also performs cursor hygiene: adopts previous coordinator's cursor and
   cleans up orphaned buffers from dead bb-mcp instances.

   Memory piggyback is session-scoped (caller-id only).
   Hivemind cursor hygiene keeps project scope (piggyback-id)."
  [resources data]
  (let [project-id        (:project-id data)
        context-refs      (store-context-categories (:context-store-fn resources)
                                                    data project-id 600000)
        piggyback-entries (into (vec (:axioms data)) (:priority-conventions data))
        caller-id         (or (:_caller_id data) "coordinator")
        piggyback-id      (ctx-id/make-piggyback-agent-id
                           (ctx-id/parse-caller-id caller-id)
                           (ctx-id/parse-project-scope project-id))]
    ;; Cursor hygiene: adopt previous cursor + clean up stale state (hivemind — project-scoped)
    (when-let [cursor-adopt-fn (:cursor-adopt-fn resources)]
      (try (cursor-adopt-fn piggyback-id project-id) (catch Exception _)))
    ;; Memory piggyback: session-scoped (caller-id only, no project dimension)
    (when (and (:piggyback-fn resources) (seq piggyback-entries))
      ((:piggyback-fn resources) caller-id piggyback-entries context-refs))
    (assoc data
           :context-refs context-refs
           :piggyback-enqueued? (boolean (seq piggyback-entries)))))

(defn handle-end
  "Terminal state handler. Format and return final catchup response.
   EDN handler key: :end"
  [resources {:keys [data]}]
  (if-let [build-response-fn (:build-response-fn resources)]
    (build-response-fn data)
    ;; Default: return summary data
    (select-keys data [:project-id :project-name :scopes :git-info
                       :permeation :context-refs :piggyback-enqueued?
                       :axioms-meta :priority-meta :sessions-meta
                       :decisions-meta :conventions-meta :snippets-meta
                       :expiring-meta :addon-data
                       :project-tree-scan :disc-decay])))

(defn handle-error
  "Error state handler. Captures error context.
   EDN handler key: :error"
  [resources {:keys [error data] :as _fsm}]
  (if-let [error-response-fn (:error-response-fn resources)]
    (error-response-fn (or error (:error data)))
    (throw (ex-info "Catchup workflow error"
                    {:data (select-keys data [:directory :project-id :error])
                     :error error}))))

;; =============================================================================
;; Handler Map (for EDN spec registration in workflow registry)
;; =============================================================================

(def handler-map
  "Maps EDN keyword handlers to implementation functions.
   Used by registry/register-handlers! for EDN spec compilation."
  {:start         handle-start
   :scope-resolve handle-scope-resolve
   :query-memory  handle-query-memory
   :transform handle-transform
   :addon-pass    handle-addon-pass
   :maintenance   handle-maintenance
   :deliver       handle-deliver
   :end           handle-end
   :error         handle-error})

;; =============================================================================
;; In-Code FSM Spec (inline functions, no EDN needed)
;; =============================================================================

(defn always [_data] true)

(def catchup-session-spec
  "hive-events FSM spec for the catchup session workflow.
   Uses inline functions -- no handler-map needed at compile time.

   State graph:
   ```
   ::fsm/start --> ::scope-resolve -+--> ::query-memory --> ::transform
                                    |        |
                                    |        +--> ::error (query failed)
                                    |
                                    +--> ::error (no chroma / no project-id)
   ::transform --> ::addon-pass --> ::maintenance --> ::deliver --> ::end
   ```"
  {:fsm
   {::fsm/start
    {:handler    handle-start
     :dispatches [[::scope-resolve (fn [data] (true? (:chroma-configured? data)))]
                  [::fsm/error (fn [data] (not (:chroma-configured? data)))]]}

    ::scope-resolve
    {:handler    handle-scope-resolve
     :dispatches [[::query-memory (fn [data] (some? (:project-id data)))]
                  [::fsm/error always]]}

    ::query-memory
    {:handler    handle-query-memory
     :dispatches [[::transform (fn [data] (not (:query-failed? data)))]
                  [::fsm/error (fn [data] (true? (:query-failed? data)))]]}

    ::transform
    {:handler    handle-transform
     :dispatches [[::addon-pass always]]}

    ::addon-pass
    {:handler    handle-addon-pass
     :dispatches [[::maintenance always]]}

    ::maintenance
    {:handler    handle-maintenance
     :dispatches [[::deliver always]]}

    ::deliver
    {:handler    handle-deliver
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

(defn compile-catchup
  "Compile the catchup session FSM spec. Call once, reuse the compiled FSM."
  []
  (fsm/compile catchup-session-spec))

(defn run-catchup
  "Execute a compiled catchup session FSM.

   Args:
     compiled-fsm -- Result of compile-catchup
     resources    -- Map of side-effect functions and config
     opts         -- Optional initial data overrides

   Returns:
     Final data map with catchup results."
  ([compiled-fsm resources]
   (run-catchup compiled-fsm resources {}))
  ([compiled-fsm resources opts]
   (fsm/run compiled-fsm
            resources
            {:data (merge {:directory nil
                           :chroma-configured? false
                           :query-failed? false}
                          opts)})))

(defn run-catchup-session
  "Convenience: compile and run a single catchup session.

   Example:
   ```clojure
   (run-catchup-session
     {:chroma-check-fn  chroma/embedding-configured?
      :scope-fn         scope/get-current-project-id
      :project-name-fn  catchup-scope/get-current-project-name
      :query-fn         catchup-scope/query-scoped-entries
      :query-axioms-fn  catchup-scope/query-axioms
      :query-conventions-fn catchup-scope/query-regular-conventions
      :query-expiring-fn catchup-scope/query-expiring-entries
      :git-fn           catchup-git/gather-git-info
      :entry->meta-fns  {:axiom fmt/entry->axiom-meta
                          :priority fmt/entry->priority-meta
                          :catchup #(fmt/entry->catchup-meta % 80)}
      ;; :addon-fn — optional
      :permeate-fn      permeation/auto-permeate-wraps
      :tree-scan-fn     project-tree/maybe-scan-project-tree!
      :disc-decay-fn    #(disc/apply-time-decay-to-all-discs! :project-id %)
      :piggyback-fn     memory-piggyback/enqueue!
      :context-store-fn context-store/context-put!
      :build-scopes-fn  fmt/build-scopes
      :build-response-fn fmt/build-catchup-response
      :error-response-fn fmt/catchup-error}
     {:directory \"/home/user/project\"})
   ```"
  ([resources]
   (run-catchup-session resources {}))
  ([resources opts]
   (run-catchup (compile-catchup) resources opts)))
