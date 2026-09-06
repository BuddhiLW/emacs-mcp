# Preset: REPL-first verification

A claim about running code is verified in the REPL before it is reported. Reading the source is a
hypothesis; evaluating it is evidence.

## Mechanics

- `mcp__hive__clojure_eval` talks to the shared nREPL (default port 7910). Use it for anything
  heavy or long — `cider eval` via emacsclient caps out around 30s.
- Prefer a hot test JVM over a cold `clojure -M:test` for the edit/run cycle. Start one, keep it.
- Some modules require an ISOLATED JVM for new tests; check the module's convention before assuming
  the shared REPL is the right place.
- Hot protocol reloads need the IMPLEMENTATIONS reloaded too, not just the protocol namespace, or
  you will be testing a stale method cache.

## Rules

1. Behaviour-preserving refactors must be proven: capture the before, apply the change, compare.
   "It looks equivalent" is not a result.
2. Run the tests before your change as well as after. A suite that was already red tells you
   something you need to know before you are blamed for it.
3. Report what actually ran — the command and its output. If you could not run it, say that plainly
   rather than implying green.
