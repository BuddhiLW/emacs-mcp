(ns hive-mcp.multi.types
  "ADT closed sums for the multi tool's extension contract.

   Designed-for-substitution types replacing ad-hoc map shapes that today
   silently drift between branches in the multi pipeline. Compile-time
   exhaustiveness via `hive-dsl.adt/adt-case`.

   - Op           — operation shapes accepted by the compile pipeline
   - OpResult     — per-op execution outcome
   - BatchEvent   — observability events emitted during execution
   - RegistryEntry — addon contributions routed to multi.registry by namespace

   Decision: 20260429230453-7e7627cc — multi IAddon-native extension contract
   Plan:     /home/leibniz/.claude/plans/very-nice-isp-lovely-puffin.md"
  (:require [hive-dsl.adt :as adt]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Op — what the compile pipeline normalizes
;; =============================================================================

(adt/defadt Op
  "An operation shape accepted by the multi compile pipeline.

   :op/single        — minimal {tool, command, params}
   :op/single-deps   — single op with id + depends_on (post-normalize)
   :op/dsl-verb      — uncompiled DSL sentence [verb params]
   :op/macro         — single-array fan-out batch macro"
  [:op/single        {:tool keyword? :command keyword? :params map?}]
  [:op/single-deps   {:id string? :tool keyword? :command keyword?
                      :params map? :depends_on coll?}]
  [:op/dsl-verb      {:verb string? :params map?}]
  [:op/macro         {:tool keyword? :command keyword?
                      :targets vector? :join (some-fn nil? map?)}])

;; =============================================================================
;; OpResult — per-op outcome
;; =============================================================================

(adt/defadt OpResult
  "Outcome of executing one operation.

   :result/ok       — handler returned successfully
   :result/err      — handler raised or returned an error category
   :result/skipped  — never invoked (dep failed, cycle, budget exhausted)"
  [:result/ok        {:id string? :tool keyword? :command keyword?
                      :data any? :elapsed-ms number?}]
  [:result/err       {:id string? :tool keyword? :command keyword?
                      :category keyword? :message string?
                      :data (some-fn nil? map?)}]
  [:result/skipped   {:id string? :reason keyword?
                      :upstream (some-fn nil? string?)}])

;; =============================================================================
;; BatchEvent — observability
;; =============================================================================

(adt/defadt BatchEvent
  "Streaming event emitted while a batch executes.

   Consumers dispatch via `adt-case` so adding a new variant cannot silently
   no-op in any handler that forgot to update."
  [:bx/wave-start    {:wave-num int? :op-count int?}]
  [:bx/op-start      {:op-id string? :tool keyword?
                      :command keyword? :wave-num int?}]
  [:bx/op-complete   {:op-id string? :result map?}]
  [:bx/wave-complete {:wave-num int? :total-waves int?
                      :success-count int? :failed-count int?}]
  [:bx/batch-done    {:summary map?}])

;; =============================================================================
;; RegistryEntry — what addons contribute to the multi.registry
;; =============================================================================

(adt/defadt RegistryEntry
  "Addon contribution routed by key namespace (the IAddon hooks-walk dispatches
   `(namespace k)` == \"multi\" entries through multi.registry/register-by-key!).

   :multi/tool         — adds a top-level tool the multi engine can dispatch to
   :multi/verb         — adds a DSL verb code → {tool, command} mapping
   :multi/param-alias  — adds a short→full param key alias
   :multi/batchable    — adds a tool-specific Batchable record for single-call boundary"
  [:multi/tool          {:tool-name string? :handler ifn?
                          :owner keyword? :batchable (constantly true)}]
  [:multi/verb          {:code string? :tool string?
                          :command string? :owner keyword?}]
  [:multi/param-alias   {:short string? :full keyword? :owner keyword?}]
  [:multi/batchable     {:tool-name string? :record (constantly true)
                          :owner keyword?}])
