# =============================================================================
# Stage 1: pull a canonical Node.js + npm install.
# Using the official node image keeps node/npm/npx versions consistent and
# avoids distro packaging splits (Ubuntu 24.04 ships nodejs without npm).
# Node 22 LTS — pnpm v11 requires `node:sqlite` builtin (added in v22).
# =============================================================================
FROM node:22-bookworm-slim AS node-src

# =============================================================================
# Stage 2: final sidecar runtime.
# =============================================================================
FROM eclipse-temurin:21-jdk

LABEL org.opencontainers.image.title="clojure-lsp-sidecar"
LABEL org.opencontainers.image.description="Periodic clojure-lsp analysis dumper for hive-mcp"
LABEL org.opencontainers.image.source="https://github.com/hive-agi/hive-mcp"

# Install Clojure CLI + git/ssh prerequisites
ARG CLOJURE_VERSION=1.12.4.1618
RUN apt-get update && apt-get install -y curl make git rlwrap openssh-client ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLO https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh \
    && chmod +x linux-install-$CLOJURE_VERSION.sh \
    && ./linux-install-$CLOJURE_VERSION.sh \
    && rm linux-install-$CLOJURE_VERSION.sh \
    && clojure -e "(clojure-version)"

# Bring node + npm + npx from the official node image (multi-stage copy).
# The official image installs everything under /usr/local — node binary,
# npm script, and the npm package tree under /usr/local/lib/node_modules.
# Copying these three locations gives a fully functional npm/npx without
# pulling in distro alternatives or build toolchains.
COPY --from=node-src /usr/local/bin/node /usr/local/bin/node
COPY --from=node-src /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && ln -s /usr/local/lib/node_modules/corepack/dist/corepack.js /usr/local/bin/corepack \
    && node --version && npm --version && npx --version

# Activate pnpm via corepack (ships with Node 20+). Follows pnpm.io/docker pattern:
# PNPM_HOME on PATH so future `pnpm setup`/`pnpm add -g` lands in a stable dir.
#
# Pinned to a v11+ release because the supply-chain defenses below
# (minimumReleaseAge, blockExoticSubdeps, trustPolicy) only land in v11.
# See pnpm.io/supply-chain-security.
#
# Corepack pins the version into the image so future container restarts get
# the exact same binary (no implicit upgrades on `corepack enable`).
ARG PNPM_VERSION=11.0.0
ENV PNPM_HOME="/pnpm"
ENV PATH="$PNPM_HOME/bin:$PNPM_HOME:$PATH"
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN mkdir -p "$PNPM_HOME/bin" \
    && corepack enable \
    && corepack prepare pnpm@${PNPM_VERSION} --activate \
    && pnpm --version \
    # Supply-chain default (per pnpm.io/supply-chain-security):
    #   minimumReleaseAge=10080 — quarantine packages <7 days old (defends
    #     against rapidly-published-then-yanked malicious versions).
    # This is the ONE setting pnpm v11 accepts at the global scope.
    # `blockExoticSubdeps` and `trustPolicy=no-downgrade` are also recommended
    # but must live in each project's `pnpm-workspace.yaml` — they can't be
    # globally enforced from the sidecar image. Set them per-project if you
    # care about downgrade attacks and git/tarball transitives.
    # `ignore-scripts` left at default (v11 ignores postinstall unless
    # explicitly listed in each project's `allowBuilds` — preferred).
    && pnpm config set minimumReleaseAge 10080 --global

# Install Leiningen (for project.clj-based projects like pdfboxing)
ARG LEIN_VERSION=2.11.2
RUN curl -fsSL "https://raw.githubusercontent.com/technomancy/leiningen/${LEIN_VERSION}/bin/lein" \
       -o /usr/local/bin/lein \
    && chmod +x /usr/local/bin/lein \
    && LEIN_ROOT=1 lein version

# Trust common git hosts so SSH-backed clojure :git/url deps don't fail on host-key prompt
RUN mkdir -p /etc/ssh && ssh-keyscan -H github.com gitlab.com bitbucket.org >> /etc/ssh/ssh_known_hosts 2>/dev/null

# Install clojure-lsp standalone JAR
ARG CLOJURE_LSP_VERSION=2025.11.28-12.47.43
RUN curl -L "https://github.com/clojure-lsp/clojure-lsp/releases/download/${CLOJURE_LSP_VERSION}/clojure-lsp-standalone.jar" \
      -o /opt/clojure-lsp.jar

# Install babashka (for future bb-based extensions)
ARG BB_VERSION=1.12.196
RUN curl -sL "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
      | tar -xzf - -C /usr/local/bin bb

COPY analyze.sh /usr/local/bin/analyze.sh
COPY extract.bb /usr/local/bin/extract.bb
RUN chmod +x /usr/local/bin/analyze.sh /usr/local/bin/extract.bb

ENV ANALYSIS_INTERVAL_SECONDS=300
ENV JAVA_OPTS="-Xmx1g -Xms256m"
ENV LSP_PROJECTS=""

VOLUME ["/cache"]
VOLUME ["/workspace"]

ENTRYPOINT ["/usr/local/bin/analyze.sh"]
