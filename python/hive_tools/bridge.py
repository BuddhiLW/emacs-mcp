"""nREPL bridge for calling hive-mcp Clojure handlers from Python.

This module provides the communication layer between Python @tool functions
and the hive-mcp Clojure JVM. Two strategies are supported:

1. **libpython-clj reverse bridge** (preferred): When running inside the same
   JVM via libpython-clj, we can call Clojure functions directly. This is
   zero-overhead and is the default when available.

2. **nREPL socket** (fallback): When running as an external process, we
   connect to the hive-mcp nREPL server (default port 7910) and evaluate
   Clojure expressions. This adds ~5-10ms latency per call.

Architecture:
    Python @tool handler
        -> bridge.call_clj("hivemind", {"command": "shout", ...})
        -> (try libpython-clj reverse call)
        -> (fallback: nREPL eval)
        -> Clojure consolidated handler
        -> JSON response back to Python
"""

from __future__ import annotations

import json
import logging
import os
import socket
import struct
from typing import Any

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Strategy 1: libpython-clj reverse bridge (in-process)
# ---------------------------------------------------------------------------

_libpython_available: bool | None = None


def _check_libpython() -> bool:
    """Check if we're running inside libpython-clj (same JVM)."""
    global _libpython_available
    if _libpython_available is not None:
        return _libpython_available
    try:
        # libpython-clj injects this module when Python is embedded
        import java  # type: ignore[import-not-found]  # noqa: F401

        _libpython_available = True
    except ImportError:
        _libpython_available = False
    return _libpython_available


def _call_via_libpython(handler_ns: str, params: dict[str, Any]) -> dict[str, Any]:
    """Call a Clojure handler directly via libpython-clj interop.

    When running inside the JVM, we can resolve and invoke Clojure functions
    directly without serialization overhead.

    Args:
        handler_ns: The consolidated handler namespace suffix, e.g. "hivemind"
        params: The parameter map to pass to the handler

    Returns:
        The handler's response as a Python dict
    """
    # Import the libpython-clj bridge utilities
    # These are available when Python is embedded in the Clojure JVM
    from libpython_clj2.python import require_resolve  # type: ignore[import-not-found]

    # Resolve the consolidated handler function
    ns_name = f"hive-mcp.tools.consolidated.{handler_ns}"
    handler_fn = require_resolve(f"{ns_name}/handle-{handler_ns}")

    # Call with Clojure keyword-ified params
    result = handler_fn(params)

    # Convert Clojure map to Python dict
    if hasattr(result, "__iter__") and not isinstance(result, (str, bytes)):
        return dict(result)
    return {"result": str(result)}


# ---------------------------------------------------------------------------
# Strategy 2: nREPL socket (external process fallback)
# ---------------------------------------------------------------------------

_NREPL_HOST = os.environ.get("HIVE_MCP_NREPL_HOST", "127.0.0.1")
_NREPL_PORT = int(os.environ.get("HIVE_MCP_NREPL_PORT", "7910"))


def _bencode_encode(obj: Any) -> bytes:
    """Encode a Python object to bencode format (nREPL wire protocol)."""
    if isinstance(obj, int):
        return f"i{obj}e".encode()
    if isinstance(obj, bytes):
        return f"{len(obj)}:".encode() + obj
    if isinstance(obj, str):
        encoded = obj.encode("utf-8")
        return f"{len(encoded)}:".encode() + encoded
    if isinstance(obj, list):
        return b"l" + b"".join(_bencode_encode(item) for item in obj) + b"e"
    if isinstance(obj, dict):
        result = b"d"
        for k, v in sorted(obj.items()):
            result += _bencode_encode(str(k)) + _bencode_encode(v)
        result += b"e"
        return result
    raise TypeError(f"Cannot bencode type: {type(obj)}")


def _bencode_decode(data: bytes, pos: int = 0) -> tuple[Any, int]:
    """Decode a bencode-encoded value starting at pos."""
    if data[pos:pos + 1] == b"i":
        end = data.index(b"e", pos)
        return int(data[pos + 1 : end]), end + 1
    if data[pos:pos + 1] == b"l":
        result = []
        pos += 1
        while data[pos:pos + 1] != b"e":
            item, pos = _bencode_decode(data, pos)
            result.append(item)
        return result, pos + 1
    if data[pos:pos + 1] == b"d":
        result = {}
        pos += 1
        while data[pos:pos + 1] != b"e":
            key, pos = _bencode_decode(data, pos)
            val, pos = _bencode_decode(data, pos)
            result[key if isinstance(key, str) else key.decode()] = val
            if isinstance(result[key if isinstance(key, str) else key.decode()], bytes):
                result[key if isinstance(key, str) else key.decode()] = val.decode("utf-8", errors="replace")
        return result, pos + 1
    # String (byte string with length prefix)
    colon = data.index(b":", pos)
    length = int(data[pos:colon])
    start = colon + 1
    value = data[start : start + length]
    return value.decode("utf-8", errors="replace"), start + length


def _nrepl_eval(code: str, host: str = _NREPL_HOST, port: int = _NREPL_PORT,
                timeout: float = 30.0) -> str:
    """Evaluate Clojure code via nREPL socket and return the result string.

    Args:
        code: Clojure expression to evaluate
        host: nREPL host
        port: nREPL port
        timeout: Socket timeout in seconds

    Returns:
        The :value from the nREPL response

    Raises:
        ConnectionError: If nREPL is unreachable
        RuntimeError: If evaluation fails
    """
    msg = {"op": "eval", "code": code}
    encoded = _bencode_encode(msg)

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect((host, port))
        sock.sendall(encoded)

        # Read responses until we get a "done" status
        buf = b""
        value = None
        error = None

        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buf += chunk

            # Try to decode accumulated buffer
            try:
                pos = 0
                while pos < len(buf):
                    resp, new_pos = _bencode_decode(buf, pos)
                    if isinstance(resp, dict):
                        if "value" in resp:
                            value = resp["value"]
                        if "err" in resp:
                            error = resp["err"]
                        if "ex" in resp:
                            error = resp.get("err", resp["ex"])
                        status = resp.get("status", [])
                        if isinstance(status, list) and "done" in status:
                            sock.close()
                            if error:
                                raise RuntimeError(f"nREPL eval error: {error}")
                            return value or ""
                    pos = new_pos
                buf = buf[pos:]
            except (IndexError, ValueError) as exc:
                # Incomplete message, keep reading
                logger.debug("nREPL bencode decode incomplete, reading more: %s", exc)
                continue

        sock.close()
        if error:
            raise RuntimeError(f"nREPL eval error: {error}")
        return value or ""

    except ConnectionRefusedError:
        raise ConnectionError(
            f"nREPL connection refused on {host}:{port}. "
            f"Ensure hive-mcp is running with nREPL enabled."
        )
    except socket.timeout:
        raise ConnectionError(
            f"nREPL connection timed out on {host}:{port} after {timeout}s"
        )


def _call_via_nrepl(handler_name: str, params: dict[str, Any]) -> dict[str, Any]:
    """Call a consolidated Clojure handler via nREPL.

    Constructs a Clojure expression that requires the handler namespace,
    calls the handler with the params map, and returns JSON.

    Args:
        handler_name: The consolidated handler name, e.g. "hivemind"
        params: The parameter map

    Returns:
        The handler's response as a Python dict
    """
    # Convert Python dict to Clojure map literal
    clj_params = _python_to_clj_literal(params)

    code = f"""
    (let [handler (requiring-resolve
                    'hive-mcp.tools.consolidated.{handler_name}/handle-{handler_name})
          result  (handler {clj_params})]
      (cheshire.core/generate-string result))
    """

    result_json = _nrepl_eval(code.strip())
    # The result is a JSON string (possibly double-quoted from nREPL)
    # Strip outer quotes if present (nREPL wraps string values in quotes)
    if result_json.startswith('"') and result_json.endswith('"'):
        result_json = json.loads(result_json)  # unescape the string
    return json.loads(result_json)


def _python_to_clj_literal(obj: Any) -> str:
    """Convert a Python object to a Clojure literal string.

    Args:
        obj: Python dict, list, str, int, float, bool, or None

    Returns:
        Clojure literal representation
    """
    if obj is None:
        return "nil"
    if isinstance(obj, bool):
        return "true" if obj else "false"
    if isinstance(obj, (int, float)):
        return str(obj)
    if isinstance(obj, str):
        # Escape for Clojure string literal
        escaped = obj.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        return f'"{escaped}"'
    if isinstance(obj, list):
        items = " ".join(_python_to_clj_literal(item) for item in obj)
        return f"[{items}]"
    if isinstance(obj, dict):
        pairs = " ".join(
            f'"{k}" {_python_to_clj_literal(v)}' for k, v in obj.items()
        )
        return "{" + pairs + "}"
    return f'"{obj}"'


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def call_handler(handler_name: str, params: dict[str, Any]) -> dict[str, Any]:
    """Call a hive-mcp consolidated handler.

    Automatically selects the best bridge strategy:
    1. libpython-clj (in-process) if available
    2. nREPL socket (fallback)

    Args:
        handler_name: Consolidated handler name (e.g. "hivemind", "kanban", "session")
        params: Parameter map matching the handler's input schema

    Returns:
        Handler response as a Python dict

    Raises:
        ConnectionError: If no bridge strategy is available
        RuntimeError: If handler evaluation fails

    Example:
        >>> result = call_handler("hivemind", {
        ...     "command": "shout",
        ...     "agent_id": "ling-123",
        ...     "event_type": "progress",
        ...     "message": "50% done"
        ... })
    """
    if _check_libpython():
        return _call_via_libpython(handler_name, params)
    return _call_via_nrepl(handler_name, params)


def is_bridge_available() -> dict[str, Any]:
    """Check bridge availability and return status.

    Returns:
        Dict with:
            available: bool - whether any bridge strategy works
            strategy: str - "libpython" or "nrepl" or "none"
            details: str - human-readable status
    """
    if _check_libpython():
        return {
            "available": True,
            "strategy": "libpython",
            "details": "Running inside JVM via libpython-clj (zero overhead)",
        }

    # Try nREPL ping
    try:
        result = _nrepl_eval("(+ 1 1)")
        if result.strip() == "2":
            return {
                "available": True,
                "strategy": "nrepl",
                "details": f"Connected to nREPL on {_NREPL_HOST}:{_NREPL_PORT}",
            }
    except (ConnectionError, RuntimeError) as e:
        logger.debug("nREPL bridge probe failed: %s", e)

    return {
        "available": False,
        "strategy": "none",
        "details": (
            f"No bridge available. libpython-clj not detected, "
            f"nREPL on {_NREPL_HOST}:{_NREPL_PORT} unreachable."
        ),
    }
