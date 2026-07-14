# Retrieval experiments

The tracked `*.example.edn` fixture is synthetic and safe to share. Keep real
memory exports, IDs, text, tags, and query cases out of tracked files.

Private-by-default locations:

- `dev/experiments/private/`
- `*.private.edn`
- `*.local.edn`
- any non-`*.example.edn` file under an experiment `fixtures/` directory

Run a local ignored case:

```sh
clojure -X:embedding-retrieval \
  :fixture '"dev/experiments/private/my-case.private.edn"'
```

Before using a real case, confirm Git ignores it:

```sh
git check-ignore -v dev/experiments/private/my-case.private.edn
```

Do not use `git add -f` on private experiment inputs.
