"""Coordination tools for Claude Agent SDK headless lings.

Exposes hivemind, kanban, and session operations as @tool-decorated functions
that can be registered with create_sdk_mcp_server(). These tools allow
SDK-spawned lings to participate in the hive swarm coordination protocol.

Tools provided:
    hivemind_shout  - Broadcast status/progress to coordinator
    hivemind_ask    - Request decision from human coordinator
    kanban_list     - List kanban tasks (filtered by status)
    kanban_update   - Update task status (todo -> inprogress -> done)
    session_wrap    - Crystallize session learnings into memory
    session_catchup - Restore session context from memory

Architecture:
    @tool decorator (claude_agent_sdk)
        -> async handler(args)
        -> bridge.call_handler(handler_name, params)
        -> Clojure consolidated handler
        -> JSON response

Usage:
    from claude_agent_sdk import create_sdk_mcp_server
    from hive_tools.coordination import all_tools

    server = create_sdk_mcp_server(
        name="hive",
        version="0.12.0",
        tools=all_tools,
    )
"""

from __future__ import annotations

import json
from typing import Any

from claude_agent_sdk import tool

from .bridge import call_handler
from .wrap_fallback import degraded_wrap_response, is_nrepl_down_error


# =============================================================================
# Response helpers
# =============================================================================


def _text_response(data: Any) -> dict[str, Any]:
    """Wrap data in MCP text content response format."""
    if isinstance(data, dict):
        text = json.dumps(data, indent=2, default=str)
    else:
        text = str(data)
    return {"content": [{"type": "text", "text": text}]}


def _error_response(message: str) -> dict[str, Any]:
    """Create an MCP error response."""
    return {
        "content": [{"type": "text", "text": f"Error: {message}"}],
        "is_error": True,
    }


# =============================================================================
# Hivemind Tools
# =============================================================================


@tool(
    "hivemind_shout",
    "Broadcast status/progress to the hivemind coordinator. "
    "Use event_type: started|progress|completed|error|blocked. "
    "Include agent_id for proper Olympus tracking.",
    {
        "type": "object",
        "properties": {
            "agent_id": {
                "type": "string",
                "description": "Your agent ID (CLAUDE_SWARM_SLAVE_ID)",
            },
            "event_type": {
                "type": "string",
                "enum": ["started", "progress", "completed", "error", "blocked"],
                "description": "Type of event to broadcast",
            },
            "message": {
                "type": "string",
                "description": "Status message describing current state",
            },
            "task": {
                "type": "string",
                "description": "Current task description (used with 'started' event)",
            },
        },
        "required": ["agent_id", "event_type"],
    },
)
async def hivemind_shout(args: dict[str, Any]) -> dict[str, Any]:
    """Broadcast a status event to the hivemind coordinator."""
    try:
        params: dict[str, Any] = {
            "command": "shout",
            "agent_id": args["agent_id"],
            "event_type": args["event_type"],
        }
        if "message" in args:
            params["message"] = args["message"]
        if "task" in args:
            params["task"] = args["task"]

        result = call_handler("hivemind", params)
        return _text_response(result)
    except Exception as e:
        return _error_response(f"hivemind_shout failed: {e}")


@tool(
    "hivemind_ask",
    "Request a decision from the human coordinator. "
    "Blocks until the coordinator responds or timeout. "
    "Use for destructive actions or ambiguous requirements.",
    {
        "type": "object",
        "properties": {
            "agent_id": {
                "type": "string",
                "description": "Your agent ID (CLAUDE_SWARM_SLAVE_ID)",
            },
            "question": {
                "type": "string",
                "description": "The question to ask the coordinator",
            },
            "options": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Available options for the coordinator to choose from",
            },
            "timeout_ms": {
                "type": "integer",
                "description": "Timeout in milliseconds (default: 300000 = 5 min)",
            },
        },
        "required": ["agent_id", "question"],
    },
)
async def hivemind_ask(args: dict[str, Any]) -> dict[str, Any]:
    """Ask the human coordinator for a decision."""
    try:
        params: dict[str, Any] = {
            "command": "ask",
            "agent_id": args["agent_id"],
            "question": args["question"],
        }
        if "options" in args:
            params["options"] = args["options"]
        if "timeout_ms" in args:
            params["timeout_ms"] = args["timeout_ms"]

        result = call_handler("hivemind", params)
        return _text_response(result)
    except Exception as e:
        return _error_response(f"hivemind_ask failed: {e}")


# =============================================================================
# Kanban Tools
# =============================================================================


@tool(
    "kanban_list",
    "List kanban tasks, optionally filtered by status. "
    "Returns task IDs, titles, and statuses.",
    {
        "type": "object",
        "properties": {
            "status": {
                "type": "string",
                "enum": ["todo", "inprogress", "inreview", "done"],
                "description": "Filter tasks by status (omit for all tasks)",
            },
            "directory": {
                "type": "string",
                "description": "Working directory for project scope (auto-detected if omitted)",
            },
        },
        "required": [],
    },
)
async def kanban_list(args: dict[str, Any]) -> dict[str, Any]:
    """List kanban tasks with optional status filter."""
    try:
        params: dict[str, Any] = {"command": "list"}
        if "status" in args:
            params["status"] = args["status"]
        if "directory" in args:
            params["directory"] = args["directory"]

        result = call_handler("kanban", params)
        return _text_response(result)
    except Exception as e:
        return _error_response(f"kanban_list failed: {e}")


@tool(
    "kanban_update",
    "Update a kanban task's status. "
    "Use to move tasks through: todo -> inprogress -> inreview -> done.",
    {
        "type": "object",
        "properties": {
            "task_id": {
                "type": "string",
                "description": "The kanban task ID to update",
            },
            "new_status": {
                "type": "string",
                "enum": ["todo", "inprogress", "inreview", "done"],
                "description": "Target status for the task",
            },
            "description": {
                "type": "string",
                "description": "Optional new description for the task",
            },
            "directory": {
                "type": "string",
                "description": "Working directory for project scope",
            },
        },
        "required": ["task_id", "new_status"],
    },
)
async def kanban_update(args: dict[str, Any]) -> dict[str, Any]:
    """Update a kanban task's status."""
    try:
        params: dict[str, Any] = {
            "command": "update",
            "task_id": args["task_id"],
            "new_status": args["new_status"],
        }
        if "description" in args:
            params["description"] = args["description"]
        if "directory" in args:
            params["directory"] = args["directory"]

        result = call_handler("kanban", params)
        return _text_response(result)
    except Exception as e:
        return _error_response(f"kanban_update failed: {e}")


@tool(
    "kanban_status",
    "Get kanban board overview with task counts by status and milestones.",
    {
        "type": "object",
        "properties": {
            "directory": {
                "type": "string",
                "description": "Working directory for project scope",
            },
        },
        "required": [],
    },
)
async def kanban_status(args: dict[str, Any]) -> dict[str, Any]:
    """Get kanban board overview."""
    try:
        params: dict[str, Any] = {"command": "status"}
        if "directory" in args:
            params["directory"] = args["directory"]

        result = call_handler("kanban", params)
        return _text_response(result)
    except Exception as e:
        return _error_response(f"kanban_status failed: {e}")


# =============================================================================
# Session Tools
# =============================================================================


@tool(
    "session_wrap",
    "Crystallize session learnings into memory without committing. "
    "Captures decisions, conventions, and discoveries made during the session.",
    {
        "type": "object",
        "properties": {
            "agent_id": {
                "type": "string",
                "description": "Your agent ID for attribution",
            },
            "directory": {
                "type": "string",
                "description": "Working directory for project scoping",
            },
        },
        "required": [],
    },
)
async def session_wrap(args: dict[str, Any]) -> dict[str, Any]:
    """Crystallize session learnings into memory.

    Degraded path: when the babashka nREPL is unreachable, persist a local
    wrap breadcrumb (content/tags/temporal metadata) to ``HIVE_WRAP_QUEUE_DIR``
    and return a ``wrap-degraded`` response instead of raising an HTTP-style
    error (kanban bug 20260404141548-1c053836).
    """
    agent_id = args.get("agent_id")
    directory = args.get("directory")
    try:
        params: dict[str, Any] = {"command": "wrap"}
        if agent_id is not None:
            params["agent_id"] = agent_id
        if directory is not None:
            params["directory"] = directory

        result = call_handler("session", params)
        return _text_response(result)
    except Exception as e:
        if is_nrepl_down_error(e):
            return _text_response(
                degraded_wrap_response(agent_id, directory, str(e))
            )
        return _error_response(f"session_wrap failed: {e}")


@tool(
    "session_catchup",
    "Restore session context from memory. "
    "Loads axioms, conventions, decisions, kanban state, and git info.",
    {
        "type": "object",
        "properties": {
            "agent_id": {
                "type": "string",
                "description": "Your agent ID",
            },
            "directory": {
                "type": "string",
                "description": "Working directory for project scoping",
            },
        },
        "required": [],
    },
)
async def session_catchup(args: dict[str, Any]) -> dict[str, Any]:
    """Restore session context from memory."""
    try:
        params: dict[str, Any] = {"command": "catchup"}
        if "agent_id" in args:
            params["agent_id"] = args["agent_id"]
        if "directory" in args:
            params["directory"] = args["directory"]

        # Catchup is actually in the workflow handler, not session
        result = call_handler("workflow", params)
        return _text_response(result)
    except Exception as e:
        return _error_response(f"session_catchup failed: {e}")


@tool(
    "session_complete",
    "Complete the session: commit changes, update kanban, crystallize learnings, "
    "and shout completion. This is the single lifecycle endpoint.",
    {
        "type": "object",
        "properties": {
            "agent_id": {
                "type": "string",
                "description": "Your agent ID (MANDATORY for attribution)",
            },
            "directory": {
                "type": "string",
                "description": "Working directory (MANDATORY for project scoping)",
            },
            "commit_msg": {
                "type": "string",
                "description": "Git commit message summarizing work done",
            },
            "task_ids": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Kanban task IDs to mark as done",
            },
        },
        "required": ["agent_id", "directory"],
    },
)
async def session_complete(args: dict[str, Any]) -> dict[str, Any]:
    """Complete session lifecycle: commit + kanban + wrap + shout.

    Degraded path: if nREPL is unreachable the wrap portion still produces a
    local breadcrumb so the session leaves a trail. Commit/kanban work that
    runs Clojure-side is unavoidable here, but we surface a structured
    degraded response rather than a raw HTTP error.
    """
    agent_id = args["agent_id"]
    directory = args["directory"]
    try:
        params: dict[str, Any] = {
            "command": "complete",
            "agent_id": agent_id,
            "directory": directory,
        }
        if "commit_msg" in args:
            params["commit_msg"] = args["commit_msg"]
        if "task_ids" in args:
            params["task_ids"] = args["task_ids"]

        result = call_handler("session", params)
        return _text_response(result)
    except Exception as e:
        if is_nrepl_down_error(e):
            payload = degraded_wrap_response(agent_id, directory, str(e))
            payload["status"] = "session-complete-degraded"
            payload["message"] = (
                "nREPL was unreachable; persisted a local wrap breadcrumb. "
                "Commit/kanban side-effects could not run — re-invoke "
                "session_complete once nREPL is back."
            )
            return _text_response(payload)
        return _error_response(f"session_complete failed: {e}")


# =============================================================================
# Tool Collections
# =============================================================================

#: Hivemind coordination tools
hivemind_tools = [hivemind_shout, hivemind_ask]

#: Kanban task management tools
kanban_tools = [kanban_list, kanban_update, kanban_status]

#: Session lifecycle tools
session_tools = [session_wrap, session_catchup, session_complete]

#: All coordination tools combined
all_tools = hivemind_tools + kanban_tools + session_tools
