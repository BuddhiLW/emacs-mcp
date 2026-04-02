"""PostToolUse hook for automatic observation and memory crystallization.

Captures tool name/input/output, scores significance using algorithmic
heuristics, and auto-creates memory entries for high-signal events.
Zero extra LLM cost — all scoring is pattern-based.

Architecture:
    PostToolUse event → SignificanceScorer → threshold check → memory bridge → nREPL/local

The hook returns `additionalContext` to Claude when significant observations
are recorded, providing progressive awareness of what's being captured.
"""

# Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from __future__ import annotations

import json
import logging
import socket
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any

from .significance import DEFAULT_SCORER, SignificanceResult, SignificanceScorer

logger = logging.getLogger(__name__)

# Maximum observations to keep in the in-memory log
MAX_OBSERVATION_LOG = 500

# Maximum content length to include in memory entries
MAX_CONTENT_LENGTH = 2000


@dataclass
class ObservationRecord:
    """A single auto-observation record."""

    timestamp: float
    tool_name: str
    tool_input_summary: str
    significance: SignificanceResult
    memory_id: str | None = None  # Set if successfully stored
    error: str | None = None  # Set if storage failed

    def to_dict(self) -> dict[str, Any]:
        """Convert to serializable dict."""
        return {
            "timestamp": self.timestamp,
            "tool_name": self.tool_name,
            "tool_input_summary": self.tool_input_summary,
            "score": self.significance.score,
            "category": self.significance.category,
            "tags": self.significance.tags,
            "memory_type": self.significance.memory_type,
            "memory_id": self.memory_id,
            "error": self.error,
        }


@dataclass
class AutoObservationConfig:
    """Configuration for the auto-observation hook.

    Attributes:
        nrepl_host: nREPL server host for memory storage.
        nrepl_port: nREPL server port.
        project_dir: Project directory for memory scoping.
        agent_id: Agent identifier for memory attribution.
        scorer: Significance scorer instance. Uses DEFAULT_SCORER if None.
        threshold: Override scorer threshold. If set, takes precedence.
        enabled: Master enable/disable switch.
        store_locally: Keep observations in local log even if nREPL unavailable.
        max_log_size: Max observations to keep in memory.
        suppress_noise_context: Don't return additionalContext for noise/low events.
        batch_interval_s: Minimum seconds between memory writes (debounce).
        dry_run: Score but don't actually store to memory (for testing).
    """

    nrepl_host: str = "localhost"
    nrepl_port: int = 7910
    project_dir: str = ""
    agent_id: str = ""
    scorer: SignificanceScorer | None = None
    threshold: float | None = None
    enabled: bool = True
    store_locally: bool = True
    max_log_size: int = MAX_OBSERVATION_LOG
    suppress_noise_context: bool = True
    batch_interval_s: float = 2.0
    dry_run: bool = False


# Module-level observation log
_observation_log: deque[ObservationRecord] = deque(maxlen=MAX_OBSERVATION_LOG)
_last_store_time: float = 0.0


def get_observation_log() -> list[ObservationRecord]:
    """Get the current observation log (most recent first)."""
    return list(reversed(_observation_log))


def clear_observation_log() -> None:
    """Clear the observation log and reset debounce timer."""
    global _last_store_time
    _observation_log.clear()
    _last_store_time = 0.0


def _summarize_input(tool_name: str, tool_input: dict[str, Any]) -> str:
    """Create a concise summary of tool input for logging."""
    if tool_name in ("Read", "Glob", "Grep"):
        # File operations: show path/pattern
        path = tool_input.get("file_path") or tool_input.get("path", "")
        pattern = tool_input.get("pattern", "")
        if path and pattern:
            return f"{path} pattern={pattern}"
        return path or pattern or str(tool_input)[:100]

    if tool_name in ("Write", "Edit", "MultiEdit"):
        path = tool_input.get("file_path", "")
        return f"file={path}" if path else str(tool_input)[:100]

    if tool_name == "Bash":
        cmd = tool_input.get("command", "")
        return cmd[:150] if cmd else str(tool_input)[:100]

    if tool_name.startswith("mcp__hive__"):
        # MCP tools: show command if present
        cmd = tool_input.get("command", "")
        if cmd:
            return f"command={cmd}"

    # Generic: truncated JSON
    try:
        s = json.dumps(tool_input, default=str)
        return s[:200] if len(s) > 200 else s
    except (TypeError, ValueError) as exc:
        logger.debug("[auto-obs] _summarize_input JSON fallback: %s", exc)
        return str(tool_input)[:200]


def _summarize_response(tool_response: Any) -> str:
    """Create a concise summary of tool response for memory content."""
    if tool_response is None:
        return "(no output)"

    response_str = str(tool_response)

    if len(response_str) > MAX_CONTENT_LENGTH:
        # Truncate but keep head and tail for context
        head = response_str[:MAX_CONTENT_LENGTH // 2]
        tail = response_str[-(MAX_CONTENT_LENGTH // 2):]
        return f"{head}\n...[truncated {len(response_str)} chars]...\n{tail}"

    return response_str


def _build_memory_content(
    tool_name: str,
    tool_input: dict[str, Any],
    tool_response: Any,
    significance: SignificanceResult,
) -> str:
    """Build the memory entry content from an observation."""
    input_summary = _summarize_input(tool_name, tool_input)
    response_summary = _summarize_response(tool_response)

    parts = [
        f"## Auto-Observation: {tool_name}",
        f"**Score**: {significance.score:.1f} ({significance.category})",
        f"**Tags**: {', '.join(significance.tags)}",
        f"**Reasons**: {', '.join(significance.reasons[:5])}",
        "",
        f"### Input",
        f"```",
        input_summary,
        f"```",
        "",
        f"### Output",
        f"```",
        response_summary[:1000],  # Cap response in memory
        f"```",
    ]

    return "\n".join(parts)


def _store_via_nrepl(
    content: str,
    memory_type: str,
    tags: list[str],
    config: AutoObservationConfig,
) -> str | None:
    """Store an observation via nREPL connection to hive-mcp.

    Uses the bencode protocol to call hive-mcp memory add.
    Returns the memory entry ID if successful, None otherwise.
    """
    if config.dry_run:
        logger.debug("[auto-obs] Dry run — skipping nREPL store")
        return "dry-run-id"

    try:
        # Build Clojure expression for memory add
        escaped_content = content.replace('"', '\\"').replace("\n", "\\n")
        tags_edn = " ".join(f'"{t}"' for t in tags)
        agent_clause = f' :agent_id "{config.agent_id}"' if config.agent_id else ""
        dir_clause = f' :directory "{config.project_dir}"' if config.project_dir else ""

        clj_expr = (
            f'(try '
            f'  (let [result ((requiring-resolve \'hive-mcp.tools.consolidated.memory/handle-memory) '
            f'    {{"command" "add" "type" "{memory_type}" '
            f'     "content" "{escaped_content}" '
            f'     "tags" [{tags_edn}] '
            f'     "duration" "short"'
            f'     {agent_clause}{dir_clause}}})]'
            f'    (str (:id result)))'
            f'  (catch Exception e (str "ERROR:" (.getMessage e))))'
        )

        return _send_nrepl_eval(clj_expr, config.nrepl_host, config.nrepl_port)

    except Exception as e:
        logger.warning(f"[auto-obs] nREPL store failed: {e}")
        return None


def _send_nrepl_eval(code: str, host: str, port: int, timeout: float = 5.0) -> str | None:
    """Send an eval request to nREPL via bencode protocol.

    Minimal bencode implementation — only encodes the eval op,
    decodes enough of the response to extract the value.
    """
    try:
        # Bencode the eval request
        msg = _bencode_dict({"op": "eval", "code": code})

        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect((host, port))

        try:
            sock.sendall(msg)

            # Read response (simplified — just accumulate until we find value)
            response_data = b""
            while True:
                try:
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    response_data += chunk
                    # Check for "done" status in bencode
                    if b"donee" in response_data:  # bencode: 4:done in status list
                        break
                except socket.timeout:
                    logger.debug("[auto-obs] nREPL recv timed out, using partial response")
                    break

            # Extract value from bencode response
            return _extract_nrepl_value(response_data)

        finally:
            sock.close()

    except (ConnectionRefusedError, socket.timeout, OSError) as e:
        logger.warning("[auto-obs] nREPL connection failed (%s:%s): %s", host, port, e)
        return None


def _bencode_dict(d: dict[str, str]) -> bytes:
    """Minimal bencode encoder for string dicts."""
    parts = [b"d"]
    for key, value in sorted(d.items()):
        # Encode key
        key_bytes = key.encode("utf-8")
        parts.append(f"{len(key_bytes)}:".encode("ascii"))
        parts.append(key_bytes)
        # Encode value
        value_bytes = value.encode("utf-8")
        parts.append(f"{len(value_bytes)}:".encode("ascii"))
        parts.append(value_bytes)
    parts.append(b"e")
    return b"".join(parts)


def _extract_nrepl_value(data: bytes) -> str | None:
    """Extract the 'value' field from a bencode nREPL response."""
    try:
        text = data.decode("utf-8", errors="replace")
        # Simple pattern: look for value field in bencode
        # Format: ...5:value<len>:<value>...
        idx = text.find("5:value")
        if idx == -1:
            return None
        # After "5:value", parse the next bencode string
        rest = text[idx + 7:]
        # Parse length prefix
        colon_idx = rest.find(":")
        if colon_idx == -1:
            return None
        try:
            length = int(rest[:colon_idx])
        except ValueError:
            logger.debug("[auto-obs] _extract_nrepl_value: non-integer length prefix")
            return None
        value = rest[colon_idx + 1 : colon_idx + 1 + length]
        return value if value else None
    except Exception:
        logger.warning("[auto-obs] _extract_nrepl_value failed", exc_info=True)
        return None


def create_auto_observation_hook(
    config: AutoObservationConfig | None = None,
):
    """Create a PostToolUse hook callback for auto-observation.

    The returned async function conforms to the Claude Agent SDK's
    HookCallback signature: (HookInput, str|None, HookContext) -> HookJSONOutput

    The hook:
    1. Extracts tool_name, tool_input, tool_response from the event
    2. Scores significance using algorithmic heuristics (zero LLM cost)
    3. For significant events (score >= threshold):
       a. Builds a memory entry content
       b. Stores via nREPL to hive-mcp memory system
       c. Returns additionalContext to Claude about what was captured
    4. For insignificant events:
       a. Logs locally if store_locally is enabled
       b. Returns empty dict (no context injection)

    Args:
        config: AutoObservationConfig instance. Uses defaults if None.

    Returns:
        Async hook callback function.

    Example:
        hook = create_auto_observation_hook(AutoObservationConfig(
            nrepl_port=7910,
            project_dir="/home/user/project",
            agent_id="ling-42",
        ))

        options = ClaudeAgentOptions(
            hooks={"PostToolUse": [HookMatcher(hooks=[hook])]}
        )
    """
    global _last_store_time

    if config is None:
        config = AutoObservationConfig()

    scorer = config.scorer or DEFAULT_SCORER
    threshold = config.threshold if config.threshold is not None else scorer.threshold

    async def _auto_observation_hook(
        input_data: dict[str, Any],
        tool_use_id: str | None,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        """PostToolUse hook for auto-observation."""
        global _last_store_time

        if not config.enabled:
            return {}

        # Extract fields from PostToolUse input
        tool_name = input_data.get("tool_name", "")
        tool_input = input_data.get("tool_input", {})
        tool_response = input_data.get("tool_response")

        # Score significance
        significance = scorer.score(tool_name, tool_input, tool_response)

        # Create observation record
        record = ObservationRecord(
            timestamp=time.time(),
            tool_name=tool_name,
            tool_input_summary=_summarize_input(tool_name, tool_input),
            significance=significance,
        )

        # Always log locally if enabled
        if config.store_locally:
            _observation_log.append(record)

        # Check significance threshold
        if significance.score < threshold:
            logger.debug(
                f"[auto-obs] {tool_name} score={significance.score:.1f} "
                f"({significance.category}) — below threshold {threshold}"
            )
            return {}

        # Debounce: don't flood memory with rapid tool calls
        now = time.time()
        if now - _last_store_time < config.batch_interval_s:
            logger.debug(
                f"[auto-obs] {tool_name} score={significance.score:.1f} — debounced"
            )
            return {}

        _last_store_time = now

        # Build memory content
        memory_content = _build_memory_content(
            tool_name, tool_input, tool_response, significance
        )
        memory_type = significance.memory_type or "note"
        memory_tags = [
            "auto-observation",
            f"tool:{tool_name.lower()}",
            *significance.tags,
        ]
        if config.agent_id:
            memory_tags.append(f"agent:{config.agent_id}")

        # Store to memory via nREPL
        memory_id = _store_via_nrepl(
            memory_content, memory_type, memory_tags, config
        )

        if memory_id:
            record.memory_id = memory_id
            logger.info(
                f"[auto-obs] Stored {tool_name} observation "
                f"(score={significance.score:.1f}, id={memory_id})"
            )
        else:
            record.error = "nREPL store failed"
            logger.warning(
                f"[auto-obs] Failed to store {tool_name} observation "
                f"(score={significance.score:.1f})"
            )

        # Return additionalContext so Claude knows what was captured
        if config.suppress_noise_context and significance.category in ("noise", "low"):
            return {}

        context_msg = (
            f"[Auto-observation] Recorded {significance.category}-signal event: "
            f"{tool_name} (score={significance.score:.1f}). "
            f"Tags: {', '.join(significance.tags[:5])}."
        )

        return {
            "hookSpecificOutput": {
                "hookEventName": "PostToolUse",
                "additionalContext": context_msg,
            }
        }

    return _auto_observation_hook
