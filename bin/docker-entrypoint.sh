#!/bin/sh
#
# Container entrypoint: run the MCP server from source, the way
# bin/hive-mcp-foss does on a workstation.
#
#   HIVE_DEPS_OVERLAY  deps.edn fragment merged through -Sdeps (default: the
#                      starter pack baked at build time; empty = bare core)
#   HIVE_HEAP          -Xmx for the server JVM (default 2g)
#   HIVE_PROFILE       system profile: desktop | k8s-headless | k8s-minimal
#
# Extra arguments are passed to the clojure CLI ahead of -M:mcp, e.g.
#   docker run hive-mcp -J-XX:+PrintFlagsFinal
set -eu

cd /app

if [ -n "${HIVE_DEPS_OVERLAY:-}" ] && [ -f "$HIVE_DEPS_OVERLAY" ]; then
  set -- -Sdeps "$(cat "$HIVE_DEPS_OVERLAY")" "$@"
fi

# -J flags are appended after the alias :jvm-opts, so they win.
exec clojure "$@" \
  -J-Xms256m -J-Xmx"${HIVE_HEAP:-2g}" \
  -M:mcp -m hive-mcp.server.core --profile "${HIVE_PROFILE:-k8s-headless}"
