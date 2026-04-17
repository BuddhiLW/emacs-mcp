(ns hive-mcp.server.routes.identity
  "Content normalization and request identity extraction.

   Pure transforms (content helpers) and context resolution (identity extraction)
   for MCP tool requests. No middleware — just data functions.

   DDD: Value Object layer — content formatting and identity ADTs."
  (:require [hive-mcp.agent.context :as ctx]
            [hive-dsl.context.identity :as ctx-id]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Content Normalization (pure transforms)
;; =============================================================================

(defn normalize-content
  "Normalize handler result to content array.
   Handles: sequential (passthrough), map with :content (unwrap MCP response),
   map (wrap), other (text wrap)."
  [result]
  (cond
    (sequential? result) (vec result)
    (and (map? result) (:content result)) (:content result)
    (map? result) [result]
    :else [{:type "text" :text (str result)}]))

(defn find-last-text-idx
  "Find index of last text-type item in content (searching from end).
   Returns nil if no text item found."
  [content]
  (some (fn [[idx item]]
          (when (= "text" (:type item)) idx))
        (map-indexed vector (reverse content))))

(defn wrap-delimited-block
  "Append a delimited block to content.
   Format:
   ---TAG---
   <body>
   ---/TAG---"
  [content tag body]
  (if (and body (seq (str body)))
    (let [block-text (str "\n\n---" tag "---\n"
                          body
                          "\n---/" tag "---")]
      (if-let [last-text-idx (find-last-text-idx content)]
        (let [actual-idx (- (count content) 1 last-text-idx)
              last-item (nth content actual-idx)]
          (assoc content actual-idx
                 (update last-item :text str block-text)))
        (conj content {:type "text" :text block-text})))
    content))

(defn wrap-piggyback
  "Append piggyback messages to content with HIVEMIND delimiters."
  [content piggyback]
  (wrap-delimited-block content "HIVEMIND" (when (seq piggyback) (pr-str piggyback))))

(defn wrap-memory-piggyback-content
  "Append memory piggyback batch to content with MEMORY delimiters."
  [content drain-result]
  (wrap-delimited-block content "MEMORY" (when drain-result (pr-str drain-result))))


;; =============================================================================
;; Identity Extraction (request context resolution)
;; =============================================================================

(defn extract-agent-id
  "Extract agent-id from args map, handling both snake_case and kebab-case keys.
   Returns default if no agent-id found in args."
  [args default]
  (or (:agent_id args)
      (:agent-id args)
      default))

(defn extract-caller-id
  "Extract the actual MCP caller identity for piggyback cursor isolation.

   Priority:
   1. :_caller_id — injected by bb-mcp from CLAUDE_SWARM_SLAVE_ID.
   2. Fallback to 'coordinator' — for old bb-mcp versions.

   CRITICAL: Do NOT use :agent_id here. For dispatch-type tools,
   agent_id is the TARGET, not the caller."
  [args]
  (or (:_caller_id args) "coordinator"))

(defn extract-project-id
  "Extract project-id from args map with multi-level fallback.

   Key priority:
   1. Explicit project_id/project-id
   2. IVessel resolution (vessel owns agent-to-context mapping)
   3. Derived from directory via scope/get-current-project-id
   4. Derived from _caller_cwd (bb-mcp's cwd)
   5. Derived from ctx/current-directory
   6. Derived from server's working directory"
  [args]
  (or (:project_id args)
      (:project-id args)
      (when-let [agent-id (or (:agent_id args) (:agent-id args) (:_caller_id args))]
        (when-not (= agent-id "coordinator")
          (ctx/request-memoize
           [:vessel-project-id agent-id]
           (fn []
             (try
               (require 'hive-mcp.protocols.vessel)
               (when-let [resolve-fn (resolve 'hive-mcp.protocols.vessel/resolve-agent-context)]
                 (:project-id (resolve-fn agent-id)))
               (catch Exception e (log/trace "routes: vessel resolution failed for" agent-id (.getMessage e)) nil))))))
      (when-let [dir (or (:directory args)
                         (:_caller_cwd args)
                         (ctx/current-directory)
                         (System/getProperty "user.dir"))]
        (ctx/request-memoize
         [:project-id dir]
         (fn []
           (require 'hive-mcp.tools.memory.scope)
           ((resolve 'hive-mcp.tools.memory.scope/get-current-project-id) dir))))))

(defn extract-caller-identity
  "Extract CallerId ADT from MCP args."
  [args]
  (ctx-id/parse-caller-id (:_caller_id args)))

(defn extract-project-scope
  "Extract ProjectScope ADT from MCP args."
  [args]
  (ctx-id/parse-project-scope (extract-project-id args)))
