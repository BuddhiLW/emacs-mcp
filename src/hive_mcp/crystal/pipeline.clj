(ns hive-mcp.crystal.pipeline
  "Pure harvest pipeline with railway-oriented error handling.

   Orchestrates IHarvestSource instances in parallel, collects HarvestOutcomes,
   merges successful results, validates shape, and returns a SynthesisInput map.

   Pipeline (result/ok-> railway):
     harvest-all → merge-outcomes → validate-context → SynthesisInput

   SynthesisInput shape:
     {:hivemind-messages  vec of shout maps
      :kanban-changes     vec of completed-task maps
      :memory-stats       map with :created-count :accessed-count
      :git-commits        vec of commit strings
      :session-timing     map with :session-start :session-end :duration-minutes
      :source-errors      vec of error/timeout descriptors (may be empty)}

   Pure functions — no side effects beyond future execution for parallelism.

   DDD: Application service layer — composes harvest sources into synthesis input."
  (:require [hive-dsl.adt :refer [defadt adt-case]]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IHarvestSource Protocol (inline until crystal/protocol.clj lands — T1)
;; =============================================================================
;; TODO: When protocol.clj is committed, replace with:
;;   (:require [hive-mcp.crystal.protocol :refer [IHarvestSource]])
;; and delete this block.

(defprotocol IHarvestSource
  "A source of harvest data for session crystallization.
   Each source has a unique keyword id and a harvest fn that returns a data map."
  (source-id [this] "Unique keyword identifying this source, e.g. :hivemind, :kanban.")
  (harvest   [this ctx] "Harvest data from this source given context map. Returns a map or throws."))

;; =============================================================================
;; HarvestOutcome ADT
;; =============================================================================

;; Name must stay distinct from crystal.harvest.protocol/HarvestOutcome: defadt's
;; registry is global and keyed by type name, so a duplicate name silently overwrites.
(defadt PipelineOutcome
  "Result of harvesting a single source in the pipeline — closed sum type."
  [:harvest/ok      {:source-id keyword? :data map?}]
  [:harvest/timeout {:source-id keyword? :elapsed-ms number?}]
  [:harvest/error   {:source-id keyword? :message string?}])

;; =============================================================================
;; Harvest Execution
;; =============================================================================

(def ^:private ^:const default-timeout-ms 10000)

(defn harvest-one
  "Run a single IHarvestSource, returning a HarvestOutcome ADT value.
   Catches all exceptions — never throws."
  [source ctx]
  (let [sid (source-id source)]
    (try
      (let [data (harvest source ctx)]
        (pipeline-outcome :harvest/ok {:source-id sid :data (or data {})}))
      (catch Exception e
        (log/warn "harvest-one: source" sid "failed:" (.getMessage e))
        (pipeline-outcome :harvest/error {:source-id sid
                                          :message   (.getMessage e)})))))

(defn harvest-all
  "Run all harvest sources in parallel via futures, returning vec of HarvestOutcomes.
   Each source gets timeout-ms (default 10s) before being marked :harvest/timeout.
   Fault-isolated: one source failing never blocks others."
  ([sources ctx] (harvest-all sources ctx default-timeout-ms))
  ([sources ctx timeout-ms]
   (let [;; Launch all sources in parallel
         tagged-futures (mapv (fn [src]
                                {:source src
                                 :future (future (harvest-one src ctx))
                                 :t0     (System/currentTimeMillis)})
                              sources)
         ;; Collect with timeout
         outcomes (mapv (fn [{:keys [source] :as tf}]
                          (let [sid    (source-id source)
                                result (deref (:future tf) timeout-ms ::timeout)]
                            (if (= result ::timeout)
                              (do (future-cancel (:future tf))
                                  (log/warn "harvest-all: source" sid "timed out after" timeout-ms "ms")
                                  (pipeline-outcome :harvest/timeout
                                                    {:source-id  sid
                                                     :elapsed-ms (double timeout-ms)}))
                              result)))
                        tagged-futures)]
     (log/info "harvest-all:" (count outcomes) "sources collected"
               "(" (count (filter #(= (:adt/variant %) :harvest/ok) outcomes)) "ok"
               (count (filter #(not= (:adt/variant %) :harvest/ok) outcomes)) "failed)")
     outcomes)))

;; =============================================================================
;; Merge Outcomes
;; =============================================================================

(defn merge-outcomes
  "Merge successful HarvestOutcomes into a map keyed by source-id.
   Failed/timed-out outcomes are collected in :source-errors.
   Returns a Result (ok or err)."
  [outcomes]
  (if (empty? outcomes)
    (result/err :pipeline/no-sources {:message "No harvest sources provided"})
    (let [grouped (group-by (fn [o] (if (= (:adt/variant o) :harvest/ok) :ok :fail))
                            outcomes)
          merged  (reduce (fn [acc o]
                            (assoc acc (:source-id o) (:data o)))
                          {} (:ok grouped))
          errors  (mapv (fn [o]
                          (-> (select-keys o [:source-id :message :elapsed-ms])
                              (assoc :outcome-type (:adt/variant o))))
                        (:fail grouped))]
      (result/ok (cond-> merged
                   (seq errors) (assoc :source-errors errors))))))

;; =============================================================================
;; Validate & Transform → SynthesisInput
;; =============================================================================

(def ^:private synthesis-keys
  "Required keys in SynthesisInput output."
  [:hivemind-messages :kanban-changes :memory-stats :git-commits :session-timing])

(defn validate-context
  "Transform merged source data into canonical SynthesisInput shape.
   Extracts known fields from source-keyed data, defaulting missing sources
   to empty values. Returns a Result.

   Source-id → SynthesisInput mapping:
     :hivemind → :hivemind-messages (from :messages)
     :kanban   → :kanban-changes    (from :tasks-completed)
     :memory   → :memory-stats      (passed through)
     :git      → :git-commits       (from :commits)
     :session  → :session-timing    (passed through)"
  [merged]
  (let [input {:hivemind-messages (get-in merged [:hivemind :messages] [])
               :kanban-changes    (get-in merged [:kanban :tasks-completed] [])
               :memory-stats      (or (get merged :memory)
                                      {:created-count 0 :accessed-count 0})
               :git-commits       (get-in merged [:git :commits] [])
               :session-timing    (or (get merged :session)
                                      {:session-start nil
                                       :session-end   nil
                                       :duration-minutes 0})
               :source-errors     (get merged :source-errors [])}]
    (result/ok input)))

;; =============================================================================
;; Pipeline Composition (result/ok-> railway)
;; =============================================================================

(defn run-pipeline
  "Execute the full harvest pipeline: sources → SynthesisInput.

   Railway-oriented: short-circuits on first error, carries error context.
   Each step returns a Result; ok-> threads the unwrapped :ok value forward.

   Opts:
     :timeout-ms  — per-source timeout (default 10000)

   Returns:
     (result/ok SynthesisInput) on success
     (result/err ...) with category + context on failure"
  ([sources ctx] (run-pipeline sources ctx {}))
  ([sources ctx {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
   (result/ok-> (harvest-all sources ctx timeout-ms)
                merge-outcomes
                validate-context)))

;; =============================================================================
;; Convenience: Source Builders
;; =============================================================================

(defn make-source
  "Create an IHarvestSource from a keyword id and a harvest function.
   Convenience for tests and ad-hoc sources.

   (make-source :git (fn [ctx] {:commits (list-commits (:dir ctx))}))"
  [id harvest-fn]
  (reify IHarvestSource
    (source-id [_] id)
    (harvest [_ ctx] (harvest-fn ctx))))

(comment
  ;; Example: build sources and run pipeline
  (let [hivemind-src (make-source :hivemind (fn [_] {:messages [{:text "deployed v2"}]}))
        kanban-src   (make-source :kanban   (fn [_] {:tasks-completed [{:id "T1" :title "Fix bug"}]}))
        git-src      (make-source :git      (fn [_] {:commits ["abc123 fix: null check"]}))
        memory-src   (make-source :memory   (fn [_] {:created-count 3 :accessed-count 12}))
        session-src  (make-source :session  (fn [_] {:session-start "10:00" :session-end "12:30"
                                                     :duration-minutes 150}))]
    (run-pipeline [hivemind-src kanban-src git-src memory-src session-src] {:dir "/tmp"}))
  ;; => {:ok {:hivemind-messages [{:text "deployed v2"}]
  ;;          :kanban-changes [{:id "T1" :title "Fix bug"}]
  ;;          :git-commits ["abc123 fix: null check"]
  ;;          :memory-stats {:created-count 3 :accessed-count 12}
  ;;          :session-timing {:session-start "10:00" ...}
  ;;          :source-errors []}}
  )
