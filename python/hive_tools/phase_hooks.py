"""SAA phase hooks for observation capture and phase-boundary compression.

Hook callbacks that bridge to hive-mcp via nREPL for:
- Exploration capture (Read/Grep/Glob -> memory entries)
- Plan storage (abstract phase output -> memory with plan tags)
- Context injection (compressed context from previous phase -> prompt)
- Pre-compact save (extract key observations before Claude compacts)

Each hook conforms to the Claude Agent SDK HookCallback signature:
    async def hook(input_data, tool_use_id, context) -> HookJSONOutput

Bridge: Uses hive_tools.bridge.call_handler for nREPL communication (~5ms).
"""

# Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from __future__ import annotations

import logging
from typing import Any

from .bridge import call_handler

logger = logging.getLogger(__name__)

# Tools that produce exploration observations (file paths, structures, patterns)
_EXPLORATION_TOOLS = {"Read", "Glob", "Grep"}

# Maximum observations to include in pre-compact save
_MAX_PRECOMPACT_OBS = 30

# Maximum content length for memory entries
_MAX_MEMORY_CONTENT = 3000


def _extract_exploration_summary(
    tool_name: str, tool_input: dict[str, Any], tool_response: Any
) -> tuple[str, list[str], int]:
    """Extract a summary, tags, and abstraction level from an exploration tool use.

    Returns:
        (content, tags, abstraction_level)
    """
    if tool_name == "Read":
        file_path = tool_input.get("file_path", "")
        response_str = str(tool_response or "")[:_MAX_MEMORY_CONTENT]
        content = f"## File Read: {file_path}\n\n```\n{response_str[:500]}\n```"
        tags = ["exploration", "file-read", f"file:{file_path.split('/')[-1]}"]
        return content, tags, 1  # concrete file path

    if tool_name == "Grep":
        pattern = tool_input.get("pattern", "")
        path = tool_input.get("path", "")
        response_str = str(tool_response or "")[:_MAX_MEMORY_CONTENT]
        content = (
            f"## Grep Search: `{pattern}`\n"
            f"**Path**: {path}\n\n"
            f"```\n{response_str[:500]}\n```"
        )
        tags = ["exploration", "grep-search", f"pattern:{pattern[:50]}"]
        return content, tags, 2  # structural/behavioral finding

    if tool_name == "Glob":
        pattern = tool_input.get("pattern", "")
        response_str = str(tool_response or "")[:_MAX_MEMORY_CONTENT]
        content = (
            f"## Glob Pattern: `{pattern}`\n\n"
            f"```\n{response_str[:500]}\n```"
        )
        tags = ["exploration", "glob-pattern", f"pattern:{pattern[:50]}"]
        return content, tags, 1  # file structure discovery

    # Fallback for unexpected tools
    content = f"## Tool: {tool_name}\n\n{str(tool_response or '')[:500]}"
    tags = ["exploration", f"tool:{tool_name.lower()}"]
    return content, tags, 1


def create_capture_exploration_hook(
    cwd: str = "",
    agent_id: str = "",
) -> Any:
    """Create a PostToolUse hook that captures exploration tool uses as memory entries.

    For silence and act phases: Read -> level-1 entry, Grep/Glob -> level-2 entry.

    Args:
        cwd: Working directory for project scoping.
        agent_id: Agent identifier for memory attribution.

    Returns:
        Async hook callback function.
    """

    async def capture_exploration(
        input_data: dict[str, Any],
        tool_use_id: str | None,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        tool_name = input_data.get("tool_name", "")

        # Only capture exploration tools
        if tool_name not in _EXPLORATION_TOOLS:
            return {}

        tool_input = input_data.get("tool_input", {})
        tool_response = input_data.get("tool_response")

        try:
            content, tags, abstraction_level = _extract_exploration_summary(
                tool_name, tool_input, tool_response
            )
            tags.extend(["SAA", "auto-capture"])
            if agent_id:
                tags.append(f"agent:{agent_id}")

            result = call_handler("memory", {
                "command": "add",
                "type": "note",
                "content": content,
                "tags": tags,
                "duration": "short",
                "abstraction_level": abstraction_level,
                "directory": cwd,
                "agent_id": agent_id,
            })

            memory_id = result.get("id", "")
            logger.debug(
                f"[phase-hooks] Captured {tool_name} exploration "
                f"(level={abstraction_level}, id={memory_id})"
            )

            return {
                "hookSpecificOutput": {
                    "hookEventName": "PostToolUse",
                    "additionalContext": (
                        f"[Capture] {tool_name} observation stored "
                        f"(level={abstraction_level}, id={memory_id})"
                    ),
                }
            }

        except Exception as e:
            logger.warning("[phase-hooks] capture_exploration failed: %s", e, exc_info=True)
            return {}

    return capture_exploration


def create_store_plan_hook(
    cwd: str = "",
    agent_id: str = "",
) -> Any:
    """Create a PostToolUse hook that detects plan output and stores it to memory.

    For abstract phase: detects when Claude produces structured plan content
    and stores it with plan-specific tags and abstraction_level=4.

    Args:
        cwd: Working directory for project scoping.
        agent_id: Agent identifier for memory attribution.

    Returns:
        Async hook callback function.
    """

    async def store_plan(
        input_data: dict[str, Any],
        tool_use_id: str | None,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        tool_name = input_data.get("tool_name", "")
        tool_response = input_data.get("tool_response")

        # Plans typically emerge from Read/Grep (re-checking observations)
        # or from the final assistant message. We detect plan-like content.
        response_str = str(tool_response or "")

        # Heuristic: plan-like content has numbered steps or "## Plan"
        is_plan_like = any(
            marker in response_str
            for marker in ["## Plan", "Step 1", "1. ", "### Steps", "Action plan"]
        )

        if not is_plan_like:
            return {}

        try:
            content = response_str[:_MAX_MEMORY_CONTENT]
            tags = ["plan", "SAA", "abstract-phase"]
            if agent_id:
                tags.append(f"agent:{agent_id}")

            result = call_handler("memory", {
                "command": "add",
                "type": "decision",
                "content": f"## SAA Plan Output\n\n{content}",
                "tags": tags,
                "duration": "medium",
                "abstraction_level": 4,
                "directory": cwd,
                "agent_id": agent_id,
            })

            memory_id = result.get("id", "")
            logger.info(
                f"[phase-hooks] Stored plan to memory (id={memory_id})"
            )

            return {
                "hookSpecificOutput": {
                    "hookEventName": "PostToolUse",
                    "additionalContext": (
                        f"[Plan Stored] Abstract phase plan saved to memory "
                        f"(id={memory_id}, tags=plan,SAA)"
                    ),
                }
            }

        except Exception as e:
            logger.warning("[phase-hooks] store_plan failed: %s", e, exc_info=True)
            return {}

    return store_plan


def create_inject_context_hook(
    compressed_context: str = "",
) -> Any:
    """Create a UserPromptSubmit hook that prepends compressed context.

    For abstract and act phases: injects the compressed context from the
    previous phase into the prompt via additionalContext.

    Args:
        compressed_context: Compressed context string from phase compressor.

    Returns:
        Async hook callback function.
    """

    async def inject_context(
        input_data: dict[str, Any],
        tool_use_id: str | None,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        if not compressed_context:
            return {}

        return {
            "hookSpecificOutput": {
                "hookEventName": "UserPromptSubmit",
                "additionalContext": (
                    "## Context from Previous Phase\n\n"
                    f"{compressed_context}"
                ),
            }
        }

    return inject_context


def create_pre_compact_save_hook(
    cwd: str = "",
    agent_id: str = "",
) -> Any:
    """Create a PreCompact hook that saves key observations before Claude compacts.

    Triggered when Claude's context window approaches its limit. Extracts
    the most important observations and stores them as memory entries
    so they survive context compaction.

    Args:
        cwd: Working directory for project scoping.
        agent_id: Agent identifier for memory attribution.

    Returns:
        Async hook callback function.
    """

    async def pre_compact_save(
        input_data: dict[str, Any],
        tool_use_id: str | None,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        # Extract conversation summary from the compact event
        conversation = input_data.get("conversation", [])

        # Collect significant observations from recent messages
        observations = []
        for msg in conversation[-_MAX_PRECOMPACT_OBS:]:
            content = str(msg.get("content", ""))
            # Filter for substantive content (not just tool calls)
            if len(content) > 100 and any(
                kw in content.lower()
                for kw in ["pattern", "found", "observation", "note", "important"]
            ):
                observations.append(content[:500])

        if not observations:
            return {}

        try:
            content = (
                "## Pre-Compact Observations\n\n"
                + "\n---\n".join(observations[:10])
            )
            tags = ["pre-compact", "SAA", "context-preservation"]
            if agent_id:
                tags.append(f"agent:{agent_id}")

            result = call_handler("memory", {
                "command": "add",
                "type": "note",
                "content": content[:_MAX_MEMORY_CONTENT],
                "tags": tags,
                "duration": "short",
                "abstraction_level": 2,
                "directory": cwd,
                "agent_id": agent_id,
            })

            memory_id = result.get("id", "")
            logger.info(
                f"[phase-hooks] Pre-compact save: {len(observations)} observations "
                f"stored (id={memory_id})"
            )

            return {
                "hookSpecificOutput": {
                    "hookEventName": "PreCompact",
                    "additionalContext": (
                        f"[Pre-Compact] Saved {len(observations)} observations "
                        f"to memory before context compaction (id={memory_id})"
                    ),
                }
            }

        except Exception as e:
            logger.warning("[phase-hooks] pre_compact_save failed: %s", e, exc_info=True)
            return {}

    return pre_compact_save
