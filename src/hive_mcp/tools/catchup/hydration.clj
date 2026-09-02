(ns hive-mcp.tools.catchup.hydration
  "Phase-2 content re-hydration for the catchup bundle.

   query-all-scoped returns metadata-only entries (6-field projection) so
   the hierarchy scan stays under budget. hydrate-buckets takes the trimmed
   display-bound subset and batch-fetches full content in one RPC, chunked
   + parallel via hive-weave bounded-pmap. Missing entries pass through
   as their metadata shell untouched — graceful degradation by default."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.tools.catchup.scope-filter :as sf]
            [hive-weave.parallel :as wpar]
            [clojure.tools.logging :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]
            [hive-mcp.tools.catchup.bundle-cache :as bc]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Telemetry wrapper — DI via hive-ttracking when present
;; =============================================================================

(def ^:private tt-timed-query-var
  "Late-bound reference to `hive-ttracking.core/timed-query`. DI via
   classpath presence so the ns still loads on minimal CI builds."
  (delay
    (rescue nil
            (require 'hive-ttracking.core)
            (resolve 'hive-ttracking.core/timed-query))))

(defn- timed-query-inline
  "Fallback telemetry wrapper used when hive-ttracking is absent."
  [label qfn]
  (fn []
    (let [t0 (System/currentTimeMillis)
          result (qfn)
          elapsed (- (System/currentTimeMillis) t0)
          n (count (or result []))]
      (if (zero? n)
        (log/warn "catchup hydration" label "returned 0 entries in" elapsed "ms")
        (log/info "catchup hydration" label ":" n "entries in" elapsed "ms"))
      result)))

(defn- timed-query
  "Dispatch to hive-ttracking.core/timed-query when available, else inline."
  [label qfn]
  (if-let [tt @tt-timed-query-var]
    (tt label qfn)
    (timed-query-inline label qfn)))

(defn has-tag?
  "True when `entry` has `tag` in its tag set. PUBLIC — used by bundle/split."
  [entry tag]
  (contains? (set (:tags entry)) tag))

(defn- index-by-id
  "Build an {id → entry} index from a coll of entries."
  [entries]
  (into {} (map (juxt :id identity)) entries))

(def ^:private hydrate-chunk-size
  "Split batch-get into N-sized chunks fanned out in parallel.
   Sweep on 180 rows × 13 fields:
     90×2=70s, 45×4=32s, 30×6=25s, 20×9=17s, 15×12=14s.
   Chunk=20 balances concurrency with per-call overhead (~10x vs single-shot)."
  20)

(def ^:private hydrate-chunk-budget-ms
  "Per-chunk timeout for hydrate fan-out. 20 rows × 13 fields lands ~2-3s
   cold; 20s leaves 6x headroom before bounded-pmap fallback kicks in."
  20000)

(defn- batch-fetch-content
  "Best-effort batch fetch from IMemoryStoreBatch, chunked + parallel via
   hive-weave bounded-pmap. Returns [] when the active store doesn't
   implement batch reads; timed-out chunks contribute []."
  [ids]
  (let [store (mem-proto/get-store)]
    (cond
      (not (and (seq ids) (mem-proto/batch-store? store)))
      []

      (<= (count ids) hydrate-chunk-size)
      (rescue [] ((timed-query "hydrate/single-shot"
                               #(with-resilience
                                  (mem-proto/get-entries store ids)))))

      :else
      (let [chunks (mapv vec (partition-all hydrate-chunk-size ids))
            fetch  (fn [c] ((timed-query "hydrate/chunk"
                                         #(with-resilience
                                            (mem-proto/get-entries store c)))))]
        (vec (mapcat identity
                     (wpar/bounded-pmap
                       {:concurrency (count chunks)
                        :timeout-ms  hydrate-chunk-budget-ms
                        :fallback    []}
                       fetch chunks)))))))

(defn- merge-hydrated
  "Prefer the hydrated entry when present; keep :distance from the
   metadata shell (vector-search scores don't survive batch-get)."
  [meta-entry hydrated-entry]
  (if hydrated-entry
    (cond-> hydrated-entry
      (:distance meta-entry) (assoc :distance (:distance meta-entry)))
    meta-entry))

(defn- hydrate-content
  "Phase-2 content re-hydration. Takes metadata-only entries, resolves full
   content through the bundle-cache content tier (ids it already holds are
   answered from memory; the rest go to one batch-get), returns hydrated
   entries in the same order. Called after phase-1 trimming so we only pay
   content-transport cost for the small, display-bound subset."
  [entries]
  (if-not (seq entries)
    entries
    (rescue entries
      (let [by-id (bc/cached-entries (mapv :id entries) batch-fetch-content)]
        (mapv #(merge-hydrated % (get by-id (:id %))) entries)))))

(defn hydrate-buckets
  "Phase-2 sweep: dedupe ids across all buckets, single batch-get, then
   swap each metadata shell for its hydrated twin. Entries missing from
   the store pass through untouched."
  [buckets]
  (let [all-entries (sf/distinct-by :id (apply concat (vals buckets)))
        by-id       (-> all-entries hydrate-content index-by-id)
        swap-each   (fn [coll]
                      (mapv #(merge-hydrated % (get by-id (:id %))) coll))]
    (reduce-kv (fn [m k v] (assoc m k (swap-each v))) {} buckets)))
