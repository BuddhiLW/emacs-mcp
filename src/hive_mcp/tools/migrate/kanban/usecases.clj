(ns hive-mcp.tools.migrate.kanban.usecases
  "Use cases for the kanban cross-store migrator. Pure-as-possible:
   takes a `deps` map of port adapters, returns Result. All transitions
   emit hive-events on the side-channel — control flow is entirely the
   railway, never the event-loop.

   Deps shape:
     {:source-lister  IIdLister     ; e.g. milvus
      :source-reader  IEntryReader  ; e.g. milvus
      :target-reader  IEntryReader  ; e.g. qdrant
      :writer         IEntryWriter  ; e.g. qdrant
      :state          IState}"
  (:refer-clojure :exclude [run!])
  (:require [hive-dsl.result :as r]
            [hive-mcp.tools.migrate.kanban.events :as mig-events]
            [hive-mcp.tools.migrate.kanban.ports :as ports]
            [hive-mcp.tools.migrate.kanban.pure :as pure]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- now-iso [] (str (java.time.ZonedDateTime/now)))

;; =============================================================================
;; init-ids! — Phase A
;; =============================================================================

(defn init-ids!
  "Materialize the candidate id set from the source store and persist into
   migration state. Returns r/ok with `{:total :per-collection}` on success."
  [{:keys [source-lister state] :as _deps}]
  (mig-events/emit :kanban-mig/scan-started {:at (now-iso)})
  (r/let-ok [list-result   (ports/list-ids source-lister)
             current-state (ports/load-state state)]
    (let [ids       (:ids list-result)
          per-coll  (:per-collection list-result)
          new-state (-> current-state
                        (assoc :phase           :ids-listed
                               :all-ids         ids
                               :cursor          0
                               :phase-a-at      (now-iso)
                               :phase-a-summary per-coll))]
      (r/let-ok [_ (ports/save-state! state new-state)]
        (mig-events/emit :kanban-mig/ids-listed
                          {:total (count ids) :per-collection per-coll})
        (r/ok {:total          (count ids)
               :per-collection per-coll})))))

;; =============================================================================
;; migrate-batch! — single batch, no state mutation
;; =============================================================================

(defn- classify-each
  "Pure: derive {:id :outcome :source} for each id in the batch given
   pre-fetched source/target maps."
  [batch source-map target-map]
  (mapv (fn [id]
          (let [src (get source-map id)
                tgt (get target-map id)]
            {:id id
             :outcome (pure/classify-outcome src tgt)
             :source src}))
        batch))

(defn- writes-needed
  "Filter the outcomes whose source needs to be written to the target."
  [outcomes]
  (filter #(= :ready-to-write (:outcome %)) outcomes))

(defn- finalize-outcomes
  "Replace each :ready-to-write outcome with the post-write outcome
   (:written / :would-write / :failed) using the writer's per-id results.

   On dry-run, all :ready-to-write become :would-write."
  [outcomes write-results dry-run?]
  (let [by-id (into {} (map (juxt :id identity) write-results))]
    (mapv (fn [{:keys [id outcome] :as o}]
            (cond
              (not= :ready-to-write outcome) o
              dry-run?                       (assoc o :outcome :would-write)
              :else
              (let [w (get by-id id)]
                (cond
                  (:ok? w)  (assoc o :outcome :written)
                  :else     (assoc o :outcome :failed
                                     :error   (or (:error w) "no-writer-result"))))))
          outcomes)))

(defn- empty-batch-result
  [cursor]
  (r/ok {:cursor    cursor
         :outcomes  []
         :tally     pure/empty-tally
         :done?     true}))

(defn migrate-batch!
  "Run one cursor-bounded batch. Reads source + target, classifies, writes
   the ready ones, returns outcomes + tally. Does NOT mutate state — the
   caller (`step!`) folds the result into state."
  [{:keys [source-reader target-reader writer state] :as _deps}
   {:keys [batch-size dry-run?] :or {batch-size 50 dry-run? false}}]
  (r/let-ok [s (ports/load-state state)
             :let [[batch new-cursor done?]
                   (pure/slice-batch (:all-ids s) (:cursor s) batch-size)]]
    (if (empty? batch)
      (empty-batch-result new-cursor)
      (do
        (mig-events/emit :kanban-mig/batch-started
                          {:size (count batch) :cursor (:cursor s)})
        (r/let-ok [source-map    (ports/read-by-ids source-reader batch)
                   target-map    (ports/read-by-ids target-reader batch)
                   :let [outcomes (classify-each batch source-map target-map)
                         ready    (writes-needed outcomes)
                         entries  (mapv :source ready)]
                   write-results (cond
                                   (empty? ready) (r/ok [])
                                   dry-run?       (r/ok [])
                                   :else
                                   (ports/write-entries writer entries))
                   :let [final-outcomes (finalize-outcomes outcomes write-results dry-run?)
                         tally          (pure/tally-outcomes final-outcomes)
                         result         {:cursor   new-cursor
                                         :outcomes final-outcomes
                                         :tally    tally
                                         :done?    done?}]]
          (mig-events/emit :kanban-mig/batch-completed
                            (select-keys result [:cursor :tally :done?]))
          (r/ok result))))))

;; =============================================================================
;; step! — one batch, fold into state
;; =============================================================================

(defn- fold-state-after-batch
  "Pure: produce the next state given the current state and a batch result."
  [s {:keys [cursor outcomes tally done?]}]
  (-> s
      (assoc :cursor        cursor
             :last-step-at  (now-iso)
             :phase         (if done? :done :running))
      (update :stats pure/merge-tally tally)
      (update-in [:stats :scanned] (fnil + 0) (count outcomes))
      (update :failed
              (fn [xs]
                (into (or xs [])
                      (->> outcomes
                           (filter #(= :failed (:outcome %)))
                           (map #(select-keys % [:id :error]))))))))

(defn step!
  "Run one batch and persist the new state. Returns r/ok of
   `{:state :batch-result}`."
  [{:keys [state] :as deps} opts]
  (r/let-ok [batch-result (migrate-batch! deps opts)
             s            (ports/load-state state)]
    (let [new-state (fold-state-after-batch s batch-result)]
      (r/let-ok [saved (ports/save-state! state new-state)]
        (r/ok {:state saved :batch-result batch-result})))))

;; =============================================================================
;; run! — repeat step! until done / max-steps
;; =============================================================================

(defn run!
  "Loop step! up to `:max-steps` times or until the migration is :done.
   Stops on err. Returns r/ok with `{:steps-run :stopped :state}`."
  [deps {:keys [max-steps] :or {max-steps 50} :as opts}]
  (loop [i 0 last-state nil]
    (let [result (step! deps opts)]
      (cond
        (r/err? result)
        result

        (-> result :ok :batch-result :done?)
        (r/ok {:steps-run (inc i)
               :stopped   :done
               :state     (-> result :ok :state)})

        (>= (inc i) max-steps)
        (do
          (mig-events/emit :kanban-mig/run-done
                            {:steps-run (inc i) :stopped :max-steps})
          (r/ok {:steps-run (inc i)
                 :stopped   :max-steps
                 :state     (-> result :ok :state)}))

        :else
        (recur (inc i) (-> result :ok :state))))))

;; =============================================================================
;; status — read-only
;; =============================================================================

(defn status
  [{:keys [state] :as _deps}]
  (r/let-ok [s (ports/load-state state)]
    (r/ok {:phase           (:phase s)
           :cursor          (:cursor s)
           :total           (count (:all-ids s))
           :remaining       (max 0 (- (count (:all-ids s)) (:cursor s)))
           :stats           (:stats s)
           :failed-count    (count (:failed s))
           :phase-a-summary (:phase-a-summary s)
           :phase-a-at      (:phase-a-at s)
           :last-step-at    (:last-step-at s)})))
