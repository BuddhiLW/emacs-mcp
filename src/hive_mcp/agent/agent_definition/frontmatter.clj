(ns hive-mcp.agent.agent-definition.frontmatter
  "YAML frontmatter parsing and coercion into agent-def maps.

   Pulled out of `compose` so each SLAP layer stays under the hard
   LOC ceiling. This is still conceptually part of the compose step
   (turning raw source into a definition); `compose.clj` orchestrates
   the filesystem walk and reuses these helpers."

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]))

;; =============================================================================
;; Raw Frontmatter Split
;; =============================================================================

(def ^:private frontmatter-regex
  "Regex to match YAML frontmatter delimited by --- markers.
   Group 1 captures the YAML content between markers."
  #"(?s)\A---\s*\n(.*?)---\s*\n?(.*)")

(defn parse-frontmatter
  "Parse markdown content into {:frontmatter <map> :content <string>}.

   Splits on YAML frontmatter delimiters (---).
   If no frontmatter found, returns {:frontmatter {} :content raw-text}.
   Malformed YAML returns {:frontmatter {} :content raw-text :parse-error msg}."
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
;; Per-Field Coercion Helpers (private)
;; =============================================================================

(defn- parse-tools
  "nil/\"*\"/[\"*\"] → nil (= all tools). Otherwise a vector of tool names."
  [tools-value]
  (cond
    (nil? tools-value)    nil
    (string? tools-value) (if (= "*" tools-value) nil [tools-value])
    (sequential? tools-value)
    (let [tools (mapv str tools-value)]
      (if (some #(= "*" %) tools) nil tools))
    :else nil))

(defn- parse-model
  "\"inherit\" → :inherit. Non-blank string → trimmed. Else nil."
  [model-value]
  (when (and (string? model-value)
             (not (str/blank? model-value)))
    (let [trimmed (str/trim model-value)]
      (if (= "inherit" (str/lower-case trimmed))
        :inherit
        trimmed))))

(defn- parse-pos-int
  "Positive integer (from int or numeric string), else nil."
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
  "nil / comma-sep string / vector → vector of strings or nil."
  [value]
  (cond
    (nil? value)        nil
    (string? value)     (when-not (str/blank? value)
                          (mapv str/trim (str/split value #",")))
    (sequential? value) (vec (filter string? value))
    :else               nil))

(defn- parse-hooks
  "Map of event-kw → handler-specs vector, or nil."
  [hooks-value]
  (when (map? hooks-value)
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) k (keyword k))
                  (if (sequential? v) (vec v) [v])]))
          hooks-value)))

(defn- parse-mcp-servers
  "Sequence of string refs or inline maps, or nil."
  [value]
  (when (sequential? value)
    (vec (keep (fn [item]
                 (cond
                   (string? item) item
                   (map? item)    item
                   :else          nil))
               value))))

(defn- parse-spawn-mode
  "Coerce spawn-mode frontmatter value to keyword, or nil."
  [spawn-mode-raw]
  (when (and spawn-mode-raw
             (or (keyword? spawn-mode-raw)
                 (string? spawn-mode-raw)))
    (if (keyword? spawn-mode-raw)
      spawn-mode-raw
      (keyword spawn-mode-raw))))

(defn- parse-hivemind-role
  "Coerce hivemind-role frontmatter value to a valid role keyword, or nil."
  [hm-role-raw]
  (when hm-role-raw
    (let [valid-roles #{:role/hivemind :role/worker :role/standalone}
          kw (cond
               (keyword? hm-role-raw) hm-role-raw
               (string? hm-role-raw)  (keyword hm-role-raw)
               :else nil)]
      (when (valid-roles kw) kw))))

(defn- fm-get
  "Get a key from frontmatter, trying each alias (kw/string) in order.
   Returns the first non-nil value."
  [frontmatter & aliases]
  (some #(get frontmatter %) aliases))

;; =============================================================================
;; Frontmatter → agent-def Coercion
;; =============================================================================

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
  (let [agent-type      (fm-get frontmatter :name "name")
        description     (fm-get frontmatter :description "description")
        tools-raw       (fm-get frontmatter :tools "tools")
        disallowed-raw  (fm-get frontmatter
                                :disallowed-tools "disallowed-tools"
                                :disallowedTools  "disallowedTools")
        model-raw       (fm-get frontmatter :model "model")
        max-turns-raw   (fm-get frontmatter
                                :max-turns "max-turns"
                                :maxTurns  "maxTurns")
        hooks-raw       (fm-get frontmatter :hooks "hooks")
        mcp-servers-raw (fm-get frontmatter
                                :mcp-servers "mcp-servers"
                                :mcpServers  "mcpServers")
        skills-raw      (fm-get frontmatter :skills "skills")
        spawn-mode-raw  (fm-get frontmatter :spawn-mode "spawn-mode")
        hm-role-raw     (fm-get frontmatter
                                :hivemind-role "hivemind-role"
                                :hivemindRole  "hivemindRole")]
    (when (and agent-type (string? agent-type))
      (if-not (and description (string? description))
        {:error (str "Agent '" agent-type "' missing required 'description' in frontmatter")}
        (let [system-prompt (if (str/blank? content)
                              (str "You are " agent-type ".")
                              content)
              tools       (parse-tools tools-raw)
              disallowed  (parse-tools disallowed-raw)
              model       (parse-model model-raw)
              max-turns   (parse-pos-int max-turns-raw)
              hooks       (parse-hooks hooks-raw)
              mcp-servers (parse-mcp-servers mcp-servers-raw)
              skills      (parse-string-vec skills-raw)
              spawn-mode  (parse-spawn-mode spawn-mode-raw)
              hm-role     (parse-hivemind-role hm-role-raw)
              filename    (when file-path
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
