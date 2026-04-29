(ns hive-mcp.plan.kg-degraded
  "KG-degraded escape hatch for plan-to-kanban.

   Why this module exists
   ----------------------
   Plan-to-kanban creates kanban tasks (load-bearing) AND emits KG edges
   (best-effort enrichment). The KG layer can wedge — write-coalescing
   queue stalls, datalevin conn closed mid-flight, konserve load-order —
   and `with-tx-batch` then blocks the calling thread indefinitely.
   Without an escape hatch, plan-to-kanban hangs forever even though the
   tasks are perfectly creatable. This module provides a bounded,
   degraded-safe wrapper so kanban always lands; edges that miss can be
   backfilled later via `kg edge`.

   Stratified design (CPPB)
   ------------------------
   Layers, top-down:

     Collect   — `kg-call-timeout-ms` resolves the per-call budget from
                 (1) ~/.config/hive-mcp/config.edn :plan.kg-call-timeout-ms
                 (2) HIVE_PLAN_KG_TIMEOUT_MS env (via hive-di defconfig)
                 (3) literal default (5000ms)
     Plan      — `warning-tag` shapes a Result error into the short
                 `kg-<reason>:<label>:<detail>` string the MCP response
                 surfaces.
     Process   — `call-with-timeout` runs one KG thunk with a hard
                 timeout via hive-weave's `safe-future-call`. Always
                 returns a hive-dsl Result.
     Build     — `apply-kg-calls` aggregates a vector of named thunks
                 into `{:edges all-ok-edges :warnings […] :degraded? bool}`.

   Reading direction: `apply-kg-calls` (Build) calls `call-with-timeout`
   (Process) which calls `kg-call-timeout-ms` (Collect); warnings flow
   back up through `warning-tag` (Plan)."
  (:require [hive-di.core :as di]
            [hive-dsl.result :as r]
            [hive-mcp.config.core :as config]
            [hive-weave.safe :as weave-safe]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Collect — Config DI
;; =============================================================================

(di/defconfig PlanKgConfig
  :call-timeout-ms (di/env "HIVE_PLAN_KG_TIMEOUT_MS"
                           :default 5000
                           :type :int
                           :doc "Hard timeout per KG batch call in plan-to-kanban."))

(defn- read-config-edn-override
  "Read :plan.kg-call-timeout-ms from ~/.config/hive-mcp/config.edn.
   Returns int or nil (rescued — config layer must never break us)."
  []
  (try
    (when-let [v (config/get-config-value "plan.kg-call-timeout-ms")]
      (cond (integer? v) v
            (string? v)  (parse-long v)
            :else        nil))
    (catch Throwable _ nil)))

(defn kg-call-timeout-ms
  "Resolve the timeout budget. config.edn wins over env wins over default.
   Pure-ish: reads global config + env, never throws."
  []
  (let [edn-override (read-config-edn-override)
        overrides    (cond-> {} edn-override (assoc :call-timeout-ms edn-override))
        result       (resolve-PlanKgConfig overrides)]
    (or (:call-timeout-ms (:ok result))
        5000)))

;; =============================================================================
;; Plan — Pure shaping
;; =============================================================================

(defn warning-tag
  "Shape a Result error into a short, human-grep-able warning string.
   Pure — no I/O, no side effects."
  [label err-result]
  (let [err-cat (:error err-result)
        data    (dissoc err-result :error)]
    (case err-cat
      :weave/timeout
      (str "kg-timeout:" label ":" (:timeout-ms data) "ms")

      :weave/exception
      (str "kg-error:" label ":" (or (:message data) (:class data) "unknown"))

      ;; Unknown error category — preserve raw shape for debugging.
      (str "kg-degraded:" label ":" (pr-str err-cat)))))

;; =============================================================================
;; Process — Bounded execution
;; =============================================================================

(defn call-with-timeout
  "Run KG thunk `f` with the resolved timeout. Returns a hive-dsl Result:
     (r/ok value)  — thunk completed
     (r/err …)     — :weave/timeout or :weave/exception per hive-weave

   The future is cancelled on timeout (hive-weave guarantees this)."
  [label f]
  (weave-safe/safe-future-call
   {:timeout-ms (kg-call-timeout-ms) :name label}
   f))

;; =============================================================================
;; Build — Aggregation
;; =============================================================================

(defn apply-kg-calls
  "Run a vector of named KG thunks. Aggregates results.

   `calls` is a seq of `[label thunk]` pairs OR maps {:label … :thunk …}.

   Returns:
     {:edges     vector of edge ids (concatenated from ok results, nils dropped)
      :warnings  vector of warning-tag strings (one per degraded call)
      :degraded? boolean}

   Each thunk's value is treated as either a single edge id, a vector of
   edges, or nil; all are flattened into `:edges`. Caller decides whether
   to log; this fn does not log on its own to keep the result pure."
  [calls]
  (let [normalized (map (fn [c]
                          (if (map? c)
                            [(:label c) (:thunk c)]
                            [(first c) (second c)]))
                        calls)
        results    (mapv (fn [[label thunk]]
                           [label (call-with-timeout label thunk)])
                         normalized)
        ok-edges   (->> results
                        (keep (fn [[_label res]]
                                (when (r/ok? res) (:ok res))))
                        (mapcat (fn [v]
                                  (cond
                                    (nil? v)        nil
                                    (sequential? v) v
                                    :else           [v])))
                        vec)
        warnings   (->> results
                        (keep (fn [[label res]]
                                (when-not (r/ok? res)
                                  (warning-tag label res))))
                        vec)]
    {:edges     ok-edges
     :warnings  warnings
     :degraded? (boolean (seq warnings))}))

(defn log-degradation!
  "Side-effecting helper — caller invokes once if `:degraded?` is true."
  [{:keys [warnings]} ctx]
  (log/warn "plan_to_kanban: KG enrichment degraded — tasks created, edges skipped"
            (merge {:warnings warnings} ctx)))
