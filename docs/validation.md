# Input Validation Implementation

## Overview

Following the CLARITY principle "Inputs are guarded", this implementation provides comprehensive input validation for REPL evaluation and other MCP tool operations.

## Implementation

### Core Validation Module

**File**: `/home/lages/dotfiles/gitthings/emacs-mcp/src/emacs_mcp/validation.clj`

The validation module provides:

1. **Individual field validators**:
   - `validate-code` - Ensures code is a non-empty string
   - `validate-mode` - Validates mode is `:silent` or `:explicit`
   - `validate-port` - Validates port is in range 1-65535
   - `validate-timeout` - Validates timeout is a positive integer
   - `validate-buffer-name` - Ensures buffer name is non-empty string
   - `validate-line-number` - Validates line number is positive

2. **Request validators**:
   - `validate-eval-request` - Complete REPL eval request validation
   - `validate-cider-eval-request` - CIDER-specific eval validation
   - `validate-buffer-request` - Buffer operation validation
   - `validate-goto-line-request` - Line navigation validation

3. **Error handling**:
   - `wrap-validation-error` - Converts validation exceptions to MCP error format

### Integration Points

**File**: `/home/lages/dotfiles/gitthings/emacs-mcp/src/emacs_mcp/tools.clj`

Updated handlers:
- `handle-eval-elisp` - Validates elisp code
- `handle-cider-eval-silent` - Validates CIDER silent eval requests
- `handle-cider-eval-explicit` - Validates CIDER explicit eval requests
- `handle-get-buffer-content` - Validates buffer name
- `handle-goto-line` - Validates line number

## Validation Rules

### Code Validation
- **Required**: Yes
- **Type**: String
- **Constraints**: Non-empty, non-blank
- **Error examples**:
  - `nil` → "Code is required"
  - `"   "` → "Code cannot be empty or blank"
  - `123` → "Code must be a string"

### Mode Validation
- **Required**: No
- **Type**: Keyword
- **Valid values**: `:silent`, `:explicit`
- **Error examples**:
  - `:invalid` → "Invalid mode (valid values: #{:silent :explicit})"
  - `"silent"` → "Mode must be a keyword"

### Port Validation
- **Required**: No
- **Type**: Integer
- **Range**: 1-65535
- **Error examples**:
  - `0` → "Port must be between 1 and 65535"
  - `99999` → "Port must be between 1 and 65535 (valid range: 1-65535)"
  - `"7888"` → "Port must be an integer"

### Timeout Validation
- **Required**: No
- **Type**: Integer
- **Constraints**: Positive (> 0)
- **Error examples**:
  - `0` → "Timeout must be positive"
  - `-100` → "Timeout must be positive"
  - `"5000"` → "Timeout must be an integer"

### Buffer Name Validation
- **Required**: Depends on operation
- **Type**: String
- **Constraints**: Non-empty, non-blank
- **Error examples**:
  - `"   "` → "Buffer name cannot be empty or blank"
  - `123` → "Buffer name must be a string"

### Line Number Validation
- **Required**: Depends on operation
- **Type**: Integer
- **Constraints**: Positive (> 0)
- **Error examples**:
  - `0` → "Line number must be positive"
  - `-5` → "Line number must be positive"
  - `"42"` → "Line number must be an integer"

## Error Format

All validation errors are thrown as `ex-info` with structured data:

```clojure
{:type :validation
 :field :code            ;; Field that failed validation
 :reason :missing        ;; Reason for failure (:missing, :blank, :invalid-type, :invalid-value, :out-of-range)
 :received "value"       ;; Optional: value that was received
 :valid #{...}           ;; Optional: valid values for the field
 :valid-range [min max]} ;; Optional: valid range for numeric fields
```

### MCP Response Format

Validation errors are converted to MCP error responses:

```clojure
{:type "text"
 :text "Validation error: Code is required (field: code)"
 :isError true}
```

## Usage Examples

### Valid Requests

```clojure
;; Basic eval request
(validate-eval-request {:code "(+ 1 2)"})
;; => nil (success)

;; Full eval request with all options
(validate-eval-request {:code "(println 1)"
                        :mode :silent
                        :port 7888
                        :timeout 5000})
;; => nil (success)

;; CIDER eval request
(validate-cider-eval-request {:code "(defn foo [] 42)"})
;; => nil (success)
```

### Invalid Requests

```clojure
;; Missing code
(validate-eval-request {})
;; => ExceptionInfo: "Code is required"
;;    {:type :validation, :field :code, :reason :missing}

;; Invalid mode
(validate-eval-request {:code "(+ 1 2)" :mode :wrong})
;; => ExceptionInfo: "Invalid mode"
;;    {:type :validation, :field :mode, :reason :invalid-value,
;;     :valid #{:silent :explicit}, :received :wrong}

;; Invalid port
(validate-eval-request {:code "(+ 1 2)" :port 99999})
;; => ExceptionInfo: "Port must be between 1 and 65535"
;;    {:type :validation, :field :port, :reason :out-of-range,
;;     :valid-range [1 65535], :received 99999}
```

### Handler Integration

```clojure
(defn handle-cider-eval-silent
  "Evaluate Clojure code via CIDER silently."
  [params]
  (try
    (v/validate-cider-eval-request params)
    (let [{:keys [code]} params]
      ;; ... evaluation logic ...
      )
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))
```

## Testing

Comprehensive test suite in `/home/lages/dotfiles/gitthings/emacs-mcp/test/emacs_mcp/validation_test.clj`:

- 14 test cases covering all validators
- Edge cases: nil, blank, wrong types, out of range
- Integration testing with handlers
- Error message formatting verification

### Running Tests via REPL

```clojure
(require '[emacs-mcp.validation :as v])

;; Test individual validators
(v/validate-code "(+ 1 2)")              ;; => nil (valid)
(v/validate-mode :silent)                ;; => nil (valid)
(v/validate-port 7888)                   ;; => nil (valid)

;; Test error handling
(try
  (v/validate-code nil)
  (catch Exception e
    (v/wrap-validation-error e)))
;; => {:type "text", :text "Validation error: ...", :isError true}
```

## Design Decisions

1. **Fail-fast validation**: Throws on first validation error rather than accumulating errors
2. **Structured error data**: Uses `ex-info` with detailed metadata for programmatic error handling
3. **Boundary validation**: All validation happens at system boundaries (handler entry points)
4. **Type safety**: Strict type checking prevents runtime errors deeper in the system
5. **Clear error messages**: User-friendly messages with context (field name, valid values/ranges)

## CLARITY Alignment

This implementation directly supports the CLARITY principle:

**I**nputs are guarded:
- All external inputs validated at system boundaries
- Type safety enforced before processing
- Invalid data rejected with clear error messages
- Prevents malformed requests from entering the system

## Future Enhancements

Potential additions:
- clojure.spec integration for more sophisticated validation
- Validation metrics (track validation failures)
- Custom validators for domain-specific constraints
- Validation rule composition
- Async validation support
