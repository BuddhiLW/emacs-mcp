(ns hive-mcp.tools.memory.classify
  "Abstraction level auto-classification using type, content, and tag signals."
  (:require [hive-mcp.extensions.registry :as ext]
            [hive-mcp.knowledge-graph.schema :as kg-schema]
            [hive-dsl.result :refer [rescue]]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private l4-patterns
  "Regex patterns indicating intent-level abstraction."
  [#"(?i)\b(must\s+always|must\s+never|inviolable|non-negotiable)\b"
   #"(?i)^#*\s*(axiom|principle|ADR)\s*[:.]\s"
   #"(?i)\b(architectural\s+decision|design\s+rationale|strategic\s+decision)\b"
   #"(?i)\[ax\]"])

(def ^:private l3-patterns
  "Regex patterns indicating pattern-level abstraction."
  [#"(?i)^#*\s*(pattern|convention|idiom|recipe|guideline|workflow)\s*[:.]\s"
   #"(?i)\b(always\s+do|never\s+do|when\s+.*?,\s+(do|use)|best\s+practice)\b"
   #"(?i)\b(anti-pattern|code\s+smell|recurring\s+(pattern|structure))\b"])

(def ^:private l1-patterns
  "Regex patterns indicating file-level abstraction."
  [#"(?i)\b(file|path|directory):\s*\S+"
   #"(?i)\b(git\s+commit|commit\s+hash|SHA-?256)\b"
   #"(?i)\bat\s+line\s+\d+"
   #"(?i)\bline\s+\d+[-\u2013]\d+\b"
   #"(?i)\b(kondo|lint|compilation)\s+(error|warning)\b"])

(defn content-keyword-level
  "Analyze content text for abstraction level signals, returning 1-4 or nil."
  [content]
  (when (and content (not (str/blank? content)))
    (let [content-str (str content)]
      (cond
        (some #(re-find % content-str) l4-patterns) 4
        (some #(re-find % content-str) l3-patterns) 3
        (some #(re-find % content-str) l1-patterns) 1
        :else nil))))

(defn tag-level-signal
  "Check tags for abstraction level signals, returning 1-4 or nil."
  [tags]
  (let [tag-set (set (or tags []))]
    (cond
      (some tag-set ["axiom" "principle" "ADR" "strategic"]) 4
      (some tag-set ["convention" "pattern" "idiom" "best-practice"
                     "anti-pattern" "workflow" "recipe"]) 3
      (some tag-set ["file-state" "kondo" "lint" "git-state"
                     "disc" "compilation"]) 1
      :else nil)))

(defn classify-abstraction-level
  "Auto-classify abstraction level (1-4) for a memory entry using type, content, and tags."
  [entry-type content tags]
  (let [type-level (kg-schema/derive-abstraction-level entry-type)
        content-level (content-keyword-level content)
        tag-level (tag-level-signal tags)
        heuristic-level (or content-level tag-level type-level)
        enhanced-level (when-let [classify-fn (ext/get-extension :gs/classify)]
                         (rescue nil (classify-fn entry-type content tags heuristic-level)))]
    (or enhanced-level heuristic-level type-level)))
