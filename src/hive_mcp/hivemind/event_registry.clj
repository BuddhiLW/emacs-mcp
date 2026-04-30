(ns hive-mcp.hivemind.event-registry
  "Single source of truth for hivemind event types and their properties.

   All consumers derive from this registry — no scattered enums.
   Leaf namespace: zero hive-mcp dependencies (safe to require anywhere).

   Design principle: Knowledge-Layer-First / SST (Single Source of Truth).
   Adding a new event type = adding one entry here. All downstream
   validation, MCP schemas, status mapping, and formatting derive automatically.

   Sum type variants: started, progress, completed, error, blocked, wrap_notify.

   Consumers:
   - hivemind/messaging.clj   → event-type->slave-status
   - hivemind/tools.clj       → MCP enum for shout tool
   - consolidated/hivemind.clj → MCP enum for consolidated shout
   - channel/piggyback.clj    → message formatting
   - transport/olympus.clj    → event forwarding
   - events/effects/notification.clj → shout effect handler
   - events/schemas.clj       → ShoutEffectData schema")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Hivemind event type registry. array-map preserves insertion order.

   Each type has:
   - :description    Human-readable description of when to emit this event
   - :severity       Severity level (:info, :warn, :error)
   - :slave-status   Corresponding DataScript slave status keyword
   - :terminal?      Whether this event type ends a workflow (no further events expected)
   - :mcp?           Visible in MCP tool enums (default true)
   - :valid-next     Set of event types that can follow this one (nil = any)
   - :format         Formatting hints for piggyback/display
     - :icon         Display icon/prefix
     - :abbreviated  Short form for compact display"
  (array-map
   :started    {:description "Agent has begun work on a task"
                :severity    :info
                :slave-status :working
                :terminal?   false
                :mcp?        true
                :valid-next  #{:progress :completed :error :blocked}
                :format      {:icon "▶" :abbreviated "start"}}

   :progress   {:description "Agent reports intermediate progress on current task"
                :severity    :info
                :slave-status :working
                :terminal?   false
                :mcp?        true
                :valid-next  #{:progress :completed :error :blocked}
                :format      {:icon "⟳" :abbreviated "prog"}}

   :completed  {:description "Agent has finished its task successfully"
                :severity    :info
                :slave-status :idle
                :terminal?   true
                :mcp?        true
                :valid-next  #{:started}
                :format      {:icon "✓" :abbreviated "done"}}

   :error      {:description "Agent encountered an error during task execution"
                :severity    :error
                :slave-status :error
                :terminal?   true
                :mcp?        true
                :valid-next  #{:started :progress}
                :format      {:icon "✗" :abbreviated "err"}}

   :blocked    {:description "Agent is blocked and needs external input or decision"
                :severity    :warn
                :slave-status :blocked
                :terminal?   false
                :mcp?        true
                :valid-next  #{:progress :completed :error}
                :format      {:icon "⏸" :abbreviated "blk"}}

   :wrap_notify {:description "Session wrap notification for coordinator permeation"
                 :severity    :info
                 :slave-status :idle
                 :terminal?   true
                 :mcp?        false
                 :valid-next  nil
                 :format      {:icon "📦" :abbreviated "wrap"}}

   ;; =========================================================================
   ;; Inter-Ling Conversation Protocol (decision 20260401222011-493b1a6e)
   ;; tell    — fire-and-forget DM (no blocking, no correlation needed)
   ;; ask     — DM that blocks sender until :conversation/respond delivers
   ;; respond — answer to a prior :conversation/ask, correlated via :ask-id
   ;; Orthogonal to slave-status FSM (:mcp? false, :valid-next nil).
   ;; =========================================================================

   :conversation/tell    {:description "Direct message from one ling to another (fire-and-forget)"
                          :severity    :info
                          :slave-status :idle
                          :terminal?   false
                          :mcp?        false
                          :valid-next  nil
                          :format      {:icon "→" :abbreviated "tell"}}

   :conversation/ask     {:description "Question from one ling to another, expects :conversation/respond"
                          :severity    :info
                          :slave-status :idle
                          :terminal?   false
                          :mcp?        false
                          :valid-next  nil
                          :format      {:icon "?" :abbreviated "ask"}}

   :conversation/respond {:description "Response to a prior :conversation/ask, correlated by :ask-id"
                          :severity    :info
                          :slave-status :idle
                          :terminal?   true
                          :mcp?        false
                          :valid-next  nil
                          :format      {:icon "!" :abbreviated "resp"}}))

;; =============================================================================
;; Derived views (computed once at load time)
;; =============================================================================

(def all-event-types
  "Set of all valid event type keywords."
  (set (keys registry)))

(def all-event-type-strings
  "Set of all valid event type strings (for MCP/transport)."
  (set (map name all-event-types)))

(def mcp-event-types
  "Ordered vector of event type strings visible in MCP tool enums."
  (->> registry
       (filter (fn [[_k v]] (:mcp? v)))
       (map (comp name key))
       vec))

(def event-type->slave-status
  "Map of event type keyword -> DataScript slave status keyword."
  (into {} (map (fn [[k v]] [k (:slave-status v)])) registry))

(def terminal-event-types
  "Set of event types that end a workflow."
  (->> registry
       (filter (fn [[_k v]] (:terminal? v)))
       (map key)
       set))

(def severity-levels
  "Map of event type keyword -> severity keyword."
  (into {} (map (fn [[k v]] [k (:severity v)])) registry))

;; =============================================================================
;; Functions
;; =============================================================================

(defn valid-event-type?
  "Check if event-type (keyword or string) is valid."
  [t]
  (or (contains? all-event-types (if (keyword? t) t (keyword t)))
      (contains? all-event-type-strings (if (string? t) t (name t)))))

(defn slave-status
  "Get the DataScript slave status for an event type. Default: :idle."
  [event-type]
  (get event-type->slave-status
       (if (keyword? event-type) event-type (keyword event-type))
       :idle))

(defn severity
  "Get the severity level for an event type. Default: :info."
  [event-type]
  (get severity-levels
       (if (keyword? event-type) event-type (keyword event-type))
       :info))

(defn terminal?
  "Check if an event type is terminal (ends a workflow)."
  [event-type]
  (contains? terminal-event-types
             (if (keyword? event-type) event-type (keyword event-type))))

(defn valid-transition?
  "Check if transitioning from one event type to another is valid.
   Returns true if the transition is allowed, or if no transition rules exist."
  [from-event to-event]
  (let [from-kw (if (keyword? from-event) from-event (keyword from-event))
        to-kw (if (keyword? to-event) to-event (keyword to-event))
        valid-next (get-in registry [from-kw :valid-next])]
    (or (nil? valid-next)
        (contains? valid-next to-kw))))

(defn format-icon
  "Get the display icon for an event type."
  [event-type]
  (let [kw (if (keyword? event-type) event-type (keyword event-type))]
    (get-in registry [kw :format :icon] "•")))

(defn mcp-enum
  "Generate MCP JSON schema enum for tool definitions."
  []
  mcp-event-types)
