(ns hive-mcp.agent.agent-definition.validate
  "Validation rules, error assembly, and the map→record boundary constructor.

   Delegates schema definitions to
   `hive-mcp.agent.agent-definition.spec`. Handles the
   validate/explain/valid?/validate-or-throw!/make-agent-def API.

   SLAP role: validate — constraint checks + error humanization."

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

  (:require [malli.core :as m]
            [malli.error :as me]
            [hive-mcp.agent.agent-definition.spec :as spec]))

;; =============================================================================
;; Map Coercion
;; =============================================================================
;;
;; We accept both plain maps and AgentDef records. To avoid a hard compile-time
;; dependency on the AgentDef class (which lives in the façade namespace to
;; preserve its legacy Java class name), we detect "record-like" maps via the
;; `clojure.lang.IRecord` marker interface. The behavior is identical: strip
;; nil-valued entries when coercing a record to a plain map.

(defn- ->plain-map
  [x]
  (if (instance? clojure.lang.IRecord x)
    (into {} (remove (comp nil? val)) x)
    x))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate
  "Validate an agent definition (map or record) against the AgentDefinition schema.

   Accepts both plain maps and AgentDef records (records are coerced to maps
   for schema validation — malli validates maps, not records).

   Returns:
   - {:valid true :data agent-def} on success (returns the input map form)
   - {:valid false :errors {...}} on failure with humanized errors

   Example:
   (validate {:agent-type \"explore\" :description \"Fast searcher\" :system-prompt \"You search.\"})
   ;=> {:valid true :data {...}}"
  [agent-def]
  (let [m (->plain-map agent-def)]
    (if (m/validate spec/AgentDefinition m)
      {:valid true :data m}
      {:valid false
       :errors (me/humanize (m/explain spec/AgentDefinition m))})))

(defn valid?
  "Predicate: is this a valid agent definition (map or record)?"
  [agent-def]
  (m/validate spec/AgentDefinition (->plain-map agent-def)))

(defn explain
  "Human-readable explanation of why an agent definition is invalid.
   Accepts maps or records. Returns nil if valid."
  [agent-def]
  (let [m (->plain-map agent-def)]
    (when-not (m/validate spec/AgentDefinition m)
      (me/humanize (m/explain spec/AgentDefinition m)))))

(defn validate-or-throw!
  "Validate and return agent-def, or throw ex-info with humanized errors.
   Use at definition registration boundaries."
  [agent-def]
  (let [m      (->plain-map agent-def)
        result (validate m)]
    (if (:valid result)
      (:data result)
      (throw (ex-info (str "Invalid agent definition for "
                           (or (:agent-type m) "<unknown>")
                           ": " (pr-str (:errors result)))
                      {:agent-type (:agent-type m)
                       :errors (:errors result)})))))
