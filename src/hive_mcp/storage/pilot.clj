;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.storage.pilot
  "Coordinated-commit pilot — STORAGE-4.5.

   Wires the new yggdrasil adapters (datahike + datalevin) into the
   workspace coordinator and triggers a `coordinated-commit!` whenever
   `:session/wrap` fires. The adapters were shipped in STORAGE-4.2 and
   STORAGE-4.3; this ns is the smallest end-to-end exercise of the
   workspace surface to validate the protocol shape against real
   stores.

   ## Lifecycle

   1. At boot (after slots are open), call `register-adapters!` with a
      map of `{slot-key → {:kind :datahike|:datalevin :handle ... :system-name ...}}`.
      Each entry is wrapped in the matching adapter and handed to
      `workspace/manage-system!`. Missing/closed slots are skipped with
      a WARN — `register-adapters!` never throws.

   2. Register the session-wrap handler with `register-wrap-handler!`.
      The handler is a `reg-event-fx` interceptor that fires AFTER the
      existing wrap-crystallize handler so a failure in coordinated-
      commit cannot block memory persistence.

   3. On `:session/wrap`, the handler calls `commit-on-wrap!` which
      builds a per-system commit-fn-map from the registered adapters
      and invokes `workspace/coordinated-commit!`.

   ## Degraded modes

   - workspace not started      → `commit-on-wrap!` returns `:degraded/no-workspace`
   - no adapters registered     → returns `:degraded/no-adapters`
   - yggdrasil dep missing      → workspace itself short-circuits; this
                                  ns surfaces `:degraded/coordinated-commit-nil`
   - one adapter throws         → other systems still commit; the
                                  failing adapter's snapshot-id is `nil`

   ## What this pilot does NOT do

   - Branch coordination on swarm-fork — see STORAGE-4.6.
   - Durable adapter registration across restarts — every boot needs
     `register-adapters!` to be called by the slot init path.
   - Backpressure on commit-fn-map size — fine for the 2-system pilot
     (Datahike :memory + Datalevin :carto); revisit for >5 systems.

   ## Testing

   No test harness is required for the pilot itself; smoke verification
   is the live `:session/wrap` flow. To dry-run:

       (pilot/register-adapters!
        {:memory {:kind :datahike  :handle conn  :system-name \"hk-mem\"}
         :carto  {:kind :datalevin :handle store :system-name \"dl-carto\"}})
       (pilot/commit-on-wrap! {:session-id \"smoke\"})

   Returns the coordinated-commit record, or one of the `:degraded/*`
   keywords above."
  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.events.interceptors :as interceptors]
            [hive-mcp.storage.adapters.datahike :as adh]
            [hive-mcp.storage.adapters.datalevin :as adl]
            [hive-mcp.storage.workspace :as ws]
            [yggdrasil.protocols :as ygp]
            [taoensso.timbre :as log]))

(defonce ^:private registered-systems
  ^{:doc "Atom of {slot-key → ygp-protocol-satisfying-record}. Populated by
          `register-adapters!`, consumed by `commit-on-wrap!`."}
  (atom {}))

(defn registered
  "Return the current registry snapshot — read-only. Useful for
   diagnostics and for tests that want to assert on the wired set."
  []
  @registered-systems)

(defn- build-adapter
  "Wrap a slot's storage handle in the matching yggdrasil adapter.
   Returns nil + WARN on unknown `:kind` so registration stays best-effort."
  [slot-key {:keys [kind handle system-name] :as spec}]
  (when (and handle system-name)
    (case kind
      :datahike  (adh/create-system {:conn   handle
                                     :system-name system-name})
      :datalevin (adl/create-system {:handle handle
                                     :system-name system-name})
      (do (log/warn "pilot: unknown adapter kind for slot"
                    {:slot slot-key :spec spec})
          nil))))

(defn register-adapters!
  "Wrap each `slot-spec` in the matching adapter, hand it to the
   workspace coordinator, and stash it locally so `commit-on-wrap!`
   can build the commit-fn-map. Idempotent — re-registering a slot
   replaces the previous adapter (and re-runs `manage-system!`).

   `specs` ::= `{slot-key {:kind :datahike|:datalevin
                           :handle <conn-or-store>
                           :system-name string}}`

   Returns the resulting registry map."
  [specs]
  (doseq [[slot-key spec] specs
          :let [adapter (build-adapter slot-key spec)]
          :when adapter]
    (ws/manage-system! adapter)
    (swap! registered-systems assoc slot-key adapter)
    (log/info "pilot: registered adapter"
              {:slot slot-key
               :system-id (ygp/system-id adapter)
               :system-type (ygp/system-type adapter)}))
  @registered-systems)

(defn unregister-adapters!
  "Drop every registered adapter from the workspace + local registry.
   Used at shutdown and in test teardown."
  []
  (let [snapshot @registered-systems]
    (doseq [adapter (vals snapshot)]
      (ws/unmanage-system! (ygp/system-id adapter)))
    (reset! registered-systems {})
    snapshot))

(defn- commit-fn-for
  "Default commit-fn delegated to the adapter: snapshot the current state
   and return the snapshot-id. Adapters that don't return a stable id
   from `snapshot-id` will fall back to the LSN/UUID their backend
   exposes — both are coordinator-safe."
  [adapter]
  (fn [_system]
    (try
      (ygp/snapshot-id adapter)
      (catch Throwable t
        (log/warn t "pilot: snapshot-id threw"
                  {:system-id (ygp/system-id adapter)})
        nil))))

(defn commit-on-wrap!
  "Build the commit-fn-map from the current registry and invoke
   `workspace/coordinated-commit!`. Returns the coordinated-commit
   record, or one of:

     :degraded/no-workspace   — workspace not started (yggdrasil missing
                                or `start-workspace!` never called)
     :degraded/no-adapters    — registry empty (boot path didn't wire)
     :degraded/coordinated-commit-nil — workspace up but
                                yggdrasil/coordinated-commit! returned nil
                                (see workspace.clj `coordinated-commit!`)

   `metadata` is forwarded for log context (session-id, agent-id, etc.) —
   the actual commit message is owned by yggdrasil's HLC."
  [metadata]
  (let [systems @registered-systems]
    (cond
      (not (ws/started?))
      (do (log/debug "pilot: workspace not started — coordinated-commit skipped"
                     {:metadata metadata})
          :degraded/no-workspace)

      (empty? systems)
      (do (log/warn "pilot: no adapters registered — coordinated-commit skipped"
                    {:metadata metadata})
          :degraded/no-adapters)

      :else
      (let [commit-fn-map (into {}
                                (for [[_slot adapter] systems]
                                  [(ygp/system-id adapter) (commit-fn-for adapter)]))
            result        (ws/coordinated-commit! commit-fn-map)]
        (if (some? result)
          (do (log/info "pilot: coordinated-commit OK"
                        {:metadata metadata
                         :systems (vec (keys commit-fn-map))})
              result)
          (do (log/warn "pilot: workspace coordinated-commit returned nil"
                        {:metadata metadata})
              :degraded/coordinated-commit-nil))))))

;; ---------------------------------------------------------------------------
;; Event wiring — fires AFTER the existing :session/wrap handler so a
;; failure in coordinated-commit cannot block memory persistence.
;; ---------------------------------------------------------------------------

(defn handle-session-wrap-coordinated-commit
  "Auxiliary handler for :session/wrap that delegates to
   `commit-on-wrap!`. Intentionally does NOT emit `:wrap-crystallize`
   or any other effect — the existing
   `hive-mcp.events.handlers.session/handle-session-wrap` owns that.
   This handler's only side-effect is to log an entry naming the
   coordinated-commit outcome."
  [_coeffects [_ {:keys [session-id slave-id project] :as ev-data}]]
  (let [outcome (commit-on-wrap! {:session-id session-id
                                  :slave-id   slave-id
                                  :project    project
                                  :event      :session/wrap})]
    {:log {:level   :info
           :message (str "pilot: coordinated-commit outcome=" (pr-str outcome)
                         " for session=" session-id)}
     :pilot/outcome {:outcome outcome :event-data ev-data}}))

(defn register-wrap-handler!
  "Register the coordinated-commit handler under a distinct event-id
   `:storage.pilot/session-wrapped` and ensure the existing
   `:session/wrap` handler dispatches into it. Splitting into a
   separate event-id avoids interceptor ordering subtleties with the
   primary wrap-crystallize handler."
  []
  (ev/reg-event :storage.pilot/session-wrapped
                [interceptors/debug]
                handle-session-wrap-coordinated-commit))

;; ---------------------------------------------------------------------------
;; Swarm-fork = workspace branch! — STORAGE-4.6
;;
;; Spawning a ling forks the workspace: every adapter under coordination
;; gets a per-ling branch so the ling's writes land on an isolated
;; commit graph. Wrap merges that branch back to :main where the
;; adapter's Mergeable surface allows; backends that lack merge
;; semantics (e.g. Datalevin in this iteration) keep the branch as a
;; labelled snapshot for observability and skip the merge phase.
;;
;; Branch naming is structural: `(keyword (str "ling/" ling-id))`. The
;; `ling/` prefix segregates ling branches from feature branches a
;; human might create via `ws/branch!` directly.
;; ---------------------------------------------------------------------------

(defn ling-branch-name
  "Derive the workspace branch keyword for `ling-id`. Pure — no side
   effects. Always returns a namespaced keyword so dispatch is cheap
   and the registry stays self-describing."
  [ling-id]
  (when-let [id (some-> ling-id str not-empty)]
    (keyword "ling" id)))

(defn- safe-branch!
  "Call ygp/branch! on `adapter`, swallowing per-adapter failures so one
   misbehaving backend can't poison the fan-out. Returns
   `{:slot kw :system-id str :branch kw :ok? bool :error str?}`."
  [slot-key adapter branch-kw]
  (try
    (ygp/branch! adapter branch-kw)
    {:slot      slot-key
     :system-id (ygp/system-id adapter)
     :branch    branch-kw
     :ok?       true}
    (catch Throwable t
      (log/warn t "pilot: branch! failed for slot — fan-out continues"
                {:slot slot-key
                 :system-id (ygp/system-id adapter)
                 :branch branch-kw})
      {:slot      slot-key
       :system-id (ygp/system-id adapter)
       :branch    branch-kw
       :ok?       false
       :error     (.getMessage t)})))

(defn- safe-merge!
  "Call ygp/merge! on `adapter` if it advertises :mergeable. Returns
   `{:slot :system-id :branch :merged? :error?}`. Adapters without
   Mergeable record `:merged? false` with a `:reason \"unsupported\"`."
  [slot-key adapter branch-kw]
  (let [caps (try (ygp/capabilities adapter) (catch Throwable _ #{}))]
    (if-not (contains? caps :mergeable)
      {:slot      slot-key
       :system-id (ygp/system-id adapter)
       :branch    branch-kw
       :merged?   false
       :reason    "unsupported"}
      (try
        (ygp/merge! adapter branch-kw)
        {:slot      slot-key
         :system-id (ygp/system-id adapter)
         :branch    branch-kw
         :merged?   true}
        (catch Throwable t
          (log/warn t "pilot: merge! failed for slot — fan-out continues"
                    {:slot slot-key
                     :system-id (ygp/system-id adapter)
                     :branch branch-kw})
          {:slot      slot-key
           :system-id (ygp/system-id adapter)
           :branch    branch-kw
           :merged?   false
           :error     (.getMessage t)})))))

(defn branch-all-for-ling!
  "Fan out `branch!` across every registered adapter for `ling-id`.
   Returns a vector of per-system result maps. Returns
   `:degraded/no-adapters` when nothing is registered (boot path
   didn't wire) — caller decides whether that's fatal or expected."
  [ling-id]
  (let [systems @registered-systems
        branch  (ling-branch-name ling-id)]
    (cond
      (nil? branch)
      (do (log/warn "pilot: branch-all-for-ling! given empty ling-id" {:ling-id ling-id})
          :degraded/empty-ling-id)

      (empty? systems)
      (do (log/debug "pilot: branch-all-for-ling! — no adapters" {:ling-id ling-id})
          :degraded/no-adapters)

      :else
      (let [results (mapv (fn [[slot adapter]] (safe-branch! slot adapter branch))
                          systems)]
        (log/info "pilot: branched workspace for ling"
                  {:ling-id ling-id :branch branch :results results})
        results))))

(defn merge-all-for-ling!
  "Fan out `merge!` back to :main across every registered adapter for
   `ling-id`. Adapters lacking :mergeable record `:merged? false
   :reason \"unsupported\"` and the branch lingers as a labelled
   snapshot. Returns vector of per-system result maps, or a
   `:degraded/*` keyword."
  [ling-id]
  (let [systems @registered-systems
        branch  (ling-branch-name ling-id)]
    (cond
      (nil? branch)        :degraded/empty-ling-id
      (empty? systems)     :degraded/no-adapters
      :else
      (let [results (mapv (fn [[slot adapter]] (safe-merge! slot adapter branch))
                          systems)]
        (log/info "pilot: merged ling branch back to :main"
                  {:ling-id ling-id :branch branch :results results})
        results))))

(defn handle-ling-started-fork
  "Auxiliary handler for :ling/started — fans out workspace branching.
   Mirrors `handle-session-wrap-coordinated-commit`'s log-only effect
   shape; the existing primary handler still owns DataScript
   registration."
  [_coeffects [_ {:keys [slave-id name] :as ev-data}]]
  (let [ling-id (or slave-id name)
        outcome (branch-all-for-ling! ling-id)]
    {:log {:level   :info
           :message (str "pilot: branch-all-for-ling outcome=" (pr-str outcome)
                         " for ling=" ling-id)}
     :pilot/fork-outcome {:outcome outcome :event-data ev-data}}))

(defn handle-ling-completed-merge
  "Auxiliary handler for :ling/completed — fans out merge-back.
   Failures don't propagate; the primary :ling/completed handler still
   dispatches :session/end."
  [_coeffects [_ {:keys [slave-id agent-id] :as ev-data}]]
  (let [ling-id (or slave-id agent-id)
        outcome (merge-all-for-ling! ling-id)]
    {:log {:level   :info
           :message (str "pilot: merge-all-for-ling outcome=" (pr-str outcome)
                         " for ling=" ling-id)}
     :pilot/merge-outcome {:outcome outcome :event-data ev-data}}))

(defn register-ling-handlers!
  "Register the swarm-fork handlers under distinct event-ids
   `:storage.pilot/ling-started` and `:storage.pilot/ling-completed`.
   Boot wiring is responsible for dispatching into them after the
   primary `:ling/started` / `:ling/completed` handlers — same
   ordering pattern as `register-wrap-handler!`."
  []
  (ev/reg-event :storage.pilot/ling-started
                [interceptors/debug]
                handle-ling-started-fork)
  (ev/reg-event :storage.pilot/ling-completed
                [interceptors/debug]
                handle-ling-completed-merge))
