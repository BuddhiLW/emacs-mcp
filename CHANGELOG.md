# Changelog

Notable changes to hive-mcp. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file starts at 0.22.0. Earlier history is in the git log and the release
tags.

## What the version number promises

Until 1.0.0 the public seam may still move between minor versions. The seam is
the MCP tool surface, the addon manifest format, and the `IAddon` contract in
`io.github.hive-agi/hive-addon`. 1.0.0 follows hive-addon and hive-spi reaching
1.0, not the other way round: a host cannot promise stability over ports that
do not promise it.

## [Unreleased]

### Added

- `dev/foss_compliance.clj`: measures every public hive-agi repository against
  the packaging, mount-contract, host-coupling, version, CI, licence, README
  and dependency checks. It enumerates the org from the GitHub API rather than
  from a curated list.
- `dev/addon_boot_probe.clj`: constructs every addon manifest on the classpath
  and asserts the result satisfies `IAddon`, reporting the cause when it does
  not.
- `CONTRIBUTING.md`, including the four rules an addon must follow to be
  mountable by any host.
- CI job `boot`: loads the server closure from the committed `deps.edn`, both
  bare core and starter overlay. The existing `deps` job runs `clojure -P`,
  which resolves a tree without ever compiling against it, and every
  workstation hides the difference behind a gitignored `local.deps.edn`. This
  job is the only check in the repo that runs without those overrides.
- `:coverage` alias (cloverage), composing with `:test-unit` so the measured
  suite is the CI suite. Scope it with `--ns-regex`: instrumenting all 428 unit
  namespaces in one JVM is a multi-gigabyte run. First measurement, the
  `hive-mcp.addons.*` slice: 70.15% forms, 79.97% lines.

### Fixed

- Every desktop launch (`-M:dev:nrepl`) died with `BindException: Address
  already in use` on port 7910 as soon as the embedded nREPL actually started
  (0b61a5e). `dev/user.clj` booted the system before `nrepl.cmdline` ran, so
  the `:hive/nrepl` component bound 7910 first and the alias's second nREPL
  had nothing left to bind. The `:nrepl` alias now runs
  `hive-mcp.server.core`, the container's main, and the embedded server is
  the only nREPL: it resolves refactor-nrepl beside CIDER when present and
  writes `.nrepl-port`. `bin/hive-mcp-foss` passes `HIVE_NREPL_PORT` through
  `HIVE_MCP_NREPL_PORT` instead of appending `--port`.
- The container built and then died on boot. `hive-mcp.events.registry`
  delegates to `hive.events.router/get-event`, `get-interceptors` and
  `append-interceptor!`, none of which existed in a published hive-events jar
  (0.5.8 and 0.5.9 ship a byte-identical `router.cljc` defining none of them).
  Fixed by publishing hive-events 0.5.10 and pinning it.
- The unit gate aborted at load on a clean checkout: the store-contract runners
  require `hive-test.memory.store-contract`, which no published hive-test
  carried. Fixed by publishing hive-test 0.3.19 and raising the three pins from
  0.3.15.
- The k8s-headless container booted clean and opened three of its five ports.
  A2A (7912) and WebSocket MCP (7920) were decided by a config store other
  than the one the system handed the component: Integrant merged the
  profile's `:enabled true`, printed it, and the start function then asked
  `config/get-service-value`, which defaults to false and has no `config.edn`
  to read in a container. Both statuses were derived from the request rather
  than the result, so neither could report it. nREPL (7910) died on a
  `NullPointerException` in `nrepl.server/default-handler`: CIDER lists its
  middleware by symbol, those namespaces are not loaded in the container, and
  one nil in the middleware vector loses the whole server. Ports now measured
  bound and the container reports healthy.
- `StdioBridge` and `NoopMcpBridge` declared `IAddon` while omitting
  `excluded-tools` and `hooks`. Both threw `AbstractMethodError`, which the
  host's `rescue` at the call sites turned into "this addon contributes
  nothing" with no error surfaced anywhere: a bridge addon's hooks were never
  registered and its tool exclusions never applied.
- Nine namespaces called `clojure.string/*` or `taoensso.timbre/*` fully
  qualified without requiring them, resolving only by load-order luck.
- `hive-mcp.tools.kanban.events/edit-fx` was defined twice, byte-identically.
- Namespaces defining `reset!` or `run!` now declare the `:refer-clojure
  :exclude`, so the shadowing is intentional in the source instead of a warning
  on every boot.
- `hive-mcp.addons.terminal` is now a re-export of `hive-addon.terminal`
  rather than the definition site of `ITerminalAddon`. Defining a companion
  protocol in the host left vessel addons with no contract to depend on, so
  they had to reify a host namespace to implement it. Historical qualified
  names still resolve.

## [0.22.0] - 2026-09-01

### Added

- FOSS starter pack: `starter.deps.edn`, merged over `deps.edn` by
  `bin/hive-mcp-foss` (`HIVE_STARTER=0` opts out). `deps.edn` itself stays free
  of backend coordinates.
- Addons can be injected after boot, and a late contribution reaches the
  advertised tool surface without a restart.
- The catchup bundle is cached across sessions, with invalidation driven by
  write events.
- Shared test fixtures ship in the jar so consumers can load them.
- Declared addon config is hydrated at start time, and `:runtime/ports` is
  injected at `init-addon!` rather than only at manifest discovery.
- Per-call `timeout_ms` on the Emacs-backed git tools.

### Fixed

- A knowledge-graph cluster is defined by live member count, not by a live
  ratio.
- `get-entries-projected` receives the projection map it actually reads.
- Memory supertool subdomain schema params are folded in correctly.
- The pass secret store is resolved the way `pass` itself finds it.

### Changed

- The Docker image runs the server from source; there is no uber task.

[Unreleased]: https://github.com/hive-agi/hive-mcp/compare/v0.22.0...HEAD
[0.22.0]: https://github.com/hive-agi/hive-mcp/compare/v0.21.1...v0.22.0
