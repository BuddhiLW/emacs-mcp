#!/usr/bin/env bash
# ============================================================================
# bin/test-sandboxed.sh — run the hive-mcp test suite in a FULLY ISOLATED JVM
# ----------------------------------------------------------------------------
# Cold `clojure -M:test` on the live-server host has killed the running
# production hive-mcp server TWICE (2026-06-29). Root cause: Clojure namespace
# load + un-fixtured test bodies boot real services against SHARED stores/ports:
#   Killer #1  konserve(0.9.340) file-lock contention on the shared datahike
#              KG store (~/.local/share/hive-mcp/datahike) => live writer dies.
#   Killer #2  a second file store / service booted against a live resource
#              (the CWD-relative swarm datahike store data/swarm/datahike, or
#               nrepl 7910 / nats / milvus / qdrant).
#
# This wrapper runs the EXISTING runner (:test => cognitect.test-runner) inside
# a throwaway sandbox where HOME + every XDG root + the JVM's user.home + the
# config.edn + every store path resolve INTO a mktemp -d dir, and every live
# service is disabled or redirected to a closed loopback port. The live server,
# its stores, and real infra are never touched.
#
# Usage:
#   bin/test-sandboxed.sh                                  # whole suite (sandboxed)
#   bin/test-sandboxed.sh --focus hive-mcp.knowledge-graph.edges-test
#   bin/test-sandboxed.sh -n hive-mcp.knowledge-graph.edges-test   # native flag
#   bin/test-sandboxed.sh -r 'knowledge-graph.*'          # namespace regex
#   bin/test-sandboxed.sh --keep-sandbox --focus <ns>     # don't delete sandbox
#   bin/test-sandboxed.sh --help
#
# Any arg other than --focus/--keep-sandbox/--help is passed THROUGH verbatim to
# cognitect.test-runner (-n/--namespace, -r/--namespace-regex, -v/--var,
# -i/--include, -e/--exclude, -d/--dir ...). --focus <ns> is sugar for -n <ns>.
#
# Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
# SPDX-License-Identifier: AGPL-3.0-or-later
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE="$PROJECT_DIR/dev/test-sandbox.config.edn"

die() { echo "test-sandboxed: FATAL: $*" >&2; exit 2; }

usage() { sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

# ── Capture the REAL environment BEFORE we clobber it ───────────────────────
REAL_HOME="${HOME:?HOME must be set}"
REAL_KG_STORE="$REAL_HOME/.local/share/hive-mcp/datahike"   # the live KG store
TMPROOT="${TMPDIR:-/tmp}"; TMPROOT="${TMPROOT%/}"

[[ -f "$TEMPLATE" ]] || die "missing sandbox config template: $TEMPLATE"
command -v clojure >/dev/null 2>&1 || die "clojure CLI not found on PATH"

# ── Argument parsing: --focus => -n, else pass through ──────────────────────
KEEP_SANDBOX=0
RUNNER_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)      usage ;;
    --keep-sandbox) KEEP_SANDBOX=1; shift ;;
    --focus)        RUNNER_ARGS+=("-n" "${2:?--focus requires a namespace}"); shift 2 ;;
    --focus=*)      RUNNER_ARGS+=("-n" "${1#*=}"); shift ;;
    *)              RUNNER_ARGS+=("$1"); shift ;;
  esac
done

# ── Create the sandbox root (the ONLY path this script ever deletes) ────────
SBX="$(mktemp -d "$TMPROOT/hive-mcp-test-sbx.XXXXXXXX")"

# ── Guardrails: prove the sandbox is a throwaway temp dir, not a real path ──
[[ -n "$SBX" && -d "$SBX" ]]                 || die "sandbox root not created"
[[ "$SBX" == "$TMPROOT/hive-mcp-test-sbx."* ]] || die "sandbox root has an unexpected path: $SBX"
[[ "$SBX" != "$REAL_HOME" ]]                 || die "sandbox root == real HOME ($REAL_HOME)"
[[ "$SBX" != "$PROJECT_DIR" ]]               || die "sandbox root == project dir"

# ── Cleanup: delete ONLY our mktemp dir, never a real path (NOTE: no exec) ──
# shellcheck disable=SC2317  # reached via `trap`, not inline — SC can't see it
cleanup() {
  if [[ "$KEEP_SANDBOX" -eq 0 && -n "${SBX:-}" && -d "$SBX" \
        && "$SBX" == "$TMPROOT/hive-mcp-test-sbx."* && "$SBX" != "$REAL_HOME" ]]; then
    rm -rf "$SBX"
  else
    echo "test-sandboxed: kept sandbox at $SBX" >&2
  fi
}
trap cleanup EXIT INT TERM

# ── Build the sandbox filesystem tree ───────────────────────────────────────
mkdir -p "$SBX/.config/hive-mcp" "$SBX/.local/share" "$SBX/.cache" "$SBX/run" "$SBX/state" "$SBX/tmp"
chmod 700 "$SBX/run"   # XDG_RUNTIME_DIR must be 0700

# Share the user's IMMUTABLE dependency caches (jars/git deps/tools) so HOME=$SBX
# does NOT trigger a full re-download. These are read-only caches, NOT app state:
# app state (config.edn, KG/swarm stores, carto sentinel) lives under .config /
# .local/share / .state which are REAL sandbox dirs, never symlinked out.
for c in .m2 .gitlibs .clojure; do
  if [[ -e "$REAL_HOME/$c" && ! -e "$SBX/$c" ]]; then
    ln -s "$REAL_HOME/$c" "$SBX/$c"
  fi
done

# ── Render the sandbox config from the auditable template ───────────────────
SBX_CONFIG="$SBX/.config/hive-mcp/config.edn"
sed "s#__SANDBOX__#$SBX#g" "$TEMPLATE" > "$SBX_CONFIG"
chmod 600 "$SBX_CONFIG"

# ── Environment isolation ───────────────────────────────────────────────────
# HOME + XDG => sandbox (redirects any HOME/XDG-relative lookup + child procs).
export HOME="$SBX"
export XDG_CONFIG_HOME="$SBX/.config"
export XDG_DATA_HOME="$SBX/.local/share"
export XDG_CACHE_HOME="$SBX/.cache"
export XDG_RUNTIME_DIR="$SBX/run"
# Keep the Clojure toolchain pointed at the REAL caches (fast, no re-resolve).
export GITLIBS="$REAL_HOME/.gitlibs"
export CLJ_CONFIG="$REAL_HOME/.clojure"
# KG store overrides (belt-and-suspenders on top of the config + JVM prop).
export HIVE_KG_DB_PATH="$SBX/state/kg-datahike"   # if datahike ever opens: sandbox
export HIVE_KG_DH_BACKEND="memory"                # if datahike ever opens: no file lock
export HIVE_KG_BACKEND="datascript"               # env-level backend hint
export HIVE_PROFILE="test-sandbox"                # in case a boot path reads it

# ── Refuse to run if the KG path could resolve to the LIVE store ────────────
[[ "$HIVE_KG_DB_PATH" != "$REAL_KG_STORE" ]] || die "HIVE_KG_DB_PATH resolves to the LIVE store"
[[ "$HIVE_KG_DB_PATH" == "$SBX/"* ]]         || die "HIVE_KG_DB_PATH is not inside the sandbox"
[[ "$HOME" != "$REAL_HOME" ]]                || die "HOME failed to redirect off the real home"

# ── JVM options: user.home redirect + KG datascript + heap cap ──────────────
# -Duser.home is the LOAD-BEARING redirect: hive-mcp.config.io/config-path and
# knowledge-graph.store.datahike-config both build paths from
# (System/getProperty "user.home"), which -D overrides regardless of HOME env.
JVM_OPTS=(
  -J-Duser.home="$SBX"
  -J-Djava.io.tmpdir="$SBX/tmp"         # redirect Files/createTempDirectory (swarm tests) into the sandbox, not real /tmp
  -J-Dhive.kg.backend=datascript        # top-priority backend force (see connection/detect-backend)
  -J-Xmx2g                              # heap cap: datascript unit tests need little
  -J-XX:+ExitOnOutOfMemoryError         # a runaway test dies fast, never thrashes the box
)

# ── Audit: echo the resolved isolation env ──────────────────────────────────
cat >&2 <<AUDIT
test-sandboxed: ISOLATION ENV -------------------------------------------------
  sandbox root      : $SBX
  HOME              : $HOME        (real was: $REAL_HOME)
  XDG_CONFIG_HOME   : $XDG_CONFIG_HOME
  XDG_DATA_HOME     : $XDG_DATA_HOME
  XDG_CACHE_HOME    : $XDG_CACHE_HOME
  XDG_RUNTIME_DIR   : $XDG_RUNTIME_DIR
  config.edn        : $SBX_CONFIG   (real ~/.config/hive-mcp/config.edn untouched)
  user.home (JVM)   : $SBX   (via -Duser.home)
  KG backend        : datascript (-Dhive.kg.backend + HIVE_KG_BACKEND)
  HIVE_KG_DB_PATH   : $HIVE_KG_DB_PATH
  HIVE_KG_DH_BACKEND: $HIVE_KG_DH_BACKEND
  GITLIBS (shared)  : $GITLIBS
  CLJ_CONFIG(shared): $CLJ_CONFIG
  live store guard  : NOT $REAL_KG_STORE
  runner            : clojure -M:test  (cognitect.test-runner)
  runner args       : ${RUNNER_ARGS[*]:-<none: whole suite>}
-------------------------------------------------------------------------------
AUDIT

# ── Run (NOT exec — so the EXIT trap fires and the sandbox is cleaned up) ────
cd "$PROJECT_DIR"
set +e
clojure "${JVM_OPTS[@]}" -M:test ${RUNNER_ARGS[@]+"${RUNNER_ARGS[@]}"}
rc=$?
set -e
echo "test-sandboxed: runner exited rc=$rc" >&2
exit "$rc"
