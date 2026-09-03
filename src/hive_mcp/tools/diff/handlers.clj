(ns hive-mcp.tools.diff.handlers
  "Core MCP handlers for diff propose, list, apply, reject, and details."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.bounded-atom :refer [bput! bget bounded-swap!]]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.tools.diff.auto-approve :as auto-approve]
            [hive-mcp.tools.diff.compute :as compute]
            [hive-mcp.tools.diff.state :as state :refer [mcp-error-json]]
            [hive-mcp.tools.diff.validation :as validation]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn handle-propose-diff
  "Handle propose_diff tool call, storing a proposed diff for review."
  [{:keys [file_path _old_content _new_content _description drone_id directory] :as params}]
  (let [project-root (or directory
                         (ctx/current-directory)
                         (validation/get-project-root))]
    (log/debug "propose_diff called" {:file file_path :drone drone_id :project-root project-root})
    (if-let [error (validation/validate-propose-params params)]
      (do
        (log/warn "propose_diff validation failed" {:error error})
        (mcp-error-json error))
      (let [translated-path (validation/translate-sandbox-path file_path)
            _ (when (not= translated-path file_path)
                (log/info "Translated sandbox path" {:from file_path :to translated-path}))
            path-result (validation/validate-diff-path translated-path project-root)]
        (if-not (:valid path-result)
          (do
            (log/warn "propose_diff path validation failed"
                      {:file translated-path :original file_path :error (:error path-result) :drone drone_id})
            (mcp-error-json (:error path-result)))
          (try
            (let [resolved-path (:resolved-path path-result)
                  proposal (compute/create-diff-proposal (assoc params :file_path resolved-path))]
              (bput! state/pending-diffs (:id proposal) proposal)
              (log/info "Diff proposed" {:id (:id proposal)
                                         :file resolved-path
                                         :original-path file_path
                                         :drone drone_id})
              (mcp-json {:id (:id proposal)
                         :status "pending"
                         :file-path resolved-path
                         :original-path (when (not= file_path resolved-path) file_path)
                         :description (:description proposal)
                         :message "Diff proposed for review. Hivemind will apply or reject."}))
            (catch Exception e
              (log/error e "Failed to propose diff")
              (mcp-error-json (str "Failed to propose diff: " (.getMessage e))))))))))

(defn handle-list-proposed-diffs
  "Handle list_proposed_diffs tool call, returning metadata-only tier-1 response."
  [{:keys [drone_id]}]
  (log/debug "list_proposed_diffs called" {:drone_id drone_id})
  (try
    (let [;; Iterate raw bounded-atom entries, extracting :data from each
          all-diffs (mapv :data (vals @(:atom state/pending-diffs)))
          filtered (if (str/blank? drone_id)
                     all-diffs
                     (filter #(= drone_id (:drone-id %)) all-diffs))
          safe-diffs (map (fn [d]
                            (-> d
                                (update :created-at str)
                                (dissoc :old-content :new-content
                                        :hunks
                                        :unified-diff)))
                          filtered)]
      (log/info "Listed proposed diffs" {:count (count safe-diffs)})
      (mcp-json {:count (count safe-diffs)
                 :diffs (vec safe-diffs)}))
    (catch Exception e
      (log/error e "Failed to list proposed diffs")
      (mcp-error-json (str "Failed to list diffs: " (.getMessage e))))))

(defn handle-apply-diff
  "Handle apply_diff tool call, applying a proposed diff to the filesystem."
  [{:keys [diff_id]}]
  (log/debug "apply_diff called" {:diff_id diff_id})
  (cond
    (str/blank? diff_id)
    (do
      (log/warn "apply_diff missing diff_id")
      (mcp-error-json "Missing required field: diff_id"))

    (nil? (bget state/pending-diffs diff_id))
    (do
      (log/warn "apply_diff diff not found" {:diff_id diff_id})
      (mcp-error-json (str "Diff not found: " diff_id)))

    :else
    (let [{:keys [file-path old-content new-content]} (bget state/pending-diffs diff_id)
          file-exists? (.exists (io/file file-path))
          creating-new-file? (and (str/blank? old-content) (not file-exists?))]
      (cond
        (str/blank? new-content)
        (do
          (log/warn "apply_diff blocked: empty new_content" {:diff_id diff_id :file file-path})
          (bounded-swap! state/pending-diffs dissoc diff_id)
          (mcp-error-json "Cannot apply diff: new_content is empty or whitespace-only. This typically indicates the LLM returned an empty response."))

        creating-new-file?
        (try
          (let [parent (.getParentFile (io/file file-path))]
            (when (and parent (not (.exists parent)))
              (.mkdirs parent)))
          (spit file-path new-content)
          (bounded-swap! state/pending-diffs dissoc diff_id)
          (log/info "New file created" {:id diff_id :file file-path})
          (mcp-json {:id diff_id
                     :status "applied"
                     :file-path file-path
                     :created true
                     :message "New file created successfully"})
          (catch Exception e
            (log/error e "Failed to create file" {:diff_id diff_id})
            (mcp-error-json (str "Failed to create file: " (.getMessage e)))))

        (not file-exists?)
        (do
          (log/warn "apply_diff file not found" {:file file-path})
          (mcp-error-json (str "File not found: " file-path)))

        :else
        (try
          (let [current-content (slurp file-path)]
            (cond
              (not (str/includes? current-content old-content))
              (do
                (log/warn "apply_diff old content not found in file" {:file file-path})
                (mcp-error-json "Old content not found in file. File may have been modified since diff was proposed."))

              (> (count (re-seq (re-pattern (java.util.regex.Pattern/quote old-content)) current-content)) 1)
              (do
                (log/warn "apply_diff multiple matches found" {:file file-path})
                (mcp-error-json "Multiple occurrences of old content found. Cannot apply safely - diff is ambiguous."))

              :else
              (do
                (spit file-path (str/replace-first current-content old-content new-content))
                (bounded-swap! state/pending-diffs dissoc diff_id)
                (log/info "Diff applied" {:id diff_id :file file-path})
                (mcp-json {:id diff_id
                           :status "applied"
                           :file-path file-path
                           :message "Diff applied successfully"}))))
          (catch Exception e
            (log/error e "Failed to apply diff" {:diff_id diff_id})
            (mcp-error-json (str "Failed to apply diff: " (.getMessage e)))))))))

(defn handle-reject-diff
  "Handle reject_diff tool call, removing a diff without applying."
  [{:keys [diff_id reason]}]
  (log/debug "reject_diff called" {:diff_id diff_id :reason reason})
  (cond
    (str/blank? diff_id)
    (do
      (log/warn "reject_diff missing diff_id")
      (mcp-error-json "Missing required field: diff_id"))

    (nil? (bget state/pending-diffs diff_id))
    (do
      (log/warn "reject_diff diff not found" {:diff_id diff_id})
      (mcp-error-json (str "Diff not found: " diff_id)))

    :else
    (let [{:keys [file-path drone-id]} (bget state/pending-diffs diff_id)]
      (bounded-swap! state/pending-diffs dissoc diff_id)
      (log/info "Diff rejected" {:id diff_id :file file-path :reason reason})
      (mcp-json {:id diff_id
                 :status "rejected"
                 :file-path file-path
                 :drone-id drone-id
                 :reason (or reason "No reason provided")
                 :message "Diff rejected and discarded"}))))

(defn handle-get-diff-details
  "Handle get_diff_details tool call, returning formatted hunks for review."
  [{:keys [diff_id]}]
  (log/debug "get_diff_details called" {:diff_id diff_id})
  (cond
    (str/blank? diff_id)
    (mcp-error-json "Missing required field: diff_id")

    (nil? (bget state/pending-diffs diff_id))
    (mcp-error-json (str "Diff not found: " diff_id))

    :else
    (let [diff (bget state/pending-diffs diff_id)
          formatted-diff (compute/format-hunks-as-unified (:hunks diff) (:file-path diff))]
      (mcp-json (-> diff
                    (update :created-at str)
                    (dissoc :old-content :new-content)
                    (assoc :unified-diff formatted-diff))))))

(defn handle-get-auto-approve-rules
  "Handle get_auto_approve_rules tool call."
  [_params]
  (log/debug "get_auto_approve_rules called")
  (mcp-json (auto-approve/get-auto-approve-rules)))
