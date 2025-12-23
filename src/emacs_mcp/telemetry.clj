(ns emacs-mcp.telemetry
  "Telemetry and logging utilities for evaluation operations.
   Follows CLARITY principle: 'Telemetry first' - observability is essential."
  (:require [taoensso.timbre :as log]))

(defmacro with-timing
  "Execute body and log the operation duration.
   Returns the result of the body execution.
   
   Example:
     (with-timing \"fetch-users\"
       (fetch-users-from-db))"
  [operation-name & body]
  `(let [start# (System/currentTimeMillis)
         result# (do ~@body)
         duration# (- (System/currentTimeMillis) start#)]
     (log/info :timing {:operation ~operation-name :ms duration#})
     result#))

(defn log-eval-request
  "Log structured information about an evaluation request.
   
   Parameters:
     - code: The code being evaluated
     - mode: The evaluation mode (e.g., :elisp, :cider-silent, :cider-explicit)
     - metadata: Optional map with additional context
   
   Example:
     (log-eval-request {:code \"(+ 1 2)\" 
                        :mode :elisp
                        :metadata {:user \"alice\" :session-id \"123\"}})"
  [{:keys [code mode metadata]}]
  (let [code-length (count code)
        code-preview (subs code 0 (min 50 code-length))
        code-preview-with-ellipsis (if (> code-length 50)
                                     (str code-preview "...")
                                     code-preview)]
    (log/info :eval-request
              (merge {:mode mode
                      :code-length code-length
                      :code-preview code-preview-with-ellipsis}
                     metadata))))

(defn log-eval-result
  "Log structured information about an evaluation result.
   
   Parameters:
     - success: Boolean indicating success/failure
     - error: Error message (when success is false)
     - duration: Duration in milliseconds
     - result-length: Length of result string (optional)
     - metadata: Optional map with additional context
   
   Example:
     (log-eval-result {:success true 
                       :duration-ms 42
                       :result-length 100})
     
     (log-eval-result {:success false
                       :error \"Syntax error\"
                       :duration-ms 15})"
  [{:keys [success error duration-ms result-length metadata]}]
  (if success
    (log/info :eval-success
              (merge {:duration-ms duration-ms}
                     (when result-length {:result-length result-length})
                     metadata))
    (log/warn :eval-failure
              (merge {:error error
                      :duration-ms duration-ms}
                     metadata))))

(defn log-eval-exception
  "Log an unexpected exception during evaluation.
   
   Parameters:
     - exception: The caught exception
     - operation: Name of the operation that failed
     - metadata: Optional map with additional context"
  [{:keys [exception operation metadata]}]
  (log/error :eval-exception
             (merge {:operation operation
                     :exception-type (-> exception class .getName)
                     :exception-message (.getMessage exception)}
                    metadata)
             exception))

(defmacro with-eval-telemetry
  "Comprehensive telemetry wrapper for evaluation operations.
   Logs request, result, timing, and any exceptions.
   
   Parameters:
     - mode: Evaluation mode keyword (e.g., :elisp, :cider-silent)
     - code: The code being evaluated
     - metadata: Optional metadata map
     - body: The evaluation code to execute
   
   Returns: Result of body execution
   
   Example:
     (with-eval-telemetry :elisp code {:user-id 123}
       (eval-elisp code))"
  [mode code metadata & body]
  `(let [code# ~code
         mode# ~mode
         metadata# ~metadata
         start# (System/currentTimeMillis)]
     (log-eval-request {:code code# :mode mode# :metadata metadata#})
     (try
       (let [result# (do ~@body)
             duration# (- (System/currentTimeMillis) start#)
             result-str# (if (map? result#)
                           (get result# :result "")
                           (str result#))
             success# (if (map? result#)
                        (get result# :success true)
                        true)]
         (log-eval-result {:success success#
                           :duration-ms duration#
                           :result-length (count result-str#)
                           :metadata metadata#})
         result#)
       (catch Exception e#
         (let [duration# (- (System/currentTimeMillis) start#)]
           (log-eval-exception {:exception e#
                                :operation (str "eval-" (name mode#))
                                :metadata metadata#})
           (log-eval-result {:success false
                             :error (.getMessage e#)
                             :duration-ms duration#
                             :metadata metadata#})
           (throw e#))))))

(defn configure-logging!
  "Configure Timbre logging with sensible defaults for production.
   
   Options:
     - :level - Minimum log level (:trace, :debug, :info, :warn, :error)
     - :output-fn - Custom output formatter function
     - :appenders - Map of appender configurations
   
   Example:
     (configure-logging! {:level :info})"
  ([]
   (configure-logging! {:level :info}))
  ([{:keys [level output-fn appenders]
     :or {level :info}}]
   (log/merge-config!
    {:level level
     :output-fn (or output-fn
                    (fn [{:keys [level ?ns-str msg_ ?err]}]
                      (format "%s [%s] %s%s"
                              (name level)
                              (or ?ns-str "?")
                              (force msg_)
                              (if ?err
                                (str "\n" (log/stacktrace ?err))
                                ""))))
     :appenders (or appenders
                    {:println {:enabled? true
                               :async? false
                               :min-level nil
                               :output-fn :inherit
                               :fn (fn [data]
                                     (let [{:keys [output_]} data]
                                       (println (force output_))))}})})))

(comment
  ;; Usage examples

  ;; Basic timing
  (with-timing "database-query"
    (Thread/sleep 100)
    {:result "data"})

  ;; Log evaluation request
  (log-eval-request {:code "(+ 1 2 3)"
                     :mode :elisp
                     :metadata {:session-id "abc-123"}})

  ;; Log successful result
  (log-eval-result {:success true
                    :duration-ms 45
                    :result-length 256})

  ;; Log failure
  (log-eval-result {:success false
                    :error "Syntax error at line 5"
                    :duration-ms 12})

  ;; Full telemetry wrapper
  (with-eval-telemetry :elisp "(+ 1 2)" {:user "alice"}
    {:success true :result "3"})

  ;; Configure logging
  (configure-logging! {:level :debug}))
