(ns hive-mcp.plan.parser
  "Facade for plan parsing — delegates to parser.edn, parser.markdown, and parser.util.

   Supports two parsing modes:
   1. EDN mode - Extract ```edn blocks from memory content
   2. Markdown mode - Parse ## headers as steps with [depends: ...] syntax

   Primary functions:
   - parse-plan: Auto-detect mode and parse content
   - parse-edn-plan: Parse EDN blocks specifically
   - parse-markdown-plan: Parse markdown headers as steps
   - plan->task-specs: Convert plan to kanban task specs
   - validate-dependencies: Check dependency graph integrity


   Sub-modules:
   - hive-mcp.plan.parser.edn      — EDN extraction, normalization, phase parsing
   - hive-mcp.plan.parser.markdown  — Markdown header parsing, annotation extraction
   - hive-mcp.plan.parser.util     — Task-spec conversion, dependency validation"
  (:require [hive-mcp.plan.parser.edn :as edn-parser]
            [hive-mcp.plan.parser.markdown :as md-parser]
            [hive-mcp.plan.parser.util :as util]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Re-exported EDN API
;; =============================================================================

(def contains-edn-block?
  "Check if content contains any ```edn ... ``` blocks."
  edn-parser/contains-edn-block?)

(def contains-edn-plan?
  "Check if content contains EDN plan (raw or in blocks)."
  edn-parser/contains-edn-plan?)

(def parse-edn-plan
  "Parse plan from EDN content or EDN blocks.

   Tries four strategies in order:
   1. Parse content directly as EDN (for raw EDN plans)
   2. Extract balanced {} containing :steps from mixed content
   3. Find single ```edn block with :steps
   4. Collect multiple ```edn phase blocks and flatten to unified :steps"
  edn-parser/parse-edn-plan)

;; =============================================================================
;; Re-exported Markdown API
;; =============================================================================

(def parse-markdown-plan
  "Parse plan from markdown content.

   ## headers become steps (level 2)
   [depends: step-1] syntax for dependencies
   [priority: high] syntax for priority
   [id: custom-id] syntax for explicit step IDs"
  md-parser/parse-markdown-plan)

;; =============================================================================
;; Unified Plan Parsing
;; =============================================================================

(defn parse-plan
  "Parse plan from memory content, auto-detecting format.

   If content contains ```edn blocks, attempts EDN parsing first.
   Falls back to markdown parsing only if the EDN structure was unrecognized
   (no schema validation reached). When EDN structure parsed but schema
   validation failed, the schema error is surfaced — silent markdown
   fallback hid actionable schema errors behind a misleading
   \"No ## headers found\" message.

   Args:
   - content: Memory entry content (string)
   - opts: Optional map with:
     - :prefer-format - :edn or :markdown to force format
     - :memory-id - ID to attach to resulting plan

   Returns:
   - {:success true :plan ...} with normalized, validated plan
   - {:success false :error ...} with error details"
  ([content] (parse-plan content {}))
  ([content {:keys [prefer-format memory-id]}]
   (let [title (md-parser/extract-title content)
         result (cond
                  (= prefer-format :edn)
                  (edn-parser/parse-edn-plan content {:title title})

                  (= prefer-format :markdown)
                  (md-parser/parse-markdown-plan content)

                  (edn-parser/contains-edn-plan? content)
                  (let [edn-result (edn-parser/parse-edn-plan content {:title title})]
                    (cond
                      (:success edn-result)  edn-result
                      (:details edn-result)  edn-result
                      :else                  (md-parser/parse-markdown-plan content)))

                  :else
                  (md-parser/parse-markdown-plan content))]
     (if (and (:success result) memory-id)
       (update result :plan assoc :memory-id memory-id)
       result))))

;; =============================================================================
;; Re-exported Utility API
;; =============================================================================

(def plan->task-specs
  "Convert a parsed plan to kanban task specifications.

   Useful for feeding into mcp_mem_kanban_create.

   Returns: vector of task spec maps ready for kanban creation"
  util/plan->task-specs)

(def validate-dependencies
  "Validate that all depends-on references exist as step IDs.

   Returns: {:valid true} or {:valid false :missing [...] :step ...}"
  util/validate-dependencies)
