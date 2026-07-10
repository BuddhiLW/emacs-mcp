# MCP-First: Prefer MCP Tools Over Native Tools

You have access to powerful MCP (Model Context Protocol) tools that are faster and more integrated than native Claude Code tools. **ALWAYS prefer MCP tools.**

## CRITICAL: Path Conventions (NEVER Hallucinate Paths)

**ALWAYS use relative paths or paths derived from your environment:**
- Use **relative paths**: `src/hive_mcp/foo.clj` (preferred)
- Use **$PWD** / **cwd**: Your working directory
- Use **$HOME**: The user's home directory

**NEVER construct absolute paths like:**
- ❌ `/Users/someone/...` (macOS paths from training data)
- ❌ `/home/otheruser/...` (other users' paths)

**Why?** Claude may hallucinate paths from training data. These don't exist on this system. Always use relative paths or derive from environment variables.

## Tool Hierarchy (Use In This Order)

### 1. Hive MCP (`mcp__hive__*`) - PREFERRED
Use these for ALL hive-mcp integrated operations:

| Instead of...          | Use MCP Tool                          |
|------------------------|---------------------------------------|
| `Read` file            | `mcp__hive__get_buffer_content`  |
| `Grep` search          | `mcp__hive__projectile_search`   |
| `Glob` find files      | `mcp__hive__projectile_files`    |
| Git status/diff        | `mcp__hive__magit_status/diff`   |
| Git commit/push        | `mcp__hive__magit_commit/push`   |
| Git branches           | `mcp__hive__magit_branches`      |
| Eval elisp             | `mcp__hive__eval_elisp`          |
| Eval Clojure           | `mcp__hive__cider_eval_silent`   |

### 2. Claude Context (`mcp__claude-context__*`) - Semantic Search
For **semantic/conceptual** code search (not just text matching):

```
mcp__claude-context__search_code  - Natural language queries
mcp__claude-context__index_codebase - Index before searching
```

**Examples:**
- "Find authentication logic" → `search_code(query: "authentication login flow")`
- "Where is error handling?" → `search_code(query: "error handling exception catching")`

### 3. Carto (`mcp__hive__code`) - Clojure File Edits

For ANY edit to `.clj` / `.cljs` / `.cljc` files, the carto write path is mandatory — raw `Edit`/`Write`/`file_write` skip lint, REPL refresh, and ns-tracking.

**First move on a fresh ling, BEFORE any code edit:**
```
ToolSearch(query: "select:mcp__hive__code")
```

| Task                   | Carto tool                              |
|------------------------|-----------------------------------------|
| Replace top-level form | `mcp__hive__code` `write-form`          |
| Insert before/after    | `mcp__hive__code` `insert-form`         |
| Eval code              | `mcp__hive__clojure_eval`               |
| Whole-file create      | `mcp__hive__file_write` (no existing form) |

### 4. Memory MCP - Persistent Context
```
mcp__hive__mcp_memory_add     - Store notes/decisions/conventions
mcp__hive__mcp_memory_query   - Retrieve with scope filtering
mcp__hive__mcp_memory_search_semantic - Semantic search
```

## Rules

1. **NEVER use native `Read`** when buffer is open in Emacs - use `get_buffer_content`
2. **NEVER use native `Grep`** for project search - use `projectile_search`
3. **NEVER use native `Bash` for git** - use `magit_*` tools
4. **ALWAYS check `emacs_status`** first to verify Emacs connection
5. **For semantic search**, use `claude-context` over text grep

## Speed Comparison

| Operation       | Native Tool | MCP Tool      | Speed Gain |
|-----------------|-------------|---------------|------------|
| Project search  | Grep        | projectile    | ~3x faster |
| Git status      | Bash git    | magit_status  | ~2x faster |
| Code search     | Grep regex  | claude-context| Semantic!  |
| File read       | Read        | buffer_content| Live state |

## Example Workflow

```
1. Check emacs: mcp__hive__emacs_status
2. Get context: mcp__hive__mcp_get_context
3. Search code: mcp__claude-context__search_code (semantic)
4. Read file:   mcp__hive__get_buffer_content (if open)
5. Edit:        mcp__clojure-mcp-emacs__clojure_edit (structural)
6. Git:         mcp__hive__magit_* (status/stage/commit)
```

## Tool Error Recovery

When an MCP tool returns `"Missing required field: X"`:

1. **DO NOT ask the user** - infer the value
2. **Retry IMMEDIATELY** with the missing field

### Common Inferences

| Missing Field | Inference Strategy |
|---------------|-------------------|
| `directory` | Your current working directory (`$PWD`) |
| `path` | The file you're working on or last read |
| `agent_id` | Your `$CLAUDE_SWARM_SLAVE_ID` |
| `commit_msg` | Summarize your recent work |
| `buffer_name` | The file path you want to read |

### Example

```
# Error: {"error": "Missing required field: directory"}

# WRONG - Ask user
"What directory?"

# RIGHT - Infer and retry
mcp__hive__magit_status(directory: "/home/user/project")
```

**Principle**: Required fields usually have obvious values from context. Only ask when genuinely ambiguous.

---

## Anti-Patterns (AVOID)

```
# BAD - Using native tools when MCP available
Bash(git status)           # Use magit_status instead
Bash(grep -r "pattern" .)  # Use projectile_search instead
Read("/path/to/file")      # Use get_buffer_content if open

# GOOD - MCP-first approach
mcp__hive__magit_status(directory: "/project")
mcp__hive__projectile_search(pattern: "pattern")
mcp__hive__get_buffer_content(buffer_name: "file.clj")
```
