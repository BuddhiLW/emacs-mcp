(ns hive-mcp.swarm.datascript.connection
  "DataScript connection management for swarm state.

   Provides:
   - Global connection atom (thread-safe via DataScript internals)
   - Connection lifecycle (create, get, reset)
   - Shared helper functions (now, gen-id)"

  (:require [datascript.core :as d]
            [taoensso.timbre :as log]
            [hive-mcp.swarm.datascript.schema :as schema]
            [hive-mcp.server.guards :as guards]
            [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Connection Management (Thread-Safe Atom)
;;; =============================================================================

;; Global DataScript connection atom.
;; Thread-safe via DataScript's internal atom.
(defonce ^:private conn (atom nil))

(def ^:dynamic *test-conn*
  "Per-thread override for the global swarm connection.
   When non-nil, get-conn returns this instead of the global atom.
   Bound by hive-mcp.test-fixtures/with-isolated-swarm so tests run
   against a fresh in-memory conn without polluting prod state when
   evaluated inside the live MCP server's nREPL.
   Honors axiom 20260122235103-7151cc29 (Test Isolation Silent Server Death)."
  nil)

(defn create-conn
  "Create a new DataScript connection with swarm schema.
   Returns the connection (atom wrapper around db)."
  []
  (d/create-conn schema/schema))

(defn get-conn
  "Get the global swarm connection, creating if needed.
   Returns *test-conn* when bound (test isolation override)."
  []
  (or *test-conn*
      @conn
      (do
        (reset! conn (create-conn))
        (log/info "Created swarm DataScript connection")
        @conn)))

(defn reset-conn!
  "Reset the global connection to empty state."

  []
  (guards/when-not-coordinator
   "ds/reset-conn! blocked"
   (reset! conn (create-conn))
   (log/debug "Swarm DataScript connection reset")))

(defn ensure-conn
  "Ensure connection exists, return it."
  []
  (get-conn))

;;; =============================================================================
;;; Helper Functions
;;; =============================================================================

(defn now
  "Current timestamp as java.util.Date."
  []
  (java.util.Date.))

(defn gen-id
  "Generate a unique ID with optional prefix."
  ([] (str (java.util.UUID/randomUUID)))
  ([prefix] (str prefix "-" (java.util.UUID/randomUUID))))

(def DSConn
  "DataScript connection: atom wrapping a DB, satisfies d/conn?."
  [:fn {:error/message "must be a DataScript connection"} d/conn?])

(m/=> ensure-conn [:=> [:cat] DSConn])
