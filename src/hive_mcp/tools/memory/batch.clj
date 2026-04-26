(ns hive-mcp.tools.memory.batch
  "Wave 3 / T13 Phase 3 — Batchable runner for the consolidated `memory` tool.

   Wraps `hive-mcp.batch/make-default-runner` with:
     - a `:resolve-handler` that looks up handlers in the memory tool's
       `canonical-handlers` map (flat commands + nested `:kg` / `:migration`
       subdomains, via `hive-mcp.tools.cli/resolve-handler`);
     - a `:emit-fx` shim that forwards to `hive.events.fx` when available.

   The record delegates to `DefaultBatchRunner` — we don't duplicate the
   pipeline. Per-call opts still win over the baked-in defaults (see
   `hive-mcp.batch/coerce-batch-opts`), so the shared contract suite at
   `hive-mcp.batch.contract-test` can rebind `:resolve-handler` to its
   stub without breaking this runner.

   `hive-mcp.tools.consolidated.memory` is resolved lazily via
   `requiring-resolve` so this namespace loads in isolation even when
   deeper memory dependencies (e.g. embeddings stack) are unavailable.
   Contract tests bind their own resolver anyway.

   This is purely additive — no existing memory call-sites change."
  (:require [clojure.string :as str]
            [hive-mcp.batch :as batch]
            [hive-mcp.batch.protocol :as proto]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.tools.cli :as cli]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Handler resolution — route `tool-name` to one of memory's canonical cmds
;; =============================================================================

(defn- lazy-canonical-handlers
  "Resolve `hive-mcp.tools.consolidated.memory/canonical-handlers` on
   first use. Returns the deref'd var, or `{}` if the ns is unavailable
   (e.g. in a contract-test context where we never touch the real map)."
  []
  (or (rescue nil
              (when-let [v (requiring-resolve
                            'hive-mcp.tools.consolidated.memory/canonical-handlers)]
                @v))
      {}))

(defn- resolve-memory-handler
  "Look up a command (string / keyword / dotted nested path) in the memory
   canonical handler tree. Returns a 1-arg handler fn or nil.

   Accepts:
     \"add\"               → (:add handlers)
     \"kg edge\"            → nested via cli/resolve-handler
     \"migration.backup\"  → treat dot as segment separator."
  [handlers tool-name]
  (when (and tool-name (not (str/blank? (str tool-name))))
    (let [canonical (str/replace (str tool-name) #"\." " ")
          path      (cli/parse-command canonical)
          resolved  (cli/resolve-handler handlers path)]
      (when (fn? (:handler resolved))
        (:handler resolved)))))

;; =============================================================================
;; FX bridge — forward runner FX into hive.events.fx when present
;; =============================================================================

(defn- default-emit-fx
  "Best-effort forwarder — swallow errors so the Batchable contract
   (never-throws) holds even when the event bus isn't loaded."
  [fx-id fx-data]
  (rescue nil
          (when-let [get-fx (requiring-resolve 'hive.events.fx/get-fx)]
            (when-let [h (get-fx fx-id)]
              (h fx-data)))))

;; =============================================================================
;; Batchable record
;; =============================================================================

(defrecord MemoryBatchRunner [handlers delegate]
  proto/Batchable
  (batch-execute [_this ops opts]
    (proto/batch-execute delegate ops opts))
  (batch-schema [_this]
    (proto/batch-schema delegate)))

(defn make-memory-runner
  "Factory — build a `MemoryBatchRunner` satisfying `Batchable`.

   Optional cfg keys:
     :handlers   override the handler map (defaults to lazily-resolved
                 `hive-mcp.tools.consolidated.memory/canonical-handlers`)
     :emit-fx    override the FX emitter (defaults to `default-emit-fx`)

   Per-call `:resolve-handler` / `:emit-fx` passed to `batch-execute`
   still win over the baked-in values (see `batch/coerce-batch-opts`)."
  ([] (make-memory-runner {}))
  ([{:keys [handlers emit-fx]}]
   (let [hs       (or handlers (lazy-canonical-handlers))
         resolver (partial resolve-memory-handler hs)
         delegate (batch/make-default-runner
                   {:resolve-handler resolver
                    :emit-fx         (or emit-fx default-emit-fx)})]
     (->MemoryBatchRunner hs delegate))))

;; =============================================================================
;; Public convenience shim — mirrors `hive-mcp.tools.multi/run-multi`
;; =============================================================================

(def ^:private default-runner
  "Lazy shared Batchable runner. Built on first use so the heavy
   `consolidated.memory` ns is only loaded when batch is actually
   invoked."
  (delay (make-memory-runner)))

(defn run-batch
  "Execute a vector of memory ops through the `Batchable` protocol.

   `ops` is a vector of `{:id :tool :command ...}` maps where `:tool`
   is a memory command string (e.g. `\"add\"`, `\"kg edge\"`,
   `\"migration backup\"`). Returns the standard Batchable result
   shape `{:success :waves :summary :errors?}`.

   Additive — no existing memory call-site uses this yet. Consolidated
   batch entrypoints still flow through `hive-mcp.tools.multi`."
  ([ops] (run-batch ops {}))
  ([ops opts]
   (proto/batch-execute @default-runner ops opts)))
