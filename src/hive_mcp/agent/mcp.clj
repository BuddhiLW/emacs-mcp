(ns hive-mcp.agent.mcp
  "MCP tool definitions for agent delegation."
  (:require [clojure.string :as str]
            [hive-mcp.telemetry.prometheus :as prom]
            [hive-mcp.tools.core :refer [mcp-error mcp-json]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn handle-delegate-drone
  "Handle delegate_drone MCP tool call, delegating to the provided function."
  [delegate-drone-fn {:keys [task files preset trace parent_id cwd]}]
  ;; DEPRECATION WARNING: Prefer unified 'delegate' tool
  (log/warn {:event :deprecation-warning
             :tool "delegate_drone"
             :message "DEPRECATED: Use 'delegate' tool instead. delegate({task: \"...\", files: [...]})"})

  (let [trace-id (str "mcp-" (java.util.UUID/randomUUID))
        start-time-ns (System/nanoTime)
        file-count (count (or files []))
        task-preview (when task (subs task 0 (min 100 (count task))))]

    (prom/inc-mcp-requests! "delegate_drone")

    (log/info {:event :delegate-drone/request-start
               :trace-id trace-id
               :file-count file-count
               :preset (or preset "drone-worker")
               :parent-id parent_id
               :has-cwd (boolean cwd)
               :task-preview task-preview})

    (if (str/blank? task)
      (do
        (log/warn {:event :delegate-drone/validation-failed
                   :trace-id trace-id
                   :error-type :validation
                   :error "Task is required"})
        (prom/inc-errors-total! :validation true)
        (mcp-error "Task is required"))

      (try
        (let [result (delegate-drone-fn {:task task
                                         :files files
                                         :preset (or preset "drone-worker")
                                         :trace (if (nil? trace) true trace)
                                         :parent-id parent_id
                                         :cwd cwd})
              duration-sec (/ (- (System/nanoTime) start-time-ns) 1e9)]

          (prom/observe-request-duration! "delegate_drone" duration-sec)

          ;; Ensure we always return a structured response
          (if (nil? result)
            (do
              (log/warn {:event :delegate-drone/nil-result
                         :trace-id trace-id
                         :duration-sec duration-sec
                         :error-type :execution})
              (prom/inc-errors-total! :execution true)
              (mcp-error "Drone returned nil - execution may have failed silently"))

            (let [status (or (:status result) :unknown)
                  success? (= :completed status)]
              (log/info {:event :delegate-drone/request-end
                         :trace-id trace-id
                         :status status
                         :success success?
                         :duration-sec duration-sec
                         :drone-id (:agent-id result)
                         :files-modified (count (or (:files-modified result) []))
                         :files-failed (count (or (:files-failed result) []))})

              (mcp-json (assoc result
                               :status status
                               :trace-id trace-id
                               :message (or (:message result)
                                            (if success?
                                              "Drone completed successfully"
                                              "Drone execution finished")))))))

        (catch clojure.lang.ExceptionInfo e
          ;; Structured error from delegate! (conflicts, validation, etc.)
          (let [data (ex-data e)
                error-type (or (:error-type data) :delegation-error)
                duration-sec (/ (- (System/nanoTime) start-time-ns) 1e9)
                error-category (case error-type
                                 :conflict :conflict
                                 :validation :validation
                                 :timeout :timeout
                                 :rate-limit :rate-limit
                                 :execution)]

            (prom/observe-request-duration! "delegate_drone" duration-sec)
            (prom/inc-errors-total! error-category (not= error-category :execution))

            (log/error {:event :delegate-drone/failed
                        :trace-id trace-id
                        :error-type error-type
                        :error-category error-category
                        :error (ex-message e)
                        :drone-id (:drone-id data)
                        :conflicts (:conflicts data)
                        :files files
                        :duration-sec duration-sec
                        :task-preview task-preview})

            (mcp-json {:status :failed
                       :error (ex-message e)
                       :error-type error-type
                       :trace-id trace-id
                       :conflicts (:conflicts data)
                       :drone-id (:drone-id data)
                       :files (:files data)})))

        (catch Exception e
          ;; Unexpected error - log and return structured response
          (let [duration-sec (/ (- (System/nanoTime) start-time-ns) 1e9)
                stacktrace-str (pr-str (.getStackTrace e))]

            (prom/observe-request-duration! "delegate_drone" duration-sec)
            (prom/inc-errors-total! :unexpected false)

            (log/error {:event :delegate-drone/unexpected-error
                        :trace-id trace-id
                        :error (ex-message e)
                        :exception-class (.getName (class e))
                        :files files
                        :duration-sec duration-sec
                        :task-preview task-preview
                        :stacktrace (subs stacktrace-str 0 (min 500 (count stacktrace-str)))})

            (mcp-json {:status :failed
                       :error (str "Drone delegation failed: " (ex-message e))
                       :error-type :unexpected
                       :trace-id trace-id
                       :exception-class (.getName (class e))})))))))

(defn make-tools
  "Create MCP tool definitions with injected handler functions.
   REMOVED: Flat agent/openrouter/preset tools no longer exposed. Use consolidated `config` tool."
  [_delegate-drone-fn]
  [])
