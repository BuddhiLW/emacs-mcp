# PROJECT_SUMMARY.md - emacs-mcp

## Overview

**emacs-mcp** is an MCP (Model Context Protocol) server that enables Claude to interact with a running Emacs instance. It provides bidirectional collaboration between Claude and Emacs through:

1. **Clojure MCP Server** - Exposes Emacs operations as MCP tools via `emacsclient`
2. **Emacs Package (emacs-mcp.el)** - Provides persistent memory, context gathering, workflows, and a stable API

## Project Structure

```
emacs-mcp/
├── src/emacs_mcp/           # Clojure MCP server
│   ├── server.clj           # MCP server entry point, tool definitions
│   ├── emacsclient.clj      # Low-level emacsclient communication
│   └── synergy.clj          # High-level functions combining both MCPs
├── elisp/                   # Emacs Lisp package
│   ├── emacs-mcp.el         # Main entry, minor mode
│   ├── emacs-mcp-memory.el  # JSON-based persistent storage
│   ├── emacs-mcp-context.el # Context gathering (buffer, project, git)
│   ├── emacs-mcp-triggers.el# Keybindings (C-c m), hooks
│   ├── emacs-mcp-transient.el # Magit-style menus
│   ├── emacs-mcp-workflows.el # User-defined multi-step automations
│   └── emacs-mcp-api.el     # Stable API for Claude to call
├── deps.edn                 # Clojure dependencies
├── start-mcp.sh             # Shell script to start MCP server
└── README.md                # Documentation
```

## Key Files

### Clojure Server

| File | Purpose |
|------|---------|
| `src/emacs_mcp/server.clj` | MCP server initialization, tool definitions (eval_elisp, find_file, etc.) |
| `src/emacs_mcp/emacsclient.clj` | Wraps `emacsclient --eval` calls, handles escaping/parsing |
| `src/emacs_mcp/synergy.clj` | High-level functions: `jump-to!`, `show-in-buffer!`, `get-full-context!` |

### Emacs Package

| File | Purpose |
|------|---------|
| `elisp/emacs-mcp.el` | Minor mode, initialization, requires submodules |
| `elisp/emacs-mcp-memory.el` | Per-project JSON storage for notes, snippets, conventions, decisions |
| `elisp/emacs-mcp-context.el` | Gathers buffer, region, defun, project, git info |
| `elisp/emacs-mcp-api.el` | Stable API: `emacs-mcp-api-get-context`, `emacs-mcp-api-memory-add`, etc. |
| `elisp/emacs-mcp-workflows.el` | Multi-step workflow engine with step types: `:elisp`, `:shell`, `:prompt` |
| `elisp/emacs-mcp-triggers.el` | Keybindings (`C-c m` prefix), hook infrastructure |
| `elisp/emacs-mcp-transient.el` | Transient menus for memory, context, workflows |

## Dependencies

### Clojure (deps.edn)

| Dependency | Version | Role |
|------------|---------|------|
| `org.clojure/clojure` | 1.12.1 | Core language |
| `org.clojure/data.json` | 2.5.1 | JSON parsing |
| `org.clojure/core.async` | 1.7.701 | Async operations |
| `io.modelcontextprotocol/mcp-clojure-sdk` | git sha | MCP protocol implementation |
| `com.taoensso/timbre` | 6.8.0 | Logging |

### Emacs

| Dependency | Version | Role |
|------------|---------|------|
| Emacs | 28.1+ | Required for `project.el`, native JSON |
| `transient` | 0.4.0+ | Magit-style menus (optional, bundled in recent Emacs) |

## Available Tools/APIs

### MCP Tools (from server.clj)

```clojure
;; Execute arbitrary elisp
(eval_elisp {:code "(buffer-name)"})

;; File operations
(find_file {:path "/path/to/file.el"})
(save_buffer {})
(goto_line {:line 42})

;; Buffer operations
(list_buffers {})
(get_buffer_content {:buffer "*scratch*"})
(switch_to_buffer {:name "file.el"})
(insert_text {:text "hello"})

;; Info
(emacs_status {})
(current_buffer {})
(project_root {})
(recent_files {})
```

### Synergy Functions (synergy.clj)

```clojure
(require '[emacs-mcp.synergy :as syn])

;; Get full context including memory
(syn/get-full-context!)

;; Navigate to file:line with highlight
(syn/jump-to! "/path/to/file.clj" 42)

;; Display content in Emacs buffer
(syn/show-in-buffer! "*Results*" "# Content here" "markdown-mode")

;; Notifications
(syn/notify! "Task complete!" :type "info")

;; Memory operations
(syn/add-note! "Remember this" :tags ["important"])
(syn/query-memory! "note" :limit 10)

;; Run user workflow
(syn/run-workflow! "commit" :args {:message "Fix bug"})
```

### Emacs API (emacs-mcp-api.el)

```elisp
;; Context
(emacs-mcp-api-get-context)        ; Full context with memory
(emacs-mcp-api-get-buffer-context) ; Current buffer info
(emacs-mcp-api-get-region)         ; Selected text
(emacs-mcp-api-get-defun)          ; Current function

;; Memory
(emacs-mcp-api-memory-add "note" "content" '("tag1" "tag2"))
(emacs-mcp-api-memory-query "note" nil 20)
(emacs-mcp-api-memory-get "entry-id")

;; Workflows
(emacs-mcp-api-list-workflows)
(emacs-mcp-api-run-workflow "quick-note" '(:note-text "hello"))

;; Interaction
(emacs-mcp-api-notify "Message" "info")
(emacs-mcp-api-prompt "Question?" "default")
(emacs-mcp-api-confirm "Are you sure?")
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Claude                              │
└────────────────────────┬────────────────────────────────────┘
                         │ MCP Protocol (stdio)
                         v
┌────────────────────────────────────────────────────────────┐
│              emacs-mcp (Clojure Server)                    │
│                                                            │
│  server.clj ──► Tool handlers                              │
│  emacsclient.clj ──► Shell out to emacsclient              │
│  synergy.clj ──► High-level workflows                      │
└───────────────────────────────────────────────────────────┘
                         │
                         │ emacsclient --eval "(elisp...)"
                         v
┌────────────────────────────────────────────────────────────┐
│                    Emacs (daemon)                          │
│                                                            │
│  emacs-mcp.el ──► Minor mode, initialization               │
│  emacs-mcp-api.el ──► Stable API for Claude                │
│  emacs-mcp-memory.el ──► ~/.emacs.d/emacs-mcp/projects/    │
│  emacs-mcp-context.el ──► Buffer, project, git info        │
│  emacs-mcp-workflows.el ──► Multi-step automations         │
└────────────────────────────────────────────────────────────┘
```

## Implementation Patterns

### Clojure Server

- **Tool definitions** use `mcp-clojure-sdk` macros in `server.clj`
- **Emacsclient calls** go through `ec/eval-elisp!` which handles escaping
- **Error handling** wraps elisp in `condition-case` when needed
- **Synergy functions** use keyword args: `(fn! arg :opt val)`

### Emacs Package

- **Memory storage**: JSON files in `~/.emacs.d/emacs-mcp/projects/<sha1>/`
- **Project scoping**: Uses SHA1 of project root as ID
- **API functions**: All return JSON-serializable plists
- **Workflows**: Step-based with types `:elisp`, `:shell`, `:prompt`, `:condition`
- **Keybindings**: All under `C-c m` prefix

## Development Workflow

### Running the MCP Server

```bash
# For Claude Code (production)
./start-mcp.sh

# For development with REPL
clojure -M:nrepl  # Starts on port 7910
```

### Testing

```bash
# Run Clojure tests
clojure -M:test

# In REPL, test emacsclient
(require '[emacs-mcp.emacsclient :as ec])
(ec/eval-elisp! "(emacs-version)")
```

### Loading Emacs Package

```elisp
(add-to-list 'load-path "/path/to/emacs-mcp/elisp")
(require 'emacs-mcp)
(emacs-mcp-mode 1)
```

## Extension Points

### Custom Memory Types
```elisp
;; Add handler in emacs-mcp-memory.el
(puthash 'my-type handler emacs-mcp-memory-type-handlers)
```

### Custom Workflow Steps
```elisp
(emacs-mcp-workflow-register-step-type
 :my-step
 (lambda (step env) 
   ;; Process step, return updated env
   env))
```

### Custom Context Providers
```elisp
;; Add to emacs-mcp-context-providers alist
```

### Transient Menu Extensions
```elisp
;; Add groups to emacs-mcp-transient-extra-groups
```

## Tested Features (Verified Working)

| Feature | Status |
|---------|--------|
| Context API | ✓ Full buffer/project/git/memory |
| Memory persistence | ✓ Notes saved to JSON |
| Memory query | ✓ Retrieves stored entries |
| Workflows | ✓ `quick-note`, `commit` built-in |
| Notifications | ✓ Messages in Emacs |
| Jump to file:line | ✓ Opens with line highlight |
| Show in buffer | ✓ Creates buffer with content |
| Synergy functions | ✓ Full integration |

## Quick Reference

### Start Server for Claude Code
```json
// ~/.claude/settings.json
{
  "mcpServers": {
    "emacs-mcp": {
      "command": "/path/to/emacs-mcp/start-mcp.sh"
    }
  }
}
```

### Common Operations
```clojure
;; From Clojure REPL or synergy.clj
(syn/get-full-context!)                    ; Get everything
(syn/jump-to! "file.clj" 42)              ; Navigate
(syn/show-in-buffer! "*Out*" "text" nil)  ; Display
(syn/notify! "Done!")                      ; Alert user
```
