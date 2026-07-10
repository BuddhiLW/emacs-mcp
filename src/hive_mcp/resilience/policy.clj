(ns hive-mcp.resilience.policy
  "L1 (pure) — `ErrorClass` → `RetryDecision`.

   `RetryDecision` is a small map:
     `{:retry? bool :backoff-ms int :escalate? bool}`

   - `:retry?`     true when the call should be retried in place after
                   an optional kick + wait.
   - `:backoff-ms` ceiling for the kick + wait window. Caller may
                   shorten it but never exceed.
   - `:escalate?`  true when the caller should surface the error AS-IS
                   (no swallowing) so a higher layer can react. For
                   `:err/schema-mismatch` this prevents the silent
                   reconnect-loop spam observed before the fix.

   No I/O. Decision table is data-driven so the property suite can
   exhaustively probe every variant."
  (:require [hive-dsl.adt :as adt]
            [hive-mcp.resilience.protocol :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-budget-ms
  "Mirrors `hive-mcp.vectordb.resilience/default-budget-ms` so the
   two budgets compose without surprise when a caller wraps both."
  8000)

(def ^:private decision-table
  "Closed dispatch table — one entry per `ErrorClass` variant. Adding a
   new variant in `protocol.clj` forces an entry here (otherwise
   `decide` falls through to the conservative `:err/unknown` row)."
  {:err/transient       {:retry? true  :backoff-ms default-budget-ms :escalate? false}
   :err/schema-mismatch {:retry? false :backoff-ms 0                 :escalate? true}
   :err/auth            {:retry? false :backoff-ms 0                 :escalate? true}
   :err/validation      {:retry? false :backoff-ms 0                 :escalate? true}
   :err/unknown         {:retry? false :backoff-ms 0                 :escalate? true}})

(defn decide
  "Given an `ErrorClass` ADT value, return a `RetryDecision` map."
  [err-class]
  (let [variant (adt/adt-variant err-class)]
    (or (get decision-table variant)
        (get decision-table :err/unknown))))

(defn retry?
  "Convenience predicate — true if the policy says to retry."
  [err-class]
  (:retry? (decide err-class)))

(defn schema-mismatch?
  "Re-export from protocol so callers don't need a second require."
  [err-class]
  (proto/schema-mismatch? err-class))
