(ns hive-mcp.tools.memory.core
  "Core utilities and macros for memory tool handlers."
  (:require [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.protocols.memory :as mem-proto]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defmacro with-store
  "Execute body with memory store validation and error handling.
   Guards on store-set? (DIP: abstracts over any IMemoryStore backend)."
  [& body]
  `(if-not (mem-proto/store-set?)
     (mcp-error "Memory store not configured")
     (try
       ~@body
       (catch Exception e#
         (mcp-error (ex-message e#))))))

(defmacro with-chroma
  "DEPRECATED: Use with-store. Kept for backward compatibility."
  [& body]
  `(with-store ~@body))

(defmacro with-entry
  "Execute body with entry lookup, handling not-found case."
  [[entry-sym id-expr] & body]
  `(with-store
     (if-let [~entry-sym (mem-proto/get-entry (mem-proto/get-store) ~id-expr)]
       (do ~@body)
       (mcp-error (str "Entry not found: " ~id-expr)))))
