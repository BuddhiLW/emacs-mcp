(ns hive-mcp.engine.hprof.policy
  "Pure rotation decisions for hprof retention (ENGINE-L0.3).

   Given a collection of `HprofFile` records plus a policy map, decide
   which files to KEEP, which to GZIP, and which to DELETE.

   Strictly pure — no IO, no clocks beyond the mtime carried on each
   record. This lets `.boot` orchestrate without entangling decision
   logic with filesystem effects, and lets tests assert behaviour on
   in-memory fixtures.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn sort-newest-first
  "Sort hprofs by mtime descending (most-recent first)."
  [hprofs]
  (sort-by :mtime-ms > hprofs))

(defn- already-gzipped?
  [{:keys [path]}]
  (and (string? path) (.endsWith ^String path ".gz")))

(defn classify
  "Return `{:keep [...] :gzip [...] :delete [...]}` for the given hprofs
   under the given policy.

   - The N most-recent files survive (`:keep`).
   - When `:gzip?` is true, survivors not already `.gz` are scheduled
     for compression (`:gzip` is a subset of `:keep`).
   - Everything past the cap is deleted.

   `:keep-n` of 0 deletes every hprof. Negative values are clamped to 0."
  [hprofs {:keys [keep-n gzip?]}]
  (let [n         (max 0 (or keep-n 0))
        ranked    (sort-newest-first hprofs)
        survivors (vec (take n ranked))
        culled    (vec (drop n ranked))
        gzip-set  (if gzip?
                    (vec (remove already-gzipped? survivors))
                    [])]
    {:keep   survivors
     :gzip   gzip-set
     :delete culled}))

(defn total-bytes
  "Sum :bytes across the given hprofs. nil-safe."
  [hprofs]
  (reduce + 0 (map (fn [h] (or (:bytes h) 0)) hprofs)))
