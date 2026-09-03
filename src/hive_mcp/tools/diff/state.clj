(ns hive-mcp.tools.diff.state
  "Diff state management: atoms, error helpers, TDD status."
  (:require [clojure.data.json :as json]
            [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bclear! register-sweepable!]]
            [hive-mcp.server.guards :as guards]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn mcp-error-json
  "Create an error MCP response with JSON-encoded error message."
  [error-message]
  {:type "text"
   :text (json/write-str {:error error-message})
   :isError true})

;; gc-fix-3: pending-diffs — LRU eviction, 200 entries, 30min TTL
;; Diffs are short-lived proposals; stale ones waste memory.
(defonce pending-diffs
  (bounded-atom {:max-entries 200
                 :ttl-ms 1800000    ;; 30 minutes
                 :eviction-policy :lru}))
(register-sweepable! pending-diffs :pending-diffs)

(def default-auto-approve-rules
  "Default rules for auto-approving diff proposals."
  {:max-lines-changed 100
   :no-deletions-only true
   :require-description true
   :allowed-path-patterns [#".*\.clj[sx]?$"
                           #".*\.edn$"
                           #".*\.md$"
                           #".*\.json$"]})

(defonce auto-approve-rules (atom default-auto-approve-rules))

(defn clear-pending-diffs!
  "Clear pending diffs (guarded, no-op if coordinator running)."
  []
  (guards/when-not-coordinator
   "clear-pending-diffs! called"
   (bclear! pending-diffs)))

(defn update-diff-tdd-status!
  "Update a diff's TDD status after drone runs tests/lint."
  [diff-id tdd-status]
  (when (bget pending-diffs diff-id)
    (let [current (bget pending-diffs diff-id)]
      (when current
        (bput! pending-diffs diff-id
               (update current :tdd-status
                       (fn [existing]
                         (merge existing tdd-status))))))
    (log/info "Updated diff TDD status" {:diff-id diff-id :tdd-status tdd-status})
    (bget pending-diffs diff-id)))
