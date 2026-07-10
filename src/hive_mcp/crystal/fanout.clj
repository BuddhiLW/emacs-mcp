(ns hive-mcp.crystal.fanout
  "Per-scope wrap synthesis fan-out — Step 5 + Step 6 of the per-scope
   wrap plan (memory `20260504173159-46dc47f1`).

   Takes a `HarvestByScope` (step-3 output) and emits one synthesised wrap
   entry per touched project scope, plus one umbrella entry for cross-
   cutting facts (cross-pid KG edges, multi-project decisions).

   ## DIP boundary

   This namespace depends only on hive-mcp public APIs:
     - `hive-mcp.crystal.harvest.by-scope` — pure ADT
     - `hive-mcp.crystal.core/summarize-session-progress` — delegates via
       the `:cc/summarize-progress` extension key
     - `hive-mcp.crystal.core/session-id` / `session-tag`

   It does **not** require hive-knowledge or any LLM addon. The
   per-scope synthesiser is whatever has been registered under
   `:cc/summarize-progress` — hive-knowledge plugs the LLM-backed impl
   in at addon load; without it the mechanical fallback in
   `summarize-session-progress-fallback` runs per scope.

   ## Output shape

   ```
   [{:pid <project-id-string-or-:umbrella>
     :entry {:type :note :content <synth>
             :tags [\"scope:project:<pid>\" \"session-summary\" ...]
             :duration :short}}
    ...]
   ```

   Step-6 layers the explicit scope tag (`scope:project:<pid>` for real
   pids, `scope:multi-project` for umbrella) onto each entry's `:tags`
   so the IMemoryStore add at the step-8 boundary cannot mis-classify
   the entry by deriving scope from pwd.

   Pure (no IO of its own; the synthesiser fn it calls is the only IO seam)."
  (:require [hive-mcp.crystal.core :as core]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Scope tag constants (step 6) — declared up-front so downstream defs can
;; reference them at compile time. Plain `def` (not ^:const) avoids the
;; AOT inlining footgun that bites multi-form file edits.
;; =============================================================================

(def scope-tag-prefix "scope:project:")
(def multi-project-tag "scope:multi-project")

(defn scope-tag-for
  "Return the scope tag string for a pid. Real pid strings get the
   `scope:project:<pid>` form; the umbrella sentinel keyword gets
   `scope:multi-project`."
  [pid]
  (if (= pid bs/umbrella-sentinel)
    multi-project-tag
    (str scope-tag-prefix pid)))

(defn with-scope-tag
  "Prepend the scope tag derived from `pid` to `entry`'s `:tags`. Tag
   addition is idempotent — applying twice is a no-op."
  [entry pid]
  (let [tag      (scope-tag-for pid)
        existing (vec (or (:tags entry) []))]
    (if (some #{tag} existing)
      entry
      (assoc entry :tags (vec (cons tag existing))))))

;; =============================================================================
;; Slice → legacy-harvested adapter
;; =============================================================================

(defn slice->harvested
  "Turn a ScopeSlice + pid + session-meta into a map shaped like the
   legacy `harvest-all` result. The downstream synthesiser
   (`:cc/summarize-progress` extension or its mechanical fallback)
   was written against that shape, so this adapter is the migration seam."
  [pid slice {:keys [session directory agent-id session-timing session-temporal]}]
  {:progress-notes      (vec (:progress-notes      slice))
   :completed-tasks     (vec (:completed-tasks     slice))
   :git-commits         (vec (:git-commits         slice))
   :recalls             (into {} (mapv (fn [r]
                                         (cond
                                           (map-entry? r) r
                                           (sequential? r) [(first r) (second r)]
                                           :else [r r]))
                                       (:recalls slice)))
   :hivemind-messages   (vec (:hivemind-messages   slice))
   :kanban-activity     {:tasks-completed (vec (:kanban-activity slice))
                         :completed-count (count (:kanban-activity slice))}
   :kg-edges-created    {:edges (vec (:kg-edges-created slice))
                         :count (count (:kg-edges-created slice))}
   :kanban-movements    {:movements (vec (:kanban-movements slice))
                         :count     (count (:kanban-movements slice))}
   :memory-ids-created  (vec (:memory-ids-created  slice))
   :memory-ids-accessed (vec (:memory-ids-accessed slice))
   :session             session
   :directory           directory
   :agent-id            agent-id
   :session-timing      session-timing
   :session-temporal    session-temporal
   :project-id          pid
   :summary             {:progress-count       (count (:progress-notes slice))
                         :task-count           (count (:completed-tasks slice))
                         :commit-count         (count (:git-commits slice))
                         :recall-count         (count (:recalls slice))
                         :hivemind-shout-count (count (:hivemind-messages slice))
                         :kanban-completed     (count (:kanban-activity slice))
                         :kg-edge-count        (count (:kg-edges-created slice))
                         :kanban-movement-count (count (:kanban-movements slice))
                         :created-count        (count (:memory-ids-created slice))
                         :accessed-count       (count (:memory-ids-accessed slice))
                         :created-by-type      (frequencies (keep :type (:memory-ids-created slice)))}})

;; =============================================================================
;; Umbrella → legacy-harvested adapter
;; =============================================================================

(defn umbrella->harvested
  "Project the UmbrellaSlice into a legacy-harvested-shape map for the
   umbrella wrap. Cross-cutting facts (cross-pid edges, multi-project
   decisions, global shouts) all surface as content the synthesiser can
   summarise — the LLM doesn't need to know they came from the umbrella."
  [{:keys [umbrella] :as _hbs} {:keys [session directory agent-id]}]
  (let [{:keys [cross-pid-edges cross-cutting-decisions hivemind-shouts-global
                session-timing session-temporal]} umbrella]
    {:progress-notes      (vec cross-cutting-decisions)
     :completed-tasks     []
     :git-commits         []
     :recalls             {}
     :hivemind-messages   (vec hivemind-shouts-global)
     :kanban-activity     {:tasks-completed [] :completed-count 0}
     :kg-edges-created    {:edges (vec cross-pid-edges)
                           :count (count cross-pid-edges)}
     :kanban-movements    {:movements [] :count 0}
     :memory-ids-created  []
     :memory-ids-accessed []
     :session             session
     :directory           directory
     :agent-id            agent-id
     :session-timing      session-timing
     :session-temporal    session-temporal
     :project-id          "multi-project"
     :scope               :umbrella
     :summary             {:progress-count       (count cross-cutting-decisions)
                           :task-count           0
                           :commit-count         0
                           :recall-count         0
                           :hivemind-shout-count (count hivemind-shouts-global)
                           :kanban-completed     0
                           :kg-edge-count        (count cross-pid-edges)
                           :kanban-movement-count 0
                           :created-count        0
                           :accessed-count       0
                           :created-by-type      {}}}))

;; =============================================================================
;; Per-scope synthesis call
;; =============================================================================

(defn- synthesise-one
  "Invoke the public synthesiser for a single legacy-harvested map.
   Returns the synth entry (map) or nil. The synthesiser is whatever is
   registered under the `:cc/summarize-progress` extension key — which
   delegate-falls-back to the mechanical summary when no addon is loaded."
  [harvested]
  (core/summarize-session-progress (:progress-notes harvested)
                                   (:git-commits    harvested)
                                   harvested))

(defn- non-empty-slice?
  "Skip emitting a wrap for a scope whose slice has no datums at all."
  [slice]
  (boolean
    (or (seq (:progress-notes      slice))
        (seq (:completed-tasks     slice))
        (seq (:git-commits         slice))
        (seq (:recalls             slice))
        (seq (:hivemind-messages   slice))
        (seq (:kanban-activity     slice))
        (seq (:kg-edges-created    slice))
        (seq (:kanban-movements    slice))
        (seq (:memory-ids-created  slice))
        (seq (:memory-ids-accessed slice)))))

(defn- non-empty-umbrella?
  "Skip emitting an umbrella wrap when the umbrella has no payload."
  [hbs]
  (not (bs/umbrella-empty? hbs)))

;; =============================================================================
;; Public API: synthesize-wraps
;; =============================================================================

(defn synthesize-wraps
  "Fan-out wrap synthesis from a HarvestByScope.

   Returns a vector of `{:pid <pid-or-:umbrella> :entry <wrap-entry>}`
   maps. The `:entry` map has the same shape produced by the legacy
   `summarize-session-progress` (`{:type :note :content :tags :duration}`),
   one per touched scope plus one umbrella when there are cross-cutting
   facts. Empty slices are skipped; a session that produced no harvest at
   all returns `[]`.

   Step-6: every returned `:entry` carries an explicit scope tag prepended
   to its `:tags`. Step-8 wires this output into `handle-native-wrap` for
   the IMemoryStore add fan-out."
  [hbs]
  (let [meta-keys (select-keys hbs [:session :directory :agent-id])
        meta-with-scalars (merge meta-keys
                                  (select-keys (:umbrella hbs)
                                               [:session-timing :session-temporal]))
        per-scope-results
        (for [pid   (sort (bs/scope-pids hbs))
              :let  [slice (get-in hbs [:by-scope pid])]
              :when (non-empty-slice? slice)
              :let  [harvested (slice->harvested pid slice meta-with-scalars)
                     entry     (synthesise-one harvested)]
              :when entry]
          {:pid pid :entry (with-scope-tag entry pid)})

        umbrella-result
        (when (non-empty-umbrella? hbs)
          (let [harvested (umbrella->harvested hbs meta-with-scalars)
                entry     (synthesise-one harvested)]
            (when entry
              {:pid bs/umbrella-sentinel
               :entry (with-scope-tag entry bs/umbrella-sentinel)})))]

    (let [results (vec (cond-> per-scope-results
                         umbrella-result (concat [umbrella-result])))]
      (log/info "synthesize-wraps:"
                "scopes-emitted:" (count per-scope-results)
                "umbrella?" (some? umbrella-result)
                "total:" (count results))
      results)))