(ns hive-mcp.events.handlers.memory-read
  "Memory read-path event handlers.

   Routes memory query/search/get through the event system so that
   cross-cutting concerns (carto-filter, memory-tracking) can be
   injected as interceptors instead of alter-var-root wrapping.

   Events:
   - :memory/query  - Structured memory query by type/tags/scope
   - :memory/search - Semantic (vector) memory search
   - :memory/get    - Single entry retrieval by ID

   Uses requiring-resolve to avoid circular deps (events -> tools -> chroma)."

  (:require [hive-mcp.events.core :as ev]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Handlers
;; =============================================================================

(defn handle-memory-query
  "Handler for :memory/query events.
   Delegates to hive-mcp.tools.memory.crud.query/handle-query."
  [_coeffects event]
  (let [params (second event)
        handler (requiring-resolve 'hive-mcp.tools.memory.crud.query/handle-query)]
    {:mcp-response (handler params)}))

(defn handle-memory-search
  "Handler for :memory/search events.
   Delegates to hive-mcp.tools.memory.search/handle-search-semantic."
  [_coeffects event]
  (let [params (second event)
        handler (requiring-resolve 'hive-mcp.tools.memory.search/handle-search-semantic)]
    {:mcp-response (handler params)}))

(defn handle-memory-get
  "Handler for :memory/get events.
   Delegates to hive-mcp.tools.memory.crud.retrieve/handle-get-full."
  [_coeffects event]
  (let [params (second event)
        handler (requiring-resolve 'hive-mcp.tools.memory.crud.retrieve/handle-get-full)]
    {:mcp-response (handler params)}))

(defn handle-memory-get-metadata
  "Handler for :memory/get-metadata events.
   Delegates to hive-mcp.tools.memory.crud.retrieve/handle-get-metadata."
  [_coeffects event]
  (let [params (second event)
        handler (requiring-resolve 'hive-mcp.tools.memory.crud.retrieve/handle-get-metadata)]
    {:mcp-response (handler params)}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-handlers!
  "Register memory read-path event handlers.
   Called from hive-mcp.events.handlers/register-handlers!."
  []
  (ev/reg-event :memory/query  [ev/metrics] handle-memory-query)
  (ev/reg-event :memory/search [ev/metrics] handle-memory-search)
  (ev/reg-event :memory/get    [ev/metrics] handle-memory-get)
  (ev/reg-event :memory/get-metadata [ev/metrics] handle-memory-get-metadata))
