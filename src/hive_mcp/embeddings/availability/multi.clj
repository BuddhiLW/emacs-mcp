(ns hive-mcp.embeddings.availability.multi
  (:require [hive-dsl.result :as r]))

(defmulti secret-available?
  "Open dispatch port on :impl. Contract: [:map [:impl :keyword]] -> :boolean.
   Unhandled dispatch -> r/err :hive-mcp.embeddings.availability/unhandled. Extend via defmethod."
  :impl
  :default ::unhandled)

(defmethod secret-available? ::unhandled
  [subject]
  (r/err :hive-mcp.embeddings.availability/unhandled
         {:dispatch (:impl subject) :subject subject}))
