(ns hive-mcp.agent.coordinator-role
  "CoordinatorRole ADT — closed algebraic type for agent coordinator roles.

   Built on hive-dsl.adt/defadt. Provides type-safe role dispatch
   with compile-time exhaustiveness checking via adt-case.

   Replaces the ICoordinatorAware protocol (ISP fix: not every agent
   needs coordinator awareness — model role as DATA, not protocol).

   Variants:
     :role/coordinator — Restricted tool set focused on delegation
     :role/worker      — Full tool pool, implements delegated tasks
     :role/standalone  — No coordinator awareness (default)

   Usage:
     (require '[hive-mcp.agent.coordinator-role :as cr])

     ;; Construct
     (cr/coordinator-role :role/coordinator)
     ;; => {:adt/type :CoordinatorRole, :adt/variant :role/coordinator}

     ;; Coerce from keyword (nil if invalid)
     (cr/->coordinator-role :role/worker)

     ;; Predicate
     (cr/coordinator-role? x)

     ;; Exhaustive dispatch
     (adt-case CoordinatorRole role
       :role/coordinator {:tools coordinator-tool-set}
       :role/worker      {:tools (compute-worker-tools agent-def)}
       :role/standalone  {:tools all-tools})

     ;; Pure functions replacing ICoordinatorAware protocol methods:
     (cr/coordinator-tools role agent-def)
     (cr/coordinator-mode? role)"
  (:require [hive-dsl.adt :refer [defadt adt-case adt-variant]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ADT Definition
;; =============================================================================

(defadt CoordinatorRole
  "Agent coordinator roles — closed sum type.
   :role/coordinator — Restricted tool set focused on delegation:
                       agent spawn, send-message, task management, read-only tools.
   :role/worker      — Full tool pool minus any disallowed-tools from definition.
                       Implements tasks delegated by a coordinator.
   :role/standalone  — No coordinator awareness. Agent operates independently
                       with its full configured tool set. This is the default."
  :role/coordinator
  :role/worker
  :role/standalone)

;; =============================================================================
;; Keyword Coercion
;; =============================================================================

(defn from-keyword
  "Coerce a keyword or string to a CoordinatorRole ADT value.
   Returns nil if the input is not a valid role.

   (from-keyword :role/coordinator) => {:adt/type :CoordinatorRole, :adt/variant :role/coordinator}
   (from-keyword :bogus)            => nil"
  [k]
  (let [kw (cond
             (keyword? k) k
             (string? k)  (keyword k)
             :else        nil)]
    (when kw (->coordinator-role kw))))

(defn to-keyword
  "Extract the variant keyword from a CoordinatorRole ADT value.
   This is the inverse of from-keyword for round-trip serialization.

   (to-keyword (coordinator-role :role/coordinator)) => :role/coordinator"
  [cr]
  (adt-variant cr))

;; =============================================================================
;; Variant Sets
;; =============================================================================

(def all-roles
  "Set of all CoordinatorRole variant keywords."
  (:variants CoordinatorRole))

;; =============================================================================
;; Coordinator Tool Sets
;; =============================================================================

(def ^:private coordinator-delegation-tools
  "Tools available to agents in :role/coordinator mode.
   Focused on delegation primitives and read-only operations."
  #{"agent" "send_message" "task_create" "task_update" "task_list"
    "task_stop" "read_file" "grep" "glob_files" "hivemind"})

;; =============================================================================
;; Pure Functions (replace ICoordinatorAware protocol methods)
;; =============================================================================

(defn coordinator-mode?
  "Returns true if this role is :role/coordinator.
   Pure function replacement for ICoordinatorAware/coordinator-mode?.

   (coordinator-mode? (coordinator-role :role/coordinator)) => true
   (coordinator-mode? (coordinator-role :role/worker))      => false
   (coordinator-mode? (coordinator-role :role/standalone))  => false"
  [role]
  (adt-case CoordinatorRole role
    :role/coordinator true
    :role/worker      false
    :role/standalone  false))

(defn coordinator-tools
  "Return the set of tool name strings available for the given role.
   Pure function replacement for ICoordinatorAware/allowed-tools.

   Dispatches on CoordinatorRole ADT:
   - :role/coordinator → delegation primitives only
   - :role/worker      → all tools minus :disallowed-tools from agent-def
   - :role/standalone  → nil (meaning: use whatever the agent is configured with)

   agent-def: optional agent definition map (used for :role/worker disallowed-tools).

   (coordinator-tools (coordinator-role :role/coordinator) {})
   => #{\"agent\" \"send_message\" ...}

   (coordinator-tools (coordinator-role :role/worker) {:disallowed-tools [\"bash\"]})
   => nil  ;; means 'all tools' — caller applies disallowed-tools filter"
  ([role] (coordinator-tools role nil))
  ([role agent-def]
   (adt-case CoordinatorRole role
     :role/coordinator coordinator-delegation-tools
     :role/worker      nil ;; Full pool — caller applies :disallowed-tools from agent-def
     :role/standalone  nil)))

(defn worker-tool-pool
  "Return the full tool pool that would be delegated to spawned workers.
   Only meaningful when role is :role/coordinator. Returns nil otherwise.
   Pure function replacement for ICoordinatorAware/worker-tool-pool.

   (worker-tool-pool (coordinator-role :role/coordinator) all-tools)
   => <all-tools set>

   (worker-tool-pool (coordinator-role :role/worker) all-tools)
   => nil"
  [role all-available-tools]
  (adt-case CoordinatorRole role
    :role/coordinator all-available-tools
    :role/worker      nil
    :role/standalone  nil))
