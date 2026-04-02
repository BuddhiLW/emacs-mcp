FROM eclipse-temurin:21-jdk

LABEL org.opencontainers.image.title="clojure-lsp-sidecar"
LABEL org.opencontainers.image.description="Periodic clojure-lsp analysis dumper for hive-mcp"
LABEL org.opencontainers.image.source="https://github.com/hive-agi/hive-mcp"

# Install Clojure CLI
ARG CLOJURE_VERSION=1.12.4.1618
RUN apt-get update && apt-get install -y curl make git rlwrap \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLO https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh \
    && chmod +x linux-install-$CLOJURE_VERSION.sh \
    && ./linux-install-$CLOJURE_VERSION.sh \
    && rm linux-install-$CLOJURE_VERSION.sh \
    && clojure -e "(clojure-version)"

# Install clojure-lsp standalone JAR
ARG CLOJURE_LSP_VERSION=2025.11.28-12.47.43
RUN curl -L "https://github.com/clojure-lsp/clojure-lsp/releases/download/${CLOJURE_LSP_VERSION}/clojure-lsp-standalone.jar" \
      -o /opt/clojure-lsp.jar

# Install babashka (for future bb-based extensions)
ARG BB_VERSION=1.12.196
RUN curl -sL "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
      | tar -xzf - -C /usr/local/bin bb

COPY analyze.sh /usr/local/bin/analyze.sh
RUN chmod +x /usr/local/bin/analyze.sh

ENV ANALYSIS_INTERVAL_SECONDS=300
ENV JAVA_OPTS="-Xmx1g -Xms256m"
ENV LSP_PROJECTS=""

VOLUME ["/cache"]
VOLUME ["/workspace"]

ENTRYPOINT ["/usr/local/bin/analyze.sh"]
