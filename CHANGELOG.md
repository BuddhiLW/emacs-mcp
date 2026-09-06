# Changelog

Notable changes to hive-mcp. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file starts at 1.0.0, which absorbs the work that accumulated on
`staging/v0.22.0`. Earlier history is in the git log and the release tags.

## What the version number promises

From 1.0.0 the public seam does not move without a major bump. The seam is
three things:

1. **The MCP tool surface**: the tool root names, their `command` vocabulary
   and the shape of a tool's arguments. Adding a root, a command or an optional
   argument is minor. Removing or renaming one, or making an optional argument
   required, is major.
2. **The addon manifest format** and the `IAddon` contract in
   `io.github.hive-agi/hive-addon`, which reached 1.0.0 alongside this release.
   An addon that mounts against hive-mcp 1.x keeps mounting across 1.x.
3. **The ports in `io.github.hive-agi/hive-spi`**, also 1.0.0. A host cannot
   promise stability over ports that publish no promise of their own, which is
   why those two went first.

What is deliberately NOT promised: anything under an implementation namespace
that the tool surface does not expose, the wire format of internal events, and
the on-disk layout of the stores.

One thing the artifact does promise that is easy to miss: hive-mcp 1.x ships
the datalog backends (datahike, datalevin, datascript) as dependencies. Work is
under way to move them behind ports into sibling libraries
([DEVINCULATE-DATALOG]); when they leave the default tree, that is a major
bump, not a quiet minor, because a consumer's storage would change under it.

## [Unreleased]

### Changed

- The repository root is down to what a checkout needs. The presets moved from
  `presets/` to `resources/presets/`, so they ship in the jar and the image; the
  file fallback (`presets.dir`, `HIVE_MCP_PRESETS_DIR`) defaults to that path.
  The shared test-support namespaces (`hive-mcp.knowledge-graph.store.fixtures`,
  `.harness`, `hive-mcp.recall.golden`) moved from `testing/` to `src/` under the
  same names, so consumers such as hive-knowledge see no change. The observability
  stack configs moved from `config/` to `docker/observability/`, and the test
  sandbox template to `dev/`.
- One test tree. The backend-coupled suites moved from `test-backends/` into
  `test/` under the namespace prefix `hive-mcp.backends`; the default runners
  exclude that prefix by namespace regex and the `:test-backends` alias selects
  exactly it, so `clj -Sdeps "$(cat local.deps.edn)" -M:test:test-backends`
  still runs them and nothing else.

### Removed

- `python/` (the hive-tools Claude Agent SDK wrappers, never published and
  referenced by nothing), `modules/clj-kondo-mcp` (a stale copy of the
  clj-kondo-mcp repository), `recipes/` and the MELPA check (the elisp lives
  in hive-emacs, which validates it), `seeds/` (its ten conventions now live in
  hive memory), `k8s/ollama-embed` (a nomic-embed-text deployment nothing
  referenced), and the root-level `test-int/`, `PLAN.md`, `leak.jfc` and
  `fp-arg-order-convention.md`.

## [1.1.1]

A patch release. The tool surface, the manifest format and the ports are
where 1.1.0 left them; one existing optional argument gained a second reader,
and two defaults that were quietly costing a core or a model load are gone.

### Fixed

- A `git` or `magit` batch-commit operation whose `files` was a
  space-separated string staged nothing and then committed whatever the index
  already held under that operation's message. `files` now normalizes to
  `:all` or a vector of paths (a list, a single path, or a whitespace-separated
  string), and a commit that names paths stages them and verifies the
  restricted index first: a path absent from the working tree, an empty
  `git diff --cached --name-only` for those paths, or any unreadable verdict
  fails the operation instead of falling through to the commit. The `files`
  schema property is spliced from one definition in `tools.core` into both
  tool-defs, so `git` and `magit` cannot disagree about whether a list is
  accepted.
- The lsp-sidecar image and `analyze.sh` run git non-interactively
  (`GIT_TERMINAL_PROMPT=0`, SSH in batch mode with a bounded connect timeout),
  so an uncached or private `:git/url` dependency met during `clojure -Spath`
  fails fast instead of blocking the sidecar on a credential, host-key or
  passphrase prompt.

### Changed

- The `remote` argument, which `fetch` already honoured, now also names the
  remote `push` pushes the current branch to. Absent or blank it contributes
  nothing, and the elisp emitted for a push is byte-identical to before.
- Datalevin-backed slots are opened with `{:background-sampling? false}`. The
  sampler picked a random attribute every 10 s and rescanned it whenever a
  carto scan moved its count by 5%, pinning a core for the length of the
  scan; the query planner samples lazily on first use, so queries are
  unaffected.
- Every legacy Ollama embedding default (config merge, server init,
  `ollama-config`) named `nomic-embed-text`, which configured the memory
  collection with a 768-dimension model at boot and loaded nomic into Ollama
  next to the `qwen3-embedding:4b` lane that actually serves memory. The
  defaults now name `qwen3-embedding:4b`, and the Ollama model table carries
  its 2560 dimensions. A deployment that relied on the old implicit default
  keeps it by setting `embeddings.ollama.model` explicitly.
- The presets gain the v2 axes (carto-first, commit-hygiene, cppb-stratified,
  ddd-ports, gitops-safety, malli-first, memory-crystallize, ocp-data,
  repl-first, subtask-worker, trifecta), each stating one discipline, with
  `ROLES-v2.md` naming the bundles.

## [1.1.0]

The first release after the seam froze. Nothing here moves the tool surface,
the manifest format or the ports; three of the changes are hosts behaving
correctly where a stub or an addon had been standing in for them.

### Fixed

- `multi` on an unextended host now sorts waves and reports cycles instead of
  flattening every op into wave 1. Without the batch addon, `assign-waves`
  fell back to one flat wave, so a dependency chain succeeded at its root and
  errored every dependent op with "dependencies failed", and `detect-cycles`
  fell back to `[]`, so a real cycle validated. Both fall back to
  `hive.events.multi` (Kahn's algorithm, a cycle-reporting validator), which
  the plan namespace already required. A registered extension still wins.
  This survived a green suite because every multi test installed a stub that
  registered the correct implementation; the new tests force every `:bx/*`
  lookup to miss, which is the shape production has.
- On that same unextended host, a `$ref:` the host cannot parse (no `:bx/a`
  extension) is classified as a broken ref and the op is skipped, instead of
  the literal `"$ref:..."` string reaching the handler as a value. The flat
  wave had hidden this: every dependent op failed on its dependencies before
  the classifier could be reached.
- `memory migrate-scoped` puts the tag into the store query instead of
  enumerating a whole project and filtering in Clojure, and refuses a
  migration that resolves zero targets, naming the tag, the project and the
  query, where it used to report `{:migrated 0}` as success.
- The addon loader registers an init answer's `:metadata :extensions` only
  when it is a `{keyword fn}` map, and mirrors that on teardown. An addon
  that reported something else there (a language tier's set of file
  extensions) tripped the registry assert after its own side effects had
  landed, and the teardown then walked the value as map entries; the same
  assert was aborting the tool-registry refresh after a hot reload, leaving
  MCP verbs on pre-reload handlers. A non-map is now logged and skipped.

### Changed

- `io.github.hive-agi/hive-hot` resolves from Clojars (0.1.7) instead of a
  `:git/tag` coordinate. Same version; the last hive-agi library in `:deps`
  that was not an `:mvn/version`.
- The starter notes state what `hive-tmux` needs before it can mount (tmux,
  Python 3, libtmux on the interpreter libpython-clj binds) and why its pin is
  0.1.1.

## [1.0.0]

1.0.0 is not a feature release. It is the release where the seam stops moving,
and the work below is what had to be true first: the committed tree boots and
tests on a clean checkout, the shipped container binds every port it announces,
the host no longer calls its own deprecated vars, and the two libraries the
contract rests on (hive-spi, hive-addon) publish 1.0.0 promises of their own.

Two large refactors stay open on purpose, because neither changes the seam:
[MQ-ADOPT] (internal addon-loader cutover) and [DEVINCULATE-DATALOG] (moving
the datalog drivers behind ports). Shipping 1.0 says the contract is stable,
not that the roadmap is empty.

### Changed

- `io.github.hive-agi/hive-spi` 0.2.1 to **1.0.0** and
  `io.github.hive-agi/hive-addon` 0.3.12 to **1.0.0**. Both gained a CHANGELOG
  and a versioning statement saying what a major, minor and patch mean for an
  implementor; neither changed a contract to get there. This pin is the
  substance of hive-mcp's own promise, not a routine bump: a host cannot
  promise stability over ports that publish none.
- `clj-kondo` over `src`: 129 warnings to 67, 0 errors. Twenty-six files
  carried a require or a refer nothing used. `tools/registry.clj` is the
  opposite case and now states it: its twelve legacy consolidated requires are
  loaded for their registration side effect, excluded by name in an ns-level
  linter config rather than by switching the linter off, so a genuinely dead
  require there still reports.

### Removed

Deprecations the host itself still called, resolved per site rather than left
standing. A deprecation a host keeps calling never lands: callers feel no
pressure to move and the var cannot be removed without breaking the host.

- `hive-mcp.crystal.hooks/harvest-all`, `/harvest-session-progress`,
  `/harvest-completed-tasks`, `/harvest-git-commits` and `/crystallize-session`.
  These were backward-compat delegates from the harvest decomposition. Callers
  use `hive-mcp.crystal.harvest.collect/*` and
  `hive-mcp.crystal.synthesis/synthesize` directly, which is what the
  deprecation notices said to do. `crystal.hooks` now holds only event handlers
  and hook registration, as its own docstring claimed.
- `hive-mcp.knowledge-graph.connection.writer/drain-writer!` and its re-export
  on `connection`. It was a one-line alias for `flush-pending!`; the six call
  sites now call `flush-pending!`.
- `hive-mcp.agent.hive-agent-bridge` and
  `hive-mcp.agent.drone.backend.hive-agent`, deprecated since 0.16.0. The
  bridge dispatches through the `:ag/run` extension key, which no addon in the
  ecosystem registers any more (hive-agent contributes `:ag/context`,
  `:ag/tools`, `:ag/loop-factory`, `:ag/loop-backend`, `:ag/llm-router`), so
  the backend could only ever answer "hive-agent is not available on
  classpath". Nothing in `src` required either namespace: `ext-router` lists
  only `:sdk-drone` and `:agentic-loop`. Selecting `:hive-agent` now falls to
  the `resolve-backend` default, which names the registered backends.

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

[Unreleased]: https://github.com/hive-agi/hive-mcp/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/hive-agi/hive-mcp/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/hive-agi/hive-mcp/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/hive-agi/hive-mcp/compare/v0.22.0...v1.0.0
[0.22.0]: https://github.com/hive-agi/hive-mcp/compare/v0.21.1...v0.22.0
