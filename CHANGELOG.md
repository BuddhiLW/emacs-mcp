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

### Fixed

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
