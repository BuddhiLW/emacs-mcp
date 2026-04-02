(ns hive-mcp.tools.memory.gaps
  "Knowledge gap auto-detection via regex/keyword analysis of memory content."
  (:require [hive-mcp.extensions.registry :as ext]
            [hive-dsl.result :refer [rescue]]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private max-gaps
  "Maximum number of knowledge gaps to extract per entry."
  10)

(def ^:private max-gap-length
  "Maximum character length for a single gap descriptor."
  80)

(defn- truncate-gap
  "Truncate a gap descriptor to max-gap-length, preserving word boundaries."
  [s]
  (if (<= (count s) max-gap-length)
    s
    (let [truncated (subs s 0 max-gap-length)
          last-space (.lastIndexOf truncated " ")]
      (if (pos? last-space)
        (str (subs truncated 0 last-space) "...")
        (str (subs s 0 (- max-gap-length 3)) "...")))))

(defn extract-questions
  "Extract sentences ending with question marks from content."
  [content]
  (->> (re-seq #"[^.!?\n]*\?" content)
       (map str/trim)
       (remove str/blank?)
       (remove #(< (count %) 10))
       (mapv #(truncate-gap (str "question: " %)))))

(defn extract-todo-markers
  "Extract TODO/TBD/FIXME/HACK/XXX markers and their context."
  [content]
  (->> (re-seq #"(?i)\b(TODO|TBD|FIXME|HACK|XXX)\b[:\s]*([^\n.!?]{0,60})" content)
       (mapv (fn [[_ marker context]]
               (let [marker-lower (str/lower-case marker)
                     ctx (str/trim (or context ""))]
                 (truncate-gap
                  (if (str/blank? ctx)
                    (str marker-lower ": (no description)")
                    (str marker-lower ": " ctx))))))))

(def ^:private uncertainty-patterns
  "Regex patterns for uncertainty language in content."
  [#"(?i)\b(unclear|unknown|uncertain|unsure|undecided|unresolved)\b[:\s]*([^\n.!?]{0,60})"
   #"(?i)\b(not\s+sure|needs?\s+investigation|needs?\s+clarification|open\s+question)\b[:\s]*([^\n.!?]{0,60})"
   #"(?i)\b(needs?\s+to\s+be\s+determined|remains?\s+to\s+be\s+seen)\b[:\s]*([^\n.!?]{0,60})"])

(defn extract-uncertainty
  "Extract uncertainty language from content."
  [content]
  (->> uncertainty-patterns
       (mapcat #(re-seq % content))
       (mapv (fn [[_ marker context]]
               (let [marker-lower (str/lower-case (str/trim marker))
                     ctx (str/trim (or context ""))]
                 (truncate-gap
                  (if (str/blank? ctx)
                    (str "uncertain: " marker-lower)
                    (str "uncertain: " marker-lower " " ctx))))))
       (distinct)
       (vec)))

(def ^:private missing-patterns
  "Regex patterns for missing/incomplete markers in content."
  [#"(?i)\b(missing|incomplete|placeholder|stub|not\s+implemented|needs?\s+work)\b[:\s]*([^\n.!?]{0,60})"])

(defn extract-missing
  "Extract missing/incomplete markers from content."
  [content]
  (->> missing-patterns
       (mapcat #(re-seq % content))
       (mapv (fn [[_ marker context]]
               (let [marker-lower (str/lower-case (str/trim marker))
                     ctx (str/trim (or context ""))]
                 (truncate-gap
                  (if (str/blank? ctx)
                    (str "missing: " marker-lower)
                    (str "missing: " marker-lower " " ctx))))))
       (distinct)
       (vec)))

(def ^:private assumption-patterns
  "Regex patterns for assumption markers in content."
  [#"(?i)\b(assuming|assumption)\b[:\s]*([^\n.!?]{0,60})"])

(defn extract-assumptions
  "Extract assumption markers from content."
  [content]
  (->> assumption-patterns
       (mapcat #(re-seq % content))
       (mapv (fn [[_ marker context]]
               (let [marker-lower (str/lower-case (str/trim marker))
                     ctx (str/trim (or context ""))]
                 (truncate-gap
                  (if (str/blank? ctx)
                    (str "assumption: " marker-lower)
                    (str "assumption: " ctx))))))
       (distinct)
       (vec)))

(defn extract-knowledge-gaps
  "Analyze content to detect knowledge gaps via lightweight regex extraction."
  [content]
  (if (or (nil? content) (str/blank? (str content)))
    []
    (let [content-str (str content)
          questions    (extract-questions content-str)
          todos        (extract-todo-markers content-str)
          uncertainty  (extract-uncertainty content-str)
          missing      (extract-missing content-str)
          assumptions  (extract-assumptions content-str)
          all-gaps (->> (concat questions todos uncertainty missing assumptions)
                        (distinct)
                        (take max-gaps)
                        (vec))
          enhanced-gaps (when-let [analyze-fn (ext/get-extension :gs/detect-gaps)]
                          (rescue nil (analyze-fn content-str all-gaps)))]
      (or enhanced-gaps all-gaps))))
