(ns hive-mcp.multi.batchables
  "Explicit `Batchable` records for the three highest-leverage core tools:
   memory, kg, kanban.

   Each record wraps the existing single-store-call batch handlers
   (`hive-mcp.tools.memory.crud/handle-batch-add` etc.) so multi's per-op
   loop collapses into ONE store round-trip per op-class per wave instead
   of the N round-trips the `tools/cli/make-batch-handler` path produces.

   ─── Why grouping by command ──────────────────────────────────────────
   Batchable's `batch-execute` receives a heterogeneous vector of ops for
   ONE tool that may span multiple commands (e.g. memory:add + memory:edit
   interleaved). Each existing batch-X handler takes only one command's
   worth of ops, so we group and dispatch per group.

   ─── Liskov contract ──────────────────────────────────────────────────
   Result shape is identical to DefaultBatchableAdapter:
     {:success bool :waves {1 {:ops [...] :results [...]}} :summary {...}}
   so callers cannot tell whether the optimized record or the default is
   in use. Property test `batchable_lsp_test/lsp-substitutability` enforces.

   Decision: 20260429230453-7e7627cc"
  (:require [hive-mcp.batch.protocol :as bproto]
            [hive-dsl.result :as r :refer [rescue]]
            [clojure.data.json :as json]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Shared helpers
;; =============================================================================

(defn- group-by-command
  "Group a vector of ops by their `:command` keyword, preserving insertion order
   within each group."
  [ops]
  (reduce (fn [m op]
            (update m (keyword (:command op)) (fnil conj []) op))
          {}
          ops))

(defn- decode-mcp-text
  "Best-effort decode of the MCP text envelope into a map. Returns the
   original value if it isn't a parseable text envelope."
  [v]
  (if (and (map? v) (string? (:text v)))
    (rescue v
      (json/read-str (:text v) :key-fn keyword))
    v))

(defn- handler-result->per-op
  "Project a batch handler's response onto per-op result entries.

   The existing memory/kg/kanban batch handlers return an MCP envelope
   `{:type \"text\" :text \"<json>\"}` whose decoded body usually contains
   a `:results` vector aligned with the input ops. We try that first, then
   fall back to a synthetic ok per op."
  [ops handler-result]
  (let [decoded (decode-mcp-text handler-result)
        per-results (when (and (map? decoded) (sequential? (:results decoded)))
                      (:results decoded))]
    (if per-results
      (mapv (fn [op r]
              (let [errored? (or (false? (:success r)) (some? (:error r)))]
                (cond-> {:id (:id op) :success (not errored?) :result r}
                  errored? (assoc :error (or (:error r) "batch op failed")))))
            ops
            (concat per-results (repeat nil)))
      (mapv (fn [op] {:id (:id op) :success true :result decoded})
            ops))))

(defn- safe-call
  "Invoke a handler under rescue-log; returns the result or
   `{:success false :error msg}` on any throw."
  [handler params]
  (let [out (atom nil)]
    (rescue {:type "error" :error "batch handler threw"}
      (reset! out (handler params)))
    (or @out
        {:type "error" :error "batch handler returned nil"})))

(defn- ok-wave [ops results]
  {1 {:ops ops :results results}})

(defn- summary [ops results]
  (let [total (count ops)
        succ  (count (filter :success results))]
    {:total total :success succ :failed (- total succ) :waves 1}))

(defn- error-results
  "Build per-op :success false entries with a shared message."
  [ops msg]
  (mapv (fn [op] {:id (:id op) :success false :error msg}) ops))

(defn- dispatch-by-command
  "Generic per-tool dispatch: group ops by command, look up a handler in the
   `handlers` map for that command keyword, and call it once with
   `{:operations [grouped-ops]}`. Ops in groups without a handler get
   `:multi/missing-command` style errors.

   Returns the standard {:success :waves :summary} Batchable output."
  [tool-name handlers ops]
  (let [groups (group-by-command ops)
        results
        (vec
         (mapcat
          (fn [[cmd group-ops]]
            (if-let [batch-handler (get handlers cmd)]
              (handler-result->per-op group-ops
                                      (safe-call batch-handler
                                                 {:operations group-ops}))
              (error-results group-ops
                             (str tool-name ": no batch handler for command: " cmd))))
          groups))]
    {:success (every? :success results)
     :waves   (ok-wave ops results)
     :summary (summary ops results)}))

;; =============================================================================
;; MemoryBatchable
;; =============================================================================

(defn- memory-handlers
  "Lazy-resolve memory's existing batch-X handlers."
  []
  {:add  (rescue nil (some-> (requiring-resolve 'hive-mcp.tools.memory.crud/handle-batch-add)
                             deref))
   :edit (rescue nil (some-> (requiring-resolve 'hive-mcp.tools.memory.crud/handle-batch-edit)
                             deref))
   :get  (rescue nil (some-> (requiring-resolve 'hive-mcp.tools.memory.crud/handle-batch-get)
                             deref))})

(defrecord MemoryBatchable []
  bproto/Batchable
  (batch-execute [_ ops _opts]
    (dispatch-by-command "memory" (memory-handlers) ops))
  (batch-schema [_]
    {:type "object"
     :properties {:operations {:type "array"
                                :items {:type "object"
                                        :properties {:command {:type "string"
                                                                :enum ["add" "edit" "get"]}}}}
                  :dry_run    {:type "boolean"}}}))

;; =============================================================================
;; KgBatchable
;; =============================================================================

(defn- kg-handlers []
  {:edge     (rescue nil (some-> (requiring-resolve 'hive-mcp.tools.consolidated.kg/handle-batch-edge)
                                 deref))
   :traverse (rescue nil (some-> (requiring-resolve 'hive-mcp.tools.consolidated.kg/handle-batch-traverse)
                                 deref))})

(defrecord KgBatchable []
  bproto/Batchable
  (batch-execute [_ ops _opts]
    (dispatch-by-command "kg" (kg-handlers) ops))
  (batch-schema [_]
    {:type "object"
     :properties {:operations {:type "array"
                                :items {:type "object"
                                        :properties {:command {:type "string"
                                                                :enum ["edge" "traverse"]}}}}}}))

;; =============================================================================
;; KanbanBatchable
;; =============================================================================

(defn- kanban-handlers []
  {:update (rescue nil (some-> (requiring-resolve 'hive-mcp.tools.consolidated.kanban/handle-batch-update)
                               deref))})

(defrecord KanbanBatchable []
  bproto/Batchable
  (batch-execute [_ ops _opts]
    (dispatch-by-command "kanban" (kanban-handlers) ops))
  (batch-schema [_]
    {:type "object"
     :properties {:operations {:type "array"
                                :items {:type "object"
                                        :properties {:command {:type "string"
                                                                :enum ["update"]}}}}}}))

;; =============================================================================
;; Constructors — used by core_seed
;; =============================================================================

(defn memory-batchable [] (->MemoryBatchable))
(defn kg-batchable     [] (->KgBatchable))
(defn kanban-batchable [] (->KanbanBatchable))
