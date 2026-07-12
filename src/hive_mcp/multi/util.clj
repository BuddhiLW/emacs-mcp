(ns hive-mcp.multi.util
  "Shared helpers for the multi tool."
  (:require [hive-dsl.result :refer [rescue]]
            [clojure.data.json :as json]))

(defn decode-mcp-text
  "Best-effort decode of the MCP text envelope into a map. Returns the
   original value if it isn't a parseable text envelope."
  [v]
  (if (and (map? v) (string? (:text v)))
    (rescue v
      (json/read-str (:text v) :key-fn keyword))
    v))
