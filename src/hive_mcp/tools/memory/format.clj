(ns hive-mcp.tools.memory.format
  "JSON formatting utilities for memory entries."
  (:require [clojure.data.json :as json]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn entry->json-alist
  "Convert entry map to JSON-serializable format.
   Returns nil when entry is nil (defensive guard against silent {:tags []} output)."
  [entry]
  (when entry
    (let [base (-> entry
                   (update :tags #(or % []))
                   (dissoc :document))
          kg-outgoing (:kg-outgoing base)
          kg-incoming (:kg-incoming base)]
      (cond-> (dissoc base :kg-outgoing :kg-incoming)
        (seq kg-outgoing) (assoc :kg_outgoing_ids kg-outgoing)
        (seq kg-incoming) (assoc :kg_incoming_ids kg-incoming)))))

(defn- truncate-string
  "Truncate string to max-len, adding ellipsis if truncated."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (- max-len 3)) "...")
    s))

(defn- content->preview
  "Extract preview from entry content."
  [content max-len]
  (cond
    (string? content)
    (subs content 0 (min max-len (count content)))

    (map? content)
    (or (:description content)
        (:title content)
        (:name content)
        (subs (json/write-str content) 0 (min max-len (count (json/write-str content)))))

    :else
    (str content)))

(defn entry->metadata
  "Convert entry to metadata-only format (~10x fewer tokens than full entry)."
  ([entry]
   (entry->metadata entry 100))
  ([entry max-preview-len]
   (let [preview (content->preview (:content entry) max-preview-len)]
     {:id (:id entry)
      :type (:type entry)
      :preview (truncate-string (str preview) (- max-preview-len 3))
      :tags (or (:tags entry) [])
      :created (:created entry)})))

(defn entries->json
  "Convert collection of entries to JSON string."
  [entries]
  (json/write-str (mapv entry->json-alist entries)))

(defn entries->metadata-json
  "Convert collection of entries to metadata-only JSON string."
  [entries]
  (json/write-str (mapv entry->metadata entries)))
