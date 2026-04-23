(ns hive-mcp.tools.memory.migration.helpers
  "Shared helpers for memory migration:
   - Read/update .hive-project.edn files
   - Scope-tag detection and rewriting utilities."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; .hive-project.edn IO
;; =============================================================================

(defn read-hive-project-edn
  "Read and parse .hive-project.edn from a directory."
  [directory]
  (rescue nil
          (let [edn-file (io/file directory ".hive-project.edn")]
            (when (.exists edn-file)
              (edn/read-string (slurp edn-file))))))

(defn update-hive-project-edn!
  "Update .hive-project.edn with new project-id and append old to aliases."
  [directory old-project-id new-project-id]
  (try
    (let [edn-file (io/file directory ".hive-project.edn")
          existing (when (.exists edn-file)
                     (edn/read-string (slurp edn-file)))
          current-aliases (or (:aliases existing) [])
          updated-aliases (if (some #{old-project-id} current-aliases)
                            current-aliases
                            (conj current-aliases old-project-id))
          updated-config (assoc (or existing {})
                                :project-id new-project-id
                                :aliases updated-aliases)]
      (spit (.getAbsolutePath edn-file)
            (pr-str updated-config))
      (log/info "Updated .hive-project.edn:" (.getAbsolutePath edn-file)
                {:project-id new-project-id :aliases updated-aliases})
      {:success true :config updated-config})
    (catch Exception e
      (log/warn "Failed to update .hive-project.edn:" (.getMessage e))
      {:error (.getMessage e)})))

;; =============================================================================
;; Scope-tag utilities
;; =============================================================================

(defn hash-scope?
  "Detect if a scope looks like a hash (orphaned old-style scope)."
  [scope-id]
  (and (string? scope-id)
       (> (count scope-id) 12)
       (boolean (re-matches #"^[a-f0-9]+$" scope-id))))

(defn extract-scope-id
  "Extract the scope ID from a scope:project: tag."
  [tag]
  (when (and (string? tag) (str/starts-with? tag "scope:project:"))
    (subs tag (count "scope:project:"))))

(defn orphaned-scope-tag?
  "Check if a tag is an orphaned hash-based scope tag."
  [tag]
  (when-let [scope-id (extract-scope-id tag)]
    (hash-scope? scope-id)))

(defn update-scope-tag
  "Replace old scope tag with new scope tag in a tags vector."
  [tags old-scope new-scope]
  (let [old-tag (str "scope:project:" old-scope)
        new-tag (str "scope:project:" new-scope)]
    (mapv #(if (= % old-tag) new-tag %) tags)))
