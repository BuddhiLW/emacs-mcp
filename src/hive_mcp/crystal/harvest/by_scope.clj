(ns hive-mcp.crystal.harvest.by-scope
  "HarvestByScope ADT — pure data shape for per-scope-partitioned wrap harvest.

   Single-scope harvest collapses session activity into one project-id derived
   from pwd. That loses information whenever a session touches more than one
   project. This namespace defines the post-partition shape that downstream
   synthesis fans out over.

   Step 1 of the per-scope wrap emission plan
   (memory `20260504173159-46dc47f1`).

   ## Shape

   ```
   {:by-scope {<pid> ScopeSlice ...}   ; one entry per touched project
    :umbrella UmbrellaSlice            ; cross-cutting facts (cross-pid edges,
                                       ; multi-project decisions, whole-session
                                       ; metrics that don't belong to any one pid)
    :session   <session-tag>           ; carried-over session-level metadata
    :directory <abs-path>
    :agent-id  <id>
    :errors    [<harvest-error> ...]}
   ```

   `<pid>` is a real project-id string. Sentinel `:umbrella` (keyword) is
   reserved at the per-source pid-attribution layer (step 2) for data that
   has no per-project home and must flow into UmbrellaSlice rather than a
   ScopeSlice.

   ## CPPB

   - **Collect**: per-source pid attribution (step 2) — flat tagged stream
   - **Promote**: `partition-by-scope` (step 3) — flat → HarvestByScope
   - **Pipeline**: `synthesize-wraps` (step 5) — HarvestByScope → [wrap-entry...]
   - **Boundary**: writer fan-out (step 8) — entries → IMemoryStore

   This namespace is pure: no IO, no protocols, no log calls. Just data
   shapes, predicates, constructors. Trifecta tests in
   `hive-mcp.crystal.harvest.by-scope-test`."
  (:require [malli.core :as m]
            [malli.error :as me]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Sentinels and predicates
;; =============================================================================

(def umbrella-sentinel
  "Keyword used at per-source attribution to route data into UmbrellaSlice
   instead of a per-pid ScopeSlice. Reserved — never a valid project-id."
  :umbrella)

(def ^:private reserved-pid-strings
  "String pids that must NOT appear as keys in :by-scope. They either name
   the umbrella bucket itself or denote a non-project scope sentinel."
  #{"umbrella" "multi-project" ""})

(defn valid-pid?
  "True if `s` is a usable project-id string for :by-scope keys.

   A valid pid is a non-blank string that is not the umbrella sentinel,
   not the multi-project label, and does not start with the scope-tag
   prefix (those are tag literals, not pids)."
  [s]
  (and (string? s)
       (not (reserved-pid-strings s))
       (not (clojure.string/starts-with? s "scope:"))))

;; =============================================================================
;; Schemas
;; =============================================================================

(def ScopeSlice
  "Per-scope slice — same conceptual shape as the legacy monolithic harvest
   result but constrained to one project's activity.

   All fields default to empty so a slice with no activity in a given
   dimension serialises cleanly. The shape mirrors what `synthesize-wrap`
   already consumes, so step-5 fan-out can call the existing synthesiser
   per slice without translation."
  [:map {:closed false}
   [:progress-notes      {:default []} [:sequential :map]]
   [:completed-tasks     {:default []} [:sequential :map]]
   [:git-commits         {:default []} [:sequential :string]]
   [:recalls             {:default {}} [:map-of :string :any]]
   [:hivemind-messages   {:default []} [:sequential :map]]
   [:kanban-activity     {:default {}} [:map-of :keyword :any]]
   [:kg-edges-created    {:default {}} [:map-of :keyword :any]]
   [:kanban-movements    {:default {}} [:map-of :keyword :any]]
   [:memory-ids-created  {:default []} [:sequential :any]]
   [:memory-ids-accessed {:default []} [:sequential :string]]])

(def UmbrellaSlice
  "Cross-cutting harvest facts.

   - `:cross-pid-edges`         — KG edges whose endpoints' scopes differ.
   - `:cross-cutting-decisions` — decisions/conventions tagged
                                  scope:multi-project at the source.
   - `:session-timing`          — whole-session timing (single value).
   - `:session-temporal`        — whole-session temporal metadata.
   - `:hivemind-shouts-global`  — shouts not attributable to a project."
  [:map {:closed false}
   [:cross-pid-edges         {:default []}        [:sequential :map]]
   [:cross-cutting-decisions {:default []}        [:sequential :map]]
   [:session-timing          {:optional true}     [:maybe :map]]
   [:session-temporal        {:optional true}     [:maybe :map]]
   [:hivemind-shouts-global  {:default []}        [:sequential :map]]])

(def HarvestByScope
  "Top-level shape returned by step-3 partitioner. Consumed by step-5
   synthesise fan-out."
  [:map {:closed false}
   [:by-scope  [:map-of [:and :string [:fn {:error/message "must be a valid project-id"}
                                       valid-pid?]]
                        ScopeSlice]]
   [:umbrella  UmbrellaSlice]
   [:session   {:optional true} [:maybe :string]]
   [:directory {:optional true} [:maybe :string]]
   [:agent-id  {:optional true} [:maybe :string]]
   [:errors    {:default []}    [:sequential :map]]])

;; =============================================================================
;; Validators
;; =============================================================================

(defn valid?
  "Pure structural validation. Returns boolean."
  [hbs]
  (m/validate HarvestByScope hbs))

(defn explain
  "Returns malli error map (or nil if valid) for debugging shape failures."
  [hbs]
  (some-> (m/explain HarvestByScope hbs) (me/humanize)))

(defn scope-pids
  "Set of project-ids present in :by-scope. Excludes umbrella by construction."
  [hbs]
  (set (keys (:by-scope hbs))))

(defn umbrella-empty?
  "True when umbrella has no cross-cutting payload (only defaults)."
  [hbs]
  (let [u (:umbrella hbs)]
    (and (empty? (:cross-pid-edges u))
         (empty? (:cross-cutting-decisions u))
         (empty? (:hivemind-shouts-global u))
         (nil? (:session-timing u))
         (nil? (:session-temporal u)))))

;; =============================================================================
;; Constructors
;; =============================================================================

(def empty-scope-slice
  "Zero-activity ScopeSlice. Use as accumulator seed when partitioning a
   per-source datum into a freshly-discovered pid bucket."
  {:progress-notes      []
   :completed-tasks     []
   :git-commits         []
   :recalls             {}
   :hivemind-messages   []
   :kanban-activity     {}
   :kg-edges-created    {}
   :kanban-movements    {}
   :memory-ids-created  []
   :memory-ids-accessed []})

(def empty-umbrella-slice
  "Zero-activity UmbrellaSlice."
  {:cross-pid-edges         []
   :cross-cutting-decisions []
   :session-timing          nil
   :session-temporal        nil
   :hivemind-shouts-global  []})

(defn empty-by-scope
  "Zero-activity HarvestByScope.

   Carries optional session-level metadata (`:session`, `:directory`,
   `:agent-id`) so the partitioner can hand back a still-validating shell
   even when no harvest source produced any datum."
  ([] (empty-by-scope nil))
  ([{:keys [session directory agent-id]}]
   (cond-> {:by-scope {}
            :umbrella empty-umbrella-slice
            :errors   []}
     session   (assoc :session session)
     directory (assoc :directory directory)
     agent-id  (assoc :agent-id agent-id))))

(defn assoc-scope
  "Set the slice for `pid`. Replaces any existing slice. Idempotent on
   identical input. Throws (via malli) if `pid` is not a valid project-id."
  [hbs pid slice]
  {:pre [(valid-pid? pid)]}
  (assoc-in hbs [:by-scope pid] slice))

(defn merge-umbrella
  "Deep-merge `extra` into the umbrella slice. Sequential fields are
   concatenated; scalar fields prefer `extra` when non-nil."
  [hbs extra]
  (let [seq-keys     [:cross-pid-edges :cross-cutting-decisions :hivemind-shouts-global]
        scalar-keys  [:session-timing :session-temporal]
        umb          (:umbrella hbs)
        merged-seqs  (reduce (fn [m k]
                               (assoc m k (vec (concat (get umb k []) (get extra k [])))))
                             {}
                             seq-keys)
        merged-scals (reduce (fn [m k]
                               (assoc m k (or (get extra k) (get umb k))))
                             {}
                             scalar-keys)]
    (assoc hbs :umbrella (merge umb merged-seqs merged-scals))))

(defn record-error
  "Append a harvest error map to `:errors`."
  [hbs err]
  (update hbs :errors (fnil conj []) err))