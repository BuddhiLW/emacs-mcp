(ns hive-mcp.migration.core
  "Core migration logic for KG, Memory, and future adapters.

   Backup naming: {scope}-{backend}-{timestamp}.edn
   Example: kg-datalevin-20260203T164530.edn

   Project Scope Awareness:
   - Backups include :backup/project-id in metadata
   - list-backups supports :project-id filtering (reads backup files)
   - validate-backup exposes :backup/project-id
   - Prevents cross-project restore contamination"

  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Backup Directory & Naming
;; =============================================================================

(def ^:private default-backup-dir "data/backups")

(def backup-format-version
  "Current on-disk backup envelope version, stamped as :backup/version by
   `backup-metadata`. v1 carried no :backup/project-id; v2 always does.
   Readers treat a missing :backup/version as v1."
  2)

(defn ensure-backup-dir!
  "Ensure backup directory exists."
  [dir]
  (let [f (io/file dir)]
    (when-not (.exists f)
      (log/info "Creating backup directory" {:path dir})
      (.mkdirs f))
    dir))

(defn generate-backup-name
  "Generate unique backup filename with timestamp.

   Format: {scope}-{backend}-{yyyyMMddTHHmmss}.edn

   Arguments:
     scope   - :kg, :memory, :kanban, :full
     backend - :datascript, :datalevin, :datahike, :chroma

   Returns filename string (not full path)."
  [scope backend]
  (let [now (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss")
        timestamp (.format now formatter)]
    (format "%s-%s-%s.edn" (name scope) (name backend) timestamp)))

(defn parse-backup-name
  "Parse backup filename to extract metadata.

   Returns {:scope :kg, :backend :datalevin, :timestamp \"20260203T164530\"}
   or nil if filename doesn't match pattern."
  [filename]
  (when-let [[_ scope backend timestamp]
             (re-matches #"(\w+)-(\w+)-(\d{8}T\d{6})\.edn" filename)]
    {:scope (keyword scope)
     :backend (keyword backend)
     :timestamp timestamp
     :filename filename}))

;; =============================================================================
;; Backup Listing
;; =============================================================================

(defn- read-backup-project-id
  "Read only the :backup/project-id from a backup file.
   Returns project-id string or nil on failure.
   Lightweight - reads full file but only extracts one field."
  [path]
  (rescue nil
          (let [data (-> path slurp edn/read-string)]
            (:backup/project-id data))))

(defn list-backups
  "List all backups in the backup directory.

   Arguments:
     opts - Optional map:
       :dir        - Backup directory (default: data/backups)
       :scope      - Filter by scope (:kg, :memory, :kanban, :full)
       :backend    - Filter by backend
       :project-id - Filter by project scope (reads backup metadata)
       :limit      - Max results (default: 20)

   Returns vector of backup metadata maps, newest first.
   When :project-id is specified, each backup file is read to check its
   :backup/project-id metadata. Backups without project-id metadata are
   treated as 'global' scope (backward compatible with v1 backups).

   Hierarchical scope matching (HCR):
   When filtering by 'hive-mcp:agora', backups from ancestor scopes
   ('hive-mcp', 'global') are also included, since a child project should
   see its parent's backups. Uses kg-scope/scope-contains? for hierarchy."
  [& [{:keys [dir scope backend project-id limit]
       :or {dir default-backup-dir
            limit 20}}]]
  (let [backup-dir (io/file dir)]
    (if (.exists backup-dir)
      (->> (.listFiles backup-dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))
           (map (fn [f]
                  (when-let [meta (parse-backup-name (.getName f))]
                    (assoc meta
                           :path (.getAbsolutePath f)
                           :size (.length f)
                           :modified (.lastModified f)))))
           (filter identity)
           (filter #(or (nil? scope) (= scope (:scope %))))
           (filter #(or (nil? backend) (= backend (:backend %))))
           ;; Project-id filtering with hierarchical scope awareness (HCR)
           ;; A filter for 'hive-mcp:agora' matches backups from:
           ;; - 'hive-mcp:agora' (exact match)
           ;; - 'hive-mcp' (parent scope)
           ;; - 'global' (root scope, always visible)
           (filter #(or (nil? project-id)
                        (let [backup-pid (or (read-backup-project-id (:path %)) "global")]
                          (or (= project-id backup-pid)
                              (= backup-pid "global")
                              (kg-scope/scope-contains? backup-pid project-id)))))
           (sort-by :timestamp #(compare %2 %1))  ;; newest first
           (take limit)
           vec)
      [])))

(defn latest-backup
  "Get the most recent backup matching criteria.

   Arguments:
     scope   - :kg, :memory, :kanban, :full (required)
     opts    - Optional {:backend :datalevin, :dir \"path\"}

   Returns backup metadata map or nil."
  [scope & [opts]]
  (first (list-backups (assoc opts :scope scope :limit 1))))

;; =============================================================================
;; Backup Metadata
;; =============================================================================

(defn backup-metadata
  "Create metadata header for backup file.

   Arguments:
     scope      - :kg, :memory, :kanban, :full
     backend    - Source backend
     counts     - Map of entity counts
     project-id - Project scope for this backup (optional, nil = 'global')

   Returns metadata map to include in backup, stamped with
   `backup-format-version`. Always includes :backup/project-id for
   scope-aware restore filtering."
  ([scope backend counts]
   (backup-metadata scope backend counts nil))
  ([scope backend counts project-id]
   (cond-> {:backup/version backup-format-version
            :backup/scope scope
            :backup/backend backend
            :backup/project-id (or project-id "global")
            :backup/created-at (java.util.Date.)
            :backup/created-by (System/getProperty "user.name")
            :backup/hostname (rescue "unknown" (.getHostName (java.net.InetAddress/getLocalHost)))
            :backup/counts counts})))

;; =============================================================================
;; File I/O
;; =============================================================================

(defn write-backup!
  "Write backup data to file with metadata.

   Arguments:
     path     - Full file path
     metadata - Backup metadata map
     data     - Data to backup (will be merged with metadata)

   Returns {:success true, :path path, :size bytes}."
  [path metadata data]
  (let [parent-dir (.getParentFile (io/file path))]
    (when (and parent-dir (not (.exists parent-dir)))
      (.mkdirs parent-dir))
    (let [content (merge metadata data)]
      (spit path (pr-str content))
      (let [size (.length (io/file path))]
        (log/info "Backup written" {:path path :size size})
        {:success true
         :path path
         :size size
         :metadata metadata}))))

(defn read-backup
  "Read backup file and parse EDN.

   Arguments:
     path - Full file path

   Returns parsed EDN data or throws on error."
  [path]
  (log/info "Reading backup" {:path path})
  (let [content (slurp path)
        data (edn/read-string content)]
    (log/info "Backup loaded" {:path path
                               :scope (:backup/scope data)
                               :counts (:backup/counts data)})
    data))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-backup
  "Validate backup file structure and integrity.

   Arguments:
     path - Path to backup file

   Returns {:valid? boolean, :errors [...], :metadata {...}}."
  [path]
  (try
    (let [data (read-backup path)
          errors (cond-> []
                   (nil? (:backup/version data))
                   (conj "Missing :backup/version")

                   (nil? (:backup/scope data))
                   (conj "Missing :backup/scope")

                   (nil? (:backup/counts data))
                   (conj "Missing :backup/counts"))]
      {:valid? (empty? errors)
       :errors errors
       :metadata (select-keys data [:backup/version :backup/scope
                                    :backup/backend :backup/created-at
                                    :backup/counts :backup/project-id])})
    (catch Exception e
      {:valid? false
       :errors [(str "Failed to read backup: " (.getMessage e))]
       :metadata nil})))

;; =============================================================================
;; Scope Guard for Restore
;; =============================================================================

(defn check-scope-compatibility
  "Check if a backup is compatible with the target project scope.

   Returns {:compatible? boolean, :backup-project-id str, :target-project-id str,
            :reason str (when incompatible)}

   Compatibility rules:
   - 'global' backups are compatible with any project (they have no project affinity)
   - Backups without :backup/project-id (v1 format) are treated as 'global'
   - Same project-id = always compatible
   - Different project-id = incompatible (requires :force-cross-project? true)
   - nil target-project-id = no scope check (compatible with everything)"
  [backup-data target-project-id]
  (let [backup-pid (or (:backup/project-id backup-data) "global")]
    (cond
      ;; No target scope specified - skip check
      (nil? target-project-id)
      {:compatible? true
       :backup-project-id backup-pid
       :target-project-id nil
       :reason "No target scope specified, skipping check"}

      ;; Same project
      (= backup-pid target-project-id)
      {:compatible? true
       :backup-project-id backup-pid
       :target-project-id target-project-id
       :reason "Same project scope"}

      ;; Global backup is always compatible
      (= backup-pid "global")
      {:compatible? true
       :backup-project-id backup-pid
       :target-project-id target-project-id
       :reason "Global backup is compatible with any project"}

      ;; Cross-project - incompatible by default
      :else
      {:compatible? false
       :backup-project-id backup-pid
       :target-project-id target-project-id
       :reason (format "Backup is from project '%s' but target is '%s'. Use :force-cross-project to override."
                       backup-pid target-project-id)})))
