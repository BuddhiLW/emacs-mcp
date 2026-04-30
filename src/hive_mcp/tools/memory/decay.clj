(ns hive-mcp.tools.memory.decay
  "Staleness decay handlers for memory entries.

   Extracted from lifecycle.clj for CC reduction. Contains:
   - handle-decay: MCP handler for scheduled decay
   - run-decay-cycle!: Hook handler for crystallize-session
   - Pure helpers: query-scoped-entries, build-decay-plan"
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.agent.context :as ctx]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pure Calculation Helpers
;; =============================================================================

(defn query-scoped-entries
  "Query Chroma entries filtered by project scope.
   Shared pipeline for decay and promotion handlers."
  [directory limit-val]
  (let [all-entries (with-resilience
                      (mem-proto/query-entries (mem-proto/get-store)
                                               {:limit limit-val
                                                :include-expired? false}))
        project-id (scope/get-current-project-id directory)
        scope-filter (scope/make-scope-tag project-id)]
    (filterv #(scope/matches-scope? % scope-filter) all-entries)))

(defn build-decay-plan
  "Build a decay plan for a single entry. Pure function.
   Returns plan map if entry should decay, nil otherwise."
  [entry opts]
  (let [delta (crystal/calculate-decay-delta entry opts)
        old-beta (get entry :staleness-beta 1)]
    (when (pos? delta)
      {:id (:id entry)
       :type (:type entry)
       :duration (:duration entry)
       :access-count (:access-count entry)
       :old-beta old-beta
       :delta delta
       :new-beta (+ old-beta delta)})))

;; =============================================================================
;; Side-Effecting Decay
;; =============================================================================

(defn apply-decay!
  "Apply staleness decay to a single entry. Returns decay result or nil."
  [entry opts]
  (when (crystal/decay-candidate? entry opts)
    (when-let [plan (build-decay-plan entry opts)]
      (with-resilience
        (mem-proto/update-staleness! (mem-proto/get-store) (:id entry)
                                     {:beta (:new-beta plan)
                                      :source :time-decay}))
      ;; Temporal dual-write: record decay event
      (temporal/record-mutation-silent!
       {:entry-id   (:id entry)
        :op         :decay
        :data       {:delta (:delta plan)
                     :old-beta (:old-beta plan)
                     :new-beta (:new-beta plan)}
        :project-id (:project-id entry)})
      (select-keys plan [:id :delta :old-beta :new-beta]))))

(defn- resolve-directory
  "Resolve directory with fallback to current context."
  [directory]
  (or directory (ctx/current-directory)))

(defn- coerce-limit
  "Coerce limit to int with default."
  [limit default]
  (or (some-> limit int) default))

;; =============================================================================
;; MCP Handler
;; =============================================================================

(defn handle-decay
  "Run scheduled staleness decay on memory entries."
  [{:keys [directory access_threshold recency_days limit dry_run]}]
  (log/info "mcp-memory-decay: starting scheduled decay cycle")
  (with-store
    (let [directory     (resolve-directory directory)
          limit-val     (coerce-limit limit 200)
          opts          {:access-threshold (coerce-limit access_threshold 3)
                         :recency-days     (coerce-limit recency_days 7)}
          scoped        (query-scoped-entries directory limit-val)
          candidates    (filter #(crystal/decay-candidate? % opts) scoped)
          plans         (keep #(build-decay-plan % opts) candidates)
          applied       (if dry_run
                          (vec plans)
                          (vec (keep #(apply-decay!
                                       (with-resilience
                                         (mem-proto/get-entry (mem-proto/get-store) (:id %)))
                                       opts)
                                     plans)))
          summary       {:decayed        (count applied)
                         :skipped        (- (count scoped) (count (vec plans)))
                         :total_scanned  (count scoped)
                         :dry_run        (boolean dry_run)
                         :entries        (mapv #(select-keys % [:id :delta :new-beta]) applied)}]
      (log/info "mcp-memory-decay: decayed" (:decayed summary)
                "of" (:total_scanned summary) "entries"
                (when dry_run "(dry run)"))
      (mcp-json summary))))

;; =============================================================================
;; Hook Handler (crystallize-session)
;; =============================================================================

(defn run-decay-cycle!
  "Bounded, idempotent decay cycle for crystallize-session hooks."
  [{:keys [directory limit]}]
  (try
    (let [directory       (resolve-directory directory)
          limit-val       (coerce-limit limit 50)
          cleanup-result  (try
                            (with-resilience
                              (mem-proto/cleanup-expired! (mem-proto/get-store)))
                            (catch Exception e
                              {:count 0 :deleted-ids []
                               :error (.getMessage e)}))
          scoped          (query-scoped-entries directory limit-val)
          opts            {:access-threshold 3 :recency-days 7}
          applied         (vec (keep #(apply-decay! % opts) scoped))]
      (cond-> {:decayed       (count applied)
               :expired       (get cleanup-result :count 0)
               :total-scanned (count scoped)
               :error         nil}
        (:error cleanup-result)
        (assoc :cleanup-error (:error cleanup-result))))
    (catch Exception e
      {:decayed 0 :expired 0 :total-scanned 0
       :error (.getMessage e)})))
