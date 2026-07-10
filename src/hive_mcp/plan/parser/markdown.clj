(ns hive-mcp.plan.parser.markdown
  "Markdown plan parser — extracts plan steps from ## headers.

   Supports two annotation styles. Inline EDN map takes precedence over the
   [key: value] grammar whenever both are present.

   Inline EDN map (canonical — hybrid markdown + EDN):
     ## Add validation
     {:id \"step-1\" :priority :high :files [\"src/a.clj\"]}
     Prose description here...

   [key: value] annotations (legacy, still honored for back-compat):
     [id: step-1]            Explicit step ID
     [depends: step-1, s2]   Dependencies
     [priority: high]        Priority level
     [estimate: small]       Effort estimate
     [files: src/a.clj]      Affected files

   Example:
     # My Plan
     ## Add validation
     {:id \"t1\" :priority :high :files [\"src/a.clj\"]}
     Implementation details...
     ## Write tests [depends: t1] [estimate: small]"

  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-mcp.plan.schema :as schema]
            [hive-mcp.plan.field-registry :as field-registry]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Regex Patterns
;; =============================================================================

(def ^:private header-pattern
  "Regex pattern to match markdown headers"
  #"^(#{1,6})\s+(.+)$")

(def ^:private depends-pattern
  "Regex pattern to extract [depends: step-1, step-2] syntax"
  #"\[depends:\s*([^\]]+)\]")

(def ^:private priority-pattern
  "Regex pattern to extract [priority: high] syntax"
  #"\[priority:\s*(high|medium|low)\]")

(def ^:private id-pattern
  "Regex pattern to extract [id: step-1] syntax"
  #"\[id:\s*([^\]]+)\]")

(def ^:private estimate-pattern
  "Regex pattern to extract [estimate: small] syntax"
  #"\[estimate:\s*(small|medium|large)\]")

(def ^:private files-pattern
  "Regex pattern to extract [files: path1, path2] syntax"
  #"\[files:\s*([^\]]+)\]")

;; =============================================================================
;; Header & Annotation Extraction
;; =============================================================================

(defn- parse-header-line
  "Parse a markdown header line into level and text.

   Returns: {:level n :text \"...\"} or nil if not a header"
  [line]
  (when-let [match (re-matches header-pattern (str/trim line))]
    {:level (count (second match))
     :text (nth match 2)}))

(defn- extract-depends
  "Extract dependency list from text.

   [depends: step-1, step-2] -> [\"step-1\" \"step-2\"]"
  [text]
  (when-let [match (re-find depends-pattern text)]
    (->> (str/split (second match) #",")
         (mapv str/trim)
         (filterv (complement str/blank?)))))

(defn- extract-priority
  "Extract priority from text.

   [priority: high] -> :high"
  [text]
  (when-let [match (re-find priority-pattern text)]
    (keyword (second match))))

(defn- extract-id
  "Extract explicit ID from text.

   [id: step-1] -> \"step-1\""
  [text]
  (when-let [match (re-find id-pattern text)]
    (str/trim (second match))))

(defn- extract-estimate
  "Extract estimate from text.

   [estimate: small] -> :small"
  [text]
  (when-let [match (re-find estimate-pattern text)]
    (keyword (second match))))

(defn- extract-files
  "Extract file paths from text.

   [files: src/a.clj, src/b.clj] -> [\"src/a.clj\" \"src/b.clj\"]"
  [text]
  (when-let [match (re-find files-pattern text)]
    (->> (str/split (second match) #",")
         (mapv str/trim)
         (filterv (complement str/blank?)))))

(defn- clean-title
  "Remove metadata annotations from title text."
  [text]
  (-> text
      (str/replace depends-pattern "")
      (str/replace priority-pattern "")
      (str/replace id-pattern "")
      (str/replace estimate-pattern "")
      (str/replace files-pattern "")
      str/trim))

;; =============================================================================
;; Step & Section Processing
;; =============================================================================

(defn- generate-step-id
  "Generate a step ID from title if not explicitly provided."
  [title index]
  (let [slug (-> title
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-|-$" ""))]
    (if (str/blank? slug)
      (str "step-" (inc index))
      (str slug "-" (inc index)))))

(defn- lines->sections
  "Split lines into sections by header level.

   Returns vector of {:header ... :content-lines [...] :level n}"
  [lines target-level]
  (loop [remaining lines
         current nil
         sections []]
    (if (empty? remaining)
      (if current
        (conj sections current)
        sections)
      (let [line (first remaining)
            header (parse-header-line line)]
        (cond
          ;; Found a header at target level - start new section
          (and header (= (:level header) target-level))
          (recur (rest remaining)
                 {:header (:text header) :content-lines [] :level target-level}
                 (if current (conj sections current) sections))

          ;; Inside a section - accumulate content
          current
          (recur (rest remaining)
                 (update current :content-lines conj line)
                 sections)

          ;; Not in a section yet - skip
          :else
          (recur (rest remaining) nil sections))))))

(defn- try-read-leading-edn-map
  "If `s` starts (after leading whitespace) with `{`, attempt to read one EDN
   value and return `[value remaining-string]`. Returns nil on anything else
   or on parse failure."
  [s]
  (let [trimmed (str/triml s)]
    (when (and (seq trimmed) (= \{ (first trimmed)))
      (try
        (let [reader (java.io.PushbackReader. (java.io.StringReader. trimmed))
              value  (edn/read reader)]
          (when (map? value)
            (let [sw (java.io.StringWriter.)
                  buf (char-array 4096)]
              (loop []
                (let [n (.read reader buf)]
                  (when (pos? n)
                    (.write sw buf 0 n)
                    (recur))))
              [value (str/triml (.toString sw))])))
        (catch Exception _ nil)))))

(defn- extract-edn-overlay
  "Split a section's content lines into [overlay-map remaining-description].
   The overlay is the first EDN map at the top of the section (after any
   leading blank lines). Returns [nil content-str] when no overlay."
  [content-lines]
  (let [content (str/join "\n" content-lines)]
    (if-let [[value rest-content] (try-read-leading-edn-map content)]
      [value (when-not (str/blank? rest-content) rest-content)]
      [nil  (when-not (str/blank? content) content)])))

(defn- section->step
  "Convert a markdown section to a plan step. Precedence for each field:
     inline EDN overlay > [key: value] annotation > inferred default.
   Addon-registered step fields (plan.field-registry) are carried from the
   inline EDN overlay."
  [section index]
  (let [title-text  (:header section)
        [overlay description] (extract-edn-overlay (:content-lines section))
        base {:id          (or (:id overlay)
                               (extract-id title-text)
                               (generate-step-id title-text index))
              :title       (clean-title title-text)
              :description (or (:description overlay) description)
              :depends-on  (or (:depends-on overlay)
                               (extract-depends title-text)
                               [])
              :priority    (or (:priority overlay)
                               (extract-priority title-text)
                               :medium)
              :estimate    (or (:estimate overlay)
                               (extract-estimate title-text)
                               :medium)
              :files       (or (:files overlay)
                               (extract-files title-text)
                               [])
              :tags        (or (:tags overlay) [])}]
    (schema/normalize-step
     (merge base (select-keys overlay (field-registry/step-field-keys))))))

(defn- extract-plan-title
  "Extract plan title from first # header or return default."
  [lines]
  (some (fn [line]
          (when-let [header (parse-header-line line)]
            (when (= 1 (:level header))
              (:text header))))
        lines))

;; =============================================================================
;; Public API
;; =============================================================================

(defn parse-markdown-plan
  "Parse plan from markdown content.

   ## headers become steps (level 2)
   ### headers become substeps (level 3) - nested under parent
   [depends: step-1] syntax for dependencies
   [priority: high] syntax for priority
   [id: custom-id] syntax for explicit step IDs

   Args:
   - content: Markdown string

   Returns:
   - {:success true :plan ...} with normalized plan
   - {:success false :error ...} if no steps found"
  [content]
  (let [lines (str/split-lines content)
        title (or (extract-plan-title lines) "Untitled Plan")
        sections (lines->sections lines 2)]
    (if (empty? sections)
      {:success false :error "No ## headers found in content"}
      (let [steps (vec (map-indexed (fn [idx section]
                                      (section->step section idx))
                                    sections))
            plan (schema/normalize-plan
                  {:id (str "plan-" (System/currentTimeMillis))
                   :title (clean-title title)
                   :steps steps
                   :source-format :markdown})]
        (if (schema/valid-plan? plan)
          {:success true :plan plan}
          {:success false
           :error "Plan failed schema validation"
           :details (schema/explain-plan plan)})))))

(defn extract-title
  "Extract plan title from markdown content lines.
   Exposed for use by the unified parser facade."
  [content]
  (extract-plan-title (str/split-lines content)))
