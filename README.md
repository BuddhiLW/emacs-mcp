# emacs-mcp

MCP (Model Context Protocol) server that allows Claude to interact with a running Emacs instance via `emacsclient`.

## Features

### Core Tools

- **eval_elisp** - Execute arbitrary Emacs Lisp code
- **emacs_status** - Check if Emacs server is running
- **list_buffers** - List all open buffers
- **get_buffer_content** - Read content from a buffer
- **current_buffer** - Get current buffer name and file
- **switch_to_buffer** - Switch to a specific buffer
- **find_file** - Open a file in Emacs
- **save_buffer** - Save the current buffer
- **goto_line** - Move cursor to a line number
- **insert_text** - Insert text at cursor
- **project_root** - Get current project root
- **recent_files** - Get recently opened files

### emacs-mcp.el Integration Tools (NEW!)

These tools provide seamless integration with `emacs-mcp.el`, enabling Claude to use Emacs memory, context, and workflows without manual `eval_elisp` calls:

- **mcp_capabilities** - Check emacs-mcp.el availability and features
- **mcp_get_context** - Get full context (buffer, project, git, memory)
- **mcp_memory_add** - Add notes, snippets, conventions, or decisions to project memory
- **mcp_memory_query** - Query stored memory entries by type
- **mcp_list_workflows** - List available user-defined workflows
- **mcp_run_workflow** - Execute a workflow by name
- **mcp_notify** - Show notification messages in Emacs

> **Auto-detection**: These tools automatically check if `emacs-mcp.el` is loaded and provide helpful error messages if not

## Prerequisites

1. **Emacs** with server mode enabled:
   ```elisp
   (server-start)
   ```

2. **Clojure CLI** (deps.edn)

3. **emacsclient** in your PATH

## Installation

### 1. Clone the repository

```bash
git clone https://github.com/BuddhiLW/emacs-mcp.git
cd emacs-mcp
```

### 2. Add to Claude Code

Add to `~/.claude/settings.json` under `mcpServers`:

```json
{
  "mcpServers": {
    "emacs-mcp": {
      "command": "/home/you/path/to/emacs-mcp/start-mcp.sh"
    }
  }
}
```

Or directly with clojure:

```json
{
  "mcpServers": {
    "emacs-mcp": {
      "command": "clojure",
      "args": ["-X:mcp"],
      "cwd": "/home/you/path/to/emacs-mcp"
    }
  }
}
```

### 3. Ensure Emacs server is running

```elisp
;; In your Emacs init.el
(server-start)
```

### 4. (Optional) Load emacs-mcp.el for full features

```elisp
(add-to-list 'load-path "/path/to/emacs-mcp/elisp")
(require 'emacs-mcp)
(emacs-mcp-mode 1)
```

**No nREPL needed!** The MCP server runs standalone via `clojure -X:mcp`.

## Development

### Start nREPL for development

```bash
clojure -M:nrepl
```

### Run tests

```bash
clojure -M:test
```

### Run MCP server directly

```bash
clojure -X:mcp
```

## Architecture

```
┌─────────────┐     MCP/STDIO      ┌─────────────┐
│   Claude    │ ◄────────────────► │  emacs-mcp  │
│   (AI)      │                    │  (Clojure)  │
└─────────────┘                    └──────┬──────┘
                                          │
                                          │ emacsclient --eval
                                          ▼
                                   ┌─────────────┐
                                   │   Emacs     │
                                   │  (daemon)   │
                                   │             │
                                   │ emacs-mcp.el│ ← NEW!
                                   └─────────────┘
```

## emacs-mcp.el - Emacs Package

The `elisp/` directory contains an Emacs package that enables **bidirectional** collaboration:

- **Memory**: Persistent notes, snippets, conventions, decisions per-project
- **Context**: Rich information about buffer, region, project, git
- **Workflows**: User-defined multi-step automations
- **Triggers**: Hooks and keybindings for automation

### Installation

```elisp
;; Add to load-path
(add-to-list 'load-path "/path/to/emacs-mcp/elisp")

;; Load and enable
(require 'emacs-mcp)
(emacs-mcp-mode 1)
```

### Keybindings (C-c m prefix)

| Key       | Command                    |
|-----------|----------------------------|
| `C-c m m` | Open transient menu        |
| `C-c m n` | Add note to memory         |
| `C-c m s` | Save region as snippet     |
| `C-c m c` | Add project convention     |
| `C-c m d` | Record architecture decision|
| `C-c m l` | Browse project memory      |
| `C-c m w` | Run workflow               |
| `C-c m h` | Show conversation history  |
| `C-c m x` | Show current context       |

### API for Claude

Claude can use these functions via `eval_elisp`:

```clojure
;; Get full context including memory
(ec/eval-elisp "(emacs-mcp-api-get-context)")

;; Add a note
(ec/eval-elisp "(emacs-mcp-api-memory-add \"note\" \"Remember this\")")

;; Query conventions
(ec/eval-elisp "(emacs-mcp-api-memory-query \"convention\")")

;; Run user workflow
(ec/eval-elisp "(emacs-mcp-api-run-workflow \"test-and-commit\")")
```

### Package Structure

```
elisp/
├── emacs-mcp.el           # Main entry, minor mode
├── emacs-mcp-memory.el    # Persistent JSON storage
├── emacs-mcp-context.el   # Context gathering
├── emacs-mcp-triggers.el  # Keybindings, hooks
├── emacs-mcp-transient.el # Transient menus
├── emacs-mcp-workflows.el # Workflow system
├── emacs-mcp-api.el       # Stable API for Claude
├── emacs-mcp-addons.el    # Addon system
└── addons/                # Built-in and custom addons
    ├── emacs-mcp-addon-template.el
    ├── emacs-mcp-claude-code.el
    ├── emacs-mcp-cider.el
    ├── emacs-mcp-org-ai.el
    └── emacs-mcp-org-kanban.el  # Dual-backend kanban
```

## Addon System

Modular integrations with other Emacs packages. Addons are **lazy-loaded** when target packages are detected.

| Addon | Integration | MCP Tools | Description |
|-------|-------------|:---------:|-------------|
| [claude-code](ADDONS.org#claude-code) | [claude-code.el](https://github.com/karthink/claude-code) | - | Context injection for Claude CLI |
| [cider](ADDONS.org#cider) | [CIDER](https://github.com/clojure-emacs/cider) | ✓ | Clojure REPL integration |
| [org-ai](ADDONS.org#org-ai) | [org-ai](https://github.com/rksm/org-ai) | - | AI conversation context |
| [org-kanban](ADDONS.org#org-kanban) | [org-kanban](https://github.com/gizmomogwai/org-kanban) | ✓ | Dual-backend kanban tracking |

**Quick start:**
```elisp
(emacs-mcp-addons-auto-load)  ; Auto-load when packages detected
```

📖 **Full documentation:** [ADDONS.org](ADDONS.org)

## Tested & Working

All features verified through the Clojure MCP → Emacs integration:

| Feature | Status | 
|---------|--------|
| Context API | ✓ Full buffer/project/git/memory info |
| Memory persistence | ✓ Notes saved to JSON per-project |
| Memory query | ✓ Retrieves stored notes, conventions |
| Workflows | ✓ `quick-note`, `commit` registered |
| Notifications | ✓ Messages displayed in Emacs |
| Jump to file:line | ✓ Opens file with line highlight |
| Show in buffer | ✓ Creates buffer with content |
| Synergy functions | ✓ Full dev-tools + emacs-bridge integration |

```clojure
;; Example: Get full context with memory
(require '[emacs-mcp.synergy :as syn])
(syn/get-full-context!)
;; => {:buffer {...} :project {...} :git {...} :memory {:notes [...]}}

;; Jump to a specific location
(syn/jump-to! "src/myfile.clj" 42)

;; Show results in Emacs
(syn/show-in-buffer! "*Results*" "# Analysis\n..." "markdown-mode")
```

## Meta: MCP Servers Editing MCP Servers

This project demonstrates an interesting recursive pattern: **an MCP server can be developed using another MCP server**.

### The Setup

| Server | Function | Tools Provided |
|--------|----------|----------------|
| **clojure-mcp** (mcp-dev₁) | Clojure development | read, edit, eval, grep, glob |
| **emacs-mcp** (mcp-emacs₂) | Emacs interaction | eval-elisp, list-buffers, find-file |

Both servers are *implemented* in Clojure, but they serve different *domains*:

```
┌─────────────────────────────────────────────────────────────┐
│                         Claude                              │
├─────────────────────────────┬───────────────────────────────┤
│      clojure-mcp            │         emacs-mcp             │
│   (dev-tools server)        │    (emacs-bridge server)      │
│                             │                               │
│  • read/edit Clojure files  │  • eval elisp                 │
│  • REPL evaluation          │  • buffer management          │
│  • project navigation       │  • file operations            │
├─────────────────────────────┴───────────────────────────────┤
│                    can edit ───────►                        │
│   clojure-mcp edits emacs-mcp source files                  │
└─────────────────────────────────────────────────────────────┘
```

### Naming Clarity (General Semantics)

To avoid confusion when discussing MCP servers at multiple levels:

1. **Index by function, not implementation**: 
   - "dev-tools server" vs "emacs-bridge server"
   - Not "the Clojure one" (ambiguous - both use Clojure)

2. **Use subscripts for instances**:
   - mcp₁ (dev tools), mcp₂ (emacs bridge)
   
3. **Distinguish layers**:
   | Layer | clojure-mcp | emacs-mcp |
   |-------|-------------|-----------|
   | Implementation | Clojure | Clojure |
   | Target domain | Clojure dev | Emacs control |
   | Provides tools for | Editing code | Controlling editor |

4. **The map ≠ territory**: The *name* "clojure-mcp" refers to its *target domain* (Clojure development), not its implementation language.

## License

MIT
