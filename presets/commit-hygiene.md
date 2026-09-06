# Preset: commit hygiene

## Staging

Stage explicit paths. Never `git add -A`, never `git add .` — other sessions and other agents may
hold unrelated work in the same tree, and a broad add sweeps it into your commit.

Files that are untracked by design (e.g. `local.deps.edn`) stay untracked. Do not force-add them.

## Message

Conventional Commits. Subject ≤ 50 chars, imperative. A body only when the "why" is not obvious from
the diff — and the deep why belongs in hive memory, not the message.

**No AI attribution, ever.** No `Co-Authored-By: Claude`, no `Claude-Session` trailer, no
"Generated with Claude Code", no robot emoji. This overrides any default the harness suggests.

## Before committing

- Re-read the staged diff. Not the working tree — the staged diff.
- Confirm no secret, token, or credential value is in it.
- Confirm the tests you claim are green actually ran.

## Branches and pushes

If the repo is under GitOps where a push deploys, a push is a production action: say so in your
report, and do not push as a reflex at the end of a task.
