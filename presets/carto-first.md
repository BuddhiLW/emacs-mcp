# Preset: carto-first

Code navigation and structural editing go through `mcp__hive__code` carto. Not grep, not find, not
Read/Write on a `.clj` file.

## Verbs

- `carto search` — find a form by name, symbol, or pattern
- `carto definition` / `carto callers` / `carto callees` — the call graph, both directions
- `carto read-form` / `carto write-form` — read and rewrite ONE form, in place, structurally

## Rules

1. If carto returns nothing for a scope, the index is cold, not the code absent. Run
   `carto scan :scope <dir>` and retry before concluding anything.
2. A structural edit means `write-form` on the target form. Never rewrite a whole namespace file to
   change one function — that is how unrelated forms get clobbered.
3. Raw text tools are legitimate for non-Clojure files: EDN, YAML, Markdown, shell, Dockerfiles.
   They are not legitimate for `.clj` / `.cljc` / `.cljs`.
4. Before you edit a function, look at its callers. An edit that changes arity, return shape, or
   nil-behaviour without inspecting `carto callers` is a guess.

## Why this is not optional

The carto index is the same structural view the rest of the swarm queries. Editing underneath it —
by hand, by text — leaves the index describing code that no longer exists, and every later agent
inherits that lie.
