# Preset roles (v2)

The v2 presets are **orthogonal axes**, not personas. Each one states a single discipline, so they
compose without contradicting each other. A role is a named bundle.

## Axes

| Preset               | Axis                                                       |
|----------------------|------------------------------------------------------------|
| `carto-first`        | how code is navigated and edited                           |
| `cppb-stratified`    | how a subsystem is layered                                 |
| `malli-first`        | how values are modeled                                     |
| `ddd-ports`          | how the domain is separated from its infrastructure        |
| `ocp-data`           | how behaviour is extended                                  |
| `repl-first`         | how claims about running code are verified                 |
| `trifecta`           | how a unit is covered                                      |
| `memory-crystallize` | where rationale is recorded                                |
| `commit-hygiene`     | how work is staged and committed                           |
| `gitops-safety`      | how cluster-affecting changes are handled                  |
| `subtask-worker`     | how a delegated task is scoped and reported                |

## Roles

| Role            | Presets |
|-----------------|---------|
| `clj-subsystem` | carto-first, cppb-stratified, malli-first, ddd-ports, ocp-data, repl-first, trifecta, memory-crystallize, commit-hygiene |
| `clj-worker`    | subtask-worker, carto-first, repl-first, memory-crystallize, commit-hygiene |
| `test-author`   | subtask-worker, carto-first, malli-first, trifecta, repl-first, commit-hygiene |
| `ops-worker`    | subtask-worker, gitops-safety, memory-crystallize, commit-hygiene |
| `investigator`  | subtask-worker, carto-first, repl-first, memory-crystallize |

## Loading

Preset markdown is injected at spawn from `${user.dir}/presets/*.md` — override with
`HIVE_MCP_PRESETS_DIR` or config `[:presets :dir]`. Claude CLI lings additionally pick up
`.claude/agents/`.

The `swarm preset list` / `search` commands query a **chroma** collection (`hive-mcp-presets`) that
is no longer populated — semantic discovery is dead while file injection still works. Either
re-point that index at the live vector store or drop the command; leaving it returning `[]` reads as
"no presets exist", which is false.

## Relation to v1

The v1 files (`solid.md`, `ddd.md`, `tdd.md`, `clarity.md`, `tester.md`, …) predate carto, hive
memory, hive-schemas, and the CPPB vocabulary. They describe generic best practice; v2 describes how
this ecosystem actually works. Keep v1 only until nothing references it.
