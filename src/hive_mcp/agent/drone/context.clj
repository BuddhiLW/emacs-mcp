(ns hive-mcp.agent.drone.context
  "Smart context injection for drone agents including surrounding lines, imports, kondo analysis, and conventions."
  (:require [hive-mcp.analysis.resolve :as resolve]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.disc :as kg-disc]
            [hive-dsl.result :as r]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- read-file-lines
  "Read file as vector of lines, returning nil if file doesn't exist."
  [path]
  (r/rescue nil
            (when (.exists (io/file path))
              (str/split-lines (slurp path)))))

(defn read-surrounding-lines
  "Read surrounding lines around a target line number."
  [path target-line & [{:keys [context] :or {context 20}}]]
  (when-let [lines (read-file-lines path)]
    (let [total-lines (count lines)
          target (or target-line 1)
          start-line (max 1 (- target context))
          end-line (min total-lines (+ target context))
          ;; Convert to 0-indexed for subvec
          selected (subvec (vec lines) (dec start-line) end-line)
          numbered (map-indexed (fn [i line]
                                  (format "%4d: %s" (+ start-line i) line))
                                selected)]
      {:content (str/join "\n" numbered)
       :start-line start-line
       :end-line end-line
       :target-line target
       :total-lines total-lines})))

(defn- parse-ns-form
  "Parse ns form from file content, returning nil on failure."
  [content]
  (r/rescue nil
            (let [forms (read-string (str "[" content "]"))]
              (first (filter #(and (list? %) (= 'ns (first %))) forms)))))

(defn extract-imports
  "Extract require/import clauses from a Clojure file."
  [path]
  (when-let [content (r/rescue nil (slurp path))]
    (when-let [ns-form (parse-ns-form content)]
      (let [ns-name (second ns-form)
            clauses (drop 2 ns-form)
            extract-clause (fn [kw]
                             (->> clauses
                                  (filter #(and (sequential? %) (= kw (first %))))
                                  (mapcat rest)
                                  (vec)))]
        {:namespace (str ns-name)
         :requires (extract-clause :require)
         :imports (extract-clause :import)
         :uses (extract-clause :use)}))))

(defn format-imports-context
  "Format imports as context string for drone."
  [imports-data]
  (when imports-data
    (let [{:keys [namespace requires]} imports-data]
      (when (seq requires)
        (str "### File Namespace & Dependencies\n"
             "```clojure\n"
             "(ns " namespace "\n"
             "  (:require " (str/join "\n            " (map pr-str requires)) "))\n"
             "```\n")))))

(defn find-related-symbols
  "Find function signatures related to the task using kondo analysis."
  [path task]
  (if-let [run-analysis (resolve/resolve-kondo-analysis)]
    (let [result (r/guard Exception []
                          (let [{:keys [analysis]} (run-analysis path)
                                var-defs (:var-definitions analysis)
                                task-words (-> task
                                               str/lower-case
                                               (str/replace #"[^a-z0-9\-]" " ")
                                               (str/split #"\s+")
                                               set)
                                relevant-defs (->> var-defs
                                                   (filter (fn [vdef]
                                                             (let [var-name (str/lower-case (str (:name vdef)))]
                                                               (some #(str/includes? var-name %) task-words))))
                                                   (take 10))]
                            (mapv (fn [vdef]
                                    {:name (str (:name vdef))
                                     :ns (str (:ns vdef))
                                     :arglists (:arglist-strs vdef)
                                     :row (:row vdef)
                                     :private (:private vdef)})
                                  relevant-defs)))]
      (when-let [err (::r/error (meta result))]
        (log/debug "Could not analyze file for related symbols:" path (:message err)))
      result)
    []))

(defn format-related-symbols
  "Format related symbols as context string."
  [symbols]
  (when (seq symbols)
    (str "### Related Functions in File\n"
         (str/join "\n"
                   (map (fn [{:keys [name arglists private]}]
                          (str "- `" (when private "^:private ") name "` "
                               (when arglists (str ": " (str/join " | " arglists)))))
                        symbols))
         "\n")))

(defn get-existing-warnings
  "Get existing lint warnings for a file."
  [path & [{:keys [level] :or {level :warning}}]]
  (if-let [run-analysis (resolve/resolve-kondo-analysis)]
    (let [result (r/guard Exception []
                          (let [{:keys [findings]} (run-analysis path)]
                            (->> findings
                                 (filter #(case level
                                            :error (= (:level %) :error)
                                            :warning (#{:error :warning} (:level %))
                                            :info true))
                                 (mapv #(select-keys % [:row :col :level :type :message]))
                                 (take 15))))]
      (when-let [err (::r/error (meta result))]
        (log/debug "Could not lint file:" path (:message err)))
      result)
    []))

(defn format-lint-context
  "Format lint findings as context for drone."
  [findings file-path]
  (when (seq findings)
    (str "### Existing Lint Issues in " (last (str/split file-path #"/")) "\n"
         "NOTE: Fix these if your changes touch affected lines:\n"
         (str/join "\n"
                   (map (fn [{:keys [row level message]}]
                          (format "- Line %d: [%s] %s" row (name level) message))
                        findings))
         "\n")))

(defn get-relevant-conventions
  "Query memory for conventions relevant to the task."
  [task project-id]
  (let [result (r/guard Exception []
                 ;; Try semantic search first (filter by project at DB level)
                        (let [store (mem-proto/get-store)
                              results (mem-proto/search-similar store task {:type "convention" :limit 5
                                                                            :project-ids (when project-id [project-id])})]
                          (if (seq results)
                            (->> results
                                 (map (fn [r]
                                        (or (get-in r [:metadata :content])
                                            (:document r))))
                                 (remove nil?)
                                 (take 3)
                                 vec)
                     ;; Fallback: query all conventions and filter
                            (let [all-convs (mem-proto/query-entries store {:type "convention"
                                                                            :project-id project-id
                                                                            :limit 20})]
                              (->> all-convs
                                   (filter (fn [conv]
                                             (let [content (str/lower-case (str (:content conv)))
                                                   tags (set (map str/lower-case (or (:tags conv) [])))]
                                        ;; Basic relevance: check if any task word appears
                                               (or (some #(str/includes? content %)
                                                         (str/split (str/lower-case task) #"\s+"))
                                                   (some #(tags %) ["drone" "coding" "clojure"])))))
                                   (map :content)
                                   (take 3)
                                   vec)))))]
    (when-let [err (::r/error (meta result))]
      (log/debug "Could not query conventions:" (:message err)))
    result))

(defn format-conventions-context
  "Format conventions as context string."
  [conventions]
  (when (seq conventions)
    (str "### Coding Conventions to Follow\n"
         (str/join "\n\n---\n\n"
                   (map-indexed (fn [i conv]
                                  (str (inc i) ". "
                                       (if (> (count conv) 500)
                                         (str (subs conv 0 500) "...")
                                         conv)))
                                conventions))
         "\n")))

(defn get-kg-file-knowledge
  "Check KG for existing knowledge about a file before reading it."
  [file-path]
  (let [fallback {:disc nil :stale? true :staleness-score 1.0
                  :knowledge-entries [] :has-knowledge? false}
        result (r/guard Exception fallback
                        (let [disc (kg-disc/get-disc file-path)
                              stale? (when disc
                                       (let [{:keys [hash exists?]} (kg-disc/file-content-hash file-path)]
                                         (or (not exists?)
                                             (and hash (:disc/content-hash disc)
                                                  (not= hash (:disc/content-hash disc))))))
                              staleness (when disc (kg-disc/staleness-score disc))
                              grounded-entries (r/rescue nil
                                                         (when (mem-proto/store-set?)
                                                           (->> (mem-proto/query-entries (mem-proto/get-store) {:limit 10})
                                                                (filter #(= file-path (get-in % [:metadata :source-file])))
                                                                (take 5))))]
                          {:disc disc
                           :stale? (boolean stale?)
                           :staleness-score (or staleness 1.0)
                           :knowledge-entries (vec (or grounded-entries []))
                           :has-knowledge? (or (some? disc) (seq grounded-entries))}))]
    (when-let [err (::r/error (meta result))]
      (log/debug "KG file knowledge lookup failed:" (:message err)))
    result))

(defn format-kg-file-context
  "Format KG knowledge about a file as context string for drone injection."
  [kg-info file-path]
  (when (:has-knowledge? kg-info)
    (let [{:keys [disc stale? staleness-score knowledge-entries]} kg-info
          sections (cond-> []
                     disc
                     (conj (str "- Disc state: "
                                (if stale? "STALE (file changed)" "fresh")
                                " (staleness: " (format "%.1f" (float staleness-score)) ")"
                                (when (:disc/read-count disc)
                                  (str ", read " (:disc/read-count disc) " times"))
                                (when (:disc/analyzed-at disc)
                                  (str ", last analyzed: " (:disc/analyzed-at disc)))))

                     (seq knowledge-entries)
                     (conj (str "- " (count knowledge-entries) " knowledge entries grounded from this file:"
                                (str/join "\n  "
                                          (map (fn [e]
                                                 (str "  * [" (or (:type e) "note") "] "
                                                      (subs (str (:content e))
                                                            0 (min 100 (count (str (:content e)))))))
                                               knowledge-entries)))))]
      (when (seq sections)
        (str "### KG Knowledge for " (last (str/split file-path #"/")) "\n"
             (str/join "\n" sections)
             "\n")))))

(defn build-drone-context
  "Build rich context for drone delegation."
  [{:keys [file-path task project-root project-id target-line]}]
  ;; SECURITY FIX: Validate path containment using canonical resolution
  (let [file-obj (io/file (if (str/starts-with? file-path "/")
                            file-path
                            (str project-root "/" file-path)))
        abs-path (r/rescue nil
                           (let [canonical (.getCanonicalPath file-obj)
                                 root-canonical (.getCanonicalPath (io/file project-root))]
                             (if (str/starts-with? canonical root-canonical)
                               canonical
                               (do
                                 (log/warn "Path escapes project directory in build-drone-context"
                                           {:file file-path :canonical canonical :root root-canonical})
                                 nil))))]
    (when-not abs-path
      (log/warn "Skipping context build for invalid path" {:file file-path}))
    (when abs-path
      (let [;; KG-first: Check knowledge graph for existing file knowledge
            kg-info (get-kg-file-knowledge abs-path)
            kg-context-str (format-kg-file-context kg-info abs-path)
            ;; Gather context components
            snippet (read-surrounding-lines abs-path target-line {:context 20})
            imports (extract-imports abs-path)
            related (find-related-symbols abs-path task)
            lint (get-existing-warnings abs-path {:level :warning})
            conventions (get-relevant-conventions task project-id)
            ;; Format each section (KG context injected first for priority)
            formatted (str
                       (when kg-context-str
                         (str kg-context-str "\n"))
                       (format-imports-context imports)
                       "\n"
                       (when snippet
                         (str "### File Context (lines " (:start-line snippet)
                              "-" (:end-line snippet) ")\n"
                              "```clojure\n"
                              (:content snippet)
                              "\n```\n"))
                       "\n"
                       (format-related-symbols related)
                       "\n"
                       (format-lint-context lint file-path)
                       "\n"
                       (format-conventions-context conventions))]
        {:file-snippet snippet
         :imports imports
         :related-fns related
         :lint-issues lint
         :conventions conventions
         :kg-info kg-info
         :formatted formatted}))))

(defn format-full-context
  "Format full drone context for task augmentation."
  [context-data]
  (when context-data
    (str "## Smart Context (Auto-Generated)\n"
         (:formatted context-data)
         "\n")))
