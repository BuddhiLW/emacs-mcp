"""Tests for the local wrap-fallback path used when nREPL is unreachable.

Covers kanban bug 20260404141548-1c053836: ``session_wrap`` / ``workflow wrap``
must degrade gracefully when the babashka nREPL is down — persisting a
breadcrumb (content + tags + temporal metadata) and returning a structured
``wrap-degraded`` response instead of raising an HTTP-style error.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import pytest

from hive_tools.coordination import session_complete, session_wrap
from hive_tools.wrap_fallback import (
    degraded_wrap_response,
    is_nrepl_down_error,
    persist_wrap_breadcrumb,
    wrap_queue_dir,
)


# =============================================================================
# is_nrepl_down_error classifier
# =============================================================================


class TestIsNreplDownError:
    def test_connection_error_classified(self):
        assert is_nrepl_down_error(ConnectionError("nREPL connection refused"))

    def test_timeout_error_classified(self):
        assert is_nrepl_down_error(TimeoutError("nREPL connection timed out"))

    def test_runtime_error_with_http_message_classified(self):
        # The user-facing string from the bug report.
        assert is_nrepl_down_error(RuntimeError("HTTP Error: server unreachable"))

    def test_runtime_error_with_nrepl_eval_message_classified(self):
        assert is_nrepl_down_error(RuntimeError("nREPL eval error: timeout"))

    def test_runtime_error_with_connection_refused_classified(self):
        assert is_nrepl_down_error(RuntimeError("Connection refused"))

    def test_value_error_not_classified(self):
        # Plain validation failures should NOT be treated as nREPL-down.
        assert not is_nrepl_down_error(ValueError("bad agent_id"))

    def test_runtime_error_with_unrelated_message_not_classified(self):
        assert not is_nrepl_down_error(RuntimeError("Schema validation failed"))


# =============================================================================
# persist_wrap_breadcrumb writes structured JSON
# =============================================================================


class TestPersistWrapBreadcrumb:
    def test_writes_json_file_with_required_fields(self, tmp_path: Path):
        now = datetime(2026, 4, 28, 12, 34, 56, tzinfo=timezone.utc)
        result = persist_wrap_breadcrumb(
            agent_id="ling-test-1",
            directory="/home/u/proj",
            reason="ConnectionError: nREPL refused",
            queue_dir=tmp_path,
            now=now,
        )

        assert result["persisted"] is True
        path = Path(result["path"])
        assert path.exists()
        assert path.parent == tmp_path

        data = json.loads(path.read_text())
        assert data["kind"] == "session-wrap-breadcrumb"
        assert data["agent_id"] == "ling-test-1"
        assert data["directory"] == "/home/u/proj"
        assert data["wrapped_at"] == now.isoformat()
        # Tags include the required temporal/scope markers.
        assert "session-wrap" in data["tags"]
        assert "wrap-degraded" in data["tags"]
        assert "nrepl-down" in data["tags"]
        assert "agent:ling-test-1" in data["tags"]
        # Content carries the reason.
        assert "ConnectionError" in data["content"]
        assert "nREPL refused" in data["content"]
        # Temporal metadata present.
        assert data["temporal"]["wrapped_at"] == now.isoformat()
        assert isinstance(data["temporal"]["epoch_ms"], int)

    def test_handles_missing_agent_and_directory(self, tmp_path: Path):
        result = persist_wrap_breadcrumb(
            agent_id=None,
            directory=None,
            reason="HTTP Error",
            queue_dir=tmp_path,
        )
        assert result["persisted"] is True
        data = json.loads(Path(result["path"]).read_text())
        assert data["agent_id"] is None
        assert data["directory"] is None
        assert "agent:unknown" in data["tags"]

    def test_creates_queue_dir_if_missing(self, tmp_path: Path):
        target = tmp_path / "nested" / "queue"
        assert not target.exists()
        result = persist_wrap_breadcrumb(
            agent_id="ling-x",
            directory="/d",
            reason="down",
            queue_dir=target,
        )
        assert result["persisted"] is True
        assert target.is_dir()

    def test_filename_sanitizes_agent_id(self, tmp_path: Path):
        # Agent IDs may contain slashes (e.g. swarm/worker-1); the filename
        # must remain a single segment.
        result = persist_wrap_breadcrumb(
            agent_id="swarm/worker-1",
            directory="/d",
            reason="down",
            queue_dir=tmp_path,
        )
        assert result["persisted"] is True
        path = Path(result["path"])
        assert path.parent == tmp_path
        assert "/" not in path.name


# =============================================================================
# wrap_queue_dir env override
# =============================================================================


class TestWrapQueueDir:
    def test_default_path_under_home(self, monkeypatch):
        monkeypatch.delenv("HIVE_WRAP_QUEUE_DIR", raising=False)
        result = wrap_queue_dir()
        assert result.name == "wrap-queue"

    def test_env_var_override(self, monkeypatch, tmp_path: Path):
        monkeypatch.setenv("HIVE_WRAP_QUEUE_DIR", str(tmp_path))
        assert wrap_queue_dir() == tmp_path


# =============================================================================
# degraded_wrap_response shape
# =============================================================================


class TestDegradedWrapResponse:
    def test_response_has_expected_keys(self, tmp_path: Path):
        payload = degraded_wrap_response(
            agent_id="ling-1",
            directory="/d",
            reason="ConnectionError: nREPL refused",
            queue_dir=tmp_path,
        )
        assert payload["status"] == "wrap-degraded"
        assert payload["degraded"] is True
        assert payload["nrepl_available"] is False
        assert payload["agent_id"] == "ling-1"
        assert payload["directory"] == "/d"
        assert payload["breadcrumb"]["persisted"] is True
        assert "message" in payload


# =============================================================================
# session_wrap end-to-end with nREPL-down bridge
# =============================================================================


class TestSessionWrapDegradedPath:
    """End-to-end: session_wrap must NOT raise when nREPL is unreachable —
    it must persist a breadcrumb and return a structured success response."""

    @pytest.fixture(autouse=True)
    def isolate_queue_dir(self, tmp_path: Path, monkeypatch):
        monkeypatch.setenv("HIVE_WRAP_QUEUE_DIR", str(tmp_path))
        self.queue_dir = tmp_path
        yield

    @pytest.mark.asyncio
    async def test_session_wrap_with_nrepl_connection_error(self):
        with patch("hive_tools.coordination.call_handler") as mock_call:
            mock_call.side_effect = ConnectionError(
                "nREPL connection refused on localhost:7910"
            )
            result = await session_wrap.handler({
                "agent_id": "ling-degraded-1",
                "directory": "/home/u/proj",
            })

        # Must NOT be marked is_error — wrap is degraded but successful.
        assert "is_error" not in result or result.get("is_error") is not True
        body = json.loads(result["content"][0]["text"])
        assert body["status"] == "wrap-degraded"
        assert body["degraded"] is True
        assert body["nrepl_available"] is False
        assert body["agent_id"] == "ling-degraded-1"
        # Breadcrumb actually written under the queue dir.
        assert body["breadcrumb"]["persisted"] is True
        bpath = Path(body["breadcrumb"]["path"])
        assert bpath.exists()
        assert bpath.parent == self.queue_dir
        data = json.loads(bpath.read_text())
        assert data["kind"] == "session-wrap-breadcrumb"
        assert data["agent_id"] == "ling-degraded-1"
        assert "session-wrap" in data["tags"]

    @pytest.mark.asyncio
    async def test_session_wrap_with_runtime_http_error(self):
        with patch("hive_tools.coordination.call_handler") as mock_call:
            mock_call.side_effect = RuntimeError("HTTP Error: nREPL down")
            result = await session_wrap.handler({})

        assert result.get("is_error") is not True
        body = json.loads(result["content"][0]["text"])
        assert body["status"] == "wrap-degraded"

    @pytest.mark.asyncio
    async def test_session_wrap_unrelated_error_still_returns_error(self):
        # Validation/domain failures should NOT silently degrade — they must
        # remain visible as errors.
        with patch("hive_tools.coordination.call_handler") as mock_call:
            mock_call.side_effect = ValueError("bad params")
            result = await session_wrap.handler({})

        assert result.get("is_error") is True
        assert "session_wrap failed" in result["content"][0]["text"]

    @pytest.mark.asyncio
    async def test_session_wrap_happy_path_unchanged(self):
        # When nREPL is up, the response goes through the normal
        # _text_response path and there is no breadcrumb.
        with patch("hive_tools.coordination.call_handler") as mock_call:
            mock_call.return_value = {"status": "wrap-started",
                                      "agent-id": "ling-1"}
            result = await session_wrap.handler({"agent_id": "ling-1"})

        assert result.get("is_error") is not True
        body = json.loads(result["content"][0]["text"])
        # No degraded markers.
        assert body.get("degraded") is not True
        # Queue dir untouched.
        assert not list(self.queue_dir.iterdir())


class TestSessionCompleteDegradedPath:
    """session_complete must also fall back to a wrap breadcrumb when nREPL
    is unreachable (vs surfacing a raw HTTP error)."""

    @pytest.fixture(autouse=True)
    def isolate_queue_dir(self, tmp_path: Path, monkeypatch):
        monkeypatch.setenv("HIVE_WRAP_QUEUE_DIR", str(tmp_path))
        self.queue_dir = tmp_path
        yield

    @pytest.mark.asyncio
    async def test_session_complete_with_nrepl_down(self):
        with patch("hive_tools.coordination.call_handler") as mock_call:
            mock_call.side_effect = ConnectionError("nREPL connection refused")
            result = await session_complete.handler({
                "agent_id": "ling-c-1",
                "directory": "/home/u/proj",
            })

        assert result.get("is_error") is not True
        body = json.loads(result["content"][0]["text"])
        assert body["status"] == "session-complete-degraded"
        assert body["degraded"] is True
        assert body["breadcrumb"]["persisted"] is True
