# Resilience Patterns for REPL Evaluation

This document explains the graceful degradation patterns implemented in `emacs-mcp.resilience` namespace, following the CLARITY framework principle: **"Yield safe failure"**.

## Overview

The resilience namespace provides three core patterns for handling evaluation failures:

1. **Fallback Strategy** - Degrades gracefully from explicit to silent mode
2. **Retry Pattern** - Handles transient failures with exponential backoff
3. **Safe Wrapper** - Never throws, always returns structured data

## Core Functions

### `eval-with-fallback`

Automatically falls back to an alternative evaluation mode if the primary fails.

**Use Case**: When you prefer interactive feedback (explicit mode) but can accept silent mode if the REPL buffer isn't available.

```clojure
(require '[emacs-mcp.resilience :as resilience]
         '[emacs-mcp.evaluator :as evaluator])

(def evaluator (evaluator/make-emacs-cider-evaluator))

;; Try explicit mode first, fall back to silent
(resilience/eval-with-fallback 
  evaluator 
  "(require 'my.namespace)"
  {:mode :explicit 
   :fallback-mode :silent})

;; Returns:
;; {:success true
;;  :result "nil"
;;  :mode-used :silent          ;; Used fallback
;;  :fallback-used true
;;  :primary-error "REPL buffer not ready"}
```

**Parameters**:
- `evaluator` - ReplEvaluator instance
- `code` - String of code to evaluate
- `opts` - Map with:
  - `:mode` - Primary mode (`:explicit` or `:silent`), default `:explicit`
  - `:fallback-mode` - Fallback mode, default `:silent`
  - `:timeout-ms` - Optional timeout
  - `:namespace` - Optional target namespace

### `eval-with-retry`

Retries evaluation on transient failures with exponential backoff.

**Use Case**: Network hiccups, connection timeouts, race conditions.

```clojure
;; Retry up to 5 times with exponential backoff
(resilience/eval-with-retry
  evaluator
  "(load-heavy-data)"
  {:silent? true}
  :max-retries 5
  :delay-ms 100           ;; Start with 100ms
  :backoff-multiplier 2)  ;; Double each time: 100, 200, 400, 800, 1600

;; Returns:
;; {:success true
;;  :result "data-loaded"
;;  :attempts 3}  ;; Succeeded on third try
```

**Parameters**:
- `evaluator` - ReplEvaluator instance
- `code` - String of code to evaluate
- `opts` - Standard eval options
- `:max-retries` - Maximum attempts (default: 3)
- `:delay-ms` - Initial delay in ms (default: 100)
- `:backoff-multiplier` - Delay multiplier (default: 2)

### `safe-eval`

Never throws exceptions - always returns structured data.

**Use Case**: Server APIs, scheduled jobs, anywhere exceptions would be problematic.

```clojure
;; Success case
(resilience/safe-eval evaluator "(+ 1 2)" {:silent? true})
;; => {:ok "3"}

;; Failure case - NO EXCEPTION THROWN
(resilience/safe-eval evaluator "(/ 1 0)" {:silent? true})
;; => {:error "Division by zero" 
;;     :type "ArithmeticException"
;;     :code "(/ 1 0)"}
```

**Returns**:
- Success: `{:ok result}`
- Failure: `{:error message :type exception-type :code code-preview}`

### `resilient-eval`

Maximum resilience - combines all three patterns.

**Use Case**: Critical operations that must be maximally reliable.

```clojure
;; Combines retry + fallback + safe wrapper
(resilience/resilient-eval
  evaluator
  "(critical-operation)"
  {:mode :explicit
   :fallback-mode :silent
   :max-retries 5
   :retry-delay-ms 100})

;; Always returns safe result, tries everything to succeed
```

## Helper Predicates

Work with both result formats (`{:success ...}` and `{:ok ...}`):

```clojure
(resilience/success? result)  ;; Check if succeeded
(resilience/failed? result)   ;; Check if failed
(resilience/get-value result) ;; Extract value (nil if failed)
(resilience/get-error result) ;; Extract error (nil if succeeded)

;; Example usage
(let [result (resilience/safe-eval evaluator "(+ 1 2)" {:silent? true})]
  (if (resilience/success? result)
    (println "Got:" (resilience/get-value result))
    (println "Error:" (resilience/get-error result))))
```

## Usage Patterns

### Pattern 1: Non-Critical Interactive Evaluation

When you want REPL feedback but can fall back to silent:

```clojure
(defn eval-with-ui-feedback [evaluator code]
  (resilience/eval-with-fallback
    evaluator
    code
    {:mode :explicit
     :fallback-mode :silent}))
```

### Pattern 2: Batch Processing

Process multiple evaluations with retries:

```clojure
(defn batch-eval [evaluator codes]
  (mapv #(resilience/eval-with-retry
           evaluator
           %
           {:silent? true}
           :max-retries 3
           :delay-ms 50)
        codes))
```

### Pattern 3: Server API

Never throw in API handlers:

```clojure
(defn api-eval-handler [evaluator request]
  (let [code (:code request)
        result (resilience/safe-eval evaluator code {:silent? true})]
    (if (resilience/success? result)
      {:status 200 :body {:result (resilience/get-value result)}}
      {:status 500 :body {:error (resilience/get-error result)}})))
```

### Pattern 4: Critical Operations

Maximum reliability:

```clojure
(defn critical-namespace-load [evaluator ns-sym]
  (resilience/resilient-eval
    evaluator
    (format "(require '%s :reload)" ns-sym)
    {:mode :explicit
     :fallback-mode :silent
     :max-retries 5
     :retry-delay-ms 200}))
```

## CLARITY Principle Alignment

This implementation follows the CLARITY framework's **"Yield safe failure"** principle:

1. **Graceful Degradation** - Falls back to less preferred but functional modes
2. **Structured Errors** - Returns error data instead of throwing
3. **Retry Logic** - Handles transient failures automatically
4. **Telemetry** - Logs all failure paths and fallback usage
5. **Never Panic** - `safe-eval` catches even unexpected Throwables

## Architecture Notes

### Design Patterns

1. **Strategy Pattern** (GoF) - Different evaluation modes as strategies
2. **Fallback Pattern** - Cascade through alternatives on failure
3. **Retry Pattern** - Exponential backoff for transient failures
4. **Null Object Pattern** - `safe-eval` returns data instead of nil/exceptions

### DDD Boundaries

The resilience layer sits at the Infrastructure Layer, wrapping the `ReplEvaluator` protocol from the Domain Layer. It provides cross-cutting concerns (retry, fallback) without polluting domain logic.

### Performance Considerations

- `eval-with-fallback`: 2x evaluation time in worst case (both modes fail)
- `eval-with-retry`: N×(base_time + delays) where N is attempts
- `safe-eval`: Minimal overhead (single try-catch)
- `resilient-eval`: Maximum overhead (combines all patterns)

**Recommendation**: Use the simplest pattern that meets your reliability requirements.

## Future Enhancements

### Circuit Breaker Pattern

Currently in planning (see `resilience.clj` comments). Would prevent cascading failures by "opening" after repeated failures:

```clojure
;; Future API
(def breaker (make-circuit-breaker {:failure-threshold 5
                                     :timeout-ms 30000}))

(eval-with-circuit-breaker breaker evaluator code opts)
```

States:
- **Closed**: Normal operation
- **Open**: Too many failures, reject immediately (fail fast)
- **Half-Open**: Testing if service recovered

This follows Michael Nygard's "Release It!" patterns and is common in microservices.

## Testing

See `test/emacs_mcp/resilience_test.clj` for comprehensive test coverage including:

- Mock evaluators for different failure scenarios
- Fallback behavior tests
- Retry logic with exponential backoff
- Safe wrapper exception handling
- Integration tests with realistic scenarios

Run tests:

```bash
clojure -X:test
```

## References

- CLARITY Framework: `.cursor/rules/00_clarity_master.mdc`
- Domain Model: `src/emacs_mcp/evaluator.clj`
- Release It! by Michael Nygard (Circuit Breaker pattern)
- GoF Design Patterns (Strategy, Decorator)
