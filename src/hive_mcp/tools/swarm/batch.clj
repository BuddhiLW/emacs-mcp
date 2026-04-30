(ns hive-mcp.tools.swarm.batch
  "Wave 3 / T13 Phase 3 — Batchable runner for the consolidated `swarm` tool.

   Swarm's `canonical-handlers` is a *nested* tree keyed by subdomain
   (`:agent`, `:wave`, `:hivemind`, `:agora`, `:olympus`). The contract
   runner receives each op's `:tool` string and must map it to a single
   handler fn; we walk the tree using `hive-mcp.tools.cli/resolve-handler`
   so callers can say `\"agent spawn\"`, `\"hivemind.shout\"`, etc.

   Swarm MIGHT justify `DAGBatchable`/`StreamingBatchable` later (it is
   the only Wave-3 tool with wave-style semantics of its own), but per
   the track-B scope we implement only `Batchable` and defer the richer
   contracts. The delegate is the `DefaultBatchRunner`, which itself
   already satisfies `DAGBatchable` and `StreamingBatchable` — so callers
   who need those can reach through to the delegate.

   The consolidated swarm ns is resolved lazily via `requiring-resolve`
   so this batch ns loads even if e.g. agent-sdk / hivemind deps are
   unavailable. Contract tests bind their own resolver anyway.

   Purely additive — `handle-swarm` / `tool-def` are untouched."
  (:require [clojure.string :as str]
            [hive-mcp.batch :as batch]
            [hive-mcp.batch.protocol :as proto]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.tools.cli :as cli]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- lazy-canonical-handlers
  []
  (or (rescue nil
              (when-let [v (requiring-resolve
                            'hive-mcp.tools.consolidated.swarm/canonical-handlers)]
                @v))
      {}))

(defn- resolve-swarm-handler
  "Walk the nested swarm handler tree. Accepts dotted or space-separated
   tool names so both `\"agent.spawn\"` and `\"agent spawn\"` work."
  [handlers tool-name]
  (when (and tool-name (not (str/blank? (str tool-name))))
    (let [canonical (str/replace (str tool-name) #"\." " ")
          path      (cli/parse-command canonical)
          resolved  (cli/resolve-handler handlers path)]
      (when (fn? (:handler resolved))
        (:handler resolved)))))

(defn- default-emit-fx
  [fx-id fx-data]
  (rescue nil
          (when-let [get-fx (requiring-resolve 'hive.events.fx/get-fx)]
            (when-let [h (get-fx fx-id)]
              (h fx-data)))))

(defrecord SwarmBatchRunner [handlers delegate]
  proto/Batchable
  (batch-execute [_this ops opts]
    (proto/batch-execute delegate ops opts))
  (batch-schema [_this]
    (proto/batch-schema delegate)))

(defn make-swarm-runner
  "Factory — build a `SwarmBatchRunner` satisfying `Batchable`."
  ([] (make-swarm-runner {}))
  ([{:keys [handlers emit-fx]}]
   (let [hs       (or handlers (lazy-canonical-handlers))
         resolver (partial resolve-swarm-handler hs)
         delegate (batch/make-default-runner
                   {:resolve-handler resolver
                    :emit-fx         (or emit-fx default-emit-fx)})]
     (->SwarmBatchRunner hs delegate))))

;; =============================================================================
;; Public convenience shim — mirrors `hive-mcp.tools.multi/run-multi`
;; =============================================================================

(def ^:private default-runner
  "Lazy shared Batchable runner. Built on first use."
  (delay (make-swarm-runner)))

(defn run-batch
  "Execute a vector of swarm ops through the `Batchable` protocol.

   `ops` is a vector of `{:id :tool :command ...}` maps where `:tool`
   is a dotted/spaced swarm command (e.g. `\"agent spawn\"`,
   `\"wave dispatch\"`, `\"hivemind.shout\"`). Returns the standard
   Batchable result shape."
  ([ops] (run-batch ops {}))
  ([ops opts]
   (proto/batch-execute @default-runner ops opts)))
