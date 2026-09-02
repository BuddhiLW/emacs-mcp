# hive-mcp container image
#
# Runs the MCP server FROM SOURCE with the Clojure CLI, exactly as
# bin/hive-mcp-foss does on a workstation. There is no uberjar stage:
# hive-build ships jar/deploy tasks only, and the server composes its
# addons at boot from deps.edn plus an optional overlay (starter.deps.edn).
#
# Build:
#   docker build -t hive-mcp .                                   # starter pack
#   docker build --build-arg DEPS_OVERLAY= -t hive-mcp:core .    # bare core
#
# Run:
#   docker run -p 7910:7910 -p 9999:9999 -p 7911:7911 -p 7912:7912 hive-mcp
#   docker run -e HIVE_PROFILE=k8s-minimal -e HIVE_HEAP=4g hive-mcp
#
# Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM eclipse-temurin:21-jdk

ARG CLOJURE_VERSION=1.12.4.1618
# deps.edn fragment merged through -Sdeps at prep time and at boot.
# Empty keeps the bare core: no vessel, no code tools, in-memory KG.
ARG DEPS_OVERLAY=starter.deps.edn

LABEL org.opencontainers.image.title="hive-mcp"
LABEL org.opencontainers.image.description="Hive MCP server: agent coordination, memory and knowledge graph"
LABEL org.opencontainers.image.source="https://github.com/hive-agi/hive-mcp"
LABEL org.opencontainers.image.licenses="AGPL-3.0-or-later"

# git: tools.deps git coordinates; lynx: hive-crawl HTML-to-text; curl: probes
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git lynx rlwrap \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLO https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
    && chmod +x linux-install-${CLOJURE_VERSION}.sh \
    && ./linux-install-${CLOJURE_VERSION}.sh \
    && rm linux-install-${CLOJURE_VERSION}.sh \
    && clojure -e "(clojure-version)"

# Non-root user; ~/.m2 and .cpcache below belong to it.
RUN groupadd -r hive && useradd -r -g hive -m -d /home/hive hive \
    && mkdir -p /app /home/hive/.config/hive-mcp /app/data \
    && chown -R hive:hive /app /home/hive
WORKDIR /app
USER hive

# ---- Layer 1: dependency cache (changes rarely) ----
COPY --chown=hive:hive deps.edn VERSION version.edn starter.deps.edn ./
COPY --chown=hive:hive lib/ lib/
ENV HIVE_DEPS_OVERLAY=${DEPS_OVERLAY}
RUN if [ -n "$HIVE_DEPS_OVERLAY" ]; then \
      clojure -Sdeps "$(cat "$HIVE_DEPS_OVERLAY")" -P -M:mcp; \
    else \
      clojure -P -M:mcp; \
    fi

# ---- Layer 2: source (changes often) ----
COPY --chown=hive:hive src/ src/
COPY --chown=hive:hive resources/ resources/
COPY --chown=hive:hive bin/docker-entrypoint.sh bin/docker-entrypoint.sh

# 7910 nREPL, 9999 WebSocket channel, 7911 Olympus, 7912 A2A gateway
EXPOSE 7910 9999 7911 7912

ENV HIVE_HEAP=2g
ENV HIVE_PROFILE=k8s-headless

# nREPL is the first server up and the last down; a TCP probe on it is
# the readiness signal. bash /dev/tcp needs no extra package.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c "echo > /dev/tcp/localhost/7910" || exit 1

# The JVM shutdown hook handles Olympus stop, coordinator marking and
# session auto-wrap on SIGTERM.
STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/bin/docker-entrypoint.sh"]
