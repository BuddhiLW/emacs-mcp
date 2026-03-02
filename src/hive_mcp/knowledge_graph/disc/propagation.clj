(ns hive-mcp.knowledge-graph.disc.propagation
  "Time decay and transitive staleness propagation for disc entities."
  (:require [hive-mcp.knowledge-graph.queries :as queries]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.disc.crud :as crud]
            [hive-mcp.knowledge-graph.disc.volatility :as vol]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- compute-decay
  "Compute time decay for a single disc (pure — no side effects).
   Returns {:path ... :updates {...} :status :ok} or {:status :error ...}."
  [disc]
  (let [path (:disc/path disc)
        result (r/guard Exception {:path path :status :error :error "decay-failed"}
                        (let [decayed (vol/apply-time-decay disc)]
                          {:path path
                           :updates {:disc/certainty-beta (:disc/certainty-beta decayed)
                                     :disc/last-observation (:disc/last-observation decayed)}
                           :status :ok}))]
    result))

(defn apply-time-decay-to-all-discs!
  "Apply time-based certainty decay to all disc entities.
   IO Sandwich: read all -> compute decay (pure) -> batch-write once."
  [& {:keys [project-id]}]
  (let [discs (crud/get-all-discs :project-id project-id)
        ;; Phase 1: Pure computation — no DB writes
        computed (mapv compute-decay discs)
        {oks :ok errs :error} (group-by :status computed)
        ;; Phase 2: Single batch write
        batch-result (when (seq oks)
                       (crud/batch-update-discs!
                        (mapv (juxt :path :updates) oks)))
        results {:updated (or (:updated batch-result) 0)
                 :skipped 0
                 :errors  (count errs)}]
    (when (seq errs)
      (log/warn "Decay computation errors" {:count (count errs)
                                            :sample (take 3 (map (fn [e] (select-keys e [:path :error])) errs))}))
    (log/info "Time decay applied to discs" results)
    results))

(defn- apply-transitive-staleness!
  "Apply staleness to a single Chroma entry with depth-based decay."
  [entry-id base-staleness depth staleness-source]
  (let [beta-increment (* base-staleness
                          (Math/pow vol/staleness-decay-factor depth))]
    (if (< beta-increment vol/staleness-min-threshold)
      {:status :skipped :entry-id entry-id :beta-increment 0}
      (let [result (r/try-effect* :disc/staleness-propagation
                                  (let [store (mem-proto/get-store)
                                        current-entry (mem-proto/get-entry store entry-id)
                                        current-beta (or (:staleness-beta current-entry) 1.0)
                                        new-beta (+ current-beta beta-increment)]
                                    (mem-proto/update-entry! store entry-id
                                                             {:staleness-beta new-beta
                                                              :staleness-depth depth
                                                              :staleness-source (name staleness-source)})
                                    (log/debug "Applied transitive staleness"
                                               {:entry-id entry-id :depth depth :beta-increment beta-increment})
                                    {:status :updated :entry-id entry-id :beta-increment beta-increment}))]
        (if (r/ok? result)
          (:ok result)
          (do (log/warn "Failed to apply staleness to entry"
                        {:entry-id entry-id :error (:message result)})
              {:status :error :entry-id entry-id :error (:message result)}))))))

(defn- tally-status!
  "Increment the counter in results-atom matching the staleness status.
   Maps :updated -> counter-key (default :propagated), :skipped, :error."
  [results-atom {:keys [status]} counter-key]
  (case status
    :updated (swap! results-atom update counter-key inc)
    :skipped (swap! results-atom update :skipped inc)
    :error   (swap! results-atom update :errors inc)))

(defn- propagate-to-dependents!
  "Apply transitive staleness to a seq of entry-ids at a given depth."
  [entry-ids base-staleness depth staleness-source results-atom counter-key]
  (doseq [eid entry-ids]
    (let [result (apply-transitive-staleness! eid base-staleness depth staleness-source)]
      (tally-status! results-atom result counter-key))))

(defn- propagate-impact!
  "Propagate staleness through impact analysis for a single entry."
  [entry-id base-staleness staleness-source results]
  (let [impact (r/guard Exception nil
                        (queries/impact-analysis entry-id
                                                 {:max-depth vol/staleness-max-depth}))]
    (if (nil? impact)
      (do (log/warn "Impact analysis failed for entry" {:entry-id entry-id})
          (swap! results update :errors inc))
      (let [{:keys [direct transitive]} impact]
        (propagate-to-dependents! direct base-staleness 1 staleness-source results :propagated)
        (propagate-to-dependents! transitive base-staleness 2 staleness-source results :propagated)))))

(defn propagate-staleness!
  "Propagate staleness from a disc to dependent entries via KG edges."
  [disc-path base-staleness staleness-source]
  (let [outer (r/guard Exception {:propagated 0 :skipped 0 :errors 1 :grounded 0}
                       (let [grounded-entries (mem-proto/query-entries (mem-proto/get-store)
                                                                       {:grounded-from disc-path})
                             results (atom {:propagated 0 :skipped 0 :errors 0 :grounded 0})]
                         (doseq [entry grounded-entries]
                           (let [entry-id (:id entry)
                                 result (apply-transitive-staleness! entry-id base-staleness 0 staleness-source)]
                             (case (:status result)
                               :updated (swap! results update :grounded inc)
                               :skipped (swap! results update :skipped inc)
                               :error (swap! results update :errors inc))
                             (propagate-impact! entry-id base-staleness staleness-source results)))
                         (log/info "Staleness propagation complete"
                                   {:disc-path disc-path :results @results})
                         @results))]
    (when-let [err (::r/error (meta outer))]
      (log/warn "Failed to propagate staleness"
                {:disc-path disc-path :error (:message err)}))
    outer))
