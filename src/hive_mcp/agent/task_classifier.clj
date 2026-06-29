(ns hive-mcp.agent.task-classifier
  "Task classification for routing decisions.

   Delegates to extension if available. Returns noop defaults otherwise.

   Extension points are resolved via the extensions registry at startup.
   When no extensions are registered, all functions gracefully degrade
   to noop results."
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
;; Noop Fallback Shapes
;; =============================================================================

(def ^:private noop-signal
  {:score 0.5 :signal :noop :detail "Extension not available"})

(def ^:private noop-classification
  {:route      :ling
   :confidence 0.0
   :reason     "Extension not available"
   :signals    {}})

;; =============================================================================
;; Public API — delegates to extension or returns noop
;; =============================================================================

(defn score-file-count
  "Score based on file count.
   Delegates to extension if available."
  [files]
  (delegate-or-noop :tc/s1 noop-signal [files]))

(defn score-complexity
  "Score based on complexity estimation.
   Delegates to extension if available."
  [task-text files]
  (delegate-or-noop :tc/s2 noop-signal [task-text files]))

(defn score-task-type
  "Score based on task type.
   Delegates to extension if available."
  [task-text files]
  (delegate-or-noop :tc/s3 noop-signal [task-text files]))

(defn score-keywords
  "Score based on keyword analysis.
   Delegates to extension if available."
  [task-text]
  (delegate-or-noop :tc/s4 noop-signal [task-text]))

(defn classify-task
  "Classify a task for routing decisions.
   Delegates to extension if available.

   Input: task map with keys:
     :title       - task title (string, required)
     :description - task description (string, optional)
     :files       - files involved (vector of path strings, optional)

   Returns:
     {:route      :drone or :ling
      :confidence 0.0-1.0
      :reason     explanation string
      :signals    map of individual signal scores}"
  [task]
  (delegate-or-noop :tc/run noop-classification [task]))

(defn classify-for-routing
  "Convenience wrapper for routing.
   Takes task description string + files vector + optional opts map.
   Returns same shape as classify-task."
  [task files & [_opts]]
  (classify-task {:title task :files files}))

(defn drone-eligible?
  "Quick predicate: is this task drone-eligible?
   Returns true when confidence >= threshold."
  [task]
  (= :drone (:route (classify-task task))))
