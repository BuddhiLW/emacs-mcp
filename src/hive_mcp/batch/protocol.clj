(ns hive-mcp.batch.protocol
  "Batchable protocol — the LSP-conforming contract any consolidated tool
   must satisfy to opt into cross-tool batch / DSL / collect semantics.

   T13 Phase 2 lands the protocol + reference implementation. Phase 3 will
   opt individual tools into `Batchable` one-by-one without touching this
   namespace.

   ─── Liskov Substitution contract ──────────────────────────────────────
   For any `impl` satisfying `Batchable`:

     1. `(batch-execute impl ops opts)` must return a map with keys
        `{:success :waves :summary}` (and optionally `:errors`).
        - `:success`  — boolean
        - `:waves`    — sorted-map of wave-num → {:ops [...] :results [...]}
        - `:summary`  — {:total N :success S :failed F :waves W}
        - `:errors`   — optional vec of validation error strings

     2. `batch-execute` MUST NEVER throw. All per-op exceptions must be
        isolated into `{:success false :error <msg>}` result entries.
        Validation errors must surface as `:errors` with `:success false`.

     3. `opts` accepts (at minimum) the same keys across implementors:
        - `:dry-run?` — boolean, validate-and-plan only
        - `:resolve-handler` — fn [tool-name] → handler-fn | nil
        - `:emit-fx` — fn [fx-id fx-data] → nil (observability)

     4. `(batch-schema impl)` returns a JSONSchema `:properties` map for
        the tool's batch-input surface (at minimum `:operations` +
        `:dry_run`). Return shape is identical across implementors so
        consolidated/multi can present a uniform manifest.

   ─── Interface Segregation ─────────────────────────────────────────────
   Two optional refinements live alongside `Batchable`:

   - `DAGBatchable`: adds `:depends_on` + multi-wave execution.
   - `StreamingBatchable`: adds per-op streaming via an `on-event` callback.

   Impls may satisfy just `Batchable` (sequential, no deps) or layer the
   extensions without breaking substitutability."
  (:refer-clojure :exclude [satisfies?]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol Batchable
  "Core batch contract — MUST be satisfied by every consolidated tool
   that opts into cross-tool multi semantics."
  (batch-execute [this ops opts]
    "Execute a vector of `ops` with the given `opts` map.

     Contract:
       - Returns `{:success bool :waves map :summary map :errors?}`.
       - NEVER throws. Per-op failure is returned inline.
       - `opts` shape: `{:dry-run? bool :resolve-handler fn :emit-fx fn}`.")
  (batch-schema [this]
    "Return a JSONSchema `:properties` map for this impl's batch input
     surface. Minimum keys: `:operations`, `:dry_run`."))

(defprotocol DAGBatchable
  "Extension — impls that honour `:depends_on` edges and produce multi-
   wave execution with topological scheduling. Strictly refines the
   `Batchable` contract; must also satisfy `Batchable`."
  (batch-with-deps [this ops opts]
    "Execute ops respecting `:depends_on` edges. Same return shape as
     `batch-execute`. Implementations must assign waves such that a
     dependent op runs in a strictly later wave than any of its deps."))

(defprotocol StreamingBatchable
  "Extension — impls that stream per-op events as execution proceeds.
   Callers receive `[event-kw event-data]` via `on-event` for
   incremental observability. Impls must also satisfy `Batchable`."
  (batch-stream [this ops opts on-event]
    "Execute `ops` and fire `(on-event event-kw event-data)` for each
     notable transition (`:op/start`, `:op/done`, `:wave/complete`).
     Returns the same shape as `batch-execute` on completion."))
