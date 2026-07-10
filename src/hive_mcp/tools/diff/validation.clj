(ns hive-mcp.tools.diff.validation
  "Diff path validation, sandbox path translation, and parameter checking."
  (:require [hive-mcp.emacs-ext.client :as ec]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn validate-propose-params
  "Validate parameters for propose_diff, returning nil if valid or an error message."
  [{:keys [file_path old_content new_content]}]
  (cond
    (str/blank? file_path) "Missing required field: file_path"
    (nil? old_content) "Missing required field: old_content"
    (nil? new_content) "Missing required field: new_content"
    (str/blank? new_content) "new_content cannot be empty or whitespace-only - LLM may have returned empty response"
    :else nil))

(defn get-project-root
  "Get project root from Emacs or fall back to current working directory."
  []
  (or (rescue nil (ec/project-root))
      (System/getProperty "user.dir")))

(defn translate-sandbox-path
  "Translate clojure-mcp sandbox paths back to real project paths."
  [file-path]
  (if-let [[_ relative-path] (re-matches #"/tmp/fs-\d+/(.+)" file-path)]
    (let [project-root (get-project-root)]
      (log/debug "Translating sandbox path" {:sandbox file-path :relative relative-path})
      (str (str/replace project-root #"/$" "") "/" relative-path))
    file-path))

(defn validate-diff-path
  "Validate a file path for propose_diff, checking for escapes and hallucinated paths."
  ([file-path] (validate-diff-path file-path nil))
  ([file-path project-root-override]
   (let [project-root (or project-root-override (get-project-root))
         file (io/file file-path)]
     (cond
      (str/blank? file-path)
       {:valid false :error "File path cannot be empty"}

       (and (.isAbsolute file)
            (not (.exists file))
            (not (.exists (.getParentFile file))))
       {:valid false
        :error (str "Invalid absolute path: '" file-path "' - "
                    "neither the file nor its parent directory exists. "
                    "Use relative paths like 'src/hive_mcp/foo.clj' or ensure the path is valid.")}

       (let [resolved (if (.isAbsolute file)
                        file
                        (io/file project-root file-path))
             canonical-path (.getCanonicalPath resolved)
             canonical-root (.getCanonicalPath (io/file project-root))]
         (not (str/starts-with? canonical-path canonical-root)))
       {:valid false
        :error (str "Path escapes project directory: '" file-path "' "
                    "would resolve outside the project root '" project-root "'. "
                    "All paths must be within the project directory.")}

       (.isAbsolute file)
       {:valid true :resolved-path (.getCanonicalPath file)}

       :else
       (let [resolved (io/file project-root file-path)
             canonical-path (.getCanonicalPath resolved)]
         {:valid true :resolved-path canonical-path})))))
