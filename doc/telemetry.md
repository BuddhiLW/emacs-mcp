# Telemetry and Logging

This document describes the telemetry system in emacs-mcp, which follows the CLARITY principle: "Telemetry first - observability is essential."

## Overview

The `emacs-mcp.telemetry` namespace provides structured logging for evaluation operations with:

- Request/response tracking
- Timing measurements
- Exception handling
- Structured log data for easy parsing

## Core Components

### 1. Basic Timing

The `with-timing` macro measures operation duration:

```clojure
(require '[emacs-mcp.telemetry :as telemetry])

(telemetry/with-timing "database-query"
  (fetch-users-from-db))
;; Logs: info :timing {:operation "database-query" :ms 142}
```

### 2. Evaluation Request Logging

Log structured information about evaluation requests:

```clojure
(telemetry/log-eval-request 
  {:code "(+ 1 2 3)"
   :mode :elisp
   :metadata {:user-id "alice" :session-id "abc-123"}})
;; Logs: info :eval-request {:mode :elisp 
;;                           :code-length 9
;;                           :code-preview "(+ 1 2 3)"
;;                           :user-id "alice"
;;                           :session-id "abc-123"}
```

Long code is automatically truncated to 50 characters with ellipsis:

```clojure
(telemetry/log-eval-request 
  {:code (apply str (repeat 100 "x"))
   :mode :test})
;; Logs: info :eval-request {:mode :test
;;                           :code-length 100
;;                           :code-preview "xxxxxxxxxx..."}
```

### 3. Result Logging

Log success or failure of operations:

```clojure
;; Success
(telemetry/log-eval-result 
  {:success true
   :duration-ms 42
   :result-length 256
   :metadata {:cache-hit true}})
;; Logs: info :eval-success {:duration-ms 42
;;                           :result-length 256
;;                           :cache-hit true}

;; Failure
(telemetry/log-eval-result 
  {:success false
   :error "Syntax error at line 5"
   :duration-ms 15})
;; Logs: warn :eval-failure {:error "Syntax error at line 5"
;;                           :duration-ms 15}
```

### 4. Exception Logging

Capture detailed exception information:

```clojure
(try
  (risky-operation)
  (catch Exception e
    (telemetry/log-eval-exception 
      {:exception e
       :operation "risky-operation"
       :metadata {:context "batch-processing"}})))
;; Logs: error :eval-exception {:operation "risky-operation"
;;                              :exception-type "java.lang.Exception"
;;                              :exception-message "Something went wrong"
;;                              :context "batch-processing"}
;;       <stack trace>
```

### 5. Comprehensive Telemetry Wrapper

The `with-eval-telemetry` macro provides complete instrumentation:

```clojure
(telemetry/with-eval-telemetry :elisp code {:user-id 123}
  (ec/eval-elisp code))
```

This automatically:
1. Logs the request with code preview
2. Measures execution time
3. Logs the result (success or failure)
4. Catches and logs any exceptions
5. Propagates metadata through all log entries

## Integration

### In Handler Functions

The telemetry is integrated into evaluation handlers:

```clojure
(defn handle-eval-elisp
  [{:keys [code]}]
  (telemetry/with-eval-telemetry :elisp code nil
    (let [{:keys [success result error]} (ec/eval-elisp code)]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))))
```

### In Low-Level Operations

The `emacsclient.clj` namespace includes timing in the core `eval-elisp` function:

```clojure
(defn eval-elisp [code]
  ;; Automatically logs duration and success/failure
  (let [start (System/currentTimeMillis)]
    ;; ... execution ...
    {:success true 
     :result output
     :duration-ms (- (System/currentTimeMillis) start)}))
```

## Log Output Format

All logs use structured data for easy parsing and filtering:

```
info [emacs-mcp.tools] :eval-request {:mode :elisp, :code-length 42, :code-preview "(defun hello () (message \"Hello\"))"}
debug [emacs-mcp.emacsclient] :emacsclient-success {:duration-ms 15, :result-length 8}
info [emacs-mcp.tools] :eval-success {:duration-ms 15, :result-length 8}
```

## Configuration

Configure logging behavior at startup:

```clojure
;; Set minimum log level
(telemetry/configure-logging! {:level :info})

;; Debug mode for development
(telemetry/configure-logging! {:level :debug})

;; Production mode (warnings and errors only)
(telemetry/configure-logging! {:level :warn})

;; Custom output format
(telemetry/configure-logging! 
  {:level :info
   :output-fn (fn [{:keys [level msg_]}]
                (format "[%s] %s" level (force msg_)))})
```

## Log Levels

- `:trace` - Very detailed debugging
- `:debug` - emacsclient call details, low-level operations
- `:info` - Evaluation requests and successes
- `:warn` - Evaluation failures, non-critical errors
- `:error` - Exceptions, critical failures

## Metrics Available

For each evaluation operation, you can track:

1. **Request metrics**:
   - Mode (elisp, cider-silent, cider-explicit)
   - Code length
   - Code preview

2. **Response metrics**:
   - Duration (milliseconds)
   - Success/failure
   - Result length
   - Error messages

3. **System metrics**:
   - emacsclient exit codes
   - Exception types and messages
   - Stack traces

## Best Practices

1. **Use metadata** to add context-specific information:
   ```clojure
   (with-eval-telemetry :elisp code 
     {:session-id session-id
      :user-id user-id
      :request-id request-id}
     (eval-elisp code))
   ```

2. **Log at appropriate levels**:
   - Use `:debug` for detailed diagnostics
   - Use `:info` for normal operations
   - Use `:warn` for recoverable errors
   - Use `:error` for critical failures

3. **Include operation context** in exception logs

4. **Monitor duration metrics** to identify performance issues

## Example: Full Workflow

```clojure
(ns my-app.eval
  (:require [emacs-mcp.telemetry :as telemetry]
            [emacs-mcp.emacsclient :as ec]))

;; Configure logging on startup
(telemetry/configure-logging! {:level :info})

;; Wrap evaluation with telemetry
(defn safe-eval [code user-id]
  (telemetry/with-eval-telemetry :elisp code 
    {:user-id user-id
     :timestamp (System/currentTimeMillis)}
    (ec/eval-elisp code)))

;; Usage
(safe-eval "(buffer-list)" "alice")
;; Logs:
;; info :eval-request {:mode :elisp, :code-length 13, 
;;                     :code-preview "(buffer-list)", 
;;                     :user-id "alice", :timestamp 1234567890}
;; debug :emacsclient-success {:duration-ms 12, :result-length 156}
;; info :eval-success {:duration-ms 12, :result-length 156,
;;                     :user-id "alice", :timestamp 1234567890}
```

## See Also

- [taoensso/timbre documentation](https://github.com/ptaoussanis/timbre)
- CLARITY framework: "Telemetry first" principle
- `src/emacs_mcp/telemetry.clj` - Implementation
- `test/emacs_mcp/telemetry_test.clj` - Usage examples
