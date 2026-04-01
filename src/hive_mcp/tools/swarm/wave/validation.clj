(ns hive-mcp.tools.swarm.wave.validation
  "Wave validation for pre-flight path checks and post-apply lint/compile checks."
  (:require [hive-mcp.analysis.resolve :as resolve]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn ensure-parent-dirs!
  "Create parent directories for all task files."
  [tasks]
  (let [created (atom 0)]
    (doseq [{:keys [file]} tasks]
      (when file
        (let [parent (.getParentFile (io/file file))]
          (when (and parent (not (.exists parent)))
            (io/make-parents file)
            (swap! created inc)))))
    @created))

(defn valid-parent-path?
  "Check if parent directory exists for a file path."
  [file-path]
  (if (nil? file-path)
    true
    (let [parent (.getParentFile (io/file file-path))]
      (or (nil? parent)
          (.exists parent)))))

(defn validate-task-paths
  "Validate all task paths have accessible parent directories."
  [tasks]
  (let [invalid (->> tasks
                     (map :file)
                     (remove nil?)
                     (remove valid-parent-path?)
                     (vec))]
    (if (seq invalid)
      (throw (ex-info "Invalid paths in wave"
                      {:error-type :validation
                       :invalid-paths invalid}))
      true)))

(defn path-validation-summary
  "Get summary of path validation without throwing."
  [tasks]
  (let [invalid (->> tasks
                     (map :file)
                     (remove nil?)
                     (remove valid-parent-path?)
                     (vec))]
    {:valid? (empty? invalid)
     :invalid-count (count invalid)
     :invalid-paths invalid}))

(def ^:const default-lint-timeout-ms
  "Default per-file lint timeout (15 seconds)."
  15000)

(defn lint-file!
  "Run clj-kondo lint on a single file. Respects :timeout-ms (default 15s)."
  [file-path & [{:keys [level timeout-ms] :or {level :error timeout-ms default-lint-timeout-ms}}]]
  (if-let [run-analysis (resolve/resolve-kondo-analysis)]
    (try
      (let [result (run-analysis file-path :timeout-ms timeout-ms)
            _ (when (:timed-out result)
                (log/warn "Lint timed out for file" {:file file-path :timeout-ms timeout-ms}))
            findings (:findings result)
            errors (filter #(= :error (:level %)) findings)
            warnings (filter #(= :warning (:level %)) findings)
            info-msgs (filter #(= :info (:level %)) findings)]
        {:valid? (case level
                   :error (empty? errors)
                   :warning (and (empty? errors) (empty? warnings))
                   :info (empty? findings))
         :errors (vec errors)
         :warnings (vec warnings)
         :info-count (count info-msgs)
         :file file-path})
      (catch Exception e
        (log/warn "Lint failed for" file-path ":" (ex-message e))
        {:valid? false
         :errors [{:message (str "Lint error: " (ex-message e))
                   :file file-path}]
         :warnings []
         :info-count 0
         :file file-path}))
    (do
      (log/warn "clj-kondo-mcp not available, skipping lint for" file-path)
      {:valid? true :errors [] :warnings [] :info-count 0 :file file-path})))

(defn lint-before-apply!
  "Run clj-kondo on proposed diff content before applying."
  [diff-content file-path & [{:keys [level] :or {level :error}}]]
  (try
    (let [temp-file (java.io.File/createTempFile "lint-" ".clj")
          _ (spit temp-file diff-content)
          result (lint-file! (.getAbsolutePath temp-file) {:level level})]
      (.delete temp-file)
      (assoc result :file file-path))
    (catch Exception e
      (log/warn "Lint-before-apply failed for" file-path ":" (ex-message e))
      {:valid? false
       :errors [{:message (str "Lint error: " (ex-message e))
                 :file file-path}]
       :warnings []
       :file file-path})))

(defn lint-files!
  "Lint multiple files, aggregating results."
  [files & [opts]]
  (let [results (mapv #(lint-file! % opts) files)
        total-errors (reduce + (map #(count (:errors %)) results))
        total-warnings (reduce + (map #(count (:warnings %)) results))]
    {:valid? (every? :valid? results)
     :total-errors total-errors
     :total-warnings total-warnings
     :results results}))

(defn file->namespace
  "Convert file path to Clojure namespace symbol."
  [file-path]
  (when file-path
    (-> file-path
        (str/replace #"^.*/(?:src|test)/" "")
        (str/replace #"\.clj[sx]?$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        (symbol))))

(defn compile-check!
  "Try to require affected namespaces to catch compile errors early."
  [files]
  (let [namespaces (->> files
                        (map file->namespace)
                        (remove nil?)
                        (distinct))
        results (atom {:success? true :failed-ns [] :errors []})]

    (doseq [ns-sym namespaces]
      (try
        (when (find-ns ns-sym)
          (remove-ns ns-sym))
        (require ns-sym :reload)
        (catch Exception e
          (swap! results update :success? (constantly false))
          (swap! results update :failed-ns conj ns-sym)
          (swap! results update :errors conj
                 {:namespace ns-sym
                  :error (ex-message e)
                  :type (type e)}))))

    @results))

(defn compile-check-safe!
  "Compile check with exception handling (won't throw)."
  [files]
  (try
    (compile-check! files)
    (catch Exception e
      (log/error "Compile check failed:" (ex-message e))
      {:success? false
       :failed-ns []
       :errors [{:error (str "Compile check error: " (ex-message e))
                 :type :check-failure}]})))

(defn pre-flight-validation!
  "Run all pre-flight validations for wave execution."
  [tasks & [{:keys [ensure-dirs validate-paths]
             :or {ensure-dirs true validate-paths true}}]]
  (let [dirs-created (if ensure-dirs
                       (ensure-parent-dirs! tasks)
                       0)
        path-summary (path-validation-summary tasks)]

    (when (and validate-paths (not (:valid? path-summary)))
      (throw (ex-info "Invalid paths in wave"
                      {:error-type :validation
                       :invalid-paths (:invalid-paths path-summary)})))

    {:valid? (:valid? path-summary)
     :dirs-created dirs-created
     :path-summary path-summary}))

(defn post-apply-validation!
  "Run all post-apply validations after diffs are applied."
  [files & [{:keys [lint lint-level compile-check]
             :or {lint true lint-level :error compile-check true}}]]
  (let [lint-result (when lint
                      (lint-files! files {:level lint-level}))
        compile-result (when compile-check
                         (compile-check-safe! files))
        valid? (and (or (nil? lint-result) (:valid? lint-result))
                    (or (nil? compile-result) (:success? compile-result)))]

    {:valid? valid?
     :lint-result lint-result
     :compile-result compile-result}))

(defn validation-summary
  "Create a human-readable validation summary."
  [pre-result & [post-result]]
  (let [parts [(format "Pre-flight: %s (dirs created: %d)"
                       (if (:valid? pre-result) "PASS" "FAIL")
                       (or (:dirs-created pre-result) 0))]]
    (when post-result
      (conj parts
            (format "Post-apply: %s (lint errors: %d, compile failures: %d)"
                    (if (:valid? post-result) "PASS" "FAIL")
                    (get-in post-result [:lint-result :total-errors] 0)
                    (count (get-in post-result [:compile-result :failed-ns] [])))))
    (str/join " | " parts)))
