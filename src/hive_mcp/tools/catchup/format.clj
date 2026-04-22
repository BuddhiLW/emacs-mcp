(ns hive-mcp.tools.catchup.format
  "Formatting and rendering functions for catchup workflow."
  (:require [hive-mcp.knowledge-graph.scope :as kg-scope]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn entry->catchup-meta
  "Convert a memory store entry to lean catchup metadata.
   Minimal footprint: id + type + short preview. No tags (available via context-refs)."
  [entry preview-len]
  (let [content (:content entry)
        content-str (if (string? content)
                      content
                      (str content))
        preview (subs content-str 0 (min (count content-str) (or preview-len 80)))]
    {:id (:id entry)
     :T (name (or (:type entry) "note"))
     :P preview}))

(defn entry->axiom-meta
  "Convert entry to axiom metadata with full content."
  [entry]
  {:id (:id entry)
   :type "axiom"
   :tags (vec (or (:tags entry) []))
   :content (:content entry)
   :severity "INVIOLABLE"})

(defn entry->priority-meta
  "Convert entry to priority convention metadata with full content."
  [entry]
  {:id (:id entry)
   :type "convention"
   :tags (vec (or (:tags entry) []))
   :content (:content entry)})

(defn build-scopes
  "Build scope list for display, including descendant scope info."
  [project-name project-id]
  (let [in-project? (and project-id (not= project-id "global"))
        base (cond-> (if in-project? [] ["scope:global"])
               project-name (conj (str "scope:project:" project-name))
               (and project-id (not= project-id project-name) (not= project-id "global"))
               (conj (str "scope:project:" project-id)))
        descendants (when (and project-id in-project?)
                      (kg-scope/descendant-scopes project-id))]
    (if (seq descendants)
      (into base (map #(str "scope:project:" %) descendants))
      base)))

(def axiom-content-cap
  "Max chars per axiom entry content."
  600)

(def block-warn-threshold
  "Log warning if a single block exceeds this char count."
  40000)

(defn cap-axiom-content
  "Cap axiom entry content at `axiom-content-cap` chars with retrieval hint."
  [axiom-entry]
  (let [content (str (:content axiom-entry))
        cap axiom-content-cap]
    (if (<= (count content) cap)
      axiom-entry
      (assoc axiom-entry :content
             (str (subs content 0 cap)
                  " [TRUNCATED - use mcp_memory_get_full " (:id axiom-entry) "]")))))

(defn trim-kg-insights
  "Trim KG insight lists to bounded sizes."
  [insights]
  (when insights
    (cond-> insights
      (:stale-files insights)
      (update :stale-files #(vec (take 5 %)))

      (get-in insights [:grounding-warnings :stale-entries])
      (update-in [:grounding-warnings :stale-entries] #(vec (take 10 %)))

      (:contradictions insights)
      (update :contradictions #(vec (take 5 %)))

      (:superseded insights)
      (update :superseded #(vec (take 5 %))))))

(defn- warn-if-oversized
  "Log warning if block text exceeds `block-warn-threshold`."
  [block-name text]
  (when (> (count text) block-warn-threshold)
    (log/warn "Catchup block" block-name "exceeds threshold:"
              (count text) "chars >" block-warn-threshold)))

(defn- make-block
  "Build a single catchup content block with warning check."
  [block-name data]
  (let [text (json/write-str data)]
    (warn-if-oversized block-name text)
    {:type "text" :text text}))

(defn build-catchup-response
  "Build the final catchup response as a vector of content blocks.
   Includes header, context, kg-insights, meta, and (when available) carto-status."
  [{:keys [project-name project-id scopes git-info permeation
           axioms-meta principles-meta priority-meta sessions-meta decisions-meta
           conventions-meta snippets-meta expiring-meta kg-insights
           project-tree-scan disc-decay carto-status context-refs]}]
  (let [total-enqueued (+ (count axioms-meta) (count principles-meta) (count priority-meta))]
    (filterv some?
    [(make-block "header"
                 {:_block "header"
                  :success true
                  :project (or project-name project-id "global")
                  :scopes scopes
                  :git git-info
                  :permeation permeation
                  :counts {:axioms (count axioms-meta)
                           :principles (count principles-meta)
                           :priority-conventions (count priority-meta)
                           :sessions (count sessions-meta)
                           :decisions (count decisions-meta)
                           :conventions (count conventions-meta)
                           :snippets (count snippets-meta)
                           :expiring (count expiring-meta)}
                  :memory-piggyback
                  (cond-> {:enqueued total-enqueued
                           :note "Axioms, principles, and priority conventions drain via ---MEMORY--- blocks. Enrichment results arrive via piggyback on subsequent calls."}
                    (seq context-refs)
                    (assoc :context-refs context-refs
                           :ref-note "Context refs point to ephemeral context-store entries (10min TTL). Future :ref mode can send only refs instead of full content."))})
     (make-block "context"
                 {:_block "context"
                  :context {:sessions sessions-meta}
                  :via-piggyback "Axioms, principles, priority conventions drain via ---MEMORY--- blocks on subsequent tool calls."
                  :via-context-refs "Decisions, conventions, snippets, expiring available via context-refs in header. Use context_get to deep-dive by necessity."})
     (make-block "kg-insights"
                 {:_block "kg-insights"
                  :kg-insights (trim-kg-insights kg-insights)})
     (make-block "meta"
                 {:_block "meta"
                  :project-tree project-tree-scan
                  :disc-decay disc-decay
                  :hint "Axioms and priority conventions are being delivered via ---MEMORY--- piggyback blocks. AXIOMS are INVIOLABLE - follow them word-for-word. Entries with :kg key have Knowledge Graph relationships."})
     (when carto-status
       (make-block "carto-status"
                   {:_block "carto-status"
                    :lsp-up?       (:lsp-up? carto-status)
                    :carto-store?  (:carto-store? carto-status)
                    :indexed-forms (:indexed-forms carto-status)
                    :last-scan-ts  (:last-scan-ts carto-status)
                    :scan-status   (:scan-status carto-status)
                    :scan-result   (:scan-result carto-status)
                    :readiness     (:readiness carto-status)
                    :warnings      (or (:warnings carto-status) [])
                    :hint          (or (:hint carto-status)
                                       "Carto readiness at-a-glance.")}))])))

(defn store-not-configured-error
  "Return error response when no IMemoryStore is registered."
  []
  {:type "text"
   :text (json/write-str {:success false
                          :error "Memory store not configured"
                          :message "Memory query requires a registered IMemoryStore (Milvus, Qdrant, etc.) with embedding provider"})
   :isError true})

(defn catchup-error
  "Return error response for catchup failures."
  [e]
  (log/error e "native-catchup failed")
  {:type "text"
   :text (json/write-str {:success false :error (.getMessage e)})
   :isError true})

(defn format-spawn-axioms
  "Format axioms section for spawn context markdown."
  [axioms]
  (when (seq axioms)
    (let [lines (map-indexed
                 (fn [idx ax]
                   (format "%d. %s" (inc idx) (:content ax)))
                 axioms)]
      (str "### Axioms (INVIOLABLE \u2014 follow word-for-word)\n\n"
           (str/join "\n\n" lines)
           "\n\n"))))

(defn format-spawn-priorities
  "Format priority conventions section for spawn context markdown."
  [conventions]
  (when (seq conventions)
    (let [lines (map-indexed
                 (fn [idx conv]
                   (format "%d. %s" (inc idx) (:content conv)))
                 conventions)]
      (str "### Priority Conventions\n\n"
           (str/join "\n\n" lines)
           "\n\n"))))

(defn format-spawn-decisions
  "Format active decisions section for spawn context markdown."
  [decisions]
  (when (seq decisions)
    (let [lines (map (fn [d] (format "- %s" (or (:preview d) (:P d) ""))) decisions)]
      (str "### Active Decisions\n\n"
           (str/join "\n" lines)
           "\n\n"))))

(defn format-spawn-git
  "Format git status section for spawn context markdown."
  [git-info]
  (when git-info
    (str "### Git Status\n\n"
         (format "- **Branch**: %s\n" (or (:branch git-info) "unknown"))
         (when (:uncommitted git-info)
           "- **Uncommitted changes**: yes\n")
         (format "- **Last commit**: %s\n" (or (:last-commit git-info) "unknown")))))

(defn format-spawn-stale-files
  "Format stale files section for spawn context markdown."
  [stale-files]
  (when (seq stale-files)
    (let [lines (map (fn [{:keys [path score days-since-read hash-mismatch?]}]
                       (format "- `%s` (staleness: %.1f%s%s)"
                               path
                               (float score)
                               (if days-since-read
                                 (format ", last read %dd ago" days-since-read)
                                 ", never read")
                               (if hash-mismatch?
                                 ", content changed"
                                 "")))
                     stale-files)]
      (str "### Files Needing Re-Grounding (file-level)\n\n"
           (str/join "\n" lines)
           "\n\n"))))

(def max-spawn-context-chars
  "Maximum characters for spawn context injection."
  12000)

(defn serialize-spawn-context
  "Serialize spawn context data to markdown string."
  [{:keys [axioms priority-conventions decisions git-info project-name stale-files]}]
  (str "## Project Context (Auto-Injected)\n\n"
       (format "**Project**: %s\n\n" (or project-name "unknown"))
       (format-spawn-axioms axioms)
       (format-spawn-priorities priority-conventions)
       (format-spawn-decisions decisions)
       (format-spawn-stale-files stale-files)
       (format-spawn-git git-info)))
