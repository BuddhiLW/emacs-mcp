(ns hive-mcp.tools.swarm.channel
  "Channel-based event management for swarm task tracking via core.async and NATS."
  (:require [clojure.core.async :as async :refer [go-loop <!]]
            [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bclear! register-sweepable!]]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Event journal — FIFO eviction, 1000 entries, 1hr TTL.
;; Events are write-heavy, read-on-demand. FIFO suits journal semantics.
(defonce ^:private event-journal
  (bounded-atom {:max-entries 1000
                 :ttl-ms 3600000    ;; 1 hour
                 :eviction-policy :fifo}))
(register-sweepable! event-journal :event-journal)

;; Channel subscriptions — keyed by event-type keyword.
;; Max 50 entries (typically only 3 event types). No TTL — subs live until stop.
(defonce ^:private channel-subscriptions
  (bounded-atom {:max-entries 50
                 :eviction-policy :fifo}))
(register-sweepable! channel-subscriptions :channel-subscriptions)

(defn- try-require-channel
  "Attempt to require the channel namespace, returns true if available."
  []
  (rescue false
          (require 'hive-mcp.channel.core)
          true))

(defn- channel-subscribe!
  "Subscribe to an event type if channel is available."
  [event-type]
  (when (try-require-channel)
    (when-let [subscribe-fn (resolve 'hive-mcp.channel.core/subscribe!)]
      (subscribe-fn event-type))))

(defn- handle-task-completed
  "Handle task-completed event from channel."
  [event]
  (let [task-id (get event "task-id")
        slave-id (get event "slave-id")
        result (get event "result")
        timestamp (get event "timestamp")]
    (log/info "Channel: task-completed" task-id "from" slave-id)
    (bput! event-journal (str task-id)
           {:status "completed"
            :result result
            :slave-id slave-id
            :timestamp (or timestamp (System/currentTimeMillis))})))

(defn- handle-task-failed
  "Handle task-failed event from channel."
  [event]
  (let [task-id (get event "task-id")
        slave-id (get event "slave-id")
        error (get event "error")
        timestamp (get event "timestamp")]
    (log/info "Channel: task-failed" task-id "from" slave-id ":" error)
    (bput! event-journal (str task-id)
           {:status "failed"
            :error error
            :slave-id slave-id
            :timestamp (or timestamp (System/currentTimeMillis))})))

(defn- handle-prompt-shown
  "Handle prompt-shown event from channel."
  [event]
  (let [slave-id (get event "slave-id")
        _prompt (get event "prompt")
        _timestamp (get event "timestamp")]
    (log/info "Channel: prompt-shown from" slave-id)
    ;; For now just log - could add to a prompts journal if needed
    ))

(defn start-channel-subscriptions!
  "Start listening for swarm events via channel subscriptions.
   Stores event-type -> sub-ch in bounded map atom for proper unsubscribe on stop."
  []
  (when (try-require-channel)
    (log/info "Starting channel subscriptions for swarm events...")

    ;; Subscribe to task-completed
    (when-let [sub (channel-subscribe! :task-completed)]
      (bput! channel-subscriptions :task-completed sub)
      (go-loop []
        (when-let [event (<! sub)]
          (handle-task-completed event)
          (recur))))

    ;; Subscribe to task-failed
    (when-let [sub (channel-subscribe! :task-failed)]
      (bput! channel-subscriptions :task-failed sub)
      (go-loop []
        (when-let [event (<! sub)]
          (handle-task-failed event)
          (recur))))

    ;; Subscribe to prompt-shown
    (when-let [sub (channel-subscribe! :prompt-shown)]
      (bput! channel-subscriptions :prompt-shown sub)
      (go-loop []
        (when-let [event (<! sub)]
          (handle-prompt-shown event)
          (recur))))

    (log/info "Channel subscriptions started")))

(defn stop-channel-subscriptions!
  "Stop all channel subscriptions. Uses channel/unsubscribe! to properly
   unsub from pub before closing channels."
  []
  (doseq [[event-type entry] @(:atom channel-subscriptions)]
    (let [sub-ch (:data entry)]
      (rescue nil
              (when-let [unsub-fn (requiring-resolve 'hive-mcp.channel.core/unsubscribe!)]
                (unsub-fn event-type sub-ch)))))
  (bclear! channel-subscriptions)
  (log/info "Channel subscriptions stopped"))

(defn check-event-journal
  "Check event journal for task completion, returns the event or nil."
  [task-id]
  (bget event-journal (str task-id)))

(defn clear-event-journal!
  "Clear all entries from the event journal."
  []
  (bclear! event-journal))

;; =============================================================================
;; Journal write port — single entry point for all external adapters
;; =============================================================================

(defn- write-to-journal!
  "Single write path into the event journal.
   Shared by all adapter-facing public fns (NATS, headless, etc.).
   DIP: callers depend on this abstraction, not on bput! directly."
  [task-id event-data]
  (bput! event-journal (str task-id) event-data))

(defn record-nats-event!
  "Record an event received from NATS into the JVM event journal."
  [task-id event-data]
  (write-to-journal! task-id event-data))

(defn record-task-result!
  "Write a task result directly to the JVM event journal.
   Works without NATS or Emacs. Used by headless backends."
  [task-id result-map]
  (write-to-journal! task-id result-map))
