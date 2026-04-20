(ns hive-mcp.tools.catchup.hierarchy
  "Hierarchy project-id resolution + per-descendant parallel fetch.

   Computes the visible project-id set for scope filtering and drives the
   parallel Milvus fan-out — H12: one branch per descendant project-id with
   `{:project-ids [pid]}` (single-element vec) instead of the legacy
   `project_id IN [a b c ...]` scalar filter. Tests whether Milvus's scalar
   index is faster on `==` than IN-list. Pure infrastructure — no cache, no
   side effects besides Milvus RPCs."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-weave.parallel :as wpar]
            [clojure.tools.logging :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def metadata-projection
  "Phase-1 scan: 6-field metadata projection. Drops content + embedding +
   document. Hydrate-buckets does a second batch-get via IMemoryStoreBatch
   for the trimmed survivor set (~180 rows) with full default-read-fields.
   Full projection on 300-row hierarchy scan stalls past 180s;
   metadata-only lands at ~40s cold.

   PUBLIC — shared with axiom_cache + bundle modules."
  ["id" "type" "tags" "created" "expires" "project_id"])

(def bundle-hierarchy-limit
  "Overall cap on hierarchy entries pulled in one shot. Metadata-only scan at
   ~7s fixed + 54ms/row → 300 rows ≈ 23s. Post-projection-prune 300 is enough
   headroom over the ~265 per-type caps.

   PUBLIC — used by chunked-hierarchy-fetch default branch + bundle."
  300)

(def ^:private hierarchy-chunk-size
  "LEGACY (pre-H12): IN-list chunk size for the `project-ids in [...]` scan.
   Retained so `chunk-count` keeps a stable signature for bundle.clj's
   per-chunk-limit math. Under H12 the per-descendant fan-out ignores it;
   only `chunk-count` still consults it for the legacy partition shape."
  15)

(def ^:private per-descendant-budget-ms
  "Per-branch timeout for the H12 single-value Milvus query. Single
   `project_id == pid` should land well under the legacy 16s/IN-15 chunk;
   30s leaves headroom for cold-path jitter before bounded-pmap drops to
   fallback []."
  30000)

(def ^:private per-descendant-limit
  "Per-descendant entry cap. Most descendants hold a handful of entries;
   50 keeps the fan-out total well under `bundle-hierarchy-limit` × N
   without truncating an unusually rich descendant. Empirically the per-type
   bundle caps (100 axioms, 50 decisions, 50 conventions) bound the
   downstream consumption anyway, so the cap protects against runaway
   metadata transport on a single noisy branch."
  50)

(def ^:private per-descendant-concurrency
  "Cap on simultaneous in-flight Milvus RPCs for the H12 fan-out. Sized to
   leave room for the global axiom + principle + scope-pierce branches
   sharing the same connection pool — a 45-way fan-out at full parallelism
   risks thrashing the pool. 8 keeps wall-clock close to single-RPC latency
   while bounding pool pressure."
  8)

(defn compute-hierarchy-project-ids
  "Compute the full set of visible project IDs for DB-level filtering."
  [project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (when in-project?
      (let [visible (kg-scope/visible-scopes project-id)
            descendants (kg-scope/descendant-scopes project-id)
            all-ids (distinct (concat visible descendants))]
        (vec (remove #(= "global" %) all-ids))))))

;; =============================================================================
;; Telemetry wrapper — DI via hive-ttracking when present (mirrors bundle.clj)
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
        (log/warn "catchup hierarchy" label "returned 0 entries in" elapsed "ms")
        (log/info "catchup hierarchy" label ":" n "entries in" elapsed "ms"))
      result)))

(defn- timed-query
  "Dispatch to hive-ttracking.core/timed-query when available, else inline."
  [label qfn]
  (if-let [tt @tt-timed-query-var]
    (tt label qfn)
    (timed-query-inline label qfn)))

(defn- fetch-one-descendant
  "Single-descendant Milvus query under H12. `:project-ids [pid]` is a
   single-element vec to coerce the backend into `project_id == pid`
   instead of `project_id IN [pid]`. Each call is wrapped in `timed-query`
   so the per-branch elapsed lands as a structured log line — cheap
   evidence for the IN-list-vs-equality hypothesis."
  [store pid]
  ((timed-query (str "hierarchy/desc/" pid)
                #(mem-proto/query-entries store
                   {:project-ids [pid]
                    :limit per-descendant-limit
                    :output-fields metadata-projection}))))

(defn chunked-hierarchy-fetch
  "H12 per-descendant fork-join: replaces the legacy
   `project_id IN [a b c ...]` scalar filter with N single-value branches
   (`{:project-ids [pid]}` per branch), fanned out via hive-weave
   bounded-pmap. Tests whether Milvus's scalar index is faster on `==` than
   IN-list. `per-chunk-limit` is accepted for signature compatibility with
   bundle.clj's chunked-limit math but ignored — H12 uses
   `per-descendant-limit` per branch. Timed-out branches contribute []
   (graceful degradation); each branch logs elapsed + row-count via
   `timed-query`."
  [store hierarchy-ids _per-chunk-limit]
  (if (empty? hierarchy-ids)
    []
    (vec (mapcat identity
                 (wpar/bounded-pmap
                   {:concurrency (min per-descendant-concurrency
                                      (count hierarchy-ids))
                    :timeout-ms  per-descendant-budget-ms
                    :fallback    []}
                   (partial fetch-one-descendant store)
                   hierarchy-ids)))))

(defn chunk-count
  "Number of parallel chunks the fan-out will produce for `hierarchy-ids`.
   Exposed so bundle.clj can compute per-chunk limits without duplicating
   the partition-all arithmetic."
  [hierarchy-ids]
  (max 1 (count (partition-all hierarchy-chunk-size hierarchy-ids))))
