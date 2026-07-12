(ns hive-mcp.memory.ids
  "Leaf identity helpers for memory entries: content hashing, id generation, timestamps."
  (:require [clojure.string]))

(defn content-hash
  "Compute SHA-256 hash of normalized content."
  [content]
  (let [content-str (if (string? content) content (pr-str content))
        normalized (-> content-str
                       clojure.string/trim
                       (clojure.string/replace #"[ \t]+" " ")
                       (clojure.string/replace #"\n+" "\n"))
        md (java.security.MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest md (.getBytes normalized "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn generate-id
  "Generate a unique timestamped ID for memory entries."
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
