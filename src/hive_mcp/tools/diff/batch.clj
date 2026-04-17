(ns hive-mcp.tools.diff.batch
  "Multi-drone batch operations for diff review and approval."
  (:require [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.tools.diff.state :as state :refer [mcp-error-json]]
            [hive-mcp.tools.diff.auto-approve :as auto-approve]
            [hive-mcp.tools.diff.handlers :as handlers]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bget bkeys]] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn batch-review-diffs
  "Get all pending diffs from multiple drones for batch review."
  ([] (batch-review-diffs nil))
  ([drone-ids]
   (let [all-diffs (mapv :data (vals @(:atom state/pending-diffs)))
         filtered (if (seq drone-ids)
                    (filter #(contains? (set drone-ids) (:drone-id %)) all-diffs)
                    all-diffs)]
     (->> filtered
          (sort-by :created-at)
          (mapv (fn [d]
                  {:id (:id d)
                   :file-path (:file-path d)
                   :description (:description d)
                   :drone-id (:drone-id d)
                   :wave-id (:wave-id d)
                   :metrics (:metrics d)
                   :tdd-status (:tdd-status d)
                   :created-at (str (:created-at d))
                   :auto-approve-check (auto-approve/auto-approve-diff? d)}))))))

(defn approve-safe-diffs!
  "Auto-approve diffs meeting safety criteria, flag others for manual review."
  ([] (approve-safe-diffs! nil {}))
  ([drone-ids] (approve-safe-diffs! drone-ids {}))
  ([drone-ids {:keys [rules dry-run] :or {rules @state/auto-approve-rules dry-run false}}]
   (let [diffs (batch-review-diffs drone-ids)
         categorized (for [d diffs
                           :let [check (auto-approve/auto-approve-diff?
                                        (bget state/pending-diffs (:id d))
                                        rules)]]
                       (assoc d :auto-check check))
         auto-approvable (filter #(get-in % [:auto-check :approved]) categorized)
         manual-review (remove #(get-in % [:auto-check :approved]) categorized)]
     (if dry-run
       {:dry-run true
        :would-approve (mapv #(select-keys % [:id :file-path :drone-id]) auto-approvable)
        :manual-review (mapv #(select-keys % [:id :file-path :drone-id :auto-check]) manual-review)}
       (let [apply-results (for [{:keys [id]} auto-approvable]
                             (let [response (handlers/handle-apply-diff {:diff_id id})
                                   parsed (rescue nil (json/read-str (:text response) :key-fn keyword))]
                               (if (:isError response)
                                 {:status :failed :id id :error (:error parsed)}
                                 {:status :applied :id id :file-path (:file-path parsed)})))
             {applied :applied failed :failed} (group-by :status apply-results)]
         (log/info "Batch approve-safe-diffs!" {:drones (count (set (map :drone-id diffs)))
                                                :total (count diffs)
                                                :auto-approved (count applied)
                                                :manual-review (count manual-review)
                                                :failed (count failed)})
         {:auto-approved (vec applied)
          :manual-review (mapv (fn [d]
                                 {:id (:id d)
                                  :file-path (:file-path d)
                                  :drone-id (:drone-id d)
                                  :reason (get-in d [:auto-check :reason])})
                               manual-review)
          :failed (vec failed)})))))

(defn handle-batch-review-diffs
  "Handle batch_review_diffs tool call."
  [{:keys [drone_ids]}]
  (log/debug "batch_review_diffs called" {:drone_ids drone_ids})
  (try
    (let [ids (when (seq drone_ids) (vec drone_ids))
          result (batch-review-diffs ids)]
      (mcp-json {:count (count result)
                 :diffs result
                 :rules (auto-approve/get-auto-approve-rules)}))
    (catch Exception e
      (log/error e "Failed to batch review diffs")
      (mcp-error-json (str "Failed to batch review: " (.getMessage e))))))

(defn handle-approve-safe-diffs
  "Handle approve_safe_diffs tool call."
  [{:keys [drone_ids dry_run]}]
  (log/debug "approve_safe_diffs called" {:drone_ids drone_ids :dry_run dry_run})
  (try
    (let [ids (when (seq drone_ids) (vec drone_ids))
          result (approve-safe-diffs! ids {:dry-run (boolean dry_run)})]
      (mcp-json result))
    (catch Exception e
      (log/error e "Failed to approve safe diffs")
      (mcp-error-json (str "Failed to approve safe diffs: " (.getMessage e))))))
