(ns hive-mcp.tools.consolidated.workflow.goal
  "Goal-directed workflow synthesis verbs for the consolidated workflow tool.

   plan-goal   — synthesize + soundness-check a Plan-EDN from a GoalSpec
                 (dry-run cert by default; author=true persists through the
                 author path). Reaches hive-workflows.mcp by requiring-resolve —
                 no hive-mcp compile dependency on hive-workflows.
   goal-schema — project the GoalSpec contract (JSON-Schema + example + how-to)
                 for zero-source-read goal authoring. Projects the hive-spi
                 GoalSpec value-object in-process through the single-source
                 compile-op seam (same pattern as kanban plan-schema), resolved
                 lazily so the tool loads even when the hive-spi workflow schemas
                 are off the runtime classpath."
  (:require [clojure.edn :as edn]
            [hive-mcp.tools.core :refer [mcp-error mcp-json]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private sentinel-absent ::absent)

(defn- call-facade
  "Resolve a hive-workflows.mcp fn and apply it, or return the absent sentinel
   when hive-workflows is off the runtime classpath."
  [fn-name & args]
  (if-let [f (try (requiring-resolve (symbol "hive-workflows.mcp" (name fn-name)))
                  (catch Throwable _ nil))]
    (apply f args)
    sentinel-absent))

(defn- ->mcp
  "Bridge a facade Result to an MCP response: absent -> loud error, a
   {:error ...} err -> loud error, anything else -> JSON."
  [v]
  (cond
    (= sentinel-absent v)
    (mcp-error "HWF2 goal planner unavailable: hive-workflows not on the runtime classpath.")

    (and (map? v) (contains? v :error))
    (mcp-error (pr-str v))

    :else (mcp-json v)))

(defn- resolve-var
  "requiring-resolve `sym` to its var, or nil when its namespace is off the
   runtime classpath. Never throws."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(def ^:private bad-edn ::bad-edn)

(defn- read-edn
  "Parse an EDN param that may arrive as a map (passed through) or string.
   Returns the bad-edn sentinel on nil / unparseable input."
  [x]
  (cond
    (map? x)    x
    (string? x) (try (edn/read-string x) (catch Throwable _ bad-edn))
    :else       bad-edn))

;; ── plan-goal ────────────────────────────────────────────────────────────────

(defn handle-plan-goal
  "Synthesize + soundness-check a plan for a GoalSpec through the facade.
   Params: :goal_spec (EDN), :verb_index (EDN map, optional), :author (bool),
   :default_method, :predicates, :directory."
  [params]
  (let [gs     (read-edn (:goal_spec params))
        vi-raw (:verb_index params)
        vi     (when (some? vi-raw) (read-edn vi-raw))]
    (cond
      (= bad-edn gs)
      (mcp-error "plan-goal requires :goal_spec — a GoalSpec {:goal #{facts} :init #{facts}} as an EDN string or map.")

      (and (some? vi-raw) (not (map? vi)))
      (mcp-error "plan-goal :verb_index must be an EDN map of verb-id -> {:requires :provides :deletes :cost}.")

      :else
      (->mcp (call-facade 'plan-goal gs
                          (cond-> {:author?        (boolean (:author params))
                                   :default-method (some-> (:default_method params) keyword)
                                   :predicates     (into #{} (map keyword) (:predicates params))
                                   :directory      (:directory params)}
                            (some? vi) (assoc :verb-index vi)))))))

;; ── goal-schema ──────────────────────────────────────────────────────────────

(def ^:private example-goal-spec
  {:goal #{[:work/committed] [:human/notified]}
   :init #{}})

(def ^:private example-verb-index
  {:demo/scan   {:provides #{[:carto/scanned]} :cost 1}
   :demo/test   {:requires #{[:carto/scanned]} :provides #{[:tests/green]} :cost 1}
   :demo/commit {:requires #{[:tests/green]} :provides #{[:work/committed]} :cost 1}
   :demo/notify {:provides #{[:human/notified]} :cost 1}})

(defn handle-goal-schema
  "Project the GoalSpec contract for zero-source-read authoring: JSON-Schema +
   valid example + verb-index example + how-to. Zero-arg. Degrades loud when the
   hive-spi workflow schemas are off the runtime classpath."
  [_params]
  (let [compile-op (resolve-var 'hive-spi.schema.derive/compile-op)
        goal-var   (resolve-var 'hive-spi.workflow.planner/GoalSpec)]
    (if (and compile-op goal-var)
      (let [{:keys [input-schema]} (compile-op @goal-var)]
        (mcp-json
         {:success            true
          :for                "plan-goal :goal_spec — a propositional GoalSpec: reach :goal facts from :init facts"
          :json-schema        input-schema
          :required           ["goal" "init"]
          :fact-shape         "[:namespaced/kw & ground-literals] — a ground EDN tuple; head is a qualified keyword"
          :enums              {:opaque ["skip" "reject"]}
          :example            example-goal-spec
          :example-verb-index example-verb-index
          :how-to             (str "1) Compose a GoalSpec {:goal #{facts} :init #{facts}}; each fact is a "
                                   "[:namespaced/kw args...] tuple. 2) Call `workflow plan-goal` with "
                                   "goal_spec=<edn> and optionally verb_index=<edn map verb-id -> "
                                   "{:requires :provides :deletes :cost}> (omit to plan over the live verb "
                                   "federation). Dry-run returns {:plan Plan-EDN :sound? bool :proof "
                                   "{step-id -> fact}}. 3) Pass author=true to persist the compiled workflow "
                                   "through the author path (fail-loud on unsound / unknown refs). The :example "
                                   "with :example-verb-index above plans to a sound 4-step workflow verbatim.")}))
      (mcp-error "goal-schema unavailable: hive-spi.workflow.planner not on the runtime classpath."))))