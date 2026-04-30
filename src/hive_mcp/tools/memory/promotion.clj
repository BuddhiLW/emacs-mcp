(ns hive-mcp.tools.memory.promotion
  "Cross-pollination promotion handlers for memory entries.

   Extracted from lifecycle.clj for CC reduction. Contains:
   - handle-xpoll-promote: MCP handler for xpoll scanning
   - run-xpoll-cycle!: Hook handler for crystallize-session
   - Pure helpers: walk-tiers, build-xpoll-plan"
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.protocols.memory :as mem-proto]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pure Calculation Helpers
;; =============================================================================

(defn walk-tiers
  "Walk duration up by max-tiers steps. Returns {:final-duration :tiers-promoted}.
   Pure function — no side effects, fully testable.

   (walk-tiers \"short\" 2) => {:final-duration \"long\" :tiers-promoted 2}
   (walk-tiers \"permanent\" 3) => {:final-duration \"permanent\" :tiers-promoted 0}
   (walk-tiers \"long\" 5) => {:final-duration \"permanent\" :tiers-promoted 1}"
  [start-duration max-tiers]
  (loop [duration start-duration
         promoted 0]
    (if (>= promoted max-tiers)
      {:final-duration duration :tiers-promoted promoted}
      (let [{:keys [new-duration changed?]} (dur/shift-duration duration +1)]
        (if changed?
          (recur new-duration (inc promoted))
          {:final-duration duration :tiers-promoted promoted})))))

(defn build-xpoll-plan
  "Build a cross-pollination plan for a single entry. Pure function.
   Returns plan map with project cross-references and tier calculation."
  [entry]
  (let [xpoll-projects (crystal/extract-xpoll-projects entry)
        tiers (crystal/scope-tiers entry)
        {:keys [final-duration]} (walk-tiers (str (:duration entry)) tiers)]
    {:id                  (:id entry)
     :type                (:type entry)
     :duration            (:duration entry)
     :cross_projects      (vec xpoll-projects)
     :cross_project_count (count xpoll-projects)
     :tiers_to_promote    tiers
     :next_duration       final-duration}))

;; =============================================================================
;; Side-Effecting Promotion
;; =============================================================================

(defn- persist-promotion!
  "Persist a tier promotion to Chroma. Returns result map."
  [id original-duration final-duration tiers-promoted]
  (let [expires (dur/calculate-expires final-duration)]
    (with-resilience
      (mem-proto/update-entry! (mem-proto/get-store) id {:duration final-duration
                                                         :expires (or expires "")}))
    ;; Temporal dual-write: record duration promotion
    (temporal/record-mutation-silent!
     {:entry-id       id
      :op             :promote
      :data           {:old-duration original-duration
                       :new-duration final-duration
                       :tiers-promoted tiers-promoted}
      :previous-value {:duration original-duration}})
    (log/info "Cross-pollination auto-promote:" id
              original-duration "->" final-duration
              "(tiers:" tiers-promoted ")")
    {:id id
     :promoted true
     :old_duration original-duration
     :new_duration final-duration
     :tiers_promoted tiers-promoted}))

(defn promote-entry-by-tiers!
  "Promote an entry by N duration tiers. Uses walk-tiers for pure calculation,
   then persists if any change occurred."
  [entry tiers]
  (let [id (:id entry)
        original (:duration entry)
        {:keys [final-duration tiers-promoted]} (walk-tiers original tiers)]
    (if (zero? tiers-promoted)
      {:id id :promoted false :duration original}
      (persist-promotion! id original final-duration tiers-promoted))))

;; =============================================================================
;; MCP Handler
;; =============================================================================

(defn handle-xpoll-promote
  "Scan and auto-promote entries accessed across multiple projects."
  [{:keys [_directory min_projects limit dry_run]}]
  (log/info "mcp-memory-xpoll-promote: scanning for cross-project knowledge")
  (with-store
    (with-resilience
      (let [limit-val   (or (some-> limit int) 500)
            opts        {:min-projects (or (some-> min_projects int) 2)}
            store (mem-proto/get-store)
            all-entries (mem-proto/query-entries store {:limit limit-val
                                                        :include-expired? false})
            candidates  (filter #(crystal/scope-eligible? % opts) all-entries)
            plans       (mapv build-xpoll-plan candidates)
            results     (if dry_run
                          plans
                          (vec (for [plan plans
                                     :let [entry (mem-proto/get-entry store (:id plan))]
                                     :when entry]
                                 (promote-entry-by-tiers! entry (:tiers_to_promote plan)))))
            promoted-ct (count (filter :promoted results))
            plan-keys   [:id :duration :cross_projects :cross_project_count :next_duration]
            result-keys [:id :promoted :old_duration :new_duration :tiers_promoted]
            summary     {:promoted       (if dry_run 0 promoted-ct)
                         :candidates     (count candidates)
                         :total_scanned  (count all-entries)
                         :dry_run        (boolean dry_run)
                         :entries        (mapv #(select-keys % (if dry_run plan-keys result-keys))
                                               results)}]
        (log/info "mcp-memory-xpoll-promote:"
                  (:promoted summary) "promoted of" (:candidates summary)
                  "candidates from" (:total_scanned summary) "entries"
                  (when dry_run "(dry run)"))
        (mcp-json summary)))))

;; =============================================================================
;; Hook Handler (crystallize-session)
;; =============================================================================

(defn run-xpoll-cycle!
  "Run bounded xpoll auto-promotion cycle for crystallize-session hooks."
  [{:keys [_directory limit]}]
  (try
    (if-not (mem-proto/store-set?)
      {:promoted 0 :candidates 0 :total-scanned 0 :error "store-not-configured"}
      (let [limit-val     (or (some-> limit int) 100)
            store         (mem-proto/get-store)
            all-entries   (with-resilience
                            (mem-proto/query-entries store {:limit limit-val
                                                            :include-expired? false}))
            candidates    (filter #(crystal/scope-eligible? %) all-entries)
            results       (vec (for [entry candidates
                                     :let [tiers (crystal/scope-tiers entry)]
                                     :when (pos? tiers)]
                                 (try
                                   (promote-entry-by-tiers! entry tiers)
                                   (catch Exception e
                                     (log/warn "run-xpoll-cycle!: failed to promote" (:id entry)
                                               ":" (.getMessage e))
                                     {:id (:id entry) :promoted false :error (.getMessage e)}))))
            promoted-ct   (count (filter :promoted results))]
        (when (pos? promoted-ct)
          (log/info "Xpoll auto-promote:" promoted-ct
                    "of" (count candidates) "candidates promoted"))
        {:promoted promoted-ct
         :candidates (count candidates)
         :total-scanned (count all-entries)
         :entries (mapv #(select-keys % [:id :promoted :old_duration :new_duration :tiers_promoted])
                        results)}))
    (catch Exception e
      (log/warn "run-xpoll-cycle! failed (non-blocking):" (.getMessage e))
      {:error (.getMessage e) :promoted 0 :candidates 0 :total-scanned 0})))
