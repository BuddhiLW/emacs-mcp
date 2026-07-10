(ns hive-mcp.system.sweepers.stale-cache
  "ISweepable impl that evicts TTL'd entries from registered in-process
   caches.

   hive-mcp's own caches are listed by symbol and resolved lazily via
   `requiring-resolve` at sweep time — missing entries are skipped
   silently so the sweeper stays loadable even when a cache ns hasn't
   been required yet.

   Downstream addons that own additional caches attach their own
   `evict-stale!` fns through `register-cache-evictor!`, typically from
   their addon initialization. The sweeper merges the static set with
   the dynamically registered set on every cycle.

   Auto-registers with `hive-mcp.system.registry` on ns load."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.system.registry :as reg]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Cache evictor registry
;; =============================================================================
;;
;; Static set: fully-qualified symbols for hive-mcp's own caches. Resolved
;; lazily at sweep time so adding an evict-stale! defn does not require
;; reloading this ns.
;;
;; Dynamic set: 0-arity fns registered at runtime by addons. Lets any
;; downstream cache opt in without hive-mcp knowing the addon's ns.

(def ^:private own-cache-evict-syms
  "hive-mcp-owned evict-stale! entry points. Adding a new cache here is
   a one-liner plus a defn in the cache's own ns."
  '[hive-mcp.tools.catchup.axiom-cache/evict-stale!])

(defonce ^:private *cache-evictors
  ^{:doc "Set of 0-arg evict-stale! fns registered at runtime."}
  (atom #{}))

(defn register-cache-evictor!
  "Register a 0-arg evict-stale! fn to be invoked on every sweep.
   Idempotent — duplicate registrations collapse into one entry.
   Returns the current registry snapshot."
  [evict-fn]
  {:pre [(fn? evict-fn)]}
  (swap! *cache-evictors conj evict-fn))

(defn registered-evictors
  "Diagnostic snapshot of the dynamic evictor set."
  []
  @*cache-evictors)

(defn- resolve-evict-fns
  "Merge the statically-listed own caches with the dynamic registry.
   Static symbols that fail to resolve are dropped silently."
  []
  (let [own (into []
                  (keep (fn [sym]
                          (try (requiring-resolve sym)
                               (catch Throwable _ nil))))
                  own-cache-evict-syms)
        registered @*cache-evictors]
    (vec (distinct (concat own registered)))))

;; =============================================================================
;; ISweepable impl
;; =============================================================================

(defrecord StaleCacheSweep []
  lifecycle/ISweepable
  (sweep-interval-s [_] 300)            ; 5 minutes
  (sweep-name [_] "caches/stale")
  (sweep! [_ _ctx]
    (let [fs      (resolve-evict-fns)
          results (for [f fs]
                    (try
                      {:cache (str f) :result (f)}
                      (catch Throwable t
                        (log/warn t "stale-cache sweep: evict-stale! threw"
                                  {:cache (str f)})
                        {:cache (str f) :error (.getMessage t)})))
          swept   (->> results
                       (keep :result)
                       (map #(if (number? %) % 1))
                       (reduce + 0))
          errors  (into [] (filter :error) results)]
      {:swept swept :errors errors})))

;; =============================================================================
;; Auto-registration
;; =============================================================================

(defonce ^:private -registered?
  (do (reg/register-sweep! (->StaleCacheSweep)) true))
