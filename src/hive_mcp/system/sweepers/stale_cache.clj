(ns hive-mcp.system.sweepers.stale-cache
  "ISweepable impl that evicts TTL'd entries from known in-process caches.

   Caches swept (looked up lazily via requiring-resolve — missing ones are
   silently skipped so this sweeper runs cleanly even when hive-knowledge
   is OFF the classpath):

     - hive-mcp.tools.catchup.axiom-cache/evict-stale!
         (stale-while-revalidate `type=axiom` cache; owner: catchup scope)
     - hive-knowledge.carto-editing.snippet-cache/evict-stale!
         (Milvus tag-filter snippet cache; owner: carto-editing)
     - hive-knowledge.carto-editing.kondo-cache/evict-stale!
         (clj-kondo analysis cache; owner: carto-editing)

   The `evict-stale!` fns themselves belong to each cache's owner ns and
   are added in follow-up tasks. Until then, this sweeper is a graceful
   no-op: requiring-resolve returns nil → we skip that cache.

   Auto-registers with hive-mcp.system.registry on ns load."
  ;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
  ;;
  ;; SPDX-License-Identifier: AGPL-3.0-or-later
  (:require [hive-mcp.protocols.lifecycle :as lifecycle]
            [hive-mcp.system.registry :as reg]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Known cache `evict-stale!` entry points
;; =============================================================================
;;
;; Listed as fully-qualified symbols so they resolve lazily at sweep time —
;; no compile-time dep on any addon ns. Adding a new cache to the sweep set
;; is a one-liner here plus an `evict-stale!` defn in the cache's own ns.

(def ^:private cache-evict-syms
  '[hive-mcp.tools.catchup.axiom-cache/evict-stale!
    hive-knowledge.carto-editing.snippet-cache/evict-stale!
    hive-knowledge.carto-editing.kondo-cache/evict-stale!])

(defn- resolve-evict-fns
  "Return the subset of configured evict-stale! fns that resolve in the
   current classloader. Missing syms are skipped silently — graceful
   degradation is the desired behavior (sweep should not fail because a
   cache owner hasn't implemented evict-stale! yet, or because the addon
   providing it is off the classpath)."
  []
  (into []
        (keep (fn [sym]
                (try (requiring-resolve sym)
                     (catch Throwable _ nil))))
        cache-evict-syms))

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
