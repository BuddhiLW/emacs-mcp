# ADR: Cluster A — Lifecycle via Protocols, Concretions in Addons

**Status:** Accepted (2026-04-24)
**Supersedes:** kanban tasks 20260417184801-7b6a0aa0, 20260417184805-63d6b6ae, 20260417184809-69bbb336 (closed as partial-done)
**Refines:** decision 20260202004516-45e16b85 (Interface-Agnostic Box Architecture)
**Applies:** convention 20260215011959-7e07b9cf (IAddon Composability Principle)

## Context

Audit of the current hive-mcp lifecycle surface showed the core module is
directly coupled to addon-specific concretions, violating the
Interface-Agnostic Box decision (20260202004516-45e16b85). Concrete findings:

- `src/hive_mcp/server/lifecycle.clj:61-80` — the JVM shutdown hook directly
  calls `olympus-ws/stop!` and resolves `hive-mcp.swarm.datascript/mark-coordinator-terminated!`
  inline. The core lifecycle module knows about Olympus and the DataScript
  swarm store by name, so either addon disappearing requires edits to core.
- `src/hive_mcp/agent/headless.clj:406-456` — `watchdog-sweep!` is bolted
  onto the headless addon as a private fn with its own
  `ScheduledExecutorService`, reading the headless-private `process-registry`.
  There is no generic "sweep" abstraction the core can schedule; every future
  sweepable resource (carto caches, idle sessions, dead LSP sidecars) would
  copy this pattern.
- `src/hive_mcp/agent/ling/spawn.clj:299-333` — `kill!` already uses
  `requiring-resolve` for the NATS bridge and swarm-event-bridge (good), but
  still makes direct calls to `ds-lings/can-kill?`, `ds-lings/remove-slave!`,
  and `lifecycle/resolve-strategy`. Release-of-claims and DataScript mutation
  run inline with no priority ordering, so kill-ordering relies on call-site
  convention rather than a registry.

Connection to solo-maintainer axiom 20260228020131-33d15f0b: bus factor = 1,
so hive-mcp core must stay small enough to hold in one engineer's head.
Every addon-shaped branch that leaks into core inflates the blast radius of
a single change.

## Decision

hive-mcp core defines three lifecycle protocols — `IShutdownHook`,
`ISweepable`, `IResourceOwner` — and owns three registries plus two
orchestrators (a JVM shutdown hook and a sweep coordinator).

- **Protocols** live in `src/hive_mcp/protocols/lifecycle.clj`.
- **Registries** (`shutdown-registry`, `sweep-registry`, `resource-registry`)
  live under `src/hive_mcp/system/registry.clj` as atoms of
  priority-ordered vectors keyed by participant id.
- **Orchestrators**: the shutdown orchestrator in `server/lifecycle.clj`
  iterates `shutdown-registry` in priority order under a wall-clock budget;
  the sweep coordinator in `system/sweep_coordinator.clj` schedules all
  `ISweepable` participants on one shared executor.
- **Concrete implementations** live in their addon projects (hive-agent,
  hive-knowledge, hive-nats, hive-qdrant, lsp-mcp, etc.) and register onto
  the hive-mcp registries via `requiring-resolve` from inside the addon's
  `IAddon.init!`.

## Architecture diagram (ASCII)

```
                hive-mcp (core)
  ┌─────────────────────────────────────────┐
  │ protocols/lifecycle.clj                 │
  │   IShutdownHook, ISweepable, IResourceOwner
  │ system/registry.clj                     │
  │   shutdown-registry, sweep-registry, resource-registry
  │ system/sweep_coordinator.clj            │
  │ server/lifecycle.clj  (orchestrator)    │
  └──────────────▲──────────────────────────┘
                 │ register! via requiring-resolve
                 │
  ┌──────────────┼──────────────┬──────────┬──────────────┐
  │              │              │          │              │
  hive-agent  hive-knowledge  hive-nats  hive-qdrant  lsp-mcp
   (lings,     (KG stores,    (NATS      (vector     (sidecar
    headless,   carto cache   client     store        process)
    terminal)   sweep)        close)     close)
```

## Priority bands

| Band    | Phase                  | Examples                                   |
|---------|------------------------|--------------------------------------------|
| 0-99    | External service stop  | Olympus WS                                 |
| 100-199 | Subprocess kill        | Lings, LSP sidecars, headless processes    |
| 200-299 | Client close           | NATS, Chroma, Qdrant                       |
| 300-399 | Store close            | Datalevin, Datahike, Proximum              |
| 400+    | Final bookkeeping      | Coordinator mark-terminated, session-end   |

Bands are enforced by the registry at insertion time; participants declare
their band in the registration call and the orchestrator sorts ascending.

## Consequences

**Positive**

- hive-mcp core shrinks; concrete addon deps drop out of the core compile
  graph. Today `server/lifecycle.clj` imports `olympus-ws`; after migration
  it imports only the protocol and registry namespaces.
- Testability: fixtures inject fake hooks without touching live state
  (applies axiom 20260122235103-7151cc29 — test isolation).
- Adding a new addon with lifecycle = `register!` call in its `init!`; no
  core edits.
- Runs cleanly with any subset of addons on the classpath. Missing addon =
  missing registration, not a `ClassNotFoundException`.

**Negative**

- Addon authors MUST register hooks; a forgotten registration is a silent
  leak (no compile-time check catches it).
- Priority bands need discipline; there is no compile-time ordering
  enforcement beyond the numeric band.
- `requiring-resolve` at register time adds a small startup cost (one-shot
  per addon, bounded).

## Migration phases

- **Phase A (greenfield):** add protocols, registries, orchestrators to core.
  No behavior change.
- **Phase B (internalize):** register the current in-core concretions
  (Olympus stop, swarm mark-terminated, headless watchdog, ling kill) via
  the new registry. Behavior preserved; all calls now flow through the
  registry.
- **Phase C (extract):** move registrations out to addons one at a time.
  Safe and reversible — reverting a single addon's `init!` is a local edit.

## Alternatives considered

- **Dependency injection via Component/Integrant** — rejected: heavier
  ceremony, more moving parts. The solo-maintainer axiom
  (20260228020131-33d15f0b) pushes for minimal infrastructure.
- **Hard-coded `when (resolve ...)` switches on classpath presence** —
  rejected: non-composable and violates the IAddon principle
  (20260215011959-7e07b9cf). Core ends up knowing every addon namespace.
- **Single god-hook per addon (one fn, many responsibilities)** —
  rejected: violates SRP; different lifecycle phases need different
  priorities and different triggering conditions (shutdown vs. sweep vs.
  resource-close are not the same event).

## Verification

- Property test: registry priority ordering + budget-rescue semantics
  (task v1 — precedes this ADR).
- Integration test: shutdown sequence runs cleanly with hive-agent OFF the
  classpath (task v2).

## Cross-references

- Plan: `/home/leibniz/PP/hive/hive-mcp/plans/cluster-a-lifecycle-protocols.edn`
- Decision: 20260202004516-45e16b85 (Interface-Agnostic Box Architecture)
- Convention: 20260215011959-7e07b9cf (IAddon Composability Principle)
- Axiom: 20260122235103-7151cc29 (test isolation via injected fixtures)
- Axiom: 20260226152711-04b81ae8 (channel leak pattern)
- Axiom: 20260227204442-3d0e1f7c (catch Throwable in futures)
- Axiom: 20260228020131-33d15f0b (solo maintainer / bus factor = 1)
