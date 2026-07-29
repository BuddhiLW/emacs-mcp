(ns hive-mcp.memory.ids
  "Leaf identity helpers for memory entries: content hashing, id generation, timestamps."
  (:require [hive-spi.memory.ids :as ids]))

(def content-hash
  "Compute SHA-256 hash of normalized content."
  ids/content-hash)

(def generate-id
  "Generate a unique timestamped ID for memory entries."
  ids/generate-id)

(def iso-timestamp
  "Return current ISO 8601 timestamp."
  ids/iso-timestamp)
