(ns hive-mcp.crystal.recall
  "Recall tracking with context-aware weighting.
   Extension points resolved via extensions registry."
  (:require [hive-mcp.crystal.core :as crystal]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.engine.bounded.protocol :as bp]
            [hive-mcp.engine.bounded.lru :as lru]
            [hive-dsl.adt :refer [defadt adt-case]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Extension Delegation Helpers
;; =============================================================================

(defn- delegate-or-noop
  "Try to delegate to extension fn, fall back to default value."
  [ext-key default-val args]
  (if-let [f (ext/get-extension ext-key)]
    (apply f args)
    (do
      (log/debug "Extension not available, returning default for" ext-key)
      default-val)))

;; =============================================================================
;; Recall Context Detection — delegates to extension
;; =============================================================================

(defn detect-recall-context
  "Delegates to extension if available."
  [context-params]
  (delegate-or-noop :cl/a :explicit-reference [context-params]))

;; =============================================================================
;; Recall Event Creation
;; =============================================================================

(defn create-recall-event
  "Create a recall event record."
  [context-params]
  (let [context (detect-recall-context context-params)]
    {:context context
     :timestamp (.toString (java.time.Instant/now))
     :source (:source context-params)
     :session (or (:session context-params) (crystal/session-id))}))

(defn batch-recall-events
  "Create recall events for multiple entries queried together."
  [entry-ids context-params]
  (let [event (create-recall-event context-params)]
    (into {} (map (fn [id] [id [event]]) entry-ids))))

;; =============================================================================
;; Recall Aggregation — delegates to extension
;; =============================================================================

(defn aggregate-recalls
  "Delegates to extension if available."
  [recalls]
  (delegate-or-noop :cl/c [] [recalls]))

(defn merge-recall-histories
  "Delegates to extension if available."
  [existing new-recalls]
  (delegate-or-noop :cl/d (vec existing) [existing new-recalls]))

;; =============================================================================
;; Recall Classification — delegates to extension
;; =============================================================================

(defn classify-query-intent
  "Delegates to extension if available."
  [query-type tags caller]
  (delegate-or-noop :cl/b :explicit-reference [query-type tags caller]))

;; =============================================================================
;; Session Boundary Detection
;; =============================================================================

(defn crosses-session-boundary?
  "Check if accessing entry crosses session boundary."
  [current-session entry-tags]
  (when-let [entry-session (crystal/extract-session-from-tags entry-tags)]
    (not= current-session entry-session)))

(defn crosses-project-boundary?
  "Check if accessing entry crosses project boundary."
  [current-project entry-tags]
  (when-let [entry-project (some #(when (str/starts-with? % "scope:project:")
                                    (subs % 14))
                                 entry-tags)]
    (not= current-project entry-project)))

;; =============================================================================
;; Recall Tracking State (Optional - for hot-path optimization)
;; =============================================================================

(defonce ^{:private true
           :doc "Bounded per-entry-id buffer for recall events.

   ENGINE-L1.3 — implemented via IBoundedQueue/LruByKey so an unflushed
   buffer cannot grow without bound. Cap defaults:
     :capacity     1000 distinct entry-ids (LRU eviction)
     :per-key-cap    20 events per entry-id (drop-oldest within key)
   Override via system properties (hive.recall-buffer.capacity,
   hive.recall-buffer.per-key-cap) for ops tuning."}
  recall-buffer
  (lru/make-by-key
   {:capacity    (Long/parseLong
                  (or (System/getProperty "hive.recall-buffer.capacity") "1000"))
    :per-key-cap (Long/parseLong
                  (or (System/getProperty "hive.recall-buffer.per-key-cap") "20"))}))

(defn buffer-recall!
  "Buffer a recall event for later persistence. Bounded — both the
   number of entry-ids and the number of events per entry-id are
   capped (see `recall-buffer`)."
  [entry-id event]
  (bp/q-offer-key! recall-buffer entry-id event))

(defn flush-recall-buffer!
  "Get and clear buffered recalls. Atomic.
   Returns: map of {entry-id [events]}"
  []
  (bp/q-drain! recall-buffer))

(defn get-buffered-recalls
  "Get buffered recalls without clearing. Returns map of {entry-id [events]}."
  []
  (bp/q-snapshot recall-buffer))

(defn recall-buffer-stats
  "Diagnostic snapshot of buffer cap usage. Useful for /health endpoints."
  []
  (bp/q-stats recall-buffer))

;; =============================================================================
;; CreatedEntry ADT — Sum type for session-tracked memory entries
;; =============================================================================

(defadt CreatedEntry
  "Session-tracked memory entry. Scoped or unscoped."
  [:entry/scoped   {:id string? :timestamp string? :project-id string?}]
  [:entry/unscoped {:id string? :timestamp string?}])

;; =============================================================================
;; Created IDs Tracking (Session-scoped)
;; =============================================================================

(defonce ^{:private true
           :doc "Buffer for tracking memory IDs created during this session."}
  created-ids-buffer
  (atom []))

(defn register-created-id!
  "Register a created memory entry ID. Constructs CreatedEntry ADT."
  [entry-id project-id]
  (when entry-id
    (let [ts (.toString (java.time.Instant/now))
          entry (if project-id
                  (created-entry :entry/scoped {:id entry-id :timestamp ts :project-id project-id})
                  (created-entry :entry/unscoped {:id entry-id :timestamp ts}))]
      (swap! created-ids-buffer conj entry))))

(defn get-created-ids
  "Get all created IDs without clearing."
  []
  @created-ids-buffer)

;; =============================================================================
;; Pure Partition (Calculation — extracted from Action per Grokking Simplicity)
;; =============================================================================

(defn partition-by-project
  "Partition entries by project-id. Returns [matched retained].
   Unscoped entries are included in matched (they belong to any project)."
  [entries project-id]
  (reduce (fn [[m r] entry]
            (adt-case CreatedEntry entry
                      :entry/scoped   (if (= project-id (:project-id entry))
                                        [(conj m entry) r]
                                        [m (conj r entry)])
                      :entry/unscoped [(conj m entry) r]))
          [[] []]
          entries))

;; =============================================================================
;; Flush (Action — IO sandwich: read atom → pure partition → write atom)
;; =============================================================================

(defn flush-created-ids!
  "Flush created IDs. 0-arity: all. 1-arity: filtered by project-id."
  ([]
   (let [ids @created-ids-buffer]
     (reset! created-ids-buffer [])
     ids))
  ([project-id]
   (if (nil? project-id)
     (flush-created-ids!)
     (let [[matched retained] (partition-by-project @created-ids-buffer project-id)]
       (reset! created-ids-buffer retained)
       matched))))

(comment
  ;; Example usage
  (detect-recall-context
   {:source "agent"
    :session "2026-01-04"
    :project "hive-mcp"
    :entry-session "2026-01-03"
    :entry-project "hive-mcp"
    :explicit? false})
  ;; => :explicit-reference (noop) or classified context (with extension)

  (aggregate-recalls
   [{:context :explicit-reference}
    {:context :explicit-reference}
    {:context :cross-session}
    {:context :catchup-structural}
    {:context :catchup-structural}
    {:context :catchup-structural}])
  ;; => [] (noop) or [{:context :catchup-structural :count 3} ...] (with extension)

  ;; CreatedEntry ADT examples
  (created-entry :entry/scoped {:id "n-123" :timestamp "2026-01-01T10:00:00Z" :project-id "project-alpha"})
  (created-entry :entry/unscoped {:id "n-456" :timestamp "2026-01-01T10:00:00Z"})

  (partition-by-project
   [(created-entry :entry/scoped {:id "a" :timestamp "t1" :project-id "project-alpha"})
    (created-entry :entry/scoped {:id "b" :timestamp "t2" :project-id "project-beta"})
    (created-entry :entry/unscoped {:id "c" :timestamp "t3"})]
   "project-alpha"))
