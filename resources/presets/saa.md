# SAA Preset: Silence-Abstract-Act

You follow the **SAA workflow** — a Korzybski-grounded methodology for structured exploration and implementation.

## Philosophy (General Semantics)

| Phase | Korzybski Term | Action |
|-------|----------------|--------|
| **S** | Silence on objective levels | Ground in territory (read files, trace code) without premature labeling |
| **A** | Abstracting | Create structured plan (EDN) with steps and dependencies |
| **A** | Acting | Execute plan via DAG-Wave, validate with TDD |

> "The map is not the territory." — Read first, abstract second, act third.

## The d[l]e Pattern

```
d[l]e SAA = dispatch ling explore → Silence-Abstract-Act
```

**Invocation:**
```
d[l]e SAA <task-description>
```

**Pipeline:**
```
[kanban task] → [explorer ling] → [EDN plan] → plan_to_kanban → [kanban tasks] → DAG-Wave
```

## Phase 1: Silence (Exploration)

Ground in the territory before creating abstractions:

1. **Catchup** — Load project context from memory
2. **KG-First** — Consult Knowledge Graph before reading files
3. **Search** — Find relevant files, patterns, existing solutions
4. **Trace** — Follow code paths, understand data flow
5. **NIH Check** — Does this already exist? Check before building

**Key Principle:** Spend tokens learning the territory, not guessing.

## Phase 2: Abstract (Plan Creation)

Create a structured EDN plan conforming to the `plan_to_kanban` contract.

### Plan Contract (Malli Schema)

```clojure
;; Required fields
{:id    "plan-<timestamp>-<topic>"   ;; Unique plan ID
 :title "Short descriptive title"    ;; Becomes plan name
 :steps [{:id          "step-1"      ;; Unique within plan
          :title       "Step title"  ;; Becomes kanban task title
          :description "Details..."  ;; Optional: context for implementer
          :depends-on  []            ;; Step IDs this depends on
          :priority    :high         ;; :high | :medium | :low
          :files       ["path.clj"]  ;; Files this step touches
          :wave        1}            ;; Execution wave (parallel group)
         ...]}

;; Optional fields
{:decision-id  "memory-entry-id"     ;; Links to source ADR/decision
 :description  "Plan summary"        ;; Brief overview
 :tags         ["topic" "area"]}     ;; For searchability
```

### Priority Values
- `:high` — Critical path, blocks other work
- `:medium` — Important but not blocking
- `:low` — Nice to have, can be deferred

### Estimate Values (Optional)
- `:small` — < 1 hour, single function/file
- `:medium` — 1-4 hours, multi-file change
- `:large` — > 4 hours, significant feature

### Wave Structure

Group parallelizable steps into waves:

```clojure
:waves
{:wave-1 {:steps [:step-1 :step-2 :step-4] :parallel true}
 :wave-2 {:steps [:step-3 :step-5] :parallel false}
 :wave-3 {:steps [:step-6 :step-7] :parallel true}}
```

**Wave Rules:**
- Wave N can only start after Wave N-1 completes
- Steps within a wave with `:parallel true` run concurrently
- Dependencies within a wave force sequential execution

## Phase 3: Act (Execution)

### Option A: plan_to_kanban (Recommended)

Store plan in memory, then convert:

```clojure
;; 1. Store plan in memory
(mcp_memory_add
  :type "note"
  :content "<EDN plan>"
  :tags ["plan" "SAA" "<topic>"]
  :duration "medium")

;; 2. Convert to kanban tasks
(plan_to_kanban :plan_id "<memory-id>")
```

This creates:
- Kanban tasks for each step
- KG edges: plan → tasks
- KG edges: task → task (dependencies)

### Option B: Direct File Output

Write plan to `src/hive_mcp/plan/<topic>.edn`:

```clojure
(write-file "src/hive_mcp/plan/lazy-preset-loading.edn" <plan>)
```

Then manually call `plan_to_kanban` or spawn lings for each wave.

### Option C: DAG-Wave Dispatch

For immediate execution without kanban:

```clojure
(wave :command "dispatch"
      :tasks [{:file "src/foo.clj" :task "Add function X"}
              {:file "src/bar.clj" :task "Update call site"}])
```

## Output File Convention

If writing plan to file:

```
src/hive_mcp/plan/<topic>.edn
```

Example: `src/hive_mcp/plan/lazy-preset-loading.edn`

## Memory Storage

Always store your plan in memory for future reference:

```clojure
(mcp_memory_add
  :type "note"
  :content "<full EDN plan>"
  :tags ["plan" "SAA" "exploration-output" "<topic>"]
  :duration "medium"
  :directory "/path/to/project")
```

Required tags:
- `plan` — Identifies as plan entry
- `SAA` — Marks as SAA workflow output
- Topic-specific tag for searchability

## Progress Reporting

```clojure
;; Start of Silence phase
(hivemind_shout :event_type "started" :message "SAA Silence: Exploring <topic>")

;; End of Silence phase
(hivemind_shout :event_type "progress" :message "SAA Abstract: Creating plan...")

;; Completion
(hivemind_shout :event_type "completed"
                :message "SAA complete: <N> steps, <M> waves. Plan ID: <id>")
```

## Anti-Patterns

- **Premature abstraction** — Creating plan before reading territory
- **Missing dependencies** — Steps that reference non-existent step IDs
- **Circular dependencies** — A depends on B depends on A
- **Vague steps** — "Implement the feature" instead of specific actions
- **Missing files** — Steps without `:files` make assignment hard
- **Skipping memory** — Not storing plan for future reference

## Validation Checklist

Before outputting plan:

- [ ] All step IDs are unique
- [ ] All `:depends-on` references exist
- [ ] No circular dependencies
- [ ] Each step has clear `:title` and `:description`
- [ ] `:files` specified for code-change steps
- [ ] Waves correctly group parallel work
- [ ] Plan stored in memory with proper tags

## Example Complete Plan

```edn
{:id "plan-20260130-lazy-presets"
 :title "Lazy Preset Loading"
 :description "Token-efficient preset loading via memory queries"
 :decision-id "20260130012010-40a7ebe6"
 :tags ["presets" "token-efficiency" "SAA"]

 :steps
 [{:id "step-1"
   :title "Add preset_core tool"
   :description "Returns summary without full content (~200 vs ~1500 tokens)"
   :files ["src/hive_mcp/presets.clj" "src/hive_mcp/tools/presets.clj"]
   :depends-on []
   :priority :high
   :wave 1}

  {:id "step-2"
   :title "Add lazy-prompt-header generator"
   :description "Build instructions for on-demand preset querying"
   :files ["src/hive_mcp/tools/swarm/prompt.clj"]
   :depends-on []
   :priority :high
   :wave 1}

  {:id "step-3"
   :title "Expose via MCP tool"
   :description "handle-swarm-preset-header for elisp to call"
   :files ["src/hive_mcp/tools/swarm/lifecycle.clj"]
   :depends-on ["step-2"]
   :priority :high
   :wave 2}]

 :waves
 {:wave-1 {:steps ["step-1" "step-2"] :parallel true}
  :wave-2 {:steps ["step-3"] :parallel false}}}
```

## Composability

This preset works with:
- `explorer` — Detailed exploration methodology
- `mcp-first` — Use hive tools effectively
- `hivemind` — Coordinator awareness
- `tdd` — Validation in Act phase
