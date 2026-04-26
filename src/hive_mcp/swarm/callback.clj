(ns hive-mcp.swarm.callback
  "Push-based task result delivery via callback dispatch.

   Replaces polling (collect) with event-driven callbacks:
   1. Ling dispatches task to drone → registers callback
   2. Channel event OR NATS event fires on completion/failure
   3. Result is auto-dispatched back to originating ling as new turn

   Dual-source architecture:
   - Channel events (Emacs/vterm lings via core.async)
   - NATS events (headless drones via NATS pub/sub)

   without modifying either system."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ling :as ling]
            [hive-mcp.swarm.datascript.queries :as ds-queries]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; State: task-id -> callback registration
;; =============================================================================

(defonce ^:private callbacks (atom {}))
;; {task-id {:ling-id "..."
;;           :registered-at <ms>
;;           :format-fn (fn [event] -> string)}}

(defonce ^:private listener-active? (atom false))
(defonce ^:private listener-channels (atom []))
(defonce ^:private nats-subs (atom []))

;; =============================================================================
;; Default Result Formatter
;; =============================================================================

(defn- default-format-fn
  "Format a channel event as a dispatch prompt for the originating ling.
   Produces a clear, structured message the ling can act on."
  [task-id event]
  (let [status (:status event "completed")
        result (:result event)
        error (:error event)
        slave-id (:slave-id event)]
    (if (= status "failed")
      (format (str "CALLBACK: Task %s FAILED.\n"
                   "Source agent: %s\n"
                   "Error: %s\n"
                   "Decide how to handle this failure — retry, skip, or escalate.")
              task-id (or slave-id "unknown") (or error "unknown error"))
      (format (str "CALLBACK: Task %s completed.\n"
                   "Source agent: %s\n"
                   "Result:\n%s")
              task-id (or slave-id "unknown") (or result "No result payload")))))

;; =============================================================================
;; Callback Registration
;; =============================================================================

(defn register!
  "Register a callback for a task.
   When the task completes/fails, the result will be dispatched
   back to the originating ling as a new turn.

   Options:
     :ling-id   - ID of the ling to receive the callback (required)
     :format-fn - (fn [task-id event] -> string) custom formatter (optional)"
  [task-id {:keys [ling-id format-fn]}]
  {:pre [(string? task-id) (string? ling-id)]}
  (swap! callbacks assoc task-id
         {:ling-id ling-id
          :registered-at (System/currentTimeMillis)
          :format-fn (or format-fn default-format-fn)})
  (log/info "Callback registered" {:task-id task-id :ling-id ling-id})
  task-id)

(defn unregister!
  "Remove a callback registration."
  [task-id]
  (swap! callbacks dissoc task-id)
  (log/debug "Callback unregistered" {:task-id task-id}))

(defn registered?
  "Check if a callback is registered for a task."
  [task-id]
  (contains? @callbacks task-id))

(defn registrations
  "List all active callback registrations."
  []
  @callbacks)

;; =============================================================================
;; Callback Execution
;; =============================================================================

(defn- fire-callback!
  "Execute a callback: dispatch result back to the originating ling.
   Removes the registration after firing (one-shot)."
  [task-id event]
  (when-let [{:keys [ling-id format-fn]} (get @callbacks task-id)]
    (try
      (let [msg (format-fn task-id event)]
        ;; Look up ling in DataScript and dispatch
        (if-let [agent-data (ds-queries/get-slave ling-id)]
          (let [agent (ling/->ling ling-id
                                   {:cwd (:slave/cwd agent-data)
                                    :presets (:slave/presets agent-data)
                                    :project-id (:slave/project-id agent-data)
                                    :spawn-mode (or (:ling/spawn-mode agent-data) :claude)})]
            (proto/dispatch! agent {:task msg :priority :high})
            (log/info "Callback fired" {:task-id task-id :ling-id ling-id
                                        :status (:status event)})
            ;; One-shot: unregister after firing
            (unregister! task-id))
          (do
            (log/warn "Callback ling not found, discarding" {:task-id task-id :ling-id ling-id})
            (unregister! task-id))))
      (catch Throwable e
        (log/error "Callback dispatch failed" {:task-id task-id :ling-id ling-id
                                               :error (ex-message e)})
        ;; Don't unregister on transient errors — allow retry on next event
        ))))

;; =============================================================================
;; Public Entry Point (for external event sources like NATS bridge)
;; =============================================================================

(defn notify-completion!
  "Public entry point for external event sources (e.g., NATS bridge).
   Fires callback if task-id is registered, no-op otherwise.
   Returns true if callback was fired, nil otherwise."
  [task-id event-data]
  (let [tid (str task-id)]
    (when (registered? tid)
      (fire-callback! tid event-data)
      true)))

;; =============================================================================
;; Channel Listener (background event loop)
;; =============================================================================

(defn- handle-event!
  "Process a channel event. Fires callback if task-id is registered."
  [event]
  (let [task-id (or (get event "task-id")
                    (:task-id event)
                    (get event :task-id))]
    (when (and task-id (registered? (str task-id)))
      (fire-callback! (str task-id) event))))

(defn start-listener!
  "Start background listener on channel AND NATS events for callback dispatch.
   Subscribes to :task-completed and :task-failed channel events.
   When NATS is connected, also subscribes to drone completion/failure subjects.
   Dual-source: channel events (Emacs/vterm) + NATS events (headless drones).
   Idempotent — no-ops if already running."
  []
  (when-not @listener-active?
    (reset! listener-active? true)
    (let [completed-ch (channel/subscribe! :task-completed)
          failed-ch (channel/subscribe! :task-failed)]
      (reset! listener-channels [[:task-completed completed-ch]
                                 [:task-failed failed-ch]])
      ;; Single go-loop consuming both channels via alts!
      (async/go-loop []
        (let [[event _ch] (async/alts! [completed-ch failed-ch]
                                       :priority true)]
          (when event
            (try
              (handle-event! event)
              (catch Throwable e
                (log/debug "Callback listener error:" (.getMessage e))))
            (when @listener-active?
              (recur)))))
      ;; NATS subscription path — dual-source for headless drones
      (try
        (when-let [connected-fn (requiring-resolve 'hive-mcp.nats.client/connected?)]
          (when (connected-fn)
            (let [wildcard-fn (requiring-resolve 'hive-mcp.nats.bridge/wildcard-subject)
                  subscribe-fn (requiring-resolve 'hive-mcp.nats.client/subscribe!)
                  completed-sub (subscribe-fn (wildcard-fn :completed)
                                              (fn [msg] (handle-event! (assoc msg :status "completed"))))
                  failed-sub (subscribe-fn (wildcard-fn :failed)
                                           (fn [msg] (handle-event! (assoc msg :status "failed"))))]
              (reset! nats-subs (filterv some? [completed-sub failed-sub]))
              (log/info "Callback NATS subscriptions active (dual-source)"))))
        (catch Exception e
          (log/debug "NATS callback subscriptions skipped:" (.getMessage e))))
      (log/info "Callback listener started" {:channels [:task-completed :task-failed]
                                             :nats-active? (boolean (seq @nats-subs))}))))

(defn stop-listener!
  "Stop the background callback listener and NATS subscriptions."
  []
  (when @listener-active?
    (reset! listener-active? false)
    ;; NATS cleanup
    (when (seq @nats-subs)
      (try
        (when-let [unsubscribe-fn (requiring-resolve 'hive-mcp.nats.client/unsubscribe!)]
          (when-let [wildcard-fn (requiring-resolve 'hive-mcp.nats.bridge/wildcard-subject)]
            (unsubscribe-fn (wildcard-fn :completed))
            (unsubscribe-fn (wildcard-fn :failed))))
        (catch Exception e
          (log/debug "NATS callback unsubscribe error:" (.getMessage e))))
      (reset! nats-subs []))
    ;; Channel cleanup
    (doseq [[event-type ch] @listener-channels]
      (try
        (channel/unsubscribe! event-type ch)
        (catch Exception e
          (log/debug "Unsubscribe error:" (.getMessage e)))))
    (reset! listener-channels [])
    (log/info "Callback listener stopped")))

;; =============================================================================
;; Convenience: dispatch-and-callback
;; =============================================================================

(defn dispatch-and-callback!
  "Dispatch a task to a target agent AND register a callback to receive
   the result back on the originating ling.

   This is the fire-and-forget replacement for dispatch + collect.

   Arguments:
     originating-ling-id - The ling that should receive the result
     target-agent-id     - The agent to dispatch the task to
     task-prompt         - The task to dispatch
     opts                - Optional: {:format-fn fn, :files [...], :priority :normal}

   Returns task-id on success, throws on failure."
  [originating-ling-id target-agent-id task-prompt & {:as opts}]
  (let [dispatch-fn (requiring-resolve 'hive-mcp.tools.agent.dispatch/handle-dispatch)
        result (dispatch-fn {:agent_id target-agent-id
                             :prompt task-prompt
                             :files (:files opts)
                             :priority (name (or (:priority opts) :normal))})
        ;; Extract task-id from dispatch result
        result-text (some-> result :text)
        parsed (try (json/read-str (or result-text "{}") :key-fn keyword)
                    (catch Exception e (log/debug "[callback] JSON parse of dispatch result failed:" (.getMessage e)) nil))
        task-id (:task-id parsed)]
    (if task-id
      (do
        (register! task-id {:ling-id originating-ling-id
                            :format-fn (:format-fn opts default-format-fn)})
        ;; Ensure listener is running
        (start-listener!)
        task-id)
      (throw (ex-info "Dispatch did not return task-id"
                      {:target target-agent-id :result result})))))

;; =============================================================================
;; Cleanup: expire stale callbacks
;; =============================================================================

(defn cleanup-stale!
  "Remove callbacks older than max-age-ms (default: 30 minutes)."
  ([] (cleanup-stale! (* 30 60 1000)))
  ([max-age-ms]
   (let [now (System/currentTimeMillis)
         stale (->> @callbacks
                    (filter (fn [[_ v]] (> (- now (:registered-at v)) max-age-ms)))
                    (map first))]
     (doseq [task-id stale]
       (unregister! task-id))
     (when (seq stale)
       (log/info "Cleaned up stale callbacks" {:count (count stale)})))))

(defn cleanup-for-agent!
  "Remove all callbacks registered by a specific ling (on ling death)."
  [ling-id]
  (let [agent-callbacks (->> @callbacks
                             (filter (fn [[_ v]] (= ling-id (:ling-id v))))
                             (map first))]
    (doseq [task-id agent-callbacks]
      (unregister! task-id))
    (when (seq agent-callbacks)
      (log/info "Cleaned up callbacks for dead ling" {:ling-id ling-id
                                                      :count (count agent-callbacks)}))))

(defn status
  "Get callback system status."
  []
  {:listener-active? @listener-active?
   :registered-count (count @callbacks)
   :nats-subscriptions (count @nats-subs)
   :registrations (mapv (fn [[tid {:keys [ling-id registered-at]}]]
                          {:task-id tid :ling-id ling-id :registered-at registered-at})
                        @callbacks)})

(comment
  ;; REPL testing
  (start-listener!)
  (status)
  (register! "test-task-1" {:ling-id "some-ling-id"})
  (registered? "test-task-1")
  (unregister! "test-task-1")
  (stop-listener!)

  ;; Full flow:
  ;; (dispatch-and-callback! "coordinator" "some-drone" "do something")
  )
