# REPL Evaluator

## Overview

The `emacs-mcp.evaluator` namespace provides a protocol-based abstraction for REPL evaluation, following Domain-Driven Design (DDD) principles. This is an **Infrastructure Layer** adapter that bridges the MCP server with Emacs CIDER REPL.

## Architecture

### Protocol: ReplEvaluator

The core abstraction defines three operations:

1. **`eval-code`** - Evaluate code with options (silent vs explicit)
2. **`connected?`** - Check if the REPL is connected
3. **`get-status`** - Get detailed connection status

### Implementation: EmacsCiderEvaluator

The `EmacsCiderEvaluator` record implements `ReplEvaluator` using:
- `emacsclient` to communicate with a running Emacs instance
- CIDER elisp functions via `emacs-mcp-cider.el`
- Two evaluation modes: silent and explicit/interactive

## Evaluation Modes

### Silent Evaluation (`:silent? true`)
- Executes code via `emacs-mcp-cider-eval-silent`
- Returns result without showing in REPL buffer
- Minimal UI feedback
- Best for: Background operations, automated scripts, MCP tool handlers

### Explicit/Interactive Evaluation (`:silent? false`, default)
- Executes code via `emacs-mcp-cider-eval-explicit`
- Shows evaluation in the REPL buffer
- Full interactive feedback (namespace, value, output)
- Best for: User-initiated evaluations, debugging, exploratory programming

## Usage

### Basic Usage

```clojure
(require '[emacs-mcp.evaluator :as eval])

;; Create an evaluator
(def evaluator (eval/make-emacs-cider-evaluator))

;; Check connection
(eval/connected? evaluator)
;; => true

;; Get detailed status
(eval/get-status evaluator)
;; => {:connected true, :type "cider", :info {...}}

;; Evaluate code silently
(eval/eval-silent evaluator "(+ 1 2)")
;; => {:success true, :result "3"}

;; Evaluate code explicitly (shows in REPL)
(eval/eval-explicit evaluator "(println \"Hello, CIDER!\")")
;; => {:success true, :result "nil"}
```

### Advanced Usage with Options

```clojure
;; Evaluate with explicit options
(eval/eval-code evaluator 
                "(defn greet [name] (str \"Hello, \" name))"
                {:silent? false})
;; => {:success true, :result "#'user/greet"}

;; Silent evaluation for automation
(eval/eval-code evaluator 
                "(greet \"World\")"
                {:silent? true})
;; => {:success true, :result "\"Hello, World\""}
```

### Error Handling

```clojure
;; Evaluating invalid code
(eval/eval-silent evaluator "(+ 1)")
;; => {:success false, :error "Wrong number of args (1) passed to: clojure.core/+"}

;; When CIDER is not loaded
(eval/get-status evaluator)
;; => {:connected false, :error "emacs-mcp-cider not loaded"}
```

## Integration with MCP Tools

The evaluator can be used in MCP tool handlers to provide consistent REPL evaluation:

```clojure
(ns my-app.tools
  (:require [emacs-mcp.evaluator :as eval]))

(def ^:private repl-evaluator (eval/make-emacs-cider-evaluator))

(defn handle-eval-code [{:keys [code interactive]}]
  (let [opts {:silent? (not interactive)}
        result (eval/eval-code repl-evaluator code opts)]
    (if (:success result)
      {:type "text" :text (:result result)}
      {:type "text" :text (:error result) :isError true})))
```

## Dependencies

The evaluator requires:
1. A running Emacs instance with `emacsclient` server enabled
2. CIDER installed in Emacs
3. `emacs-mcp-cider.el` loaded (provides the elisp functions)
4. An active CIDER REPL connection

## Design Principles

### DDD - Infrastructure Layer
- Abstracts external system interaction (Emacs/CIDER)
- Protocol defines domain contract
- Implementation handles technical details

### Dependency Injection
- Evaluator is created once and passed around
- Facilitates testing with mock implementations
- Supports multiple evaluator types in the future

### Single Responsibility
- Protocol: Define REPL operations
- Record: Implement CIDER-specific communication
- Convenience functions: Simplify common use cases

### Open/Closed Principle
- Protocol is closed for modification
- New evaluator types can be added (nREPL direct, ClojureScript, etc.)
- No changes needed to existing code using the protocol

## Future Extensions

Possible additional implementations:

- **DirectNreplEvaluator** - Direct nREPL connection without Emacs
- **ClojureScriptEvaluator** - Evaluate ClojureScript via shadow-cljs
- **RemoteEvaluator** - Evaluate on remote REPL servers
- **BatchEvaluator** - Execute multiple evaluations efficiently

All would implement the same `ReplEvaluator` protocol, allowing drop-in replacement.
