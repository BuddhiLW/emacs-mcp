(ns hive-mcp.tools.catchup.shapes
  "Entry shape contracts for the catchup pipeline.

   Three shapes flow through catchup → enrichment → synthesis:

   1. Raw Chroma entry: {:id :content :type :tags ...}
      Source: Chroma queries in catchup.clj / scope.clj
      Used by: synthesis LLM prompt, axiom/priority piggyback

   2. Catchup meta: {:id :T :P}
      Source: entry->catchup-meta (token-minimized for wire)
      Used by: catchup response blocks, context piggyback

   3. Enriched meta: {:id :T :P :kg}
      Source: KG enrichment adds :kg relations
      Used by: context piggyback blocks, KG insights

   Coercion functions ensure consumers can extract content
   regardless of which shape they receive.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn entry-content
  "Extract content string from any entry shape.
   Checks :content (raw Chroma), :preview, :P (catchup meta) in order.
   Returns empty string if none found."
  [entry]
  (str (or (:content entry) (:preview entry) (:P entry) "")))

(defn entry-id
  "Extract entry ID, defaulting to \"?\"."
  [entry]
  (or (:id entry) "?"))

(defn entry-type-name
  "Extract type as string from any entry shape.
   Handles :type (raw keyword) and :T (catchup meta string)."
  [entry]
  (let [t (or (:type entry) (:T entry))]
    (if (keyword? t) (name t) (str (or t "note")))))
