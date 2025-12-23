# Telemetry Quick Start

## What Was Added

Following the CLARITY principle "Telemetry first - observability is essential", comprehensive telemetry has been added to all evaluation operations in emacs-mcp.

## Files Created

1. **`src/emacs_mcp/telemetry.clj`** - Core telemetry utilities
   - `with-timing` - Macro for timing operations
   - `log-eval-request` - Log evaluation requests
   - `log-eval-result` - Log success/failure
   - `log-eval-exception` - Log exceptions
   - `with-eval-telemetry` - Complete wrapper for evaluations
   - `configure-logging!` - Configure Timbre logging

2. **`test/emacs_mcp/telemetry_test.clj`** - Comprehensive test suite
   - Tests for all telemetry functions
   - Examples of usage patterns
   - Verification of log output structure

3. **`doc/telemetry.md`** - Complete documentation
   - API reference
   - Usage examples
   - Best practices
   - Integration guide

## Files Modified

1. **`src/emacs_mcp/tools.clj`**
   - Added `emacs-mcp.telemetry` require
   - Wrapped `handle-eval-elisp` with telemetry
   - Wrapped `handle-cider-eval-silent` with telemetry
   - Wrapped `handle-cider-eval-explicit` with telemetry

2. **`src/emacs_mcp/emacsclient.clj`**
   - Enhanced `eval-elisp` to include timing information
   - Added structured logging for success/failure/exceptions
   - Returns duration-ms in result map

## Quick Usage

### Basic Timing
```clojure
(require '[emacs-mcp.telemetry :as telemetry])

(telemetry/with-timing "my-operation"
  (expensive-computation))
;; Logs: info :timing {:operation "my-operation" :ms 142}
```

### Evaluation Telemetry
```clojure
(telemetry/with-eval-telemetry :elisp code {:user "alice"}
  (eval-elisp code))
;; Logs request, timing, and result automatically
```

### Configure Logging
```clojure
;; Development mode
(telemetry/configure-logging! {:level :debug})

;; Production mode
(telemetry/configure-logging! {:level :info})
```

## What Gets Logged

For every evaluation operation, you now get:

1. **Request log** (`:eval-request`):
   - Mode (`:elisp`, `:cider-silent`, `:cider-explicit`)
   - Code length
   - Code preview (first 50 chars)
   - Any metadata provided

2. **Result log** (`:eval-success` or `:eval-failure`):
   - Duration in milliseconds
   - Success/failure status
   - Result length (on success)
   - Error message (on failure)

3. **Low-level logs** (`:emacsclient-success`/`:emacsclient-failure`):
   - emacsclient execution time
   - Exit codes
   - Error details

4. **Exception logs** (`:eval-exception`):
   - Exception type
   - Exception message
   - Full stack trace
   - Operation context

## Example Log Output

```
info [emacs-mcp.tools] :eval-request {:mode :elisp, :code-length 13, :code-preview "(buffer-list)"}
debug [emacs-mcp.emacsclient] :emacsclient-success {:duration-ms 12, :result-length 156}
info [emacs-mcp.tools] :eval-success {:duration-ms 12, :result-length 156}
```

## Benefits

1. **Debugging** - See exactly what code is being evaluated and results
2. **Performance** - Track evaluation duration to identify bottlenecks
3. **Monitoring** - Structured logs can be parsed by log aggregation tools
4. **Audit Trail** - Complete record of all evaluation operations
5. **Error Analysis** - Detailed exception information for troubleshooting

## Testing

Run the test suite to verify everything works:

```bash
clojure -M:test -n emacs-mcp.telemetry-test
```

## Integration

The telemetry is automatically active in:
- `eval-elisp` tool
- `cider-eval-silent` tool (if emacs-mcp-cider is loaded)
- `cider-eval-explicit` tool (if emacs-mcp-cider is loaded)

No configuration required - it works out of the box with sensible defaults (`:info` level).

## Next Steps

1. Review `doc/telemetry.md` for complete API documentation
2. Check `test/emacs_mcp/telemetry_test.clj` for usage examples
3. Customize logging configuration if needed
4. Add metadata to track user sessions or other context

## CLARITY Compliance

This implementation follows the CLARITY framework principle:

> **T**elemetry first - Observability is essential

All evaluation operations now have:
- Complete observability through structured logging
- Performance metrics (duration tracking)
- Error context for debugging
- Audit trail for compliance
