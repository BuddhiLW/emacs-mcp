(ns hive-mcp.tools.catchup.hierarchy
  "Hierarchy project-id resolution + chunked parallel fetch.

   Computes the visible project-id set for scope filtering and drives the
   parallel Milvus fan-out when that set exceeds `hierarchy-chunk-size`.
   Pure infrastructure — no cache, no side effects besides Milvus RPCs."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-weave.parallel :as wpar]))
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
  "Split the hierarchy `project-ids in [...]` scan into parallel sub-queries.
   Sweep on 45 project-ids × limit 100/chunk × 6-field projection:
     45×1=35s (baseline), 15×3=16s, 10×5=10s (cropped).
   Chunk=15 = 3-way fan-out without dropping rows under `bundle-hierarchy-limit`
   post-merge. ~2.2x vs single-shot."
  15)

(def ^:private hierarchy-chunk-budget-ms
  "Per-chunk timeout for hierarchy fan-out. Cold-path chunk lands at ~16s;
   40s leaves 2.5x headroom for jitter before the bounded-pmap drops to
   fallback []."
  40000)

(defn compute-hierarchy-project-ids
  "Compute the full set of visible project IDs for DB-level filtering."
  [project-id]
  (let [in-project? (and project-id (not= project-id "global"))]
    (when in-project?
      (let [visible (kg-scope/visible-scopes project-id)
            descendants (kg-scope/descendant-scopes project-id)
            all-ids (distinct (concat visible descendants))]
        (vec (remove #(= "global" %) all-ids))))))

(defn chunked-hierarchy-fetch
  "Parallel-chunk the hierarchy project-ids scan via hive-weave bounded-pmap.
   Each chunk gets its own Milvus RPC with a sub-vec of project-ids and a
   per-chunk limit; results are concatenated. Timed-out chunks contribute []
   (graceful degradation)."
  [store hierarchy-ids per-chunk-limit]
  (if (<= (count hierarchy-ids) hierarchy-chunk-size)
    (mem-proto/query-entries store
      {:project-ids hierarchy-ids
       :limit bundle-hierarchy-limit
       :output-fields metadata-projection})
    (let [chunks (mapv vec (partition-all hierarchy-chunk-size hierarchy-ids))
          fetch  (fn [c]
                   (mem-proto/query-entries store
                     {:project-ids c
                      :limit per-chunk-limit
                      :output-fields metadata-projection}))]
      (vec (mapcat identity
                   (wpar/bounded-pmap
                     {:concurrency (count chunks)
                      :timeout-ms  hierarchy-chunk-budget-ms
                      :fallback    []}
                     fetch chunks))))))

(defn chunk-count
  "Number of parallel chunks the fan-out will produce for `hierarchy-ids`.
   Exposed so bundle.clj can compute per-chunk limits without duplicating
   the partition-all arithmetic."
  [hierarchy-ids]
  (max 1 (count (partition-all hierarchy-chunk-size hierarchy-ids))))
