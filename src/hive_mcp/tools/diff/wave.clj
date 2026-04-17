(ns hive-mcp.tools.diff.wave
  "Wave-scoped batch operations for diff review and approval."
  (:require [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.tools.diff.state :as state :refer [mcp-error-json]]
            [hive-mcp.tools.diff.auto-approve :as auto-approve]
            [hive-mcp.tools.diff.handlers :as handlers]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bkeys]] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn get-wave-diffs
  "Get all pending diffs for a specific wave-id."
  [wave-id]
  (->> (mapv :data (vals @(:atom state/pending-diffs)))
       (filter #(= wave-id (:wave-id %)))
       (vec)))

(defn review-wave-diffs
  "Get a summary of all diffs proposed by a wave for review."
  [wave-id]
  (let [diffs (get-wave-diffs wave-id)
        summaries (mapv (fn [d]
                          {:id (:id d)
                           :file-path (:file-path d)
                           :description (:description d)
                           :drone-id (:drone-id d)
                           :metrics (:metrics d)
                           :tdd-status (:tdd-status d)
                           :status (:status d)
                           :created-at (str (:created-at d))})
                        diffs)
        auto-results (mapv (fn [d]
                             {:id (:id d)
                              :file-path (:file-path d)
                              :auto-approve (auto-approve/auto-approve-diff? d)})
                           diffs)]
    {:wave-id wave-id
     :count (count diffs)
     :diffs summaries
     :auto-approve-results auto-results}))

(defn approve-wave-diffs!
  "Approve and apply all or selected diffs from a wave."
  ([wave-id] (approve-wave-diffs! wave-id nil))
  ([wave-id diff-ids]
   (let [wave-diffs (get-wave-diffs wave-id)
         to-apply (if diff-ids
                    (filter #(contains? (set diff-ids) (:id %)) wave-diffs)
                    wave-diffs)
         results (for [{:keys [id]} to-apply]
                   (let [response (handlers/handle-apply-diff {:diff_id id})
                         parsed (rescue nil (json/read-str (:text response) :key-fn keyword))]
                     (if (:isError response)
                       {:status :failed :id id :error (:error parsed)}
                       {:status :applied :id id :file (:file-path parsed)})))
         {applied :applied failed :failed} (group-by :status results)]
     (log/info "Approved wave diffs" {:wave-id wave-id
                                      :applied (count applied)
                                      :failed (count failed)})
     {:applied (vec applied)
      :failed (vec failed)})))

(defn reject-wave-diffs!
  "Reject all diffs from a wave."
  [wave-id reason]
  (let [wave-diffs (get-wave-diffs wave-id)]
    (doseq [{:keys [id]} wave-diffs]
      (handlers/handle-reject-diff {:diff_id id :reason reason}))
    (log/info "Rejected wave diffs" {:wave-id wave-id :count (count wave-diffs) :reason reason})
    {:rejected (count wave-diffs)
     :wave-id wave-id
     :reason reason}))

(defn auto-approve-wave-diffs!
  "Auto-approve diffs meeting criteria, flag others for manual review."
  [wave-id]
  (let [wave-diffs (get-wave-diffs wave-id)
        categorized (for [d wave-diffs]
                      (assoc d :auto-check (auto-approve/auto-approve-diff? d)))
        auto-approvable (filter #(get-in % [:auto-check :approved]) categorized)
        manual-review (remove #(get-in % [:auto-check :approved]) categorized)
        apply-results (for [{:keys [id]} auto-approvable]
                        (let [response (handlers/handle-apply-diff {:diff_id id})
                              parsed (rescue nil (json/read-str (:text response) :key-fn keyword))]
                          (if (:isError response)
                            {:status :failed :id id :error (:error parsed)}
                            {:status :applied :id id})))
        {applied :applied failed :failed} (group-by :status apply-results)]
    (log/info "Auto-approved wave diffs" {:wave-id wave-id
                                          :auto-approved (count applied)
                                          :manual-review (count manual-review)
                                          :failed (count failed)})
    {:auto-approved (vec applied)
     :manual-review (mapv (fn [d]
                            {:id (:id d)
                             :file-path (:file-path d)
                             :reason (get-in d [:auto-check :reason])})
                          manual-review)
     :failed (vec failed)}))

(defn handle-review-wave-diffs
  "Handle review_wave_diffs tool call."
  [{:keys [wave_id]}]
  (log/debug "review_wave_diffs called" {:wave_id wave_id})
  (if (str/blank? wave_id)
    (mcp-error-json "Missing required field: wave_id")
    (try
      (let [result (review-wave-diffs wave_id)]
        (mcp-json result))
      (catch Exception e
        (log/error e "Failed to review wave diffs")
        (mcp-error-json (str "Failed to review wave diffs: " (.getMessage e)))))))

(defn handle-approve-wave-diffs
  "Handle approve_wave_diffs tool call."
  [{:keys [wave_id diff_ids]}]
  (log/debug "approve_wave_diffs called" {:wave_id wave_id :diff_ids diff_ids})
  (if (str/blank? wave_id)
    (mcp-error-json "Missing required field: wave_id")
    (try
      (let [result (approve-wave-diffs! wave_id diff_ids)]
        (mcp-json result))
      (catch Exception e
        (log/error e "Failed to approve wave diffs")
        (mcp-error-json (str "Failed to approve wave diffs: " (.getMessage e)))))))

(defn handle-reject-wave-diffs
  "Handle reject_wave_diffs tool call."
  [{:keys [wave_id reason]}]
  (log/debug "reject_wave_diffs called" {:wave_id wave_id :reason reason})
  (if (str/blank? wave_id)
    (mcp-error-json "Missing required field: wave_id")
    (try
      (let [result (reject-wave-diffs! wave_id (or reason "Rejected by coordinator"))]
        (mcp-json result))
      (catch Exception e
        (log/error e "Failed to reject wave diffs")
        (mcp-error-json (str "Failed to reject wave diffs: " (.getMessage e)))))))

(defn handle-auto-approve-wave-diffs
  "Handle auto_approve_wave_diffs tool call."
  [{:keys [wave_id]}]
  (log/debug "auto_approve_wave_diffs called" {:wave_id wave_id})
  (if (str/blank? wave_id)
    (mcp-error-json "Missing required field: wave_id")
    (try
      (let [result (auto-approve-wave-diffs! wave_id)]
        (mcp-json result))
      (catch Exception e
        (log/error e "Failed to auto-approve wave diffs")
        (mcp-error-json (str "Failed to auto-approve: " (.getMessage e)))))))
