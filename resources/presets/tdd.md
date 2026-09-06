# TDD (Test-Driven Development) Preset

You follow strict **Red-Green-Refactor** discipline.

## The TDD Cycle

1. **RED**: Write a failing test that expresses minimal new behavior
2. **GREEN**: Make it pass with the simplest possible change
3. **REFACTOR**: Remove duplication, clarify names, keep tests green

## Rules

- Keep cycles **small** (<5 minutes ideally)
- Write the **test first**, always
- Only write **production code to make a failing test pass**
- **One assertion** per test (prefer)
- Tests are **documentation** - make them readable
- **Never** skip the refactor step

## REPL-First TDD (Clojure)

For Clojure/ClojureScript, use REPL for rapid red→green cycles:

```clojure
;; 1. Write test in file, then hot-reload
(require '[my.test-ns :reload true])

;; 2. Run single test - instant feedback
(my.test-ns/test-my-function)
;; or
(clojure.test/run-tests 'my.test-ns)

;; 3. Fix code, reload, rerun
(require '[my.src-ns :reload true])
(my.test-ns/test-my-function)

;; 4. Iterate until green, THEN run full suite
```

**Why REPL-first:**
- No JVM cold starts (saves 5-10 sec per cycle)
- Instant feedback loop
- Hot reload preserves state
- Full `clojure -M:test` only at end for verification

**MCP Tools:**
- `clojure_eval` for REPL evaluation
- `cider_eval_silent` if CIDER connected
- `bash clojure -M:test` only for final verification

## Test Structure (AAA)

```
Arrange → Set up preconditions and inputs
Act     → Execute the behavior under test
Assert  → Verify the expected outcome
```

## Naming Convention

```
Test_<Unit>_<Scenario>_<ExpectedBehavior>

Examples:
- Test_Parser_EmptyInput_ReturnsError
- Test_Cache_ExpiredEntry_RefetchesData
- Test_Validator_InvalidEmail_ReturnsFalse
```

## What Makes a Good Test

- **Fast**: Milliseconds, not seconds
- **Isolated**: No dependencies between tests
- **Repeatable**: Same result every time
- **Self-validating**: Pass or fail, no manual inspection
- **Timely**: Written before production code

## Memory Discipline

When you spend tokens learning something, FREEZE IT immediately:

- **Test Pattern Discovered**: If you found a non-obvious way to test something, freeze it as a snippet
- **Friction → Solution**: If a test approach failed and you found what works, freeze it as a convention
- **Framework Quirk**: If you discovered a testing framework gotcha, freeze it as a convention
- **Mock/Fixture Pattern**: If you built a reusable test helper pattern, freeze it as a snippet

```
mcp_memory_add(type: "snippet", content: "Test pattern: To test X, use Y approach because Z", tags: ["testing", "pattern", "<framework>"])
```

Rule of thumb: If you spent >30 seconds figuring out how to test something, freeze it.

## Anti-Patterns to Avoid

- Testing implementation details (test behavior, not structure)
- Excessive mocking (if you mock everything, you test nothing)
- Flaky tests (fix or delete them)
- Slow tests in the fast feedback loop
- Comments in tests (the test IS the documentation)

## Before Commit (Elisp)

For elisp files, **always byte-compile before committing**:

```elisp
(byte-compile-file "path/to/file.el")
```

Fix ALL warnings before commit:
- Quote escaping: `#'fn` → `#\='fn` in docstrings
- Unused variables: remove or prefix with `_`
- Missing `declare-function` for external functions
- Unbalanced parentheses
