(ns hive-mcp.agent.agent-definition
  "Declarative agent definitions — malli schema + markdown loader.

   Ports Claude Code's AgentDefinition pattern to hive's Clojure ecosystem.
   Agents are declared via:
   1. Built-in definitions (code, :source :built-in)
   2. Project definitions (.claude/agents/*.md in project root, :source :project)
   3. User definitions   (~/.claude/agents/*.md, :source :user)

   Later sources override earlier ones (user > project > built-in).

   Markdown files use YAML frontmatter for metadata, body becomes :system-prompt.

   Example .claude/agents/reviewer.md:
   ```
   ---
   name: reviewer
   description: Code review specialist that checks for bugs and style issues.
   tools:
     - Read
     - Grep
     - Glob
   model: inherit
   max-turns: 15
   skills:
     - simplify
   ---
   You are a code reviewer. Focus on correctness, clarity, and idiomatic style.
   ```

   Schema follows CC's AgentDefinition type with Clojure adaptations:
   - agentType     → :agent-type
   - whenToUse     → :description (CC's 'description' frontmatter field)
   - tools         → :tools (vec of strings; [\"*\"] = all tools)
   - disallowedTools → :disallowed-tools
   - model         → :model (string or :inherit)
   - maxTurns      → :max-turns
   - prompt/body   → :system-prompt (string or 0-arity fn)
   - source        → :source (:built-in, :project, :user, :plugin)
   - hooks         → :hooks (optional map)
   - mcpServers    → :mcp-servers (optional vec)
   - skills        → :skills (optional vec of strings)

   Leaf namespace for schema definitions — no heavy hive-mcp deps.
   Uses clj-yaml (already in deps.edn) for frontmatter parsing."

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

  (:require [malli.core :as m]
            [malli.error :as me]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Enumeration Schemas (Value Objects)
;; =============================================================================

(def Source
  "Where the agent definition came from.
   Priority (low→high): :built-in < :plugin < :project < :user
   Higher-priority sources override lower ones during merge."
  [:enum :built-in :project :user :plugin])

(def ^:private source-priority
  "Numeric priority for merge ordering. Higher wins."
  {:built-in 0
   :plugin   1
   :project  2
   :user     3})

;; =============================================================================
;; Sub-Schemas
;; =============================================================================

(def ToolSpec
  "Tool specification: either a tool name string or \"*\" for all tools.
   When [\"*\"] is specified, the agent has access to all available tools."
  [:vector :string])

(def HooksSpec
  "Hooks configuration map. Keys are lifecycle event names,
   values are hook handler specifications.

   Example:
   {:pre-tool-call  [{:command \"validate.sh\" :timeout 5000}]
    :post-tool-call [{:command \"log.sh\"}]}"
  [:map-of :keyword [:vector [:map
                              [:command :string]
                              [:timeout {:optional true} :int]]]])

(def McpServerSpec
  "MCP server specification — either a reference by name (string)
   or an inline definition map.

   Examples:
   - \"slack\"                         ; reference existing server
   - {\"my-server\" {:command \"node\" :args [\"server.js\"]}} ; inline"
  [:or :string [:map-of :string :any]])

;; =============================================================================
;; Agent Definition Schema
;; =============================================================================

(def AgentDefinition
  "Malli schema for a declarative agent definition.

   Required fields:
   - :agent-type    Unique identifier (e.g. \"general-purpose\", \"explore\")
   - :description   When-to-use description (shown in agent picker)
   - :system-prompt The agent's system prompt (string) or 0-arity fn returning string

   Optional fields:
   - :tools            Allowed tool names, or [\"*\"] for all (default: all)
   - :disallowed-tools Explicitly disallowed tool names
   - :model            Model override (string) or \"inherit\" for parent's model
   - :max-turns        Maximum agentic turns before stopping
   - :source           Where this definition came from
   - :hooks            Lifecycle hooks map
   - :mcp-servers      MCP server specs specific to this agent
   - :skills           Skill names to preload (e.g. [\"simplify\" \"review-pr\"])
   - :filename         Original .md filename (without extension)
   - :base-dir         Directory the definition was loaded from
   - :spawn-mode       Preferred spawn mode keyword (links to spawn-mode-registry)
   - :hivemind-role    Hivemind role ADT keyword (default: :role/standalone)
                       See hive-mcp.agent.hivemind-role for ADT dispatch"
  [:map {:closed false}
   [:agent-type :string]
   [:description :string]
   [:system-prompt [:or :string fn?]]
   [:source {:optional true :default :built-in} Source]
   [:tools {:optional true} ToolSpec]
   [:disallowed-tools {:optional true} ToolSpec]
   [:model {:optional true} [:or :string [:= :inherit]]]
   [:max-turns {:optional true} pos-int?]
   [:hooks {:optional true} HooksSpec]
   [:mcp-servers {:optional true} [:vector McpServerSpec]]
   [:skills {:optional true} [:vector :string]]
   [:filename {:optional true} [:maybe :string]]
   [:base-dir {:optional true} [:maybe :string]]
   [:spawn-mode {:optional true} :keyword]
   [:hivemind-role {:optional true :default :role/standalone}
    [:enum :role/hivemind :role/worker :role/standalone]]])

;; =============================================================================
;; Record: AgentDef (internal domain representation)
;; =============================================================================

(defrecord AgentDef
  [^String agent-type
   ^String description
   system-prompt   ;; String or 0-arity fn
   source          ;; :built-in | :project | :user | :plugin
   tools           ;; vec of strings, or nil (= all tools)
   disallowed-tools ;; vec of strings, or nil
   model           ;; string, :inherit, or nil
   max-turns       ;; pos-int or nil
   hooks           ;; map or nil
   mcp-servers     ;; vec or nil
   skills          ;; vec of strings or nil
   filename        ;; string or nil
   base-dir        ;; string or nil
   spawn-mode      ;; keyword or nil
   hivemind-role]) ;; :role/hivemind | :role/worker | :role/standalone | nil

(defn agent-def?
  "Is x an AgentDef record?"
  [x]
  (instance? AgentDef x))

(defn ->map
  "Convert an AgentDef record to a plain map (for boundary output).
   Strips nil-valued optional fields for clean serialization.
   No-op if already a plain map."
  [agent-def]
  (if (agent-def? agent-def)
    (into {} (remove (comp nil? val)) agent-def)
    agent-def))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate
  "Validate an agent definition (map or record) against the AgentDefinition schema.

   Accepts both plain maps and AgentDef records (records are coerced to maps
   for schema validation — malli validates maps, not records).

   Returns:
   - {:valid true :data agent-def} on success (returns the input map form)
   - {:valid false :errors {...}} on failure with humanized errors

   Example:
   (validate {:agent-type \"explore\" :description \"Fast searcher\" :system-prompt \"You search.\"})
   ;=> {:valid true :data {...}}"
  [agent-def]
  (let [m (->map agent-def)]
    (if (m/validate AgentDefinition m)
      {:valid true :data m}
      {:valid false
       :errors (me/humanize (m/explain AgentDefinition m))})))

(defn valid?
  "Predicate: is this a valid agent definition (map or record)?"
  [agent-def]
  (m/validate AgentDefinition (->map agent-def)))

(defn explain
  "Human-readable explanation of why an agent definition is invalid.
   Accepts maps or records. Returns nil if valid."
  [agent-def]
  (let [m (->map agent-def)]
    (when-not (m/validate AgentDefinition m)
      (me/humanize (m/explain AgentDefinition m)))))

(defn validate-or-throw!
  "Validate and return agent-def, or throw ex-info with humanized errors.
   Use at definition registration boundaries."
  [agent-def]
  (let [m      (->map agent-def)
        result (validate m)]
    (if (:valid result)
      (:data result)
      (throw (ex-info (str "Invalid agent definition for "
                           (or (:agent-type m) "<unknown>")
                           ": " (pr-str (:errors result)))
                      {:agent-type (:agent-type m)
                       :errors (:errors result)})))))

;; =============================================================================
;; Boundary Constructor: map → AgentDef record
;; =============================================================================

(defn make-agent-def
  "Validate a plain map and convert to an AgentDef record.
   This is the boundary crossing point: maps external → record internal.

   - Validates against the AgentDefinition malli schema
   - Applies defaults (:source → :built-in, :hivemind-role → :role/standalone)
   - Returns an AgentDef record for internal domain use
   - Throws ex-info on validation failure

   Use at registration boundaries where external data enters the domain.
   Keyword access on the returned record works identically to maps:
     (:agent-type (make-agent-def {...})) => \"explore\"

   Example:
   (make-agent-def {:agent-type \"explore\" :description \"Fast\" :system-prompt \"You search.\"})
   ;=> #hive_mcp.agent.agent_definition.AgentDef{:agent-type \"explore\" ...}"
  [m]
  {:pre [(map? m)]}
  (let [validated (validate-or-throw! m)
        with-defaults (merge {:source        :built-in
                              :hivemind-role :role/standalone}
                             validated)]
    (map->AgentDef with-defaults)))

;; =============================================================================
;; Frontmatter Parsing
;; =============================================================================

(def ^:private frontmatter-regex
  "Regex to match YAML frontmatter delimited by --- markers.
   Group 1 captures the YAML content between markers."
  #"(?s)\A---\s*\n(.*?)---\s*\n?(.*)")

(defn parse-frontmatter
  "Parse markdown content into {:frontmatter <map> :content <string>}.

   Splits on YAML frontmatter delimiters (---).
   If no frontmatter found, returns {:frontmatter {} :content raw-text}.
   Malformed YAML returns {:frontmatter {} :content raw-text :parse-error msg}.

   Example:
   (parse-frontmatter \"---\\nname: foo\\n---\\nBody text\")
   ;=> {:frontmatter {:name \"foo\"} :content \"Body text\"}"
  [raw-text]
  (if-let [match (re-matches frontmatter-regex raw-text)]
    (let [yaml-text (nth match 1)
          body      (nth match 2)]
      (try
        (let [parsed (yaml/parse-string yaml-text)]
          (if (and parsed (map? parsed))
            {:frontmatter parsed :content (str/trim body)}
            {:frontmatter {} :content (str/trim body)}))
        (catch Exception e
          {:frontmatter {} :content (str/trim body)
           :parse-error (.getMessage e)})))
    {:frontmatter {} :content raw-text}))

;; =============================================================================
;; Frontmatter → Agent Definition Coercion
;; =============================================================================

(defn- parse-tools
  "Parse tools from frontmatter value.
   Handles: nil, string, vector of strings.
   nil → nil (meaning: all tools)
   [\"*\"] → nil (all tools — CC convention)
   [...] → vector of tool name strings"
  [tools-value]
  (cond
    (nil? tools-value)    nil
    (string? tools-value) (if (= "*" tools-value) nil [tools-value])
    (sequential? tools-value)
    (let [tools (mapv str tools-value)]
      (if (some #(= "*" %) tools) nil tools))
    :else nil))

(defn- parse-model
  "Parse model from frontmatter value.
   \"inherit\" → :inherit
   non-empty string → trimmed string
   else → nil"
  [model-value]
  (when (and (string? model-value)
             (not (str/blank? model-value)))
    (let [trimmed (str/trim model-value)]
      (if (= "inherit" (str/lower-case trimmed))
        :inherit
        trimmed))))

(defn- parse-pos-int
  "Parse a positive integer from frontmatter value.
   Returns nil if not a valid positive integer."
  [value]
  (cond
    (and (integer? value) (pos? value)) value
    (string? value)
    (try
      (let [n (Long/parseLong value)]
        (when (pos? n) n))
      (catch NumberFormatException _ nil))
    :else nil))

(defn- parse-string-vec
  "Parse a vector of strings from frontmatter value.
   Handles: nil, string (comma-separated), vector."
  [value]
  (cond
    (nil? value)          nil
    (string? value)       (when-not (str/blank? value)
                            (mapv str/trim (str/split value #",")))
    (sequential? value)   (vec (filter string? value))
    :else                 nil))

(defn- parse-hooks
  "Parse hooks from frontmatter value (map of event→handler-specs)."
  [hooks-value]
  (when (map? hooks-value)
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) k (keyword k))
                  (if (sequential? v)
                    (vec v)
                    [v])]))
          hooks-value)))

(defn- parse-mcp-servers
  "Parse MCP server specs from frontmatter value."
  [value]
  (when (sequential? value)
    (vec (keep (fn [item]
                 (cond
                   (string? item) item
                   (map? item)    item
                   :else          nil))
               value))))

(defn frontmatter->agent-def
  "Convert parsed frontmatter + body content into an agent definition map.

   Frontmatter fields (YAML):
   - name:            → :agent-type (required)
   - description:     → :description (required)
   - tools:           → :tools
   - disallowed-tools: or disallowedTools: → :disallowed-tools
   - model:           → :model
   - max-turns: or maxTurns: → :max-turns
   - hooks:           → :hooks
   - mcp-servers: or mcpServers: → :mcp-servers
   - skills:          → :skills
   - hivemind-role: or hivemindRole: → :hivemind-role

   Body content → :system-prompt

   Returns nil if required fields are missing (silently skips non-agent .md files).
   Returns {:error msg} if name is present but other required fields are missing."
  [frontmatter content source & [{:keys [file-path base-dir]}]]
  (let [;; CC uses 'name' in frontmatter for agent-type
        agent-type  (or (get frontmatter :name)
                        (get frontmatter "name"))
        description (or (get frontmatter :description)
                        (get frontmatter "description"))
        ;; Support both kebab-case and camelCase keys (CC compat)
        tools-raw          (or (get frontmatter :tools)
                               (get frontmatter "tools"))
        disallowed-raw     (or (get frontmatter :disallowed-tools)
                               (get frontmatter "disallowed-tools")
                               (get frontmatter :disallowedTools)
                               (get frontmatter "disallowedTools"))
        model-raw          (or (get frontmatter :model)
                               (get frontmatter "model"))
        max-turns-raw      (or (get frontmatter :max-turns)
                               (get frontmatter "max-turns")
                               (get frontmatter :maxTurns)
                               (get frontmatter "maxTurns"))
        hooks-raw          (or (get frontmatter :hooks)
                               (get frontmatter "hooks"))
        mcp-servers-raw    (or (get frontmatter :mcp-servers)
                               (get frontmatter "mcp-servers")
                               (get frontmatter :mcpServers)
                               (get frontmatter "mcpServers"))
        skills-raw         (or (get frontmatter :skills)
                               (get frontmatter "skills"))
        spawn-mode-raw     (or (get frontmatter :spawn-mode)
                               (get frontmatter "spawn-mode"))
        hm-role-raw        (or (get frontmatter :hivemind-role)
                               (get frontmatter "hivemind-role")
                               (get frontmatter :hivemindRole)
                               (get frontmatter "hivemindRole"))]
    ;; Silently skip files without a name (likely co-located docs)
    (when (and agent-type (string? agent-type))
      (if-not (and description (string? description))
        ;; Name present but description missing → report error
        {:error (str "Agent '" agent-type "' missing required 'description' in frontmatter")}
        ;; Build the definition map
        (let [system-prompt (if (str/blank? content)
                              (str "You are " agent-type ".")
                              content)
              tools         (parse-tools tools-raw)
              disallowed    (parse-tools disallowed-raw)
              model         (parse-model model-raw)
              max-turns     (parse-pos-int max-turns-raw)
              hooks         (parse-hooks hooks-raw)
              mcp-servers   (parse-mcp-servers mcp-servers-raw)
              skills        (parse-string-vec skills-raw)
              spawn-mode    (when (and spawn-mode-raw
                                       (or (keyword? spawn-mode-raw)
                                           (string? spawn-mode-raw)))
                              (if (keyword? spawn-mode-raw)
                                spawn-mode-raw
                                (keyword spawn-mode-raw)))
              hm-role       (when hm-role-raw
                              (let [valid-roles #{:role/hivemind :role/worker :role/standalone}
                                    kw (cond
                                         (keyword? hm-role-raw) hm-role-raw
                                         (string? hm-role-raw)  (keyword hm-role-raw)
                                         :else nil)]
                                (when (valid-roles kw) kw)))
              filename      (when file-path
                              (let [fname (last (str/split (str file-path) #"/"))]
                                (str/replace fname #"\.md$" "")))]
          (cond-> {:agent-type    (str agent-type)
                   :description   (str/replace (str description) #"\\n" "\n")
                   :system-prompt system-prompt
                   :source        source}
            tools        (assoc :tools tools)
            disallowed   (assoc :disallowed-tools disallowed)
            model        (assoc :model model)
            max-turns    (assoc :max-turns max-turns)
            hooks        (assoc :hooks hooks)
            mcp-servers  (assoc :mcp-servers mcp-servers)
            skills       (assoc :skills skills)
            spawn-mode   (assoc :spawn-mode spawn-mode)
            hm-role      (assoc :hivemind-role hm-role)
            filename     (assoc :filename filename)
            base-dir     (assoc :base-dir base-dir)))))))

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
          {:keys [frontmatter content parse-error]} (parse-frontmatter raw-text)]
      (if parse-error
        {:error (str "YAML parse error: " parse-error) :file (str file-path)}
        (let [result (frontmatter->agent-def
                      frontmatter content source
                      {:file-path (str file-path)
                       :base-dir  (or base-dir
                                      (str (.getParent (io/file file-path))))})]
          (cond
            (nil? result) nil  ;; Not an agent file
            (:error result) (assoc result :file (str file-path))
            :else result))))
    (catch Exception e
      {:error (str "Failed to read: " (.getMessage e))
       :file  (str file-path)})))

(defn load-agents-dir
  "Load all agent definitions from a directory of markdown files.

   Scans dir recursively for *.md files, parses YAML frontmatter,
   and converts to agent definition maps.

   Returns {:agents [<agent-def> ...] :errors [<error-map> ...]}

   Example:
   (load-agents-dir \"/home/user/.claude/agents\" :user)
   ;=> {:agents [{:agent-type \"reviewer\" ...}]
   ;    :errors [{:error \"...\" :file \"...\"}]}"
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
        ;; Sort by source priority so later overwrites earlier in reduce
        sorted   (sort-by #(get source-priority (:source % :built-in) 0) all-defs)
        ;; Last write wins per agent-type
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

;; =============================================================================
;; Print Method (clean REPL output)
;; =============================================================================

(defmethod print-method AgentDef
  [^AgentDef agent-def ^java.io.Writer w]
  (.write w (str "#AgentDef{:agent-type "
                 (pr-str (:agent-type agent-def))
                 " :source "
                 (pr-str (:source agent-def))
                 "}")))
