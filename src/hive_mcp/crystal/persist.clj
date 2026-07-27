(ns hive-mcp.crystal.persist
  "Per-scope wrap persistence — Step 8 of the per-scope wrap plan
   (memory `20260504173159-46dc47f1`).

   Takes the output of `hive-mcp.crystal.fanout/synthesize-wraps` (a
   vector of `{:pid :entry}` maps) and writes one IMemoryStore entry per
   element. The `:project-id` is resolved from the `:pid` field at the
   boundary — the IMemoryStore add-entry! contract honors `:project-id`
   when present in the entry map (see hive-milvus/record.clj +
   store/schema.clj), so this layer can declare scope **explicitly**
   without going through the pwd-derivation in `tools/memory/crud/write/do-add!`.

   ## Why a separate persist layer (vs. routing through `crud/write/handle-add`)

   `crud/write/do-add!` derives project-id from a single `directory`
   argument; there is no caller-supplied override. Wraps are also
   single-shot inserts — no dedup needed, no KG edges threaded from
   here, no plan-gate validation. The wrap is already a synthesised
   note. Routing through handle-add would either (a) require a new
   `:project-id` opt across the whole crud/write surface (out of
   scope) or (b) trick the resolver via per-pid directories (fragile).

   This namespace cuts the seam by going direct-to-protocol with an
   explicit `:project-id` per entry. Other write characteristics
   (resilience, content-hash, expires) are duplicated minimally so the
   wrap-write path stays self-contained.

   Pure orchestration over `mem-proto/add-entry!` — the only IO seam.
   Tests can stub the protocol via `with-redefs` to assert per-pid
   write-counts and explicit-pid honoring."
  (:require [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.memory.duration :as dur]
            [taoensso.timbre :as log]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pid → :project-id resolution
;; =============================================================================

(def ^:const umbrella-project-id
  "The project-id stamped on the umbrella wrap. NOT a real pid — it's a
   sentinel scope that the catchup reader's `scope:multi-project` tag
   surfaces independent of any project hierarchy. Distinct from the
   :umbrella keyword used inside HarvestByScope for routing."
  "multi-project")

(defn pid->project-id
  "Resolve fan-out pid → IMemoryStore :project-id field value.
   - Real pid string → unchanged
   - umbrella sentinel keyword → `umbrella-project-id` string"
  [pid]
  (if (= pid bs/umbrella-sentinel)
    umbrella-project-id
    (str pid)))

;; =============================================================================
;; Single-entry persist
;; =============================================================================

(defn- normalize-entry
  "Project the synth entry into an IMemoryStore-add-entry! ready map.

   - Resolves :project-id from `pid` (no pwd-derivation). A blank pid is
     left unstamped so the store's own resolution applies.
   - Stamps :duration (default :short for wraps).
   - Stamps :expires from the duration (legacy contract).
   - Coerces :type to string (mem-proto contract).
   - Preserves :tags exactly — the scope tag was already prepended in
     step 6 by `fanout/with-scope-tag`."
  [pid entry]
  (let [duration-str (name (or (:duration entry) :short))
        type-str     (name (or (:type entry) :note))
        project-id   (pid->project-id pid)]
    (cond-> (-> entry
                (assoc :type type-str
                       :duration duration-str
                       :expires (dur/calculate-expires duration-str))
                (update :tags #(vec (or % []))))
      (not (str/blank? project-id)) (assoc :project-id project-id))))

(defn- persist-one!
  "Write a single normalised entry through `mem-proto/add-entry!`.
   Returns `{:pid :id :success?}` (id may be nil on failure)."
  [store {:keys [pid entry] :as _wrap}]
  (try
    (let [normalized (normalize-entry pid entry)
          raw-id     (mem-proto/add-entry! store normalized)
          id?        (and (string? raw-id) (not (str/blank? raw-id)))]
      (log/info "wrap-persist:" {:pid pid :project-id (:project-id normalized)
                                  :id raw-id :ok? id?})
      {:pid pid
       :project-id (:project-id normalized)
       :id (when id? raw-id)
       :success? id?
       :error (when-not id?
                (str "add-entry! returned non-id: " (pr-str raw-id)))})
    (catch Throwable t
      (log/warn "wrap-persist failed for pid" pid ":" (ex-message t))
      {:pid pid :project-id (pid->project-id pid)
       :id nil :success? false :error (ex-message t)})))

;; =============================================================================
;; Public API: persist-wraps!
;; =============================================================================

(defn persist-wraps!
  "Iterate `wraps` (output of `fanout/synthesize-wraps`) and write each
   to the IMemoryStore. Returns a result map:

   ```
   {:total      <count>
    :persisted  <count of success?>
    :failed     <count of !success?>
    :results    [{:pid :project-id :id :success? :error?} ...]}
   ```

   Optional `store-key` (default `:default`) selects the multi-store
   slot — wraps land in the same backend that holds session-summary
   notes today.

   Boundary contract (step 8): `:project-id` is set explicitly from the
   wrap's `:pid` — the IMemoryStore add path will not derive it from
   pwd. Combined with the explicit scope tag from step 6, this fully
   eliminates the bleed at the write layer."
  ([wraps] (persist-wraps! wraps :default))
  ([wraps store-key]
   (if-not (mem-proto/store-set?)
     {:total (count wraps) :persisted 0 :failed (count wraps)
      :results []
      :error "IMemoryStore not registered"}
     (let [store (mem-proto/get-store store-key)
           results (mapv (partial persist-one! store) wraps)]
       {:total     (count results)
        :persisted (count (filter :success? results))
        :failed    (count (remove :success? results))
        :results   results}))))