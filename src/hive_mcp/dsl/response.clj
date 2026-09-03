(ns hive-mcp.dsl.response
  "Response processing for token efficiency.

   Delegates to extension if available. Returns noop defaults otherwise.

   Extension points are resolved via the extensions registry at startup.
   When no extensions are registered, all functions gracefully degrade
   to pass-through (input returned unchanged)."
  (:require [hive-mcp.extensions.delegate :refer [delegate-or-noop]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Extension Delegation Helpers
;; =============================================================================

;; =============================================================================
;; Auto-Detection for Ling Callers
;; =============================================================================

(defn ling-caller?
  "Check if the caller is a ling/swarm agent based on _caller_id prefix.
   Lings have _caller_id starting with 'swarm-' or 'ling-'.
   Returns truthy for ling callers, nil otherwise."
  [caller-id]
  (when (string? caller-id)
    (or (.startsWith ^String caller-id "swarm-")
        (.startsWith ^String caller-id "ling-")
        nil)))

;; =============================================================================
;; Param Resolution
;; =============================================================================

(defn resolve-compress-mode
  "Resolve the compression mode from MCP tool args.

   Priority (highest first):
   1. compact: false          → nil (explicit opt-out, overrides auto-detect)
   2. compact: true           → :compact
   3. compact: \"compact\"    → :compact
   4. compact: \"minimal\"    → :minimal
   5. Auto-detect: _caller_id starts with 'swarm-' or 'ling-' → :compact
   6. Otherwise              → nil (no compression)

   Auto-detection enables token-efficient responses for headless lings
   without requiring explicit compact param on every tool call.
   Coordinator ('coordinator' or nil _caller_id) keeps full responses.
   Lings can override with compact: false to get full responses."
  [args]
  (let [v (:compact args)]
    (cond
      (false? v)            nil
      (true? v)             :compact
      (string? v)           (let [kw (keyword v)]
                              (#{:compact :minimal :full} kw))
      (keyword? v)          (#{:compact :minimal :full} v)
      (nil? v)              (when (ling-caller? (:_caller_id args))
                              :compact)
      :else                 nil)))

;; =============================================================================
;; Public API — delegates to extension or returns noop (pass-through)
;; =============================================================================

(defn omit-defaults
  "Process response map via extension delegate.
   Delegates to extension if available."
  [m]
  (delegate-or-noop :dr/omit m [m]))

(defn strip-verbose
  "Process response map via extension delegate.
   Delegates to extension if available."
  [m]
  (delegate-or-noop :dr/strip m [m]))

(defn abbreviate-keys
  "Process response map via extension delegate.
   Delegates to extension if available."
  [m]
  (delegate-or-noop :dr/abbrev m [m]))

(defn minimal-entry
  "Reduce an entry-like map via extension delegate.
   Delegates to extension if available."
  [m]
  (delegate-or-noop :dr/entry m [m]))

(defn compact-response
  "Apply compact processing via extension delegate.
   Delegates to extension if available."
  [data]
  (delegate-or-noop :dr/compact data [data]))

(defn minimal-response
  "Apply minimal processing via extension delegate.
   Delegates to extension if available."
  [data]
  (delegate-or-noop :dr/minimal data [data]))

(defn compress
  "Apply response processing by mode.
   Delegates to extension if available."
  [data mode]
  (delegate-or-noop :dr/compress data [data mode]))

(defn compress-mcp-text
  "Process an MCP content item via extension delegate.
   Delegates to extension if available."
  [item mode]
  (delegate-or-noop :dr/text item [item mode]))

(defn compress-content
  "Process MCP content vector via extension delegate.
   Delegates to extension if available."
  [content mode]
  (delegate-or-noop :dr/content content [content mode]))

;; =============================================================================
;; Batch Envelope Processing — delegates to extension or returns noop
;; =============================================================================

(defn compress-batch-op
  "Process a single batch operation result via extension delegate.
   Delegates to extension if available."
  [op]
  (delegate-or-noop :dr/batch-op op [op]))

(defn compress-batch-envelope
  "Process a batch execution response envelope via extension delegate.
   Delegates to extension if available."
  [envelope]
  (delegate-or-noop :dr/batch-env envelope [envelope]))

;; =============================================================================
;; Batch Envelope Compact Formatting (pure data transformation)
;; =============================================================================

(def ^:private batch-summary-abbrev
  "Abbreviation map for batch summary sub-map keys."
  {:total   :t
   :success :ok
   :failed  :f
   :waves   :w})

(defn- abbreviate-summary
  "Abbreviate summary map keys. Pure calculation."
  [summary]
  (persistent!
   (reduce-kv (fn [acc k v]
                (assoc! acc (get batch-summary-abbrev k k) v))
              (transient {}) summary)))

(defn- compact-op-result
  "Compact a single batch op result: {:id _ :ok _ :d _} or {:id _ :ok _ :e _}.
   Renames :success->:ok, :result->:d, :error->:e. Omits nil values."
  [{:keys [id success result error]}]
  (cond-> {:id id :ok success}
    result (assoc :d result)
    error  (assoc :e error)))

(defn- flatten-wave-results
  "Flatten wave map {\"wave_1\" [...] \"wave_2\" [...]} into single result vector.
   Sorts by wave number to preserve execution order."
  [waves]
  (into []
        (mapcat (fn [[_k results]]
                  (if (sequential? results) results [])))
        (sort-by (fn [[k _]]
                   (or (some-> (re-find #"\d+" (str k)) parse-long) 0))
                 waves)))

(defn format-results-compact
  "Compact batch envelope: flatten waves, abbreviate keys. Pure calculation.

   Input:  {:success bool :summary {:total N :success M :failed F :waves W}
            :waves {\"wave_1\" [{:id _ :success _ :result _ :error _}] ...}}
   Output: {:ok bool :s {:t N :ok M :f F :w W}
            :r [{:id _ :ok _ :d _} ...]}

   For dry-run: {:ok bool :dr true :s {...} :p {...}}
   With errors: adds :E [...]"
  [{:keys [success summary waves dry_run plan errors]}]
  (let [s (abbreviate-summary (or summary {}))]
    (cond-> {:ok success :s s}
      (and waves (not dry_run))
      (assoc :r (mapv compact-op-result (flatten-wave-results waves)))

      (and dry_run plan)
      (assoc :dr true :p plan)

      errors
      (assoc :E errors))))
