(ns hive-mcp.tools.memory.migration.import
  "JSON import: migrate legacy Emacs-backed memory JSON into the current store."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.emacs.client :as ec]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn import-entry!
  "Import a single entry to memory store with content-hash deduplication."
  [entry project-id]
  (let [store (mem-proto/get-store)
        entry-hash (or (:content-hash entry)
                       (mem-proto/content-hash (:content entry)))
        entry-type (or (:type entry) "note")]
    (cond
      (mem-proto/find-duplicate store entry-type entry-hash {:project-id project-id})
      :skipped-hash

      (mem-proto/get-entry store (:id entry))
      :skipped-id

      :else
      (do
        (mem-proto/add-entry! store
                              {:id (:id entry)
                               :type entry-type
                               :content (:content entry)
                               :tags (if (vector? (:tags entry))
                                       (vec (:tags entry))
                                       (:tags entry))
                               :content-hash entry-hash
                               :created (:created entry)
                               :updated (:updated entry)
                               :duration (or (:duration entry) "long")
                               :expires (or (:expires entry) "")
                               :access-count (or (:access-count entry) 0)
                               :helpful-count (or (:helpful-count entry) 0)
                               :unhelpful-count (or (:unhelpful-count entry) 0)
                               :project-id project-id})
        :imported))))

(defn handle-import-json
  "Import memory entries from legacy JSON storage to Chroma."
  [{:keys [project-id dry-run]}]
  (log/info "mcp-memory-import-json:" project-id "dry-run:" dry-run)
  (with-store
    (let [pid (or project-id (scope/get-current-project-id))
          elisp (format "(json-encode (list :notes (hive-mcp-memory-query 'note nil %s 1000 nil t)
                                            :snippets (hive-mcp-memory-query 'snippet nil %s 1000 nil t)
                                            :conventions (hive-mcp-memory-query 'convention nil %s 1000 nil t)
                                            :decisions (hive-mcp-memory-query 'decision nil %s 1000 nil t)))"
                        (pr-str pid) (pr-str pid) (pr-str pid) (pr-str pid))
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if-not success
        (mcp-json {:error (str "Failed to read JSON: " error)})
        (let [data (json/read-str result :key-fn keyword)
              all-entries (concat (:notes data) (:snippets data)
                                  (:conventions data) (:decisions data))]
          (if dry-run
            (mcp-json {:dry-run true
                       :would-import (count all-entries)
                       :by-type {:notes (count (:notes data))
                                 :snippets (count (:snippets data))
                                 :conventions (count (:conventions data))
                                 :decisions (count (:decisions data))}})
            (let [results (mapv #(import-entry! % pid) all-entries)
                  imported (count (filter #(= :imported %) results))
                  skipped-hash (count (filter #(= :skipped-hash %) results))
                  skipped-id (count (filter #(= :skipped-id %) results))]
              (mcp-json {:imported imported
                         :skipped {:by-hash skipped-hash
                                   :by-id skipped-id
                                   :total (+ skipped-hash skipped-id)}
                         :project-id pid}))))))))
