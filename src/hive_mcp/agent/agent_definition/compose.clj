(ns hive-mcp.agent.agent-definition.compose
  "Composition layer: load markdown agent files, merge across sources,
   and expose lookup helpers.

   Frontmatter parsing + coercion lives in the sibling namespace
   `hive-mcp.agent.agent-definition.frontmatter` and is re-exposed
   here (via the façade) for backward compatibility.

   SLAP role: compose — orchestration and assembly of definitions."

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-mcp.agent.agent-definition.frontmatter :as fm]
            [hive-mcp.agent.agent-definition.spec :as spec]))

;; Re-export the two pure frontmatter entry points so the façade (and any
;; external caller holding an `ad/` alias) can keep using them here.
(def parse-frontmatter      fm/parse-frontmatter)
(def frontmatter->agent-def fm/frontmatter->agent-def)

;; =============================================================================
;; Markdown File Loading
;; =============================================================================

(defn- md-file?
  "Is this a .md file?"
  [^java.io.File f]
  (and (.isFile f)
       (str/ends-with? (.getName f) ".md")))

(defn- walk-md-files
  "Recursively find all .md files under a directory.
   Returns a seq of java.io.File objects."
  [^java.io.File dir]
  (when (and dir (.isDirectory dir))
    (->> (file-seq dir)
         (filter md-file?))))

(defn load-agent-file
  "Load a single agent definition from a markdown file.

   Returns:
   - agent-def map on success
   - {:error msg :file path} on parse failure
   - nil if the file has no agent frontmatter (co-located docs)"
  [file-path source & [{:keys [base-dir]}]]
  (try
    (let [raw-text (slurp file-path)
          {:keys [frontmatter content parse-error]} (fm/parse-frontmatter raw-text)]
      (if parse-error
        {:error (str "YAML parse error: " parse-error) :file (str file-path)}
        (let [result (fm/frontmatter->agent-def
                      frontmatter content source
                      {:file-path (str file-path)
                       :base-dir  (or base-dir
                                      (str (.getParent (io/file file-path))))})]
          (cond
            (nil? result)   nil
            (:error result) (assoc result :file (str file-path))
            :else           result))))
    (catch Exception e
      {:error (str "Failed to read: " (.getMessage e))
       :file  (str file-path)})))

(defn load-agents-dir
  "Load all agent definitions from a directory of markdown files.

   Scans dir recursively for *.md files, parses YAML frontmatter,
   and converts to agent definition maps.

   Returns {:agents [<agent-def> ...] :errors [<error-map> ...]}"
  [dir-path source]
  (let [dir (io/file dir-path)]
    (if-not (.isDirectory dir)
      {:agents [] :errors []}
      (let [md-files (walk-md-files dir)
            results  (mapv #(load-agent-file (str %) source
                                             {:base-dir (str dir-path)})
                           md-files)
            agents   (filterv #(and (map? %) (not (:error %)) (:agent-type %)) results)
            errors   (filterv #(and (map? %) (:error %)) results)]
        {:agents agents :errors errors}))))

;; =============================================================================
;; Multi-Source Loading
;; =============================================================================

(defn find-agent-dirs
  "Find .claude/agents directories to scan.

   Searches (in priority order, lowest first):
   1. Project root: <cwd>/.claude/agents/   (source :project)
   2. User home:    ~/.claude/agents/        (source :user)

   Returns vector of [dir-path source] pairs."
  [cwd]
  (let [home     (System/getProperty "user.home")
        project  (str cwd "/.claude/agents")
        user-dir (str home "/.claude/agents")
        dirs     (cond-> []
                   (.isDirectory (io/file project))
                   (conj [project :project])
                   (.isDirectory (io/file user-dir))
                   (conj [user-dir :user]))]
    dirs))

(defn load-all-custom-agents
  "Load agent definitions from all .claude/agents directories.

   Scans project and user directories, returns merged results.
   Does NOT include built-in agents (those are registered separately).

   Returns {:agents [<agent-def> ...] :errors [<error-map> ...]}"
  [cwd]
  (let [dirs    (find-agent-dirs cwd)
        results (mapv (fn [[dir source]] (load-agents-dir dir source)) dirs)]
    {:agents (vec (mapcat :agents results))
     :errors (vec (mapcat :errors results))}))

;; =============================================================================
;; Definition Merging
;; =============================================================================

(defn merge-definitions
  "Merge agent definitions from multiple sources.

   Later sources override earlier ones when agent-type matches:
   built-in < plugin < project < user

   This allows users to override built-in agent behavior by creating
   a .claude/agents/<name>.md with the same agent-type name.

   Args:
   - definition-groups: seq of vectors of agent-def maps
     e.g. [built-in-agents plugin-agents project-agents user-agents]

   Returns: vector of merged agent definitions (unique by :agent-type)"
  [& definition-groups]
  (let [all-defs (apply concat definition-groups)
        sorted   (sort-by #(get spec/source-priority (:source % :built-in) 0) all-defs)
        merged   (reduce (fn [acc agent-def]
                           (assoc acc (:agent-type agent-def) agent-def))
                         {}
                         sorted)]
    (vec (vals merged))))

(defn active-agents
  "Get the list of active agent definitions, with overrides applied.

   Combines built-in, plugin, and custom (project + user) definitions.
   Later sources override earlier ones when :agent-type matches.

   Args:
   - built-ins: vector of built-in agent definitions
   - cwd:       project working directory for .claude/agents scanning
   - opts:      {:plugins [...]}  optional plugin agent definitions

   Returns {:active [<agent-def> ...]
            :all    [<agent-def> ...]
            :errors [<error> ...]}"
  [built-ins cwd & [{:keys [plugins] :or {plugins []}}]]
  (let [{:keys [agents errors]} (load-all-custom-agents cwd)
        all-defs   (concat built-ins plugins agents)
        active     (merge-definitions built-ins plugins agents)]
    {:active active
     :all    (vec all-defs)
     :errors (when (seq errors) errors)}))

;; =============================================================================
;; Lookup Helpers
;; =============================================================================

(defn find-by-type
  "Find an agent definition by :agent-type from a collection.
   Returns nil if not found."
  [agent-type definitions]
  (some #(when (= agent-type (:agent-type %)) %) definitions))

(defn built-in?
  "Is this agent definition a built-in?"
  [agent-def]
  (= :built-in (:source agent-def)))

(defn custom?
  "Is this agent definition from a custom source (project or user)?"
  [agent-def]
  (#{:project :user} (:source agent-def)))

(defn plugin?
  "Is this agent definition from a plugin?"
  [agent-def]
  (= :plugin (:source agent-def)))

(defn has-all-tools?
  "Does this agent have access to all tools?
   True when :tools is nil or absent (CC convention: nil = all tools)."
  [agent-def]
  (nil? (:tools agent-def)))

(defn get-system-prompt
  "Get the system prompt string from an agent definition.
   Handles both static strings and 0-arity fn prompts."
  [agent-def]
  (let [prompt (:system-prompt agent-def)]
    (if (fn? prompt)
      (prompt)
      prompt)))

;; =============================================================================
;; Pretty Printing (for MCP tool descriptions)
;; =============================================================================

(defn summarize
  "Create a brief summary of an agent definition for display.

   Returns string like:
   \"general-purpose (built-in) — General-purpose agent for multi-step tasks\""
  [agent-def]
  (str (:agent-type agent-def)
       " (" (name (or (:source agent-def) :unknown)) ")"
       " — " (:description agent-def)))

(defn list-summary
  "Create a summary of all agent definitions for display."
  [definitions]
  (str/join "\n" (map summarize definitions)))
