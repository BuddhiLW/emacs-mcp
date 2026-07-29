(ns hive-mcp.tools.kanban.predicates
  "Pure predicates and enums for kanban task semantics.

   Extracted from hive-mcp.tools.memory-kanban so transitions and
   event handlers can stay pure.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def status-enum->tag
  "MCP-facing enum -> internal tag value."
  {"inprogress" "doing"
   "inreview"   "review"
   "todo"       "todo"
   "done"       "done"})

(def valid-statuses
  "Internal canonical statuses."
  #{"todo" "doing" "review" "done"})

(def status-tag-set
  "Every token that may legitimately occupy the STATUS slot of a kanban tag
   vector: the canonical internal statuses plus the MCP enum spellings that
   older entries were tagged with. Callers swapping a status tag must remove
   this whole set, never just one spelling."
  (into valid-statuses (keys status-enum->tag)))

(def priority-order
  {"high"          0  "priority-high"   0
   "medium"        1  "priority-medium" 1
   "low"           2  "priority-low"    2})

(defn normalize-status
  "Normalize MCP enum -> internal tag. Pass-through if already canonical."
  [s]
  (get status-enum->tag s s))

(defn valid-status?
  "True iff s is a canonical internal status."
  [s]
  (boolean (valid-statuses s)))

(defn done?
  "True iff status represents a completed task."
  [s]
  (= "done" (normalize-status s)))

(defn kanban-task-type?
  "True iff content map carries the kanban task-type marker."
  [content]
  (some #(= "kanban" (get content %)) [:task-type "task-type"]))

(defn kanban-entry?
  "True iff memory entry is a kanban task (by content shape)."
  [entry]
  (boolean (kanban-task-type? (:content entry))))
