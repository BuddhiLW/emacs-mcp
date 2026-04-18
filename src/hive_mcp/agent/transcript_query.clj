(ns hive-mcp.agent.transcript-query
  "TranscriptQuery + TranscriptSource ADTs — closed sum types for MCP transcript operations.

   TranscriptQuery models the query intent (what to fetch).
   TranscriptSource models the backend selection (where to fetch from).

   Both use defadt for exhaustive adt-case dispatch.

   Usage:
     (require '[hive-mcp.agent.transcript-query :as tq])

     ;; Construct
     (tq/transcript-query :query/by-agent {:agent-id \"e2e-verify-1\"})
     (tq/transcript-source :source/auto)

     ;; Exhaustive dispatch
     (adt-case TranscriptQuery q
       :query/by-agent  (query-agent store (:agent-id q))
       :query/by-time   (query-range store (:start-ms q) (:end-ms q))
       :query/since     (query-since store (:agent-id q) (:turn q))
       :query/tail      (query-tail store (:agent-id q) (:n q)))"
  (:require [hive-dsl.adt :refer [defadt]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; TranscriptQuery ADT
;; =============================================================================

(defadt TranscriptQuery
  "Closed set of transcript query operations.

   Variants:
     :query/by-agent  — All entries for an agent, ordered by turn
     :query/by-time   — Entries in a time window (epoch ms)
     :query/since     — Entries after a given turn (live follow)
     :query/tail      — Last N entries for an agent"
  [:query/by-agent {:agent-id string?}]
  [:query/by-time  {:start-ms int? :end-ms int?}]
  [:query/since    {:agent-id string? :turn int?}]
  [:query/tail     {:agent-id string? :n int?}])

;; =============================================================================
;; TranscriptSource ADT
;; =============================================================================

(defadt TranscriptSource
  "Which transcript backend to query.

   Variants:
     :source/datalevin — Structured Datalog queries (preferred)
     :source/jsonl     — Raw JSONL file read (fallback/legacy)
     :source/auto      — Try datalevin, fall back to JSONL"
  :source/datalevin
  :source/jsonl
  :source/auto)
