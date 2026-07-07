(ns hive-mcp.workflows.support
  "Shared boundary/interceptor primitives for the workflow FSM subsystem.

  Owns the try/catch/log envelope duplication that had accreted across the SAA,
  wrap-session, complete-session and catchup-session FSM handlers plus the
  spawn/drone dispatch sites:

    IDegradePolicy  — the ONE strategy seam distinguishing a FATAL caught
                      throwable (stamp a top-level :error, route to ::fsm/error)
                      from a DEGRADE-CONTINUE one (merge a per-site delta, no
                      fatal :error). Two zero-field records + two singletons;
                      policy = nil is the third (hard-propagate, no catch) mode.
    boundary-step   — the invariant guard -> try effect -> success | throw ->
                      policy | absent envelope.
    now-str         — the single injectable-clock seam (:clock-fn resources).
    trace-log-enter/exit — clock-injected :pre/:post trace interceptors.
    shout!          — the rescue-guarded always-fires non-critical hook.
    always          — the trivial dispatch predicate.
    default-handle-error — factory for the wrap+complete terminal throw.
    resolve-session-identity — the agent-id/directory/project-id triad.
    handle-evict    — the shared evict boundary step (fatal vs degrade by arg).
    mark-task-inprogress! / mark-tasks-inprogress! — kanban in-progress marker
                      with the store (update-fn) INJECTED so support stays
                      strictly below the tools stratum.

  LAYERING: depends only on taoensso.timbre + hive-mcp.dns.result. Every seam
  handed to fsm/compile stays a bare var/fn (records live only inside
  boundary-step's catch), so keyword-resolvability is preserved. Handlers stay
  (resources data) -> data' (terminals (resources fsm) -> result); this ns is
  called FROM WITHIN handler bodies."
  (:require [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))

(defn- log-at
  "Dispatch a RUNTIME log level to timbre's compile-time level macros. Avoids the
   timbre runtime-level pitfall and lets a site pick its level."
  [level msg arg]
  (case level
    :error (log/error msg arg)
    :warn  (log/warn msg arg)
    :info  (log/info msg arg)
    :debug (log/debug msg arg)
    (log/warn msg arg)))

(defprotocol IDegradePolicy
  "Strategy for folding a caught boundary-step Throwable into data. boundary-step's
   catch calls (apply-degrade policy data ex spec) uniformly; it never branches on
   which impl it holds (LSP)."
  (apply-degrade [policy data ex spec]
    "Return data' after logging + merging the per-site `spec` delta for `ex`."))

(defrecord FatalError []
  IDegradePolicy
  (apply-degrade [_ data ex {:keys [log-msg label degrade-keys]}]
    ;; All SAA fatal sites log :error with a {:error msg} map, then stamp a
    ;; top-level :error so has-error?/context-not-loaded?/plan-nil-with-error?
    ;; route to ::fsm/error. log-msg and label are INDEPENDENT strings.
    (log/error log-msg {:error (ex-message ex)})
    (merge data degrade-keys {:error (str label " failed: " (ex-message ex))})))

(defrecord DegradeContinue []
  IDegradePolicy
  (apply-degrade [_ data ex {:keys [log-msg log-level log-arg delta]
                             :or {log-level :warn log-arg ex-message}}]
    ;; Never stamps a fatal top-level :error. delta (map or ex->map) carries
    ;; exactly the markers that site's predicate reads. log-arg defaults to
    ;; ex-message (wrap's positional-STRING log); SAA domain sites pass
    ;; (fn [ex] {:error (ex-message ex)}) for the map-arg log. log-level is a
    ;; per-site param because level does NOT predict policy.
    (log-at log-level log-msg (log-arg ex))
    (merge data (if (fn? delta) (delta ex) delta))))

(def fatal
  "Shared FatalError policy singleton (records are values; no per-call alloc)."
  (->FatalError))

(def continue
  "Shared DegradeContinue policy singleton."
  (->DegradeContinue))

(defn boundary-step
  "Invariant effect envelope: guard -> try effect -> on-success | on-throw ->
   policy | absent. step keys:
     :present? (default true) — caller-computed guard.
     :run      (fn data->data') — success leg (effect call + assoc; may itself
               branch and emit a non-throwing degrade, e.g. abstract nil-plan).
     :absent   (fn data->data', default identity) — no-fn / guard-false leg.
     :policy   IDegradePolicy or nil — nil => NO catch (throw propagates, e.g.
               complete-session hard-fatal); non-nil => catch Throwable and
               delegate to apply-degrade with the ORIGINAL pre-effect data (a
               partial success assoc is discarded, matching hand-written catch).
     :spec     per-site descriptor consumed only by the policy."
  [data {:keys [run absent policy spec] :or {absent identity} :as step}]
  (if (get step :present? true)
    (if policy
      (try (run data) (catch Throwable ex (apply-degrade policy data ex spec)))
      (run data))
    (absent data)))

(defn now-str
  "Current time as a string via the injectable clock (:clock-fn resources),
   defaulting to java.time.Instant/now. The single clock seam; default output is
   byte-identical to the old direct (str (java.time.Instant/now)) calls."
  [resources]
  (str ((or (:clock-fn resources) #(java.time.Instant/now)))))

(defn shout!
  "Rescue-guarded always-fires non-critical hook (ex saa maybe-shout!). A shout
   failure never aborts a step; resolves :shout-fn from resources."
  [resources agent-id phase message]
  (when-let [f (:shout-fn resources)]
    (rescue nil (f agent-id phase message))))

(defn always
  "Trivial dispatch predicate — always true. Single home for the copies in
   wrap/complete/catchup/saa.predicates."
  [_data] true)

(defn trace-log-enter
  "Clock-injected :pre interceptor (fsm resources)->fsm appending an :enter trace
   entry. Replaces the byte-identical inline :pre in wrap/complete/catchup and
   saa.predicates' direct-clock version; :at now comes from now-str."
  [{:keys [current-state-id] :as fsm} resources]
  (update-in fsm [:data :trace-log] (fnil conj [])
             {:state current-state-id :at (now-str resources) :direction :enter}))

(defn trace-log-exit
  "Clock-injected :post interceptor twin appending an :exit trace entry."
  [{:keys [current-state-id] :as fsm} resources]
  (update-in fsm [:data :trace-log] (fnil conj [])
             {:state current-state-id :at (now-str resources) :direction :exit}))

(defn default-handle-error
  "Factory returning a terminal (resources fsm)->throws handler for the wrap+complete
   near-identical throw. message + data-keys differ per site. SAA's richer
   handle-error (log + shout + error-response-fn branch) is UNIQUE, not duplicated,
   and stays in place."
  [message data-keys]
  (fn [_resources {:keys [error data]}]
    (throw (ex-info message
                    {:agent-id (:agent-id data)
                     :data (select-keys data data-keys)
                     :error error}))))

(defn resolve-session-identity
  "Resolve the {:agent-id :directory :project-id} triad with data-then-resources
   fallback. Options parameterize the real per-site variations:
     :derive-project? (default true) — when false, :project-id is nil and scope-fn
                       is NOT called (complete-session, which ignores :project-id).
     :rescue-scope?   (default false) — when true, scope-fn is rescue-guarded (saa).
     :project-default (default nil)   — value when scope-fn yields nil (saa \"unknown\").
   Assocs nothing itself; the caller composes the returned keys it needs."
  ([resources data] (resolve-session-identity resources data nil))
  ([resources data {:keys [derive-project? rescue-scope? project-default]
                    :or {derive-project? true}}]
   (let [scope-fn  (or (:scope-fn resources) (constantly nil))
         directory (or (:directory data) (:directory resources))
         agent-id  (or (:agent-id data) (:agent-id resources))
         raw       (when (and derive-project? directory)
                     (if rescue-scope?
                       (rescue nil (scope-fn directory))
                       (scope-fn directory)))]
     {:agent-id agent-id
      :directory directory
      :project-id (when derive-project? (or raw project-default))})))

(defn handle-evict
  "Shared evict boundary step. `policy` selects the fatal-vs-degrade twin:
     nil        (complete-session) => NO catch, a thrown evict-fn propagates.
     `continue` (wrap-session)     => catch => degraded :eviction.
   The no-fn and success shapes are identical across both."
  [resources data policy]
  (let [evict-fn (:evict-fn resources)
        agent-id (:agent-id data)]
    (boundary-step data
      {:present? (some? evict-fn)
       :run     (fn [d] (assoc d :eviction (evict-fn agent-id)))
       :absent  (fn [d] (assoc d :eviction {:evicted 0 :skipped true}))
       :policy  policy
       :spec    {:log-msg "wrap-session: evict failed — continuing in degraded mode:"
                 :delta (fn [ex] {:eviction {:evicted 0 :skipped true
                                             :degraded true :error (ex-message ex)}})}})))

(defn mark-task-inprogress!
  "Mark a single kanban task in-progress. `update-fn` is INJECTED (callers pass
   c-kanban/handle-kanban) so support stays below the tools stratum. Skips nil
   task-id; rescue-guarded (a kanban failure is non-fatal)."
  [update-fn directory task-id]
  (when task-id
    (rescue nil
            (update-fn {:command "update" :task_id task-id
                        :new_status "inprogress" :directory directory}))))

(defn mark-tasks-inprogress!
  "Batch in-progress marker with per-item rescue (one bad task never aborts the rest).
   By default skips nil ids (matches drone's :when task-id and spawn's when-let).
   Pass {:skip-nil? false} to attempt EVERY (:id task) including nil — the
   orchestrator's original UNGUARDED behavior (a nil :task_id update is a rescued
   no-op). Does NOT delegate to the nil-skipping single fn, so skip-nil? false is
   byte-exact."
  ([update-fn directory tasks] (mark-tasks-inprogress! update-fn directory tasks nil))
  ([update-fn directory tasks {:keys [skip-nil?] :or {skip-nil? true}}]
   (doseq [task tasks
           :let [task-id (:id task)]
           :when (or (not skip-nil?) task-id)]
     (rescue nil
             (update-fn {:command "update" :task_id task-id
                         :new_status "inprogress" :directory directory})))))
