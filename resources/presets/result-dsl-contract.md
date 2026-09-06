# Result DSL Refactoring Contract

## Your Task
Refactor try-catch blocks in a Clojure file to use `hive-dsl.result` DSL constructs. Every try-catch MUST be classified and replaced according to the rules below. Zero exceptions to the contract.

## DSL Namespace
```clojure
(require '[hive-dsl.result :as result])
```

## Constructs Available

| Construct | Signature | Returns |
|---|---|---|
| `result/try-effect*` | `(try-effect* :cat/reason & body)` | `{:ok val}` or `{:error :cat/reason :message "..." :class "..."}` |
| `result/rescue` | `(rescue fallback & body)` | body result on success, fallback (with `::result/error` metadata) on exception |
| `result/rescue-fn` | `(rescue-fn f)` or `(rescue-fn f fallback)` | Wrapped fn returning fallback on exception — for `keep`/`map`/`filter` |
| `result/let-ok` | `(let-ok [x (may-fail) y (use x)] (result/ok (+ x y)))` | Short-circuits on first `{:error ...}`, like Either monad bind |
| `result/ok` | `(ok value)` | `{:ok value}` |
| `result/err` | `(err :cat/reason data-map)` | `{:error :cat/reason ...data}` |
| `result/ok?` | `(ok? r)` | boolean |
| `result/bind` | `(bind result f)` | monadic bind |
| `result/map-ok` | `(map-ok result f)` | functor map over ok |

## Classification Rules (MANDATORY)

### 1. BOUNDARY — IO/DB/network/elisp/process interop
**Signal**: The try body calls an external system (file IO, HTTP, Chroma, Datahike, elisp eval, socket, nREPL, subprocess).
**Replace with**: `result/try-effect*` with a domain-qualified category keyword.
```clojure
;; BEFORE:
(try
  (let [r (chroma/query coll params)]
    (process r))
  (catch Exception e
    (log/warn "query failed:" (.getMessage e))
    nil))

;; AFTER:
(result/try-effect* :chroma/query-failed
  (let [r (chroma/query coll params)]
    (process r)))
```
Category convention: `:domain/verb-failed` (e.g. `:kg/traverse-failed`, `:transport/connect-failed`, `:elisp/eval-failed`)

### 2. LOG-AND-NIL — try X catch log-warn/debug then return nil/[]/{}
**Signal**: catch block has `log/warn` or `log/debug` + returns nil, empty vec, or empty map.
**Replace with**: `result/rescue` — error info goes to metadata, NOT logging.
```clojure
;; BEFORE:
(try (kg/query store '[:find ...])
  (catch Exception e (log/debug "Failed:" (.getMessage e)) []))

;; AFTER:
(result/rescue [] (kg/query store '[:find ...]))
```
The caller can inspect error via `(::result/error (meta result))` if needed.

### 3. FALLBACK — try X catch return-default (no logging)
**Signal**: catch block returns a literal default value with no side effects.
**Replace with**: `result/rescue`
```clojure
;; BEFORE:
(try (json/read-str s :key-fn keyword) (catch Exception _ nil))

;; AFTER:
(result/rescue nil (json/read-str s :key-fn keyword))
```

### 4. PIPELINE — inside map/keep/filter/for
**Signal**: try-catch is inside an anonymous fn passed to a collection operation.
**Replace with**: `result/rescue-fn`
```clojure
;; BEFORE:
(keep (fn [x] (try (parse x) (catch Exception _ nil))) coll)

;; AFTER:
(keep (result/rescue-fn #(parse %)) coll)
```

### 5. MCP TOOL HANDLER — public handle-* function returning MCP response
**Signal**: Function name starts with `handle-`, returns `(mcp-json ...)` or `(mcp-error ...)`.
**Replace with**: Extract `logic*` fn returning Result, wrap with `try-result` + `result->mcp`.
```clojure
;; Per-file shared helpers:
(defn- try-result [category f]
  (try (f)
    (catch clojure.lang.ExceptionInfo e
      (result/err category {:message (ex-message e) :data (ex-data e)}))
    (catch Exception e
      (result/err category {:message (ex-message e) :class (str (class e))}))))

(defn- result->mcp [r]
  (if (result/ok? r)
    (mcp-json (:ok r))
    (mcp-error (or (:message r) (str (:error r))))))

;; Handler:
(defn handle-X [params]
  (log/info "X" {:params params})
  (result->mcp (try-result :domain/x-failed #(logic-x* params))))
```

### 6. ALREADY-MINIMAL — genuine boundary catch that's already clean
**Signal**: Single try-catch at the outermost level of a function, catch does meaningful recovery (not just nil/log).
**Action**: Leave as-is. Add a `; boundary` comment if desired.

## RECUR-ACROSS-TRY Special Case
If `recur` appears inside a `try` block, Clojure will fail to compile. Fix:
```clojure
;; BEFORE (broken):
(loop [...]
  (try (do-work) (recur ...) (catch Exception e ...)))

;; AFTER:
(loop [...]
  (let [r (result/rescue :retry (do-work))]
    (if (problem? r) (recur ...) r)))
```

## Verification Checklist (MUST pass before marking done)
1. `clj-kondo --lint <file>` — 0 errors, 0 warnings
2. `(require '<ns> :reload)` via nREPL — clean compile
3. Public API preserved: same defn names, same arities
4. `grep -c '(try' <file>` — 0 or 1 (boundary only)
5. No `log/warn` or `log/debug` inside catch blocks (use `rescue` metadata instead)

## Reference Files (already refactored)
- `tools/kg.clj` — MCP handler pattern with `try-result` + `result->mcp` + `let-ok`
- `tools/magit.clj` — Elisp boundary pattern with `elisp->result` + `handle-elisp`
- `tools/cider.clj` — Complex flow with `let-ok` chains
- `transport/core.clj` — Socket IO with `try-effect*` + DRY helpers
