(ns hive-mcp.crystal.harvest.partition
  "Pure partitioner — converts attribution output into a `HarvestByScope`.

   Step 3 of the per-scope wrap emission plan
   (memory `20260504173159-46dc47f1`).

   ## Input
   `attribute-harvest` output:
   ```
   {:by-source        {<slice-key> [{:pid <pid-or-:umbrella> :datum <orig>} ...]}
    :umbrella-scalars {:session-timing ... :session-temporal ...}
    :session :directory :agent-id :errors}
   ```

   ## Output
   `HarvestByScope` (see `hive-mcp.crystal.harvest.by-scope`):
   ```
   {:by-scope {<pid> ScopeSlice ...}
    :umbrella UmbrellaSlice
    :session :directory :agent-id :errors}
   ```

   ## Routing rules
   - Strong-attribution datum (`:pid <real-pid>`) → ScopeSlice for that pid
     under its slice-key.
   - Weak-attribution / unattributable datum (`:pid :umbrella`) → UmbrellaSlice
     using slice-key-aware mapping:
     - `:kg-edges-created`   → `:cross-pid-edges`
     - `:hivemind-messages`  → `:hivemind-shouts-global`
     - everything else       → `:cross-cutting-decisions`
   - `:umbrella-scalars` merges directly into UmbrellaSlice scalar fields.

   ## Invariant (property-tested)
   For every well-formed input, the count of datums in the input equals
   `(scope-datum-count + umbrella-datum-count)` in the output. No datum
   is dropped or duplicated.

   Pure. No IO, no protocols, no logging."
  (:require [hive-mcp.crystal.harvest.by-scope :as bs]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Slice-key → umbrella-field mapping for unattributable datums
;; =============================================================================

(def ^:private slice-key->umbrella-field
  "When a datum lands in the umbrella, route it to a typed UmbrellaSlice
   field by source slice-key. Anything not listed falls into the catch-all
   :cross-cutting-decisions bucket."
  {:kg-edges-created  :cross-pid-edges
   :hivemind-messages :hivemind-shouts-global})

(defn- umbrella-field-for
  [slice-key]
  (or (slice-key->umbrella-field slice-key)
      :cross-cutting-decisions))

;; =============================================================================
;; Reducers
;; =============================================================================

(defn- ensure-slice
  "Create an empty ScopeSlice for `pid` if missing. Returns the updated
   HarvestByScope."
  [hbs pid]
  (cond-> hbs
    (not (contains? (:by-scope hbs) pid))
    (assoc-in [:by-scope pid] bs/empty-scope-slice)))

(defn- conj-into-scope
  "Append `datum` to the scope-slice for `pid` under `slice-key`."
  [hbs pid slice-key datum]
  (-> hbs
      (ensure-slice pid)
      (update-in [:by-scope pid slice-key] (fnil conj []) datum)))

(defn- conj-into-umbrella
  "Append `datum` to the umbrella under the field implied by `slice-key`."
  [hbs slice-key datum]
  (let [field (umbrella-field-for slice-key)]
    (update-in hbs [:umbrella field] (fnil conj []) datum)))

(defn- route-attributed-datum
  "Route a single `{:pid :datum}` map into either a ScopeSlice or the
   umbrella based on its pid."
  [hbs slice-key {:keys [pid datum]}]
  (if (= pid bs/umbrella-sentinel)
    (conj-into-umbrella hbs slice-key datum)
    (conj-into-scope hbs pid slice-key datum)))

(defn- absorb-source
  "Fold every attributed datum from one source into the HarvestByScope."
  [hbs [slice-key attributed-datums]]
  (reduce (fn [acc d] (route-attributed-datum acc slice-key d))
          hbs
          (or attributed-datums [])))

(defn- absorb-umbrella-scalars
  "Merge whole-session scalars (timing, temporal) into the umbrella.
   Uses `bs/merge-umbrella` so semantics match step-1's contract."
  [hbs umbrella-scalars]
  (if (seq umbrella-scalars)
    (bs/merge-umbrella hbs umbrella-scalars)
    hbs))

;; =============================================================================
;; Public API
;; =============================================================================

(defn partition-harvest-by-scope
  "Pure transform: attribution output → HarvestByScope.

   Throws nothing — malformed inputs produce a well-formed empty
   HarvestByScope (defensive, since attribution may legitimately produce
   `{}` in the empty-session case)."
  [{:keys [by-source umbrella-scalars session directory agent-id errors]
    :as _attribution-result}]
  (let [seed (cond-> (bs/empty-by-scope {:session   session
                                         :directory directory
                                         :agent-id  agent-id})
               (seq errors) (assoc :errors (vec errors)))]
    (-> (reduce absorb-source seed (or by-source {}))
        (absorb-umbrella-scalars (or umbrella-scalars {})))))

;; =============================================================================
;; Inspection helpers (pure — useful for tests + debugging)
;; =============================================================================

(defn scope-datum-count
  "Total number of datums distributed across all ScopeSlices in `hbs`."
  [hbs]
  (transduce (mapcat (fn [[_pid slice]]
                       (for [[k v] slice
                             :when (sequential? v)]
                         (count v))))
             +
             0
             (:by-scope hbs)))

(defn umbrella-datum-count
  "Total number of datums in the UmbrellaSlice's three sequential fields.
   Excludes scalar fields (session-timing, session-temporal)."
  [hbs]
  (let [u (:umbrella hbs)]
    (+ (count (:cross-pid-edges u))
       (count (:cross-cutting-decisions u))
       (count (:hivemind-shouts-global u)))))

(defn total-datum-count
  "Sum of scope + umbrella datums. The conservation invariant: this equals
   the total flat-input count from attribution (for any well-formed input)."
  [hbs]
  (+ (scope-datum-count hbs)
     (umbrella-datum-count hbs)))