(ns hive-mcp.chroma.helpers
  "Shared utility functions for Chroma operations."
  (:require [clojure.data.json :as json]
            [clojure.string :as str] [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn parse-zoned-datetime
  "Parse ISO string to ZonedDateTime, returns nil on failure."
  [s]
  (when (and (string? s) (seq s))
    (rescue nil (java.time.ZonedDateTime/parse s))))

(defn- json-string?
  "Check if string looks like JSON (starts with { or [)."
  [s]
  (and s (or (str/starts-with? s "{") (str/starts-with? s "["))))

(defn try-parse-json
  "Parse JSON string, return original on failure."
  [s]
  (if (json-string? s)
    (try (json/read-str s :key-fn keyword) (catch Exception _ s))
    s))

(defn split-tags
  "Split comma-separated tags string into vector."
  [tags-str]
  (when (seq tags-str)
    (str/split tags-str #",")))

(defn serialize-content
  "Serialize content to string for storage."
  [content]
  (if (string? content) content (json/write-str content)))

(defn join-tags
  "Join tags vector to comma-separated string."
  [tags]
  (when (seq tags) (str/join "," tags)))

(def ^:private duration-aliases
  "Map of invalid/legacy duration strings to canonical values."
  {"long-term"  "long"
   "short-term" "short"
   "session"    "ephemeral"
   "project"    "medium"})

(defn normalize-duration
  "Normalize duration string to canonical value."
  [duration]
  (or (get duration-aliases duration) duration))

(defn metadata->entry
  "Convert Chroma metadata format to domain entry map."
  [{:keys [id document metadata]}]
  {:id id
   :type (:type metadata)
   :content (try-parse-json (:content metadata))
   :tags (split-tags (:tags metadata))
   :content-hash (:content-hash metadata)
   :created (:created metadata)
   :updated (:updated metadata)
   :duration (normalize-duration (:duration metadata))
   :expires (:expires metadata)
   :access-count (:access-count metadata)
   :helpful-count (:helpful-count metadata)
   :unhelpful-count (:unhelpful-count metadata)
   :project-id (:project-id metadata)
   :kg-outgoing (split-tags (:kg-outgoing metadata))
   :kg-incoming (split-tags (:kg-incoming metadata))
   :abstraction-level (:abstraction-level metadata)
   :grounded-at (:grounded-at metadata)
   :grounded-from (:grounded-from metadata)
   :knowledge-gaps (split-tags (:knowledge-gaps metadata))
   :source-hash (:source-hash metadata)
   :source-file (:source-file metadata)
   :staleness-alpha (:staleness-alpha metadata)
   :staleness-beta (:staleness-beta metadata)
   :staleness-source (when-let [s (:staleness-source metadata)]
                       (if (string? s) (keyword s) s))
   :staleness-depth (:staleness-depth metadata)
   :document document})

(defn generate-id
  "Generate a unique ID for memory entries (timestamp + random hex)."
  []
  (let [ts (java.time.LocalDateTime/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
        random-hex (format "%08x" (rand-int Integer/MAX_VALUE))]
    (str (.format ts fmt) "-" random-hex)))

(defn iso-timestamp
  "Return current ISO 8601 timestamp."
  []
  (str (java.time.ZonedDateTime/now
        (java.time.ZoneId/systemDefault))))

(defn expired?
  "Check if an entry has expired based on :expires metadata."
  [metadata]
  (when-let [expires-instant (parse-zoned-datetime (:expires metadata))]
    (.isBefore expires-instant (java.time.ZonedDateTime/now))))

(defn time-between?
  "Check if time is between start (exclusive) and end (exclusive)."
  [time start end]
  (and (.isAfter time start) (.isBefore time end)))

(defn- normalize-whitespace
  "Normalize whitespace for hashing: trim, collapse spaces and newlines."
  [s]
  (-> s str/trim (str/replace #"[ \t]+" " ") (str/replace #"\n+" "\n")))

(defn content-hash
  "Compute SHA-256 hash of content for deduplication."
  [content]
  (let [normalized (normalize-whitespace (serialize-content content))
        md (java.security.MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes normalized "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn memory-to-document
  "Convert memory entry to searchable document string."
  [{:keys [content type tags]}]
  (str "Type: " type "\n"
       (when-let [t (join-tags tags)] (str "Tags: " (str/replace t "," ", ") "\n"))
       "Content: " (serialize-content content)))

(def metadata-defaults
  "Default values for entry metadata fields."
  {:type "note" :tags "" :content-hash "" :duration "long"
   :expires "" :access-count 0 :helpful-count 0 :unhelpful-count 0
   :project-id "global"
   :kg-outgoing "" :kg-incoming ""
   :abstraction-level nil
   :grounded-at ""
   :grounded-from ""
   :knowledge-gaps ""
   :source-hash ""
   :source-file ""
   :staleness-alpha 1
   :staleness-beta 1
   :staleness-source nil
   :staleness-depth 0})
