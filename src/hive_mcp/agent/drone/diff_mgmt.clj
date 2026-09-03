(ns hive-mcp.agent.drone.diff-mgmt
  "Diff lifecycle management during drone execution."
  (:require [clojure.data.json :as json]
            [clojure.set]
            [hive-dsl.bounded-atom :refer [bget bput! bkeys]]
            [hive-dsl.result :refer [rescue]]
            [hive-mcp.tools.diff :as diff]
            [taoensso.timbre :as log]))

(defrecord DiffResults
           [applied failed proposed])

(defn ->diff-results
  "Create a DiffResults record."
  [applied failed proposed]
  (->DiffResults (vec applied) (vec failed) (vec proposed)))

(defn empty-diff-results
  "Create an empty DiffResults."
  []
  (->diff-results [] [] []))

(defn auto-apply-diffs!
  "Auto-apply diffs proposed during drone execution."
  [drone-id new-diff-ids]
  (if (empty? new-diff-ids)
    (empty-diff-results)
    (let [results (for [diff-id new-diff-ids]
                    (let [diff-info (bget diff/pending-diffs diff-id)
                          response (diff/handle-apply-diff {:diff_id diff-id})
                          parsed (rescue nil (json/read-str (:text response) :key-fn keyword))]
                      (if (:isError response)
                        {:status :failed :file (:file-path diff-info) :error (:error parsed)}
                        {:status :applied :file (:file-path diff-info)})))
          {applied :applied failed :failed} (group-by :status results)]
      (when (seq applied)
        (log/info "Auto-applied drone diffs" {:drone drone-id :files (mapv :file applied)}))
      (when (seq failed)
        (log/warn "Some drone diffs failed to apply" {:drone drone-id :failures failed}))
      (->diff-results
       (mapv :file applied)
       (mapv #(select-keys % [:file :error]) failed)
       []))))

(defn tag-diffs-with-wave!
  "Tag newly proposed diffs with wave-id for batch review tracking."
  [new-diff-ids wave-id]
  (when (and (seq new-diff-ids) wave-id)
    (doseq [diff-id new-diff-ids]
      (when-let [current (bget diff/pending-diffs diff-id)]
        (bput! diff/pending-diffs diff-id (assoc current :wave-id wave-id))))
    (log/debug "Tagged diffs with wave-id" {:wave-id wave-id :count (count new-diff-ids)})))

(defn get-new-diff-ids
  "Calculate the set of new diff IDs after drone execution."
  [diffs-before diffs-after]
  (clojure.set/difference diffs-after diffs-before))

(defn capture-diffs-before
  "Capture the current set of pending diff IDs."
  []
  (set (bkeys diff/pending-diffs)))

(defn handle-diff-results!
  "Handle diff application based on execution mode."
  [drone-id new-diff-ids {:keys [wave-id skip-auto-apply]}]
  (when (and wave-id (seq new-diff-ids))
    (tag-diffs-with-wave! new-diff-ids wave-id))

  (if skip-auto-apply
    ;; In validated mode, don't apply - just track proposed diffs
    (->diff-results [] [] (vec new-diff-ids))
    ;; Normal mode - auto-apply diffs
    (auto-apply-diffs! drone-id new-diff-ids)))

(defn summarize-diff-results
  "Create a human-readable summary of diff results."
  [results]
  (cond
    (seq (:proposed results))
    (str (count (:proposed results)) " diffs proposed for review")

    (and (seq (:applied results)) (empty? (:failed results)))
    (str (count (:applied results)) " diffs applied successfully")

    (and (seq (:applied results)) (seq (:failed results)))
    (str (count (:applied results)) " applied, " (count (:failed results)) " failed")

    (seq (:failed results))
    (str (count (:failed results)) " diffs failed")

    :else
    "No diffs"))
