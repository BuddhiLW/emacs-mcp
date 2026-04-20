(ns hive-mcp.agent.drone.validation
  "Pre/post execution validation for drone agents."
  (:require [hive-mcp.analysis.resolve :as resolve]
            [hive-mcp.swarm.logic :as logic]
            [hive-dsl.result :as r]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const max-file-size-bytes
  "Maximum file size for drone mutation (1MB)."
  (* 1024 1024))

(def ^:const binary-extensions
  "File extensions considered binary."
  #{".png" ".jpg" ".jpeg" ".gif" ".ico" ".bmp" ".webp"
    ".pdf" ".doc" ".docx" ".xls" ".xlsx"
    ".zip" ".tar" ".gz" ".rar" ".7z"
    ".jar" ".war" ".ear" ".class"
    ".exe" ".dll" ".so" ".dylib"
    ".mp3" ".mp4" ".avi" ".mov" ".wav"
    ".ttf" ".otf" ".woff" ".woff2"
    ".db" ".sqlite" ".sqlite3"})

(defn make-validation-result
  "Create a validation result map."
  [{:keys [pre-valid? post-valid? lint-errors diff-lines warnings]
    :or {pre-valid? false
         post-valid? nil
         lint-errors []
         diff-lines nil
         warnings []}}]
  {:pre-valid? pre-valid?
   :post-valid? post-valid?
   :lint-errors (vec lint-errors)
   :diff-lines diff-lines
   :warnings (vec warnings)})

(defn- file-extension
  "Extract lowercase file extension from path."
  [path]
  (when path
    (let [idx (str/last-index-of path ".")]
      (when (and idx (pos? idx))
        (str/lower-case (subs path idx))))))

(defn- binary-file?
  "Check if file is likely binary based on extension."
  [path]
  (contains? binary-extensions (file-extension path)))

(defn- file-size
  "Get file size in bytes, or nil if file doesn't exist."
  [path]
  (r/rescue nil
            (let [f (io/file path)]
              (when (.exists f)
                (.length f)))))

(defn- file-readable?
  "Check if file exists and is readable."
  [path]
  (r/rescue false
            (let [f (io/file path)]
              (and (.exists f) (.canRead f)))))

(defn- has-valid-claim?
  "Check if drone has a valid claim on the file."
  [task-id file]
  (let [result (r/guard Exception true
                        (let [file-claim (logic/get-claim-for-file file)]
                          (or (nil? file-claim)
                              (= task-id (:slave-id file-claim)))))]
    (when-let [err (::r/error (meta result))]
      (log/warn "Failed to check file claim:" (:message err)))
    result))

(defn validate-pre-execution
  "Validate file before drone mutation."
  [file task-id & [{:keys [allow-create] :or {allow-create false}}]]
  (let [warnings (atom [])
        add-warning! (fn [msg] (swap! warnings conj msg))
        f (io/file file)
        exists? (.exists f)]

    (cond
      (and (not exists?) (not allow-create))
      (make-validation-result
       {:pre-valid? false
        :warnings [(str "File does not exist: " file)]})

      (binary-file? file)
      (make-validation-result
       {:pre-valid? false
        :warnings [(str "Cannot mutate binary file: " file)]})

      (and exists? (> (or (file-size file) 0) max-file-size-bytes))
      (do
        (add-warning! (format "File exceeds size limit (%d bytes > %d max): %s"
                              (file-size file) max-file-size-bytes file))
        (make-validation-result
         {:pre-valid? false
          :warnings @warnings}))

      (not (has-valid-claim? task-id file))
      (make-validation-result
       {:pre-valid? false
        :warnings [(str "Drone does not have claim on file: " file)]})

      (and exists? (not (file-readable? file)))
      (make-validation-result
       {:pre-valid? false
        :warnings [(str "File exists but is not readable: " file)]})

      :else
      (do
        (when (not exists?)
          (add-warning! (str "File will be created: " file)))
        (when (and exists? (> (or (file-size file) 0) (* 100 1024)))
          (add-warning! (format "Large file (%d KB), may be slow to process"
                                (quot (file-size file) 1024))))
        (make-validation-result
         {:pre-valid? true
          :warnings @warnings})))))

(defn- count-diff-lines
  "Count number of changed lines between old and new content."
  [old-content new-content]
  (when (and old-content new-content)
    (let [old-lines (set (str/split-lines old-content))
          new-lines (set (str/split-lines new-content))
          added (count (set/difference new-lines old-lines))
          removed (count (set/difference old-lines new-lines))]
      (+ added removed))))

(defn- run-lint-check
  "Run kondo lint on file and extract errors."
  [file lint-level]
  (if-let [run-analysis (resolve/resolve-kondo-analysis)]
    (let [fallback [{:filename file
                     :level :error
                     :type :lint-failed
                     :message "Lint check failed"}]
          result (r/guard Exception fallback
                          (let [{:keys [findings timed-out]} (run-analysis file :timeout-ms 15000)
                                _ (when timed-out
                                    (log/warn "Lint timed out for drone validation" {:file file :timeout-ms 15000}))
                                level-kw (keyword (or lint-level "error"))
                                filtered (->> findings
                                              (filter #(case level-kw
                                                         :error (= (:level %) :error)
                                                         :warning (#{:error :warning} (:level %))
                                                         :info true))
                                              (mapv #(select-keys % [:filename :row :col :level :type :message])))]
                            filtered))]
      (when-let [err (::r/error (meta result))]
        (log/warn "Lint check failed for" file ":" (:message err)))
      result)
    (do
      (log/warn "clj-kondo-mcp not available, skipping lint for" file)
      [])))

(defn- file-was-modified?
  "Check if file was actually modified by comparing content."
  [file old-content]
  (let [result (r/guard Exception false
                        (let [new-content (slurp file)]
                          (not= old-content new-content)))]
    (when-let [err (::r/error (meta result))]
      (log/warn "Could not check file modification:" (:message err)))
    result))

(defn validate-post-execution
  "Validate file after drone mutation."
  [file old-content pre-result & [{:keys [lint-level require-modification]
                                   :or {lint-level :error
                                        require-modification true}}]]
  (let [warnings (atom (vec (:warnings pre-result)))
        add-warning! (fn [msg] (swap! warnings conj msg))
        f (io/file file)]

    (cond
      (not (.exists f))
      (do
        (add-warning! (str "File does not exist after execution: " file))
        (make-validation-result
         (merge pre-result
                {:post-valid? false
                 :warnings @warnings})))

      (and require-modification
           old-content
           (not (file-was-modified? file old-content)))
      (do
        (add-warning! (str "File was not modified: " file))
        (make-validation-result
         (merge pre-result
                {:post-valid? false
                 :diff-lines 0
                 :warnings @warnings})))

      :else
      (let [new-content (r/rescue nil (slurp file))
            diff-lines (count-diff-lines old-content new-content)
            lint-errors (run-lint-check file lint-level)
            has-errors? (seq lint-errors)]

        (when has-errors?
          (add-warning! (format "Lint found %d error(s) in %s" (count lint-errors) file)))

        (when (and diff-lines (zero? diff-lines) require-modification)
          (add-warning! "No lines were changed"))

        (make-validation-result
         (merge pre-result
                {:post-valid? (not has-errors?)
                 :lint-errors lint-errors
                 :diff-lines (or diff-lines 0)
                 :warnings @warnings}))))))

(defn validate-files-pre
  "Validate multiple files before drone execution."
  [files task-id]
  (into {}
        (for [file files]
          [file (validate-pre-execution file task-id)])))

(defn validate-files-post
  "Validate multiple files after drone execution."
  [file-contents pre-results & [opts]]
  (into {}
        (for [[file old-content] file-contents]
          (let [pre-result (get pre-results file (make-validation-result {:pre-valid? true}))]
            [file (validate-post-execution file old-content pre-result opts)]))))

(defn all-valid?
  "Check if all validation results passed for a given phase."
  [results phase]
  (let [key (case phase
              :pre :pre-valid?
              :post :post-valid?)]
    (every? #(get % key) (vals results))))

(defn summarize-validation
  "Create a summary of validation results."
  [results]
  (let [all-results (vals results)]
    {:total-files (count all-results)
     :pre-valid (count (filter :pre-valid? all-results))
     :pre-invalid (count (filter (comp not :pre-valid?) all-results))
     :post-valid (count (filter #(true? (:post-valid? %)) all-results))
     :post-invalid (count (filter #(false? (:post-valid? %)) all-results))
     :post-pending (count (filter #(nil? (:post-valid? %)) all-results))
     :total-lint-errors (reduce + (map #(count (:lint-errors %)) all-results))
     :total-diff-lines (reduce + (keep :diff-lines all-results))
     :all-warnings (vec (mapcat :warnings all-results))}))
