(ns hive-mcp.tools.memory.crud
  "CRUD facade for memory operations, re-exporting handlers from focused submodules."
  (:require [hive-mcp.tools.memory.crud.write :as write]
            [hive-mcp.tools.memory.crud.query :as query]
            [hive-mcp.tools.memory.crud.retrieve :as retrieve]
            [hive-mcp.tools.memory.crud.edit :as edit]
            [hive-mcp.tools.memory.crud.reembed :as reembed]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def handle-add
  "Add an entry to project memory with optional KG edge creation."
  write/handle-add)

(def handle-query
  "Query project memory by type with scope filtering."
  query/handle-query)

(def handle-query-metadata
  "DEPRECATED: Use handle-query with verbosity='metadata'."
  query/handle-query-metadata)

(def apply-auto-scope-filter
  "Filter entries for auto-scope mode (nil scope)."
  query/apply-auto-scope-filter)

(def handle-get-full
  "Get full content of a memory entry by ID with KG edges."
  retrieve/handle-get-full)

(def handle-batch-get
  "Get multiple memory entries by IDs in a single call."
  retrieve/handle-batch-get)

(def handle-check-duplicate
  "Check if content already exists in memory."
  retrieve/handle-check-duplicate)

(def handle-update-tags
  "Update tags on an existing memory entry."
  retrieve/handle-update-tags)

(def handle-edit
  "Edit a memory entry in place. Preserves ID → preserves KG edges."
  edit/handle-edit)

(def handle-batch-edit
  "Apply a batch of edits sequentially. Per-op results + summary tally."
  edit/handle-batch-edit)

(def handle-reembed
  "Re-embed a memory entry by id without rewriting content. Preserves id → preserves KG edges."
  reembed/handle-reembed)

(def handle-batch-reembed
  "Re-embed a batch of entries by ids. Sequential per-op; summary + per-op results."
  reembed/handle-batch-reembed)