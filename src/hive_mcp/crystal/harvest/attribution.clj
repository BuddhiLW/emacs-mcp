(ns hive-mcp.crystal.harvest.attribution
  "Per-source pid attribution — pure layer that tags each harvested datum
   with the project-id it belongs to.

   Step 2 of the per-scope wrap emission plan
   (memory `20260504173159-46dc47f1`).

   ## Why this layer exists

   Today every harvest source pre-filters by a single resolved project-id
   from the harvest's pwd. That works for single-scope sessions but
   collapses any cross-scope activity into the pwd's pid (the bleed).

   Step 4 will rewire `harvest-all` to drop those filters so each source
   returns the full session's data. This namespace is the missing link:
   given a flat (single- or multi-pid) source result, produce a uniform
   tagged stream `[{:pid <pid-or-:umbrella> :datum <orig>} ...]` that the
   step-3 partitioner can sort into a `HarvestByScope`.

   ## Attribution strength

   - **Strong** (per-datum): notes/tasks/recalls/kg-edges/kanban-mvs carry
     enough metadata (`:project-id`, `:scope`, `scope:project:X` tag) for
     each datum to declare its own pid.
   - **Weak** (source-context fallback): commits and accessed-id lists
     have no per-datum pid signal. They inherit the harvest's source-pid
     for now. Per-commit file-scope attribution and per-id store lookup
     are step-4 enrichments.

   ## API shape

   - Single-datum attributors return `{:pid <pid> :datum <orig>}`.
   - `attribute-source` dispatches by source-key and returns a vector of
     such maps.
   - `attribute-harvest` walks all known source keys in a legacy harvest
     result, plus extracts whole-session scalars (timing, temporal) into
     `:umbrella-scalars` for step-3 to thread into the umbrella slice.

   This namespace is pure — no IO, no protocols, no logging. Trifecta tests
   in `hive-mcp.crystal.harvest.attribution-test`."
  (:require [clojure.string :as str]
            [hive-mcp.crystal.harvest.by-scope :as bs]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Tag / scope extractors
;; =============================================================================

(def ^:private scope-tag-prefix "scope:project:")

(defn pid-from-tag
  "Pull a pid out of a single tag string. Returns the pid (the substring
   after `scope:project:`) when the tag has that prefix and the rest is
   a valid pid, otherwise nil."
  [tag]
  (when (and (string? tag) (str/starts-with? tag scope-tag-prefix))
    (let [pid (subs tag (count scope-tag-prefix))]
      (when (bs/valid-pid? pid) pid))))

(defn pid-from-tags
  "Scan a tag collection (vector / seq / set) for the first
   `scope:project:X` and return X. Nil if none found."
  [tags]
  (some pid-from-tag tags))

(defn pid-from-entry
  "Resolve pid from a memory-style entry map. Precedence:
     1. explicit `:project-id` field (when valid)
     2. tag-derived pid via `pid-from-tags`
     3. nil"
  [entry]
  (or (let [pid (:project-id entry)]
        (when (bs/valid-pid? pid) pid))
      (pid-from-tags (:tags entry))))

(defn pid-from-kg-edge
  "Resolve pid from a Datahike KG edge entity-map. Precedence:
     1. `:kg-edge/scope` (string)
     2. fallback: nil (caller decides between umbrella vs source-pid)

   Cross-pid edges (where source/target scopes differ) are intentionally
   left for the caller to detect via the umbrella partitioning rule —
   this fn returns the edge's own scope, not a cross-product."
  [edge]
  (let [scope (:kg-edge/scope edge)]
    (when (bs/valid-pid? scope) scope)))

(defn pid-from-kanban-movement
  "Resolve pid from a DataScript kanban-movement entity. Precedence:
     1. `:kanban-movement/project-id`
     2. nil"
  [mv]
  (let [pid (:kanban-movement/project-id mv)]
    (when (bs/valid-pid? pid) pid)))

(defn pid-from-completed-task
  "Resolve pid from a completed-task entry. Handles both DataScript
   shape (`:completed-task/project-id`) and Chroma-shape (`:project-id`
   + tags)."
  [task]
  (or (let [pid (:completed-task/project-id task)]
        (when (bs/valid-pid? pid) pid))
      (pid-from-entry task)))

(defn pid-from-shout
  "Resolve pid from a hivemind message. Messages may carry `:project-id`
   directly. Returns nil if absent (caller falls back to source-pid)."
  [msg]
  (let [pid (or (:project-id msg) (:scope msg))]
    (when (bs/valid-pid? pid) pid)))

;; =============================================================================
;; Datum-level attributors
;; =============================================================================

(defn- with-attribution
  "Wrap `datum` with `pid`, defaulting to umbrella sentinel when pid is
   nil/blank. Pure."
  [pid datum]
  {:pid   (or pid bs/umbrella-sentinel)
   :datum datum})

(defn attribute-progress-note
  "Tag a progress note with its pid. Falls back to source-pid; finally
   to umbrella if neither is available."
  [source-pid note]
  (with-attribution (or (pid-from-entry note) source-pid) note))

(defn attribute-completed-task
  [source-pid task]
  (with-attribution (or (pid-from-completed-task task) source-pid) task))

(defn attribute-commit
  "Per-commit pid is unavailable without changed-file inspection. Always
   attributes to source-pid. Step-4 enhancement: parse changed paths and
   resolve per-file scopes."
  [source-pid commit-str]
  (with-attribution source-pid commit-str))

(defn attribute-recall
  "Recalls arrive as `{id entry}` map entries. Entry carries scope info."
  [source-pid recall-pair]
  (let [[id entry] (cond
                     (map-entry? recall-pair) [(key recall-pair) (val recall-pair)]
                     (sequential? recall-pair) [(first recall-pair) (second recall-pair)]
                     :else [nil recall-pair])]
    (with-attribution (or (pid-from-entry entry) source-pid)
                      [id entry])))

(defn attribute-hivemind-message
  [source-pid msg]
  (with-attribution (or (pid-from-shout msg) source-pid) msg))

(defn attribute-kg-edge
  "KG edges carry their own scope. Edges with no scope or a non-pid scope
   land in umbrella."
  [_source-pid edge]
  (with-attribution (pid-from-kg-edge edge) edge))

(defn attribute-kanban-movement
  [source-pid mv]
  (with-attribution (or (pid-from-kanban-movement mv) source-pid) mv))

(defn attribute-memory-id-created
  "memory-ids-created entries are `{:id ... :project-id ...}` maps."
  [source-pid entry]
  (with-attribution (or (pid-from-entry entry) source-pid) entry))

(defn attribute-memory-id-accessed
  "memory-ids-accessed is a flat list of id strings — no per-id pid signal
   without a store lookup. Inherits source-pid; step-4 may enrich."
  [source-pid id]
  (with-attribution source-pid id))

;; =============================================================================
;; Source-level attribution
;; =============================================================================

(defn- attribute-seq
  [attributor-fn source-pid xs]
  (mapv (partial attributor-fn source-pid) (or xs [])))

(def ^:private source-attributors
  "Maps each known source-result key to:
     {:datum-key    where to find the iterable inside the result map
      :attributor   per-datum fn
      :slice-key    target ScopeSlice key for step-3}"
  {:progress
   {:datum-key  :notes
    :attributor attribute-progress-note
    :slice-key  :progress-notes}

   :tasks
   {:datum-key  :tasks
    :attributor attribute-completed-task
    :slice-key  :completed-tasks}

   :commits
   {:datum-key  :commits
    :attributor attribute-commit
    :slice-key  :git-commits}

   :hivemind
   {:datum-key  :messages
    :attributor attribute-hivemind-message
    :slice-key  :hivemind-messages}

   :kanban
   {:datum-key  :tasks-completed
    :attributor attribute-completed-task
    :slice-key  :completed-tasks}

   :kg-edges
   {:datum-key  :edges
    :attributor attribute-kg-edge
    :slice-key  :kg-edges-created}

   :kanban-mvs
   {:datum-key  :movements
    :attributor attribute-kanban-movement
    :slice-key  :kanban-movements}})

(defn source-keys
  "Set of source keys this layer knows how to attribute."
  []
  (set (keys source-attributors)))

(defn attribute-source
  "Attribute every datum in `source-result` (a map produced by one of the
   `harvest-*` fns). Returns `{:slice-key <ScopeSlice key>
                                :datums    [{:pid :datum} ...]}`.

   `source-key` is one of `(source-keys)`. Unknown keys throw, surfacing
   silent attribution drift early."
  [source-key source-result source-pid]
  (let [{:keys [datum-key attributor slice-key]} (source-attributors source-key)]
    (when-not slice-key
      (throw (ex-info "Unknown harvest source-key" {:source-key source-key
                                                    :known      (source-keys)})))
    {:slice-key slice-key
     :datums    (attribute-seq attributor source-pid (get source-result datum-key))}))

;; =============================================================================
;; Whole-harvest attribution
;; =============================================================================

(defn attribute-recalls-map
  "Recalls live as `{id entry}` maps, not as a vector under a key. Returns
   `{:slice-key :recalls :datums [...]}`."
  [recalls-map source-pid]
  {:slice-key :recalls
   :datums    (mapv (fn [[id entry]]
                      (attribute-recall source-pid [id entry]))
                    (or recalls-map {}))})

(defn attribute-memory-ids-created
  [created source-pid]
  {:slice-key :memory-ids-created
   :datums    (mapv (partial attribute-memory-id-created source-pid)
                    (or created []))})

(defn attribute-memory-ids-accessed
  [accessed source-pid]
  {:slice-key :memory-ids-accessed
   :datums    (mapv (partial attribute-memory-id-accessed source-pid)
                    (or accessed []))})

(defn extract-umbrella-scalars
  "Pull whole-session scalar facts out of a legacy harvest-all result.
   These have no per-pid home and feed the umbrella slice in step-3."
  [harvest-result]
  (cond-> {}
    (:session-timing   harvest-result) (assoc :session-timing   (:session-timing   harvest-result))
    (:session-temporal harvest-result) (assoc :session-temporal (:session-temporal harvest-result))))

(defn attribute-harvest
  "Walk a legacy `harvest-all` result + caller-supplied source-pid and
   produce a uniform attribution map ready for step-3 partitioning.

   Returns:
   ```
   {:by-source    {<slice-key> [{:pid :datum} ...]}
    :umbrella-scalars {:session-timing ... :session-temporal ...}
    :errors       [...]
    :session      <session-tag>
    :directory    <abs-path>
    :agent-id     <id>}
   ```

   `source-pid` is the harvest-context pid (today: pwd-derived). Datums
   without per-datum pid signal inherit it; per-datum-tagged datums use
   their own pid."
  [harvest-result source-pid]
  (let [;; Map-of-map shape: each known source feeds a slice.
        per-source-attrs
        (->> (source-keys)
             (map (fn [k]
                    (let [src-result (get harvest-result
                                          (case k
                                            :progress   :_progress
                                            :tasks      :_tasks
                                            :commits    :_commits
                                            :hivemind   :_hivemind
                                            :kanban     :_kanban
                                            :kg-edges   :_kg-edges
                                            :kanban-mvs :_kanban-mvs))]
                      ;; Legacy harvest-all returns flat fields, not nested
                      ;; per-source maps. We translate via the legacy keys.
                      [k (case k
                           :progress   {:notes           (:progress-notes    harvest-result)}
                           :tasks      {:tasks           (:completed-tasks   harvest-result)}
                           :commits    {:commits         (:git-commits       harvest-result)}
                           :hivemind   {:messages        (:hivemind-messages harvest-result)}
                           :kanban     {:tasks-completed (get-in harvest-result [:kanban-activity :tasks-completed])}
                           :kg-edges   {:edges           (get-in harvest-result [:kg-edges-created :edges])}
                           :kanban-mvs {:movements       (get-in harvest-result [:kanban-movements :movements])})])))
             (map (fn [[k pseudo-result]]
                    (attribute-source k pseudo-result source-pid)))
             vec)

        recalls-attrs    (attribute-recalls-map         (:recalls               harvest-result) source-pid)
        created-attrs    (attribute-memory-ids-created  (:memory-ids-created    harvest-result) source-pid)
        accessed-attrs   (attribute-memory-ids-accessed (:memory-ids-accessed   harvest-result) source-pid)

        all-attrs        (concat per-source-attrs [recalls-attrs created-attrs accessed-attrs])
        by-source        (reduce (fn [acc {:keys [slice-key datums]}]
                                   (update acc slice-key (fnil into []) datums))
                                 {}
                                 all-attrs)]
    (cond-> {:by-source        by-source
             :umbrella-scalars (extract-umbrella-scalars harvest-result)}
      (:session   harvest-result) (assoc :session   (:session   harvest-result))
      (:directory harvest-result) (assoc :directory (:directory harvest-result))
      (:agent-id  harvest-result) (assoc :agent-id  (:agent-id  harvest-result))
      (:errors    harvest-result) (assoc :errors    (:errors    harvest-result)))))