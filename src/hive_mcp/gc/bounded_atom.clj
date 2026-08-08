(ns hive-mcp.gc.bounded-atom
  "Bounded atom registry with TTL-based eviction and capacity limits.

   Provides infrastructure for managing atoms that can grow unboundedly
   (GC death spiral prevention). Each registered bounded atom declares:
   - :max-entries  - Maximum number of entries allowed
   - :ttl-ms       - Time-to-live for entries (nil = no TTL)
   - :evict-fn     - Function to evict stale/over-capacity entries
   - :count-fn     - Function to count current entries
   - :name         - Human-readable name for stats reporting

   The registry is the backbone for :lifecycle/sweep events, which
   iterate over all registered bounded atoms and call their eviction
   functions.

   Usage:
   ```clojure
   (register! :my-atom
     {:name         \"my-cache\"
      :atom-ref     my-atom
      :max-entries  1000
      :ttl-ms       300000
      :count-fn     (fn [a] (count @a))
      :evict-fn     (fn [a opts] (evict-stale! a opts))})

   (sweep-all!)  ;; => {:total-evicted N :per-atom [...] :duration-ms T}
   ```"
  (:require [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry
;; =============================================================================

(defonce ^{:doc "Registry of bounded atoms. Map of keyword -> bounded-atom-spec.
  Each spec: {:name str :atom-ref IDeref :max-entries int :ttl-ms long
              :count-fn (fn [atom-ref]) :evict-fn (fn [atom-ref opts]) -> evicted-count}"}
  *registry
  (atom {}))

;; =============================================================================
;; Registration API
;; =============================================================================

(defn register!
  "Register a bounded atom for lifecycle sweep management.

   Args:
   - id   - Keyword identifier for this bounded atom
   - spec - Map with keys:
     :name         - Human-readable name (defaults to (name id))
     :atom-ref     - The atom (or IDeref) to manage
     :max-entries  - Maximum entries before capacity eviction (required)
     :ttl-ms       - TTL in milliseconds (nil = no TTL eviction)
     :count-fn     - (fn [atom-ref]) -> current entry count (required)
     :evict-fn     - (fn [atom-ref {:keys [max-entries ttl-ms now-ms]}]) -> evicted count (required)

   Returns the id.
   Idempotent -- re-registering with same id overwrites."
  [id spec]
  {:pre [(keyword? id)
         (fn? (:count-fn spec))
         (fn? (:evict-fn spec))
         (some? (:atom-ref spec))
         (pos-int? (:max-entries spec))]}
  (let [full-spec (assoc spec :name (or (:name spec) (name id)))]
    (swap! *registry assoc id full-spec)
    (log/debug "[gc] Registered bounded atom:" (name id)
               "max-entries:" (:max-entries spec)
               "ttl-ms:" (:ttl-ms spec))
    id))

(defn deregister!
  "Remove a bounded atom from the registry.
   Returns true if it was registered, false otherwise."
  [id]
  (let [existed? (contains? @*registry id)]
    (swap! *registry dissoc id)
    existed?))

(defn registered?
  "Check if a bounded atom is registered."
  [id]
  (contains? @*registry id))

(defn registered-ids
  "Return the set of all registered bounded atom IDs."
  []
  (set (keys @*registry)))

(defn get-spec
  "Get the spec for a registered bounded atom. Returns nil if not registered."
  [id]
  (get @*registry id))

;; =============================================================================
;; Sweep Logic
;; =============================================================================

(defn- sweep-one
  "Sweep a single bounded atom. Returns stats map.

   Calls the atom's evict-fn with current config, captures before/after counts.
   Never throws -- catches and reports errors per-atom."
  [id {:keys [name atom-ref max-entries ttl-ms count-fn evict-fn]}]
  (try
    (let [now-ms    (System/currentTimeMillis)
          before    (count-fn atom-ref)
          evicted   (evict-fn atom-ref {:max-entries max-entries
                                        :ttl-ms     ttl-ms
                                        :now-ms     now-ms})
          remaining (count-fn atom-ref)]
      {:atom-id   id
       :name      name
       :evicted   (or evicted (- before remaining))
       :remaining remaining
       :error     nil})
    (catch Exception e
      (log/warn "[gc] Sweep failed for bounded atom" (clojure.core/name id)
                ":" (.getMessage e))
      {:atom-id   id
       :name      (or name (clojure.core/name id))
       :evicted   0
       :remaining -1
       :error     (.getMessage e)})))

(defn sweep-all!
  "Sweep all registered bounded atoms. Returns sweep stats.

   Returns:
   {:total-evicted N
    :errors        N          ;; per-atom sweeps that threw
    :per-atom      [{:atom-id :name :evicted :remaining :error}...]
    :duration-ms   T
    :atom-count    N}

   :errors is part of the headline because :total-evicted alone cannot
   distinguish an all-failed sweep from a sweep with nothing to evict.

   Safe to call even when no atoms are registered -- returns empty stats."
  []
  (let [start-ms  (System/currentTimeMillis)
        registry  @*registry
        per-atom  (mapv (fn [[id spec]] (sweep-one id spec)) registry)
        total     (reduce + 0 (map :evicted per-atom))
        errors    (count (filter :error per-atom))
        elapsed   (- (System/currentTimeMillis) start-ms)]
    (when (pos? total)
      (log/info "[gc] Sweep completed: evicted" total "entries from"
                (count per-atom) "atoms in" elapsed "ms"))
    (when (pos? errors)
      (log/warn "[gc] Sweep had" errors "of" (count per-atom)
                "bounded atoms fail to evict -- capacity is NOT being enforced on them"))
    {:total-evicted total
     :errors        errors
     :per-atom      per-atom
     :duration-ms   elapsed
     :atom-count    (count registry)}))

;; =============================================================================
;; Testing Helpers
;; =============================================================================

(defn reset-registry!
  "Clear the bounded atom registry. For testing only."
  []
  (reset! *registry {})
  nil)
