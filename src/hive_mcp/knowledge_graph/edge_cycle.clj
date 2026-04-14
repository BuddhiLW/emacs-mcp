(ns hive-mcp.knowledge-graph.edge-cycle
  "Generic 'fetch -> sort -> limit -> step -> tally' loop for edge maintenance.

   Abstracts the shared skeleton of edge promotion and decay flows in
   `hive-mcp.knowledge-graph.edges`. Each caller supplies its own fetch,
   sort key, limit, per-edge step (classify + effect), and an outcome-key
   vector used to seed the tally so zero counts still appear in the result.

   Errors thrown by `:step!` are captured via `hive-dsl.result/guard` and
   tallied under `:errors`; the loop never escapes an exception."
  (:require [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

(defn- seed-tally
  "Build an initial tally map with :evaluated 0 and 0 per outcome key,
   plus an :errors slot which is always present."
  [outcome-keys]
  (into {:evaluated 0 :errors 0}
        (map (fn [k] [k 0]) outcome-keys)))

(defn- candidates
  "Fetch, optionally sort (descending when :sort-desc? true, else ascending),
   and take at most `limit` items."
  [{:keys [fetch sort-key sort-desc? limit]}]
  (let [xs (fetch)
        sorted (if sort-key
                 (if sort-desc?
                   (sort-by sort-key > xs)
                   (sort-by sort-key xs))
                 xs)]
    (if limit (take limit sorted) sorted)))

(defn run-cycle!
  "Walk a candidate edge set, step over each, tally outcomes.

   Opts:
     :fetch        - 0-arg fn returning the candidate edge seq.
     :sort-key     - fn edge -> comparable, or nil to skip sorting.
     :sort-desc?   - when true, sort descending (default false).
     :limit        - max edges to process per cycle (nil = unbounded).
     :step!        - fn (edge) -> outcome-keyword. Runs classify + effect
                     and returns which outcome bucket to increment.
                     Exceptions are caught and tallied as :errors.
     :outcome-keys - vector of expected outcome keywords; seeds 0-counts in
                     the tally so callers can rely on their presence.
     :error-log-fn - optional fn (edge, err-map) -> nil; called for each
                     captured exception. Defaults to a terse debug log.
     :log-fn       - optional fn (tally) -> nil; called once after the loop.

   Returns a tally map of
     {:evaluated N :errors N <outcome-key> N ...}."
  [{:keys [step! outcome-keys error-log-fn log-fn] :as opts}]
  (let [edges (candidates opts)
        err-log (or error-log-fn
                    (fn [edge err]
                      (log/debug "edge-cycle step failed for edge"
                                 (:kg-edge/id edge) ":" (:message err))))
        tally (reduce
               (fn [acc edge]
                 (let [v (r/guard Exception {::failed true} (step! edge))]
                   (if (and (map? v) (::failed v))
                     (do (when-let [err (::r/error (meta v))]
                           (err-log edge err))
                         (-> acc (update :errors inc) (update :evaluated inc)))
                     (-> acc
                         (update v (fnil inc 0))
                         (update :evaluated inc)))))
               (seed-tally outcome-keys)
               edges)]
    (when log-fn (log-fn tally))
    tally))
