"""Local wrap fallback for the nREPL-down degraded path.

When the babashka nREPL backing hive-mcp is unreachable, ``session_wrap`` (and
``workflow wrap``) cannot reach the Clojure crystal pipeline (synthesis, KG
edges, hivemind shout). A raw ``ConnectionError`` surfaces to the MCP client
as an HTTP-style error, losing the wrap entirely (kanban bug
20260404141548-1c053836).

Instead, we persist a breadcrumb to a local wrap-queue directory with
content, tags, and temporal metadata. A subsequent successful wrap (or the
coordinator at session-end) can drain this queue back into Chroma/Datahike.

The breadcrumb format is intentionally simple — one JSON file per wrap — so
any future drain process (Clojure-side or out-of-band) can re-ingest without
schema gymnastics.
"""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)


WRAP_QUEUE_ENV = "HIVE_WRAP_QUEUE_DIR"
WRAP_QUEUE_DEFAULT = "~/.hive/wrap-queue"


def wrap_queue_dir() -> Path:
    """Resolve the local wrap-queue directory.

    Honors the ``HIVE_WRAP_QUEUE_DIR`` environment variable, falling back to
    ``~/.hive/wrap-queue``. Returns an absolute :class:`Path`; the directory is
    created lazily by :func:`persist_wrap_breadcrumb`.
    """
    raw = os.environ.get(WRAP_QUEUE_ENV) or WRAP_QUEUE_DEFAULT
    return Path(os.path.expanduser(raw))


def is_nrepl_down_error(exc: BaseException) -> bool:
    """Return True when the exception looks like an nREPL/HTTP transport
    failure (vs a domain error from a healthy nREPL).

    Catches:
      - ``ConnectionError`` (nREPL refused / timed out — see
        ``hive_tools.bridge._nrepl_eval``)
      - ``OSError`` subclasses (socket-level failures)
      - any exception whose message mentions ``HTTP Error`` or ``nREPL`` —
        this is the user-visible string from the original bug report.
    """
    if isinstance(exc, (ConnectionError, TimeoutError)):
        return True
    msg = str(exc) or ""
    if not msg:
        return False
    needles = ("HTTP Error", "nREPL connection", "nREPL eval", "Connection refused")
    return any(needle in msg for needle in needles)


def persist_wrap_breadcrumb(
    agent_id: str | None,
    directory: str | None,
    reason: str,
    *,
    queue_dir: Path | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Persist a wrap breadcrumb locally when the nREPL pipeline is unreachable.

    Args:
        agent_id: Ling/agent ID for attribution. May be ``None``.
        directory: Working directory the wrap was scoped to. May be ``None``.
        reason: Human-readable reason for the degraded wrap (typically the
            ``ConnectionError`` message).
        queue_dir: Override the queue directory (test seam).
        now: Override the timestamp (test seam).

    Returns:
        A metadata dict with ``persisted`` (bool), the resolved ``path`` and
        ``queue_dir``, the tags written, and the ISO timestamp. On filesystem
        failure, ``persisted`` is ``False`` and an ``error`` key explains why.
    """
    now = now or datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%S")
    qdir = queue_dir or wrap_queue_dir()

    try:
        qdir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        logger.warning("wrap breadcrumb: cannot create queue dir %s: %s",
                       qdir, exc)
        return {
            "persisted": False,
            "queue_dir": str(qdir),
            "error": f"mkdir failed: {exc}",
        }

    safe_agent = (agent_id or "unknown").replace("/", "_")
    filename = f"wrap-{ts}-{safe_agent}.json"
    path = qdir / filename

    breadcrumb = {
        "kind": "session-wrap-breadcrumb",
        "agent_id": agent_id,
        "directory": directory,
        "wrapped_at": now.isoformat(),
        "tags": [
            "session-wrap",
            "wrap-degraded",
            "nrepl-down",
            f"agent:{agent_id}" if agent_id else "agent:unknown",
        ],
        "content": (
            f"## Session Wrap (degraded — nREPL down)\n\n"
            f"Agent: {agent_id or 'unknown'}\n"
            f"Directory: {directory or 'unknown'}\n"
            f"Wrapped at: {now.isoformat()}\n\n"
            f"Reason: {reason}\n\n"
            f"Crystal synthesis, KG edges, and hivemind shout were skipped "
            f"because the nREPL backing hive-mcp was unreachable. This "
            f"breadcrumb is queued locally and will be drained on the next "
            f"successful wrap."
        ),
        "reason": reason,
        "temporal": {
            "wrapped_at": now.isoformat(),
            "epoch_ms": int(now.timestamp() * 1000),
        },
    }

    try:
        path.write_text(json.dumps(breadcrumb, indent=2))
    except OSError as exc:
        logger.warning("wrap breadcrumb: write failed %s: %s", path, exc)
        return {
            "persisted": False,
            "queue_dir": str(qdir),
            "error": f"write failed: {exc}",
        }

    logger.info("wrap breadcrumb persisted: %s", path)
    return {
        "persisted": True,
        "path": str(path),
        "queue_dir": str(qdir),
        "tags": breadcrumb["tags"],
        "wrapped_at": breadcrumb["wrapped_at"],
    }


def degraded_wrap_response(
    agent_id: str | None,
    directory: str | None,
    reason: str,
    *,
    queue_dir: Path | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Build the structured payload returned to the MCP client when wrap is
    degraded due to nREPL absence.

    Always returns a dict (never raises). Use this in tool handlers in place of
    ``_error_response`` when the underlying failure is an nREPL/HTTP transport
    issue — the wrap is still considered successful (breadcrumb persisted),
    just degraded.
    """
    breadcrumb = persist_wrap_breadcrumb(
        agent_id, directory, reason,
        queue_dir=queue_dir,
        now=now,
    )
    return {
        "status": "wrap-degraded",
        "degraded": True,
        "nrepl_available": False,
        "agent_id": agent_id,
        "directory": directory,
        "reason": reason,
        "breadcrumb": breadcrumb,
        "message": (
            "nREPL was unreachable; persisted a local wrap breadcrumb. "
            "Crystal synthesis, KG edges, and hivemind shout were skipped. "
            "The breadcrumb will be drained on the next successful wrap."
        ),
    }
