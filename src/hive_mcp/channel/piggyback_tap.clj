(ns hive-mcp.channel.piggyback-tap
  "Standalone piggyback drain for non-MCP execution paths.

   The MCP path uses wrap-handler-piggybacks (routes.clj) which is wired
   into the make-tool middleware chain. But the agentic loop path
   (executor -> registry) calls raw handlers, bypassing all middleware.

   This module provides drain-all! which collects pending messages from
   all 4 piggyback channels (async, memory, catchup, hivemind) for a
   given agent-id. Called by executor after each tool-call batch to
   ensure headless/OpenRouter lings receive hivemind shouts.

   Channel drain order (matches wrap-handler-piggybacks):
   1. TOOLRESULT — async completion results
   2. MEMORY — axioms, conventions, enrichment batches
   3. Catchup enrichment blocks
   4. HIVEMIND — agent shouts"
  (:require [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.channel.async-result :as async-buf]
            [hive-mcp.extensions.registry :as ext]
            [hive-dsl.context.identity :as ctx-id]
            [clojure.string :as str]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]
            [hive-mcp.channel.conversation-inbox :as conv-inbox]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- drain-memory-piggyback
  "Drain memory piggyback for a caller-id. Returns drain result or nil."
  ([caller-id] (drain-memory-piggyback caller-id nil))
  ([caller-id ctx]
   (try
     (require 'hive-mcp.channel.memory-piggyback)
     ((resolve 'hive-mcp.channel.memory-piggyback/drain!) caller-id ctx)
     (catch Exception e
       (log/debug "piggyback-tap: memory drain failed:" (.getMessage e))
       nil))))

(defn- drain-catchup-piggyback
  "Drain catchup enrichment blocks. Returns map of tag->body or nil."
  [caller-id]
  (when-let [drain-fn (ext/get-extension :cu/piggyback-drain)]
    (try (drain-fn caller-id)
         (catch Exception e
           (log/debug "piggyback-tap: catchup drain failed:" (.getMessage e))
           nil))))

(defn- resolve-child-project-ids
  "Resolve descendant project-ids using the project hierarchy tree
   (.hive-project.edn), NOT the Datascript slave registry. Tree-based
   resolution survives ling cleanup — shouts from terminated child-project
   lings remain visible to the parent coordinator."
  [project-id]
  (when project-id
    (rescue nil (when-let [desc-fn (requiring-resolve 'hive-mcp.knowledge-graph.scope/descendant-scopes)]
        (let [child-pids (desc-fn project-id)]
          (when (seq child-pids) (set child-pids)))))))

(defn- drain-hivemind-piggyback
  "Drain hivemind messages for an agent+project. Returns formatted messages or nil.
   Includes shouts from cross-project descendants via child project-id resolution."
  [agent-id project-id]
  (piggyback/get-messages agent-id
                          :project-id project-id
                          :additional-project-ids (resolve-child-project-ids project-id)))

(defn- format-block
  "Format a single piggyback block as delimited text."
  [tag body]
  (when body
    (str "\n---" tag "---\n"
         (if (string? body) body (pr-str body))
         "\n---/" tag "---")))

(defn drain-all!
  "Drain all 5 piggyback channels for an agent.

   Arguments:
   - agent-id:   The ling/drone identity (e.g. \"ling-xyz\")
   - project-id: Project scope string (e.g. \"hive-mcp\"), or nil for global
   - ctx:        Drain ctx forwarded to the MEMORY channel, or nil for FIFO

   Channels (in render order):
     1. TOOLRESULT — async completion results
     2. MEMORY — axioms, conventions, enrichment batches
     3. INBOX — per-agent conversation envelopes (tell/ask/respond)
     4. Catchup enrichment blocks
     5. HIVEMIND — agent shouts

   Returns a string of concatenated delimited blocks, or nil if all channels empty.
   Designed to be appended to tool observation text in the agentic loop."
  ([agent-id project-id] (drain-all! agent-id project-id nil))
  ([agent-id project-id ctx]
   (try
     (let [caller-id agent-id  ; for session-scoped channels (async, memory, catchup)

           ;; 1. Async results
           async-drain (async-buf/drain! caller-id)

           ;; 2. Memory entries
           memory-drain (drain-memory-piggyback caller-id ctx)

           ;; 3. Conversation inbox (per-agent)
           inbox-drain (try (conv-inbox/drain! agent-id)
                            (catch Exception e
                              (log/debug "piggyback-tap: inbox drain failed:"
                                         (.getMessage e))
                              nil))

           ;; 4. Catchup enrichment blocks
           catchup-blocks (drain-catchup-piggyback caller-id)

           ;; 5. Hivemind (project-scoped cursor)
           hm-caller (ctx-id/parse-caller-id agent-id)
           hm-scope (ctx-id/parse-project-scope project-id)
           hm-agent-id (ctx-id/make-piggyback-agent-id hm-caller hm-scope)
           hm-project-id (ctx-id/project-scope-string hm-scope)
           hivemind-msgs (drain-hivemind-piggyback hm-agent-id hm-project-id)

           ;; Build concatenated blocks
           blocks (cond-> []
                    async-drain
                    (conj (format-block "TOOLRESULT" (pr-str async-drain)))

                    memory-drain
                    (conj (format-block "MEMORY" (pr-str memory-drain)))

                    inbox-drain
                    (conj (format-block "INBOX" (pr-str inbox-drain)))

                    (seq catchup-blocks)
                    (into (for [[tag body] catchup-blocks]
                            (format-block
                             (clojure.string/upper-case (name tag))
                             (if (string? body) body (pr-str body)))))

                    (seq hivemind-msgs)
                    (conj (format-block "HIVEMIND" (pr-str hivemind-msgs))))]

       (when (seq blocks)
         (let [result (apply str blocks)]
           (log/debug "piggyback-tap: drained for" agent-id
                      {:async? (some? async-drain)
                       :memory? (some? memory-drain)
                       :inbox? (some? inbox-drain)
                       :catchup? (some? (seq catchup-blocks))
                       :hivemind? (some? (seq hivemind-msgs))})
           result)))
     (catch Exception e
       (log/warn "piggyback-tap: drain-all! failed:" (.getMessage e))
       nil))))
