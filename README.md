# hive-mcp

**Your AI finally remembers.**

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![MCP](https://img.shields.io/badge/MCP-Compatible-green.svg)](https://modelcontextprotocol.io)

---

## The Problem

You explain your codebase to Claude. Architecture, constraints, patterns. Then you hit the context limit. New session. **Claude forgets everything.**

## The Solution

```
Session 1                         Session 2
───────────────────────────────────────────────────
You: "Our auth uses JWT..."       You: catch me up
Claude: *learns*                  Claude: "I remember:
         ↓                         - Auth uses JWT with refresh
    *wrap up*                      - Convention: validate at boundaries
         ↓                         What should we work on?"
    [Memory]  ────────────────►
```

Persistent, project-scoped memory with semantic search. Conventions, decisions, snippets — stored locally, never forgotten.

### The two rituals

**Catch up** — reconstruct everything at the start of a session:

> hive `project workflow catchup` using pwd as dir

**Wrap up** — crystallize what you learned before you lose it:

> make memories on all learnings this session, kg connect them, sync kanban, create remaining kanban tasks if any, and `workflow wrap`. Can use `multi` command to do all at once.

Both are plain requests to the model, not slash commands — it reaches for the `project`, `memory`, `kg`, and `kanban` tools itself. `multi` batches the whole wrap into a single call.

---

## What Sets hive-mcp Apart

| Capability | hive-mcp | Typical MCP servers |
|---|---|---|
| **Knowledge Graph** | Structural edges - how knowledge relate? | Flat key-value or vector-only |
| **Session Continuity** | `/wrap` crystallizes, `/catchup` reconstructs — zero-down re-explaining across sessions | Manual copy-paste or lost |
| **Multi-Agent Coordination** | Lings (planners) + drones (executors) with file claims, hivemind shouts, and a continuous production belt | Single-agent only |
| **Scoped Memory** | Hierarchical Context Retrieval (HCR) - project scoping - with TTL decay | Global namespace or none |
| **Extension Architecture** | `requiring-resolve` stubs with noop fallbacks - plug your extensions and play | Monolithic |

---

## Quick Start

### 1. Install

**Option A: Automated with [hive-mcp-cli](https://github.com/hive-agi/hive-mcp-cli) (Recommended)**

```bash
# Requires Go 1.21+
go install github.com/hive-agi/hive-mcp-cli/cmd/hive@latest
go install github.com/hive-agi/hive-mcp-cli/cmd/hive-setup-mcp@latest

# Register and let Claude guide setup
claude mcp add hive-setup --scope user -- hive-setup-mcp
claude
# Ask: "Help me setup hive-mcp"
```

**Option B: Batteries-included, fully FOSS**

One command brings up the open-source stack — Chroma for memory, Ollama for embeddings,
optional LSP sidecar — waits until each is genuinely reachable, then starts the nREPL:

```bash
git clone https://github.com/hive-agi/hive-mcp.git && cd hive-mcp
bin/hive-mcp-foss

claude mcp add hive -- "$PWD/bin/hive-mcp-foss"
```

No private registry, no VPN, no credential store. See
[FOSS Quickstart](https://github.com/hive-agi/hive-mcp/wiki/FOSS-Quickstart) for the knobs
(`HIVE_TELEMETRY=1`, `HIVE_NATS=1`, remote Chroma/Ollama hosts).

**Option C: Manual**

```bash
export HIVE_MCP_DIR="$HOME/hive-mcp"
export BB_MCP_DIR="$HOME/bb-mcp"

git clone https://github.com/hive-agi/hive-mcp.git "$HIVE_MCP_DIR"
git clone https://github.com/hive-agi/bb-mcp.git "$BB_MCP_DIR"

claude mcp add hive --scope user -- "$HIVE_MCP_DIR/start-bb-mcp.sh"
```

### 2. Verify

```bash
claude mcp list | grep -q "hive" && echo "OK" || echo "FAILED"
```

### 3. Optional: Semantic Search

```bash
ollama pull nomic-embed-text      # Local embeddings
docker compose up -d chroma       # Chroma vector DB
```

### Prerequisites

| Requirement | Version | Install |
|---|---|---|
| Claude Code | Latest | [claude.ai/download](https://claude.ai/download) |
| Babashka | 1.3+ | [babashka.org](https://babashka.org) |
| Java | 17+ | `apt install openjdk-17-jdk` |

**Optional**: 
- Emacs 28.1+ for swarm vterm UI and buffer integration. See [Emacs Configuration](https://github.com/hive-agi/hive-mcp/wiki/Emacs-Configuration). 
- Headless mode works without Emacs (but WIP for stability of headless - recommended to use Emacs as a dependency).

---

## What hive-mcp Is

hive-mcp is a **host**: a runtime that other things mount into. It is not a library you
depend on, and it is not the thing you type into.

Three words circulate for systems in this space, and they aren't synonyms:

- **Harness** — the scaffolding that drives a model: prompt loop, tool dispatch, context
  management. Claude Code is a harness. hive-mcp is a harness *for the agents it spawns* —
  lings and drones get their loop, presets, budget and context from it — but it is not the
  harness you talk to.
- **MCP server** — the wire protocol. True, but that's transport, not architecture.
- **Host** — the runtime addons mount into and are amalgamated by. This is the load-bearing one.

In one sentence: **hive-mcp is an addon host that doubles as an agent harness.** Your MCP
client talks to it; addons supply the capabilities; it runs the sub-agents.

The rule that shapes everything else:

> hive-mcp is **CLOSED for modification, OPEN for extension via IAddon.**

Core owns protocols, registries, orchestrators, the server, memory CRUD, KG edges, swarm
coordination, the session ritual — and a **working noop default for every extension point**.
Everything else is an addon. That boundary is what makes the FOSS stack a complete system
rather than a demo, and what keeps the open core clean as the product layer grows.

---

## The Tool Surface

Tools are grouped into **domain roots**, each a namespace with subcommands (`memory add`,
`kg traverse`, `agent spawn`). Core ships these roots:

| Tool | Purpose |
|---|---|
| `memory` | Persistent entries with semantic search, TTL decay, scoping |
| `kg` | Knowledge Graph — edges, subgraphs |
| `agent` | Spawn/kill/dispatch lings and drones |
| `wave` | Parallel drone dispatch with validation |
| `hivemind` | Shout/ask coordination between agents |
| `session` | Wrap, catchup, whoami, context store |
| `workflow` | Forge belt, FSM-driven production cycles |
| `kanban` | Task management with plan-to-kanban |
| `magit` | Git operations — status, stage, commit, push |
| `cider` | Clojure nREPL eval, doc, completions |
| `preset` | Agent presets — list, search, generate headers |
| `analysis` | Kondo lint, SCC metrics, complexity hotspots |
| `lsp` | Code analysis — callers, calls, namespace graph |
| `project` | Projectile — files, search, hierarchy scan |
| `emacs` | Eval elisp, buffers, notifications |
| `olympus` | Grid layout control for multi-agent UI |
| `agora` | Multi-agent deliberation and debates |
| `config` | Runtime configuration management |
| `migration` | KG/memory backup, restore, backend switching |
| `multi` | Meta-facade — batches any of the above into one call |

Several of these (`cider`, `lsp`, `analysis`, `olympus`, `agora`) arrive from addons rather
than core. That's the point: **anything an addon registers that doesn't collide with a core
domain name becomes a new top-level tool root automatically** — no core edit, no allowlist
entry, no release. A config-driven visibility gate (`[:tool-roots :visible]`) can shrink the
advertised surface without breaking callers; hidden tools stay dispatchable by name, they
just leave `tools/list`.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│      Claude Code / any MCP client   (your harness)       │
└─────────────────────────┬────────────────────────────────┘
                          │ MCP protocol
┌─────────────────────────▼────────────────────────────────┐
│  hive-mcp — THE HOST (AGPL-3.0)                          │
│                                                          │
│   Memory  ──►  Chroma vectors + scoped entries           │
│   KG      ──►  DataScript / Datalevin / Datahike         │
│   Swarm   ──►  lings + drones + hivemind                 │
│   Session ──►  catchup / wrap rituals                    │
│                                                          │
│   protocols · registries · orchestrators · noop defaults │
└─────────────────────────┬────────────────────────────────┘
                          │ IAddon — addon → core, never the reverse
      ┌───────────────────┼───────────────────┐
      ▼                   ▼                   ▼
  :addon              :library             :addon
  (user-facing        (backend: vector     (user-facing
   tools)              store, terminal)     tools)
```

Dependencies and knowledge flow **addon → core**. A `requiring-resolve` of a concrete addon
namespace from core is the smell that says the boundary broke.

---

## Extensibility

hive-mcp uses a plugin architecture based on the **IAddon protocol** with automatic classpath discovery. Creating a new addon takes one command:

```bash
clojure -Sdeps '{:deps {io.github.hive-agi/hive-mcp {:git/tag "vX.Y.Z" :git/sha "..."}}}' \
  -Tnew create :template hive-agi/addon :name com.example/my-addon
```

This generates a complete project with:
- **IAddon protocol implementation** (defrecord with 8 lifecycle methods)
- **META-INF classpath manifest** (auto-discovered at startup, zero core changes)
- **Unit tests** (12 tests covering lifecycle, tools, health)
- **REPL-ready** development setup

### Addon Types

| Type | Use case |
|------|----------|
| **Native** | Clojure code in the same JVM — direct function calls |
| **MCP Bridge** | Proxy to external MCP servers via stdio/sse |
| **External** | Non-MCP integrations (REST APIs, CLI tools) |

Manifests also carry `:addon/kind` — `:addon` for anything contributing user-facing tools,
`:library` for pure backends (vector store, terminal, instrumentation).

### How It Works

Addons are discovered via `META-INF/hive-addons/*.edn` manifest files on the classpath (same pattern as Java's `ServiceLoader`). Manifests declare dependencies, and addons are loaded in topological order. No changes to hive-mcp core code needed.

Behaviour reaches core code paths through **generic extension keys**. Core defines the seam
and applies whatever is registered; it never learns that a given addon exists:

```clojure
;; in core — addon-agnostic, the only legitimate kind of core change
(ext/get-extension :catchup/wrap)

;; in the addon's IAddon/hooks — registered at initialize!, removed at shutdown!
{:catchup/wrap my-addon.catchup/wrap-fn}
```

### Depending on the host without depending on the host

An addon **must not** `:require` any `hive-mcp.*` namespace — the host is a runtime, not a
dependency. What it needs is expressed as a port:

- **Contracts to implement** → `hive-addon` (for `IAddon`) and `hive-contracts`
- **Host services at runtime** → soft resolution (`requiring-resolve`) behind a var-map, so
  the addon loads and degrades gracefully when the host is absent

A load-time require on the host is the violation; a soft runtime resolve is not. One hard
require makes a published addon unloadable from a plain Maven fetch.

See [The Core Engine](https://github.com/hive-agi/hive-mcp/wiki/Core-Engine),
[Creating Addons](https://github.com/hive-agi/hive-mcp/wiki/Creating-Addons) and
[ADR-0007](https://github.com/hive-agi/hive-mcp/wiki/ADR-0007-hive-addons-architecture).

---

## Agents and Skills

Two ways to package reusable agent behaviour, both **plain markdown you can drop in, copy
between machines, or publish for others** — no code, no rebuild, no host restart.

| | **Agent definitions** | **Presets** |
|---|---|---|
| Answers | *Who is this agent?* | *How should it work?* |
| Format | Markdown + YAML frontmatter | Plain markdown |
| Carries | Identity, tool allowlist, model, hooks | Methodology, constraints, output format |
| Lives in | `.claude/agents/*.md` | `presets/*.md`, custom dirs, or the memory store |
| Composes by | Priority override — highest source wins | Concatenation — stack as many as you need |

**An agent definition is a role; a preset is a skill.** One definition per agent, as many
presets as the job needs.

```markdown
---
name: reviewer
description: Reviews diffs for correctness and contract violations
tools: ["memory", "git", "fs"]
---
You review changes. Lead with the defect, not the summary.
```

Definitions resolve from four sources — `:user` (`~/.claude/agents/`) overrides `:project`
(`.claude/agents/`) overrides `:plugin` (addon-contributed) overrides `:built-in` — so you
can shadow any of them without editing them. Installing is a file copy; sharing is a
`git clone` into `~/.claude/agents/`, or an addon that contributes definitions for a whole team.

**38 presets ship built-in** across methodology (`tdd`, `solid`, `ddd`, `clarity`), roles
(`reviewer`, `debugger`, `security-auditor`, `researcher`) and coordination
(`task-coordinator`, `wave-coordinator`, `hivemind`). The `preset` tool handles the whole
lifecycle — `list`, `get`, `add`, `delete`, and **semantic `search`**, so you can find one
by describing the job rather than knowing its name.

See [Agents and Skills](https://github.com/hive-agi/hive-mcp/wiki/Agents-and-Skills).

---

## For LLMs

See [`CLAUDE.md`](CLAUDE.md) for project conventions, tool patterns, and memory usage guidelines.

## Documentation

The **[Wiki](https://github.com/hive-agi/hive-mcp/wiki)** is the current, maintained
documentation. Start with these four:

| Guide | Description |
|---|---|
| **[FOSS Quickstart](https://github.com/hive-agi/hive-mcp/wiki/FOSS-Quickstart)** | Batteries-included open-source stack, one command |
| **[The Core Engine](https://github.com/hive-agi/hive-mcp/wiki/Core-Engine)** | What hive-mcp is: host, harness, and the OCP boundary |
| **[Agents and Skills](https://github.com/hive-agi/hive-mcp/wiki/Agents-and-Skills)** | Drop-in agent definitions and presets |
| **[Creating Addons](https://github.com/hive-agi/hive-mcp/wiki/Creating-Addons)** | Scaffold and publish your own addon |

Everything else:

| Guide | Description |
|---|---|
| [Installation](https://github.com/hive-agi/hive-mcp/wiki/Installation) | Detailed setup |
| [Infrastructure Setup](https://github.com/hive-agi/hive-mcp/wiki/Infrastructure-Setup) | Docker, Ollama, Chroma |
| [Ecosystem](https://github.com/hive-agi/hive-mcp/wiki/Ecosystem) | Architecture and open-source strategy |
| [Interfaces and Protocols](https://github.com/hive-agi/hive-mcp/wiki/Interfaces-and-Protocols) | All ~49 protocols with signatures |
| [ADR-0007](https://github.com/hive-agi/hive-mcp/wiki/ADR-0007-hive-addons-architecture) | Why the addon architecture looks like this |
| [Addon Classpath Discovery](https://github.com/hive-agi/hive-mcp/wiki/Addon-Classpath-Discovery) | How manifests are found and loaded |
| [Tools Reference](https://github.com/hive-agi/hive-mcp/wiki/Tools-Reference) | Tool surface and DSL verbs |
| [Presets](https://github.com/hive-agi/hive-mcp/wiki/Presets) | System prompts for ling/drone specialization |
| [Session Continuity](https://github.com/hive-agi/hive-mcp/wiki/Session-Continuity) | catchup and wrap |
| [Emacs Configuration](https://github.com/hive-agi/hive-mcp/wiki/Emacs-Configuration) | Optional Emacs surface |
| [Troubleshooting](https://github.com/hive-agi/hive-mcp/wiki/Troubleshooting) | Common issues |

---

## Related

| Repository | Description |
|---|---|
| **[bb-mcp](https://github.com/hive-agi/bb-mcp)** | Lightweight Babashka MCP wrapper (~50MB RAM) |
| **[lsp-mcp](https://github.com/hive-agi/lsp-mcp)** | Clojure-LSP bridge addon (analysis, callers, references) |
| **[basic-tools-mcp](https://github.com/hive-agi/basic-tools-mcp)** | File read/write/glob/grep tools addon |
| **[hive-dsl](https://github.com/hive-agi/hive-dsl)** | DSL verb compiler for batch operations |
| **[hive-test](https://github.com/hive-agi/hive-test)** | Test utilities for hive-mcp addons |
| **[olympus-web-ui](https://github.com/hive-agi/olympus-web-ui)** | Web dashboard for swarm monitoring |
| **[hive-mcp-cli](https://github.com/hive-agi/hive-mcp-cli)** | Go CLI for automated setup |

---

[AGPL-3.0-or-later](LICENSE)
