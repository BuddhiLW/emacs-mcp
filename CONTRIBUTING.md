# Contributing to hive-mcp

## Getting a working checkout

```bash
git clone https://github.com/hive-agi/hive-mcp.git && cd hive-mcp
clojure -P -M:test-unit          # pre-fetch dependencies
clojure -M:test-unit             # the suite CI gates on
```

`bin/hive-mcp-foss` boots the host with the FOSS starter pack merged
(see [`starter.deps.edn`](starter.deps.edn)). `HIVE_STARTER=0` boots the bare
core instead.

Personal or unpublished dependency overrides belong in an untracked
`local.deps.edn`, never in `deps.edn`. The committed `deps.edn` must resolve
from a fresh clone with nothing else on the machine.

### Running one-off scripts

Use `clojure -M -e '(load-file "dev/your_script.clj")'`.

Do **not** use `-M:dev` for a one-shot. Clojure loads `user.clj` automatically
and `dev/user.clj` auto-starts the Integrant system, which binds ports a
running server already holds.

## Checks before you open a PR

```bash
clj-kondo --lint src test --config-dir .clj-kondo --fail-level error
clojure -M:test-unit
bb dev/foss_compliance.clj hive-mcp        # packaging, versions, licence, deps
```

## Writing an addon

hive-mcp gains capabilities by mounting addons (`IAddon`). An addon is an
ordinary library that any host can load. Four rules make that true, and each
one is checked by `dev/foss_compliance.clj`:

1. **Depend on the contract, never on the host.** Implement
   `hive-addon.protocol/IAddon` from `io.github.hive-agi/hive-addon`. Never
   `:require` a `hive-mcp.*` namespace and never name one in a `reify`.

   `reify` resolves its protocol symbol at compile time, so reifying a host
   namespace makes your namespace unloadable unless the host happens to have
   loaded it already. A guard like `(when (try-resolve 'hive-mcp.../IAddon) ...)`
   does not help: the compile has already failed by the time it could run.

   Reaching a host service at runtime is fine, and is the intended seam:
   quote the symbol and hand it to `requiring-resolve`, then degrade with a
   logged reason when it does not resolve.

2. **Ship the manifest.** The host discovers addons only through
   `META-INF/hive-addons/<name>.edn` on the classpath. Put it under
   `resources/`, and list every real root in `version.edn`:

   ```clojure
   :src-dirs ["src" "resources"]
   ```

   `hive-build` compiles source roots and copies resource roots verbatim. A
   root you do not declare is not in the jar, and an addon whose manifest is
   not in the jar is invisible to every consumer, silently.

3. **The constructor returns an IAddon.** `:addon/init-fn` must be a
   `(config -> IAddon)` with no side effects: no registration, no
   `initialize!` call. The host drives the lifecycle.

4. **A leaf addon mounts into its host addon, not into hive-mcp.** If your
   addon extends another addon's capability, declare it there. hive-mcp never
   names a leaf.

Prove it the way CI does, with the addon on the classpath and hive-mcp absent:

```bash
clojure -M -e '(load-file "dev/addon_boot_probe.clj")(addon-boot-probe/-main)'
```

Every discovered manifest should report `active`.

## Commits

Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `build:`, `chore:`).
The subject says what changed; the body says why it was wrong before.

Stage explicit paths. Never `git add -A`: other sessions may hold unrelated
work in the same checkout.

Do not add AI attribution trailers.

## Releases

`VERSION`, the newest git tag and the latest Clojars release must agree.
`release.yml` bumps the patch, tags, and deploys on a push to `main` that
touches the library; it must run the suite before it deploys.
