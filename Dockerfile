# hive-mcp Kubernetes Deployment Image
#
# Multi-stage build:
#   Stage 1 (builder): Clojure CLI + deps download + uberjar compilation
#   Stage 2 (runtime): Lean JRE image with uberjar + system deps
#
# Build:
#   docker build -t hive-mcp .
#   docker build --build-arg ADDON_PROFILE=k8s-minimal -t hive-mcp:minimal .
#
# Run:
#   docker run -p 7910:7910 -p 9999:9999 -p 7911:7911 -p 7912:7912 hive-mcp
#   docker run -e HIVE_PROFILE=k8s-minimal hive-mcp:minimal
#
# Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

# =============================================================================
# Stage 1: Builder — download deps, compile AOT, build uberjar
# =============================================================================
FROM eclipse-temurin:21-jdk AS builder

# Addon profile selects which deps.edn alias to merge.
# Profiles defined in deps.edn (managed by Track P1):
#   k8s-headless  — 10 addons (no emacs/vterm/tmux)
#   k8s-minimal   — 6 addons (knowledge, agent, ingestor, basic-tools, kondo, scc)
#   desktop       — all 14 addons (not for containers)
ARG ADDON_PROFILE=k8s-headless
ARG CLOJURE_VERSION=1.12.4.1618

# Install Clojure CLI (same pattern as lsp-sidecar Dockerfile)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git rlwrap \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLO https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
    && chmod +x linux-install-${CLOJURE_VERSION}.sh \
    && ./linux-install-${CLOJURE_VERSION}.sh \
    && rm linux-install-${CLOJURE_VERSION}.sh \
    && clojure -e "(clojure-version)"

WORKDIR /build

# ---- Layer 1: Dependency cache (changes rarely) ----
# Copy only dependency descriptors first for Docker layer caching.
# When deps.edn hasn't changed, this layer is cached and deps aren't re-downloaded.
COPY deps.edn deps.edn
COPY build.clj build.clj

# Local jars needed by deps.edn (:local/root references)
COPY lib/ lib/

# Download ALL deps for the selected profile + build tooling.
# -P = prepare (download only, don't run).
# Merge :mcp (runtime deps like nREPL) + profile alias + :build (tools.build).
RUN clj -P -M:mcp:${ADDON_PROFILE}:build

# ---- Layer 2: Source + compile (changes often) ----
COPY src/ src/
COPY resources/ resources/

# Build the uberjar via tools.build (see build.clj).
# The profile alias is passed so addon deps are included in the classpath
# during AOT compilation and bundled into the uber jar.
RUN clj -T:build uber :profile ${ADDON_PROFILE}

# =============================================================================
# Stage 2: Runtime — lean JRE with uberjar
# =============================================================================
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="hive-mcp"
LABEL org.opencontainers.image.description="Hive MCP server — AI coordination platform for K8s deployment"
LABEL org.opencontainers.image.source="https://github.com/hive-agi/hive-mcp"
LABEL org.opencontainers.image.version="0.16.0-SNAPSHOT"
LABEL org.opencontainers.image.licenses="AGPL-3.0-or-later"

# System dependencies:
#   lynx  — required by hive-crawl tool (HTML-to-text conversion)
#   curl  — useful for debugging and ad-hoc health probes
RUN apt-get update \
    && apt-get install -y --no-install-recommends lynx curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root user for security
RUN groupadd -r hive && useradd -r -g hive -m -d /home/hive hive

WORKDIR /app

# Copy uberjar from builder stage
COPY --from=builder /build/target/hive-mcp.jar /app/hive-mcp.jar

# Create directories for runtime data
RUN mkdir -p /home/hive/.config/hive-mcp /app/data \
    && chown -R hive:hive /home/hive /app

USER hive

# --- Ports ---
# 7910: nREPL server (embedded, for REPL access + CIDER)
# 9999: WebSocket channel (ws-channel for IDE bridge)
# 7911: Olympus WebSocket (swarm coordination dashboard)
# 7912: A2A gateway (agent-to-agent protocol)
EXPOSE 7910 9999 7911 7912

# --- JVM Configuration ---
# -Xmx2g: Heap cap for K8s resource limits (adjust via JAVA_OPTS env override)
# -XX:+UseG1GC: Low-latency GC suitable for interactive server
# -XX:MaxGCPauseMillis=200: GC pause target
# -XX:+UseStringDeduplication: Save memory on repeated prompt/response strings
# -Dclojure.tools.logging.factory: Route c.t.logging to SLF4J/Logback
# -Dclojure.core.async.pool-size=8: Bound core.async thread pool
# --add-opens: Required by Datalevin (LMDB) and Netty
# --add-modules: Proximum HNSW SIMD acceleration
ENV JAVA_OPTS="-Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory \
  -Dclojure.core.async.pool-size=8 \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -Dio.netty.tryReflectionSetAccessible=true \
  --add-modules=jdk.incubator.vector"

# --- Health Check ---
# TCP probe on nREPL port 7910 (always started in Phase 3 of server lifecycle).
# Uses bash /dev/tcp which is available without extra packages.
# nREPL is the first network server started and last to shut down — reliable signal.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c "echo > /dev/tcp/localhost/7910" || exit 1

# --- Shutdown ---
# JVM shutdown hook is registered in Phase 1 (lifecycle/init-hooks!).
# It handles: Olympus stop, coordinator marking, session-end/auto-wrap.
# SIGTERM triggers the hook cleanly; SIGKILL is the last resort from K8s.
STOPSIGNAL SIGTERM

# --- Profile Selection ---
# HIVE_PROFILE controls which Integrant profile to load at runtime.
# Profiles: desktop (local dev), k8s-headless (full K8s), k8s-minimal (sidecar).
# Override at runtime: docker run -e HIVE_PROFILE=k8s-minimal hive-mcp
# ADDON_PROFILE (build-time) selects deps.edn alias for addon classpath.
# HIVE_PROFILE (runtime) selects system.edn overlay for component graph.
ENV HIVE_PROFILE=k8s-headless

# --- Entrypoint ---
# Shell form to allow JAVA_OPTS and HIVE_PROFILE expansion.
# Override at runtime:
#   docker run -e JAVA_OPTS="-Xmx4g ..." -e HIVE_PROFILE=k8s-minimal hive-mcp
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/hive-mcp.jar --profile ${HIVE_PROFILE}"]
