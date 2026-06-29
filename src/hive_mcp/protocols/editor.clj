(ns hive-mcp.protocols.editor
  "Protocol definitions for editor/UI backends.

   Abstracts Emacs integration behind an IEditor protocol so that
   domain code never depends on emacs.client directly.

   Pattern: follows protocols/kg.clj (active atom + NoopEditor fallback).
   Unlike kg.clj which throws when no store is set, get-editor returns
   NoopEditor — headless mode is a first-class citizen."
  (:require [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IEditor Protocol
;;; ============================================================================

(defprotocol IEditor
  "Backend protocol for editor/UI integration.

   Implementations:
   - EmacsclientEditor  (emacs/editor_adapter.clj)
   - NoopEditor         (this file, headless fallback)
   - [future: NATSEditor, WebSocketEditor, ...]"

  (editor-id [this]
    "Return keyword identifying this editor backend.
     Examples: :emacsclient, :noop, :nats")

  (available? [this]
    "Return true if the editor backend is reachable and functional.")

  (eval-expr [this code] [this code opts]
    "Evaluate an expression in the editor.
     Returns Result ADT: (ok result-string) or (err category data).
     opts may include :timeout-ms.")

  (feature-available? [this feature-name]
    "Check if a named feature/addon is loaded in the editor.
     Returns boolean. feature-name is a string (e.g. \"hive-mcp-swarm\").")

  (send-to-terminal [this terminal-id text]
    "Send text to a terminal/REPL buffer in the editor.
     Returns Result ADT: (ok true) or (err category data)."))

;;; ============================================================================
;;; Active Editor Management
;;; ============================================================================

(declare ->NoopEditor)

(defonce ^:private slot
  (reg/single-slot {:validate #(satisfies? IEditor %)
                    :on-empty #(->NoopEditor)}))

(defn set-editor!
  "Set the active editor implementation."
  [editor]
  (reg/install! slot editor))

(defn editor-set?
  "Check if an editor has been explicitly configured."
  []
  (reg/present? slot))

(defn get-editor
  "Get the active editor. Returns NoopEditor when none is configured
   (headless mode is a first-class citizen, unlike kg.clj which throws)."
  []
  (reg/current slot))

(defn clear-editor!
  "Clear the active editor."
  []
  (reg/clear! slot))

;;; ============================================================================
;;; NoopEditor (Headless Fallback)
;;; ============================================================================

(defrecord NoopEditor []
  IEditor
  (editor-id [_this] :noop)
  (available? [_this] false)
  (eval-expr [_this _code]
    {:error :editor/not-available
     :message "No editor configured (headless mode)"})
  (eval-expr [_this _code _opts]
    {:error :editor/not-available
     :message "No editor configured (headless mode)"})
  (feature-available? [_this _feature-name] false)
  (send-to-terminal [_this _terminal-id _text]
    {:error :editor/not-available
     :message "No editor configured (headless mode)"}))

(defn noop-editor
  "Create a no-op editor fallback."
  []
  (->NoopEditor))
