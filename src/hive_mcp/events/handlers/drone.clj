(ns hive-mcp.events.handlers.drone
  "Drone lifecycle event handlers.

   Handles events related to drone (leaf agent) lifecycle:
   - :drone/started   - Drone spawned and began task
   - :drone/completed - Drone finished successfully
   - :drone/failed    - Drone execution failed"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.events.interceptors :as interceptors]
            [hive-mcp.agent.drone.feedback :as feedback]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Handler: :drone/started
;; =============================================================================

(defn handle-drone-started
  "Handler for :drone/started events.

   Called when a drone is spawned and begins task execution.
   Publishes event to channel for coordinator monitoring.

   Expects event data:
   {:drone-id   \"drone-1705000000\"
    :task-id    \"task-drone-1705000000\"
    :parent-id  \"swarm-ling-123\" (optional)
    :files      [\"src/foo.clj\"]
    :task       \"Implement feature X\"}

   Produces effects:
   - :channel-publish    - Broadcast drone-started to WebSocket clients
   - :olympus-broadcast  - Push to Olympus Web UI (port 7911)
   - :log                - Log drone start message
   - :prometheus         - Increment drone_started counter"
  [_coeffects [_ {:keys [drone-id task-id parent-id files task]}]]
  {:channel-publish {:event :drone-started
                     :data {:drone-id drone-id
                            :task-id task-id
                            :parent-id parent-id
                            :files files
                            :task-preview (when task
                                            (subs task 0 (min 100 (count task))))}}
   :olympus-broadcast {:type :agent-spawned
                       :agent {:id drone-id
                               :name drone-id
                               :type "drone"
                               :status "working"
                               :parent-id parent-id
                               :files files}
                       :timestamp (System/currentTimeMillis)}
   :log {:level :info
         :message (str "Drone started: " drone-id
                       (when parent-id (str " (parent: " parent-id ")"))
                       " files: " (count files))}
   :prometheus {:counter :drone_started
                :labels {:parent (or parent-id "none")}}})

;; =============================================================================
;; Handler: :drone/completed
;; =============================================================================

(defn handle-drone-completed
  "Handler for :drone/completed events.

   Called when a drone finishes task execution successfully.
   Publishes completion event and logs results.

   Expects event data:
   {:drone-id      \"drone-1705000000\"
    :task-id       \"task-drone-1705000000\"
    :parent-id     \"swarm-ling-123\" (optional)
    :run-id        \"wave-abc\" (optional — wave run id; when present a
                     wave-scoped completion is also published to NATS)
    :files-modified [\"src/foo.clj\"]
    :files-failed   [{:file \"path\" :error \"reason\"}]
    :duration-ms   5000
    :model         \"mistralai/devstral-2512:free\" (optional)
    :task-type     :coding | :refactor | :fix (optional)}

   Produces effects:
   - :channel-publish - Broadcast drone-completed to WebSocket clients
   - :log             - Log drone completion message (includes failure details)
   - :prometheus      - Increment drone_completed counter, record duration"

  [_coeffects [_ {:keys [drone-id task-id parent-id run-id files-modified files-failed
                         duration-ms model task-type] :as _event-data}]]
  (let [;; Format failure details for log message
        failure-details (when (seq files-failed)
                          (->> files-failed
                               (map #(str (:file %) ": " (:error %)))
                               (str/join ", ")))]

    (when (and model task-type)
      (feedback/record-pattern! (keyword task-type)
                                model
                                :success
                                {:duration-ms duration-ms
                                 :agent-id drone-id}))

    {:channel-publish {:event :drone-completed
                       :data {:drone-id drone-id
                              :task-id task-id
                              :parent-id parent-id
                              :files-modified files-modified
                              :files-failed files-failed
                              ;; Include partial-success flag for coordinator decision
                              :partial-success (boolean (seq files-failed))}}
     :olympus-broadcast {:type :agent-status
                         :agent {:id drone-id
                                 :status "completed"
                                 :duration-ms duration-ms
                                 :files-modified (count files-modified)}
                         :timestamp (System/currentTimeMillis)}
     :log {:level (if (seq files-failed) :warn :info)  ;; Escalate to warn if failures
           :message (str "Drone completed: " drone-id
                         " - " (count files-modified) " files modified"
                         (when failure-details
                           (str " | DIFF FAILURES: " failure-details)))}
     :prometheus {:counter :drone_completed
                  :labels {:parent (or parent-id "none")}
                  :histogram {:name :drone_duration_seconds
                              :value (when duration-ms (/ duration-ms 1000.0))
                              :status :success}}
     :nats-publish {:event-type :completed
                    :task-id task-id
                    :parent-id parent-id
                    :run-id run-id
                    :result {:files-modified files-modified
                             :files-failed files-failed
                             :duration-ms duration-ms}}}))

;; =============================================================================
;; Handler: :drone/failed
;; =============================================================================

(defn handle-drone-failed
  "Handler for :drone/failed events.

   Called when a drone execution fails. Publishes failure event
   for monitoring and debugging.

   Expects event data:
   {:drone-id    \"drone-1705000000\"
    :task-id     \"task-drone-1705000000\"
    :parent-id   \"swarm-ling-123\" (optional)
    :run-id      \"wave-abc\" (optional — wave run id; when present a
                   wave-scoped completion is also published to NATS)
    :error       \"Connection timeout\"
    :error-type  :nrepl-connection | :nrepl-timeout | :validation | :conflict | :execution | :unknown
    :files       [\"src/foo.clj\"]
    :duration-ms 5000 (optional)
    :model       \"mistralai/devstral-2512:free\" (optional)
    :task-type   :coding | :refactor | :fix (optional)}

   Produces effects:
   - :channel-publish - Broadcast drone-failed to WebSocket clients
   - :log             - Log drone failure message
   - :prometheus      - Increment drone_failed counter with drone_id label
                      - Record duration histogram with status=failed"

  [_coeffects [_ {:keys [drone-id task-id parent-id run-id error error-type files
                         duration-ms model task-type] :as _event-data}]]

  (when (and model task-type)
    (let [result-class (feedback/classify-result {:status :failed
                                                  :error (or error "unknown")})]
      (feedback/record-pattern! (keyword task-type)
                                model
                                result-class
                                {:duration-ms duration-ms
                                 :agent-id drone-id})))

  {:channel-publish {:event :drone-failed
                     :data {:drone-id drone-id
                            :task-id task-id
                            :parent-id parent-id
                            :error error
                            :error-type error-type
                            :files files}}
   :olympus-broadcast {:type :agent-status
                       :agent {:id drone-id
                               :status "failed"
                               :error (or error "unknown")
                               :error-type (when error-type (name error-type))}
                       :timestamp (System/currentTimeMillis)}
   :log {:level :warn
         :message (str "Drone failed: " drone-id
                       " - " (or error "unknown error")
                       (when error-type (str " (" (name error-type) ")")))}
   :prometheus {:counter :drone_failed
                :labels {:parent (or parent-id "none")
                         :error_type (name (or error-type :unknown))
                         :drone_id (or drone-id "unknown")}
                :histogram (when duration-ms
                             {:name :drone_duration_seconds
                              :value (/ duration-ms 1000.0)
                              :status :failed})}
   :nats-publish {:event-type :failed
                  :task-id task-id
                  :parent-id parent-id
                  :run-id run-id
                  :error error}})

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-handlers!
  "Register drone-related event handlers."
  []
  (ev/reg-event :drone/started
                [interceptors/debug]
                handle-drone-started)

  (ev/reg-event :drone/completed
                [interceptors/debug]
                handle-drone-completed)

  (ev/reg-event :drone/failed
                [interceptors/debug]
                handle-drone-failed))
