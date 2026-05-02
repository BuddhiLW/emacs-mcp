(ns hive-mcp.tools.migrate.kanban.ports
  "Protocols (DDD ports) for the kanban migrator. Each protocol fronts a
   single concern so adapters can swap independently — milvus today,
   chroma or proximum tomorrow.

   Every method returns a Result (`hive-dsl.result`) so the use-case layer
   composes via `r/let-ok` without try/catch ladders. Adapter
   implementations are expected to wrap their backend exceptions and
   surface a typed `:error` keyword.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IIdLister
  "Materialize the full id set we want to consider for migration."
  (list-ids [this]
    "Return r/ok of a sorted distinct vector of ids carrying the migration's
     filter (e.g. tag = \"kanban\")."))

(defprotocol IEntryReader
  "Read entries by id. Reads are batched at the boundary so adapters
   can amortize per-collection round-trips."
  (read-by-ids [this ids]
    "Return r/ok of {id => entry} for each id present in the backing store.
     Missing ids must be omitted from the map (not nil-valued).
     Entries returned use the canonical hive-mcp memory shape with
     `:content` already parsed back to a map when applicable."))

(defprotocol IEntryWriter
  "Persist entries to the migration target. Implementations are
   responsible for embedding/serialization decisions; the use-case
   layer hands them canonical entries."
  (write-entries [this entries]
    "Return r/ok of [{:id _, :ok? bool, :error? str-or-nil}] in input order."))

(defprotocol IState
  "Migration progress state. Implementations may persist anywhere
   (file, kg, datalevin) — only the contract matters."
  (load-state [this]
    "Return r/ok of the current state map, or `r/ok initial` if none.")
  (save-state! [this state]
    "Return r/ok with the saved state.")
  (reset-state! [this]
    "Return r/ok of the post-reset state. Destructive but bounded to
     this migrator's keyspace."))
