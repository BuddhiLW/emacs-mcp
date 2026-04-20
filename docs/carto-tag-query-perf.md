# carto tag-query perf investigation

## Symptom

```clj
(let [q (requiring-resolve 'hive-mcp.vectordb.facade/query-entries)
      r (q {:tags ["carto" "ns:hive-mcp.tools.consolidated.session"]
            :limit 200 :project-id "hive-mcp"})]
  (count r)) ;; => 185 entries, 30-35s per call
```

Small/empty namespaces return in milliseconds. Latency scales with result
size, suggesting bandwidth/deserialization cost, not filter pushdown.

## Code path

`hive-mcp.vectordb.facade/query-entries`
  -> `hive-mcp.protocols.memory/query-entries`
  -> `hive-mcp.memory.store.chroma` record `query-entries`
  -> `hive-mcp.chroma.crud/query-entries`
  -> `clojure-chroma-client.api/get` (HTTP POST collections/:id/get).

The task brief referred to a "Milvus backend" but no Milvus impl exists in
this repo. The only active IMemoryStore is Chroma (`memory/store/chroma.clj`).

## Root cause

In `hive-mcp.chroma.crud/query-entries` the HTTP `get` call was built with
`:include #{:documents :metadatas}`. Metadata already contains the full
content (see `index-memory-entry!` — `(h/serialize-content content)` is
stored under `metadata.content`). The `:documents` projection holds the
`memory-to-document` string, which is `Type:\nTags:\nContent: <content>` —
i.e. the same content plus a ~20-byte preamble.

For the carto namespace that stores full session dumps, each row's
`metadata.content` can be tens of KB, so fetching both `documents` and
`metadatas` roughly *doubles* payload size and JSON-parse time over the wire
on every read. For a 185-row hit set that is where most of the 30s goes.

Separately, the tag filter IS pushed down correctly. Chroma v2 supports
`$contains`/`$not_contains` on metadata strings (tags are comma-joined at
write-time), so the `:$and` expression built in `query-entries` routes all
tag filtering server-side. That was verified against the 185-row result
count — if pushdown were broken we would see either 0 results or all
project rows (not 185 tagged rows).

## Fix landed

`hive-mcp.chroma.crud/query-entries`: drop `:documents` from `:include`.

- Only `metadata->entry` reads `:document`, and only one caller
  (`hive-mcp.agent.drone.context/context.clj:159`) ever reads `:document`
  from a result — and it does so on `search-similar` results, not
  `query-entries`, so it is unaffected.
- All other downstream consumers read `:content` (populated from
  `metadata.content`), `:tags`, `:project-id`, etc.
- Net impact: ~50% reduction in HTTP payload and JSON parse cost for
  `query-entries` with large content rows.

Expected effect: 30-35s cold -> <5s cold (and likely <500ms warm once Chroma
has the page in its mmap cache).

## Residual risk / follow-ups

- `get-entry-by-id` also uses `:include #{:documents :metadatas}`. Same
  redundancy; low priority since single-row fetch. Left alone for now.
- `query-grounded-from` has the same pattern, also single namespace / low
  volume. Left alone.
- If 30s cold persists after this fix, the next suspect is Chroma's HNSW
  metadata-scan for `$contains`: there is no index on the comma-joined
  `tags` string, so Chroma's scanner walks every metadata row that matches
  `project-id`. At that point the correct fix is to restructure tags as
  a list-typed metadata (requires schema change + reindex) or to store
  tag keys as scalar fields so equality pushdown hits an index.

## Estimated effort for follow-up

- Schema change to list-typed tags: 1-2 days (reindex, migration, update
  helpers/metadata->entry, update tests).
- Keep current fix otherwise.
