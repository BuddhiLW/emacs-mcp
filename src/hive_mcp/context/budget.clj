(ns hive-mcp.context.budget
  "Context budget delegation stubs.

   Delegates to extension if available. Returns noop defaults otherwise.

   Extension points are resolved via the extensions registry at startup.
   When no extensions are registered, all functions gracefully degrade
   to pass-through results."
  (:require [hive-mcp.extensions.registry :as ext]
            [hive-mcp.extensions.delegate :refer [delegate-or-noop]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Extension Delegation Helpers
;; =============================================================================

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-total-budget
  "Default token budget for context augmentation."
  4000)

;; =============================================================================
;; Public API — delegates to extension or returns noop
;; =============================================================================

(defn estimate-tokens
  "Estimate token count for text.
   Delegates to extension if available."
  [text]
  (delegate-or-noop :cb/a 0 [text]))

(defn truncate-to-budget
  "Truncate text to fit within a token budget.
   Delegates to extension if available."
  [text token-budget]
  (delegate-or-noop :cb/b
                    {:content (or text "") :tokens 0 :truncated? false}
                    [text token-budget]))

(defn allocate-ling-context
  "Allocate token budget across ling context sections.
   Delegates to extension if available."
  [{:keys [total-budget preset axioms context history] :as input}]
  (delegate-or-noop :cb/c
                    {:preset   (or preset "")
                     :axioms   (or axioms "")
                     :context  (or context "")
                     :history  (or history "")
                     :metadata {:total-budget (or total-budget 0)
                                :total-tokens 0
                                :remaining    (or total-budget 0)}}
                    [input]))

(defn allocate-drone-context
  "Allocate token budget across drone context sections.
   Delegates to extension if available."
  [{:keys [total-budget kg-context file-contents] :as input}]
  (delegate-or-noop :cb/d
                    {:kg-context    (or kg-context "")
                     :file-contents (or file-contents "")
                     :metadata      {:total-budget (or total-budget 0)
                                     :total-tokens 0
                                     :remaining    (or total-budget 0)}}
                    [input]))

(defn allocate-unified-entries
  "Allocate token budget across entry vectors.
   Delegates to extension if available."
  [{:keys [total-budget conventions decisions snippets domain] :as input}]
  (delegate-or-noop :cb/e
                    {:conventions      (or conventions [])
                     :decisions        (or decisions [])
                     :snippets         (or snippets [])
                     :domain           (or domain [])
                     :budget-used      0
                     :budget-remaining (or total-budget 0)
                     :metadata         {:total-budget (or total-budget 0)
                                        :total-tokens 0
                                        :remaining    (or total-budget 0)
                                        :counts       {:conventions (count (or conventions []))
                                                       :decisions   (count (or decisions []))
                                                       :snippets    (count (or snippets []))
                                                       :domain      (count (or domain []))}}}
                    [input]))
