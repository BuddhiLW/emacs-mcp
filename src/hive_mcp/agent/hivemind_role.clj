(ns hive-mcp.agent.hivemind-role
  "HivemindRole ADT — closed algebraic type for agent hivemind roles.

   Built on hive-dsl.adt/defadt. Provides type-safe role dispatch
   with compile-time exhaustiveness checking via adt-case.

   Replaces the ICoordinatorAware protocol (ISP fix: not every agent
   needs hivemind awareness — model role as DATA, not protocol).

   Variants:
     :role/hivemind   — Restricted tool set focused on delegation
     :role/worker     — Full tool pool, implements delegated tasks
     :role/standalone — No hivemind awareness (default)

   Usage:
     (require '[hive-mcp.agent.hivemind-role :as hr])

     ;; Construct
     (hr/hivemind-role :role/hivemind)
     ;; => {:adt/type :HivemindRole, :adt/variant :role/hivemind}

     ;; Coerce from keyword (nil if invalid)
     (hr/->hivemind-role :role/worker)

     ;; Predicate
     (hr/hivemind-role? x)

     ;; Exhaustive dispatch
     (adt-case HivemindRole role
       :role/hivemind   {:tools hivemind-tool-set}
       :role/worker     {:tools (compute-worker-tools agent-def)}
       :role/standalone {:tools all-tools})

     ;; Pure functions replacing ICoordinatorAware protocol methods:
     (hr/hivemind-tools role agent-def)
     (hr/hivemind-mode? role)"
  (:require [hive-dsl.adt :refer [defadt adt-case adt-variant]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ADT Definition
;; =============================================================================

(defadt HivemindRole
  "Agent hivemind roles — closed sum type.
   :role/hivemind   — Restricted tool set focused on delegation:
                      agent spawn, send-message, task management, read-only tools.
   :role/worker     — Full tool pool minus any disallowed-tools from definition.
                      Implements tasks delegated by the hivemind.
   :role/standalone — No hivemind awareness. Agent operates independently
                      with its full configured tool set. This is the default."
  :role/hivemind
  :role/worker
  :role/standalone)

;; =============================================================================
;; Keyword Coercion
;; =============================================================================

(defn from-keyword
  "Coerce a keyword or string to a HivemindRole ADT value.
   Returns nil if the input is not a valid role.

   (from-keyword :role/hivemind) => {:adt/type :HivemindRole, :adt/variant :role/hivemind}
   (from-keyword :bogus)         => nil"
  [k]
  (let [kw (cond
             (keyword? k) k
             (string? k)  (keyword k)
             :else        nil)]
    (when kw (->hivemind-role kw))))

(defn to-keyword
  "Extract the variant keyword from a HivemindRole ADT value.
   This is the inverse of from-keyword for round-trip serialization.

   (to-keyword (hivemind-role :role/hivemind)) => :role/hivemind"
  [cr]
  (adt-variant cr))

;; =============================================================================
;; Variant Sets
;; =============================================================================

(def all-roles
  "Set of all HivemindRole variant keywords."
  (:variants HivemindRole))

;; =============================================================================
;; Hivemind Tool Sets
;; =============================================================================

(def ^:private hivemind-delegation-tools
  "Tools available to agents in :role/hivemind mode.
   Focused on delegation primitives and read-only operations."
  #{"agent" "send_message" "task_create" "task_update" "task_list"
    "task_stop" "read_file" "grep" "glob_files" "hivemind"})

;; =============================================================================
;; Pure Functions (replace ICoordinatorAware protocol methods)
;; =============================================================================

(defn hivemind-mode?
  "Returns true if this role is :role/hivemind.
   Pure function replacement for ICoordinatorAware/coordinator-mode?.

   (hivemind-mode? (hivemind-role :role/hivemind))   => true
   (hivemind-mode? (hivemind-role :role/worker))     => false
   (hivemind-mode? (hivemind-role :role/standalone)) => false"
  [role]
  (adt-case HivemindRole role
    :role/hivemind   true
    :role/worker     false
    :role/standalone false))

(defn hivemind-tools
  "Return the set of tool name strings available for the given role.
   Pure function replacement for ICoordinatorAware/allowed-tools.

   Dispatches on HivemindRole ADT:
   - :role/hivemind   -> delegation primitives only
   - :role/worker     -> all tools minus :disallowed-tools from agent-def
   - :role/standalone -> nil (meaning: use whatever the agent is configured with)

   agent-def: optional agent definition map (used for :role/worker disallowed-tools).

   (hivemind-tools (hivemind-role :role/hivemind) {})
   => #{\"agent\" \"send_message\" ...}

   (hivemind-tools (hivemind-role :role/worker) {:disallowed-tools [\"bash\"]})
   => nil  ;; means 'all tools' — caller applies disallowed-tools filter"
  ([role] (hivemind-tools role nil))
  ([role agent-def]
   (adt-case HivemindRole role
     :role/hivemind   hivemind-delegation-tools
     :role/worker     nil ;; Full pool — caller applies :disallowed-tools from agent-def
     :role/standalone nil)))

(defn worker-tool-pool
  "Return the full tool pool that would be delegated to spawned workers.
   Only meaningful when role is :role/hivemind. Returns nil otherwise.
   Pure function replacement for ICoordinatorAware/worker-tool-pool.

   (worker-tool-pool (hivemind-role :role/hivemind) all-tools)
   => <all-tools set>

   (worker-tool-pool (hivemind-role :role/worker) all-tools)
   => nil"
  [role all-available-tools]
  (adt-case HivemindRole role
    :role/hivemind   all-available-tools
    :role/worker     nil
    :role/standalone nil))
