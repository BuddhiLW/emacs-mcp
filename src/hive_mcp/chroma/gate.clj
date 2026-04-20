(ns hive-mcp.chroma.gate
  "Concurrency gate for ChromaDB operations.

   ChromaDB uses SQLite internally — concurrent HTTP requests can trigger
   WAL locking, causing hangs. Three named gates bound concurrency:

   - read-gate  (4 permits, 15s timeout) — queries, gets
   - write-gate (1 permit, 30s timeout)  — adds, updates, deletes
   - embed-gate (2 permits, 60s timeout) — Ollama/OpenRouter embedding calls

   All Chroma deref sites use `deref-read` or `deref-write` instead of
   bare `@`. All embedding calls use `with-embedding-gate`.

   Built on hive-weave.gate for bounded execution and Result integration."
  (:require [hive-weave.gate :as g]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Gate Instances
;; =============================================================================

(defonce read-gate
  (g/gate {:permits 4 :timeout-ms 15000 :name "chroma-read"}))

(defonce write-gate
  (g/gate {:permits 1 :timeout-ms 30000 :name "chroma-write"}))

(defonce embed-gate
  (g/gate {:permits 2 :timeout-ms 60000 :name "embedding"}))

;; =============================================================================
;; Convenience API — drop-in replacements for bare @ derefs
;; =============================================================================

(defn deref-read
  "Deref a Chroma read promise with concurrency gate + timeout.
   Replaces: @(chroma/query ...) or @(chroma/get ...)"
  ([promise]
   (g/deref-gate read-gate promise))
  ([promise timeout-ms]
   (g/deref-gate read-gate promise timeout-ms)))

(defn deref-write
  "Deref a Chroma write promise with exclusive gate + timeout.
   Replaces: @(chroma/add ...) or @(chroma/delete ...)"
  ([promise]
   (g/deref-gate write-gate promise))
  ([promise timeout-ms]
   (g/deref-gate write-gate promise timeout-ms)))

(defmacro with-embedding-gate
  "Execute body under embedding concurrency gate.
   Ollama serializes GPU work — this prevents piling up HTTP connections."
  [& body]
  `(g/with-gate embed-gate ~@body))

;; =============================================================================
;; Diagnostics
;; =============================================================================

(defn gate-stats
  "Current state of all three gates."
  []
  {:read  (g/gate-stats read-gate)
   :write (g/gate-stats write-gate)
   :embed (g/gate-stats embed-gate)})
