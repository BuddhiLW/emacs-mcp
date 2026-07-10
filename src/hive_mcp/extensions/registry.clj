(ns hive-mcp.extensions.registry
  "Opaque extension registry for optional capabilities.

   Provides a thread-safe registry where external projects can register
   implementations at startup. Consumers look up extensions by opaque
   keyword keys without knowing which project provides them.

   Usage:
     ;; Registration (at startup, by extension project)
     (register! :gs/struct-cmp my-cmp-fn)

     ;; Consumption (anywhere in hive-mcp)
     (if-let [f (get-extension :gs/struct-cmp)]
       (f node-a node-b)
       default-value)

   Thread safety: All operations are atomic via atom + swap!.
   Idempotent: Re-registering the same key replaces silently."
  (:require [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry State
;; =============================================================================

(defonce ^:private ext-slot (reg/multi-slot {}))

(defonce ^:private tool-slot (reg/multi-slot {}))

(defonce ^:private schema-registry
  (atom {}))

;; Registry for composite tool command contributions.
;; Shape: {"analysis" {"lint" {:handler fn :params {...} :description "..." :addon :kondo} ...}}
(defonce ^:private command-contributions (atom {}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn register!
  "Register an extension function under an opaque keyword key.
   Thread-safe, idempotent. Re-registration replaces the previous value."
  [k f]
  {:pre [(keyword? k) (ifn? f)]}
  (reg/reg-put! ext-slot k f)
  k)

(defn register-many!
  "Register multiple extensions at once from a map of {keyword fn}.
   Thread-safe, atomic."
  [m]
  {:pre [(map? m)]}
  (reg/reg-merge! ext-slot m))

(defn get-extension
  "Look up a registered extension by keyword key.
   Returns the function if registered, or default (nil if not provided)."
  ([k]
   (get (reg/reg-snapshot ext-slot) k))
  ([k default]
   (get (reg/reg-snapshot ext-slot) k default)))

(defn extension-available?
  "Check if an extension is registered under the given key."
  [k]
  (contains? (reg/reg-snapshot ext-slot) k))

(defn registered-keys
  "Return the set of all registered extension keys."
  []
  (set (keys (reg/reg-snapshot ext-slot))))

(defn deregister!
  "Remove an extension registration. Returns the key."
  [k]
  (reg/reg-remove! ext-slot k)
  k)

(defn clear-all!
  "Remove all registrations (fn + schema + tool + contributions). Intended for testing only."
  []
  (reg/reg-clear! ext-slot)
  (reset! schema-registry {})
  (reg/reg-clear! tool-slot)
  (reset! command-contributions {})
  nil)

;; =============================================================================
;; Schema Extension Registry
;; =============================================================================

(defn register-schema!
  "Register schema properties for a tool. Merges with existing.
   Properties is a map of {\"param_name\" {:type ... :description ...}}.
   Thread-safe, idempotent."
  [tool-name properties]
  {:pre [(string? tool-name) (map? properties)]}
  (swap! schema-registry update tool-name merge properties)
  tool-name)

(defn get-schema-extensions
  "Get merged schema property extensions for a tool. Returns map or nil."
  [tool-name]
  (get @schema-registry tool-name))

(defn clear-all-schemas!
  "Remove all schema registrations. Intended for testing only."
  []
  (reset! schema-registry {})
  nil)

;; =============================================================================
;; Tool Registry (dynamic MCP tool definitions)
;; =============================================================================

(defn register-tool!
  "Register a full MCP tool definition for dynamic discovery.
   Tool-def must have :name (string) and :handler (ifn?).
   Thread-safe, idempotent. Last-write-wins by tool name."
  [tool-def]
  {:pre [(string? (:name tool-def)) (ifn? (:handler tool-def))]}
  (reg/reg-put! tool-slot (:name tool-def) tool-def)
  (:name tool-def))

(defn get-registered-tools
  "Return seq of all dynamically registered tool definitions."
  []
  (vals (reg/reg-snapshot tool-slot)))

(defn deregister-tool!
  "Remove a dynamically registered tool by name. Returns the name."
  [tool-name]
  (reg/reg-remove! tool-slot tool-name)
  tool-name)

(defn clear-all-tools!
  "Remove all tool registrations. Intended for testing only."
  []
  (reg/reg-clear! tool-slot)
  nil)

;; =============================================================================
;; Composite Tool Command Contributions
;; =============================================================================

(defn contribute-commands!
  "Register commands that compose into a named composite tool.
   tool-name: \"analysis\", addon-id: :kondo
   commands: {\"lint\" {:handler fn :params {\"path\" {...}} :description \"...\"}}"
  [tool-name addon-id commands]
  (swap! command-contributions update tool-name merge
         (->> commands
              (map (fn [[cmd spec]] [(name cmd) (assoc spec :addon addon-id)]))
              (into {}))))

(defn retract-commands!
  "Remove all commands contributed by an addon from a tool."
  [tool-name addon-id]
  (swap! command-contributions update tool-name
         (fn [m] (into {} (remove #(= addon-id (:addon (val %))) m)))))

(defn retract-all-by-addon!
  "Remove all contributions from an addon across all tools (for shutdown)."
  [addon-id]
  (swap! command-contributions
         (fn [m]
           (into {} (map (fn [[tn cmds]]
                           [tn (into {} (remove #(= addon-id (:addon (val %))) cmds))])
                         m)))))

(defn get-contributed-commands
  "Get contributed commands for a composite tool name."
  [tool-name]
  (get @command-contributions tool-name))

(defn contributed-tool-names
  "Return vector of tool names that have command contributions."
  []
  (vec (keys @command-contributions)))
