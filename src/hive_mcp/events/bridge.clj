(ns hive-mcp.events.bridge
  "Bridge between legacy hooks system and hive-events.

   Transforms hook payloads into event vectors for dispatch.

   Hook types (legacy):
   - :task-start, :task-complete
   - :session-start, :session-end
   - :ling-spawn, :ling-terminate
   - :file-modified, :error
   - :memory-added
   - :wrap-request, :wrap-crystallize (Option A - unified wrap)
   - :kg-edge-created, :kg-edge-updated, :kg-edge-removed
   - :kg-node-promoted, :kg-edges-batch-created

   Event vectors (hive-events):
   - [:task/start data], [:task/complete data]
   - [:session/start data], [:session/end data]
   - [:ling/started data], [:ling/completed data]
   - [:file/modified data], [:error/occurred data]
   - [:memory/added data]
   - [:crystal/wrap-request data] (Option A - unified wrap)
   - [:kg/edge-created data], [:kg/edge-updated data], [:kg/edge-removed data]
   - [:kg/node-promoted data], [:kg/edges-batch-created data]")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Multimethod for Hook Type Dispatch
;; =============================================================================

(defmulti hook->event
  "Transform a hook payload into a hive-events event vector.

   Arguments:
   - hook-type: Keyword identifying the legacy hook type
   - payload: Map of hook-specific data

   Returns:
   - Event vector like [:task/complete {:task-id \"123\"}]
   - nil for unknown/unsupported hook types

   Example:
   ```clojure
   (hook->event :task-complete {:task-id \"123\" :result \"done\"})
   => [:task/complete {:task-id \"123\" :result \"done\"}]
   ```"
  (fn [hook-type _payload] hook-type))

(defmethod hook->event :memory-updated
  [_hook-type payload]
  [:memory/edited payload])

(defmethod hook->event :memory-deleted
  [_hook-type payload]
  [:memory/deleted payload])

;; Default: unknown hooks return nil (no-op)
(defmethod hook->event :default
  [_hook-type _payload]
  nil)

;; =============================================================================
;; Task Events
;; =============================================================================

(defmethod hook->event :task-start
  [_hook-type payload]
  [:task/start payload])

(defmethod hook->event :task-complete
  [_hook-type payload]
  [:task/complete payload])

;; =============================================================================
;; Session Events
;; =============================================================================

(defmethod hook->event :session-start
  [_hook-type payload]
  [:session/start payload])

(defmethod hook->event :session-end
  [_hook-type payload]
  [:session/end payload])

;; =============================================================================
;; Ling Events
;; Note: Legacy uses :ling-spawn/:ling-terminate,
;;       new events use :ling/started/:ling/completed for clarity
;; =============================================================================

(defmethod hook->event :ling-spawn
  [_hook-type payload]
  [:ling/started payload])

(defmethod hook->event :ling-started
  [_hook-type payload]
  [:ling/started payload])

(defmethod hook->event :ling-terminate
  [_hook-type payload]
  [:ling/completed payload])

(defmethod hook->event :ling-completed
  [_hook-type payload]
  [:ling/completed payload])

;; =============================================================================
;; File Events
;; =============================================================================

(defmethod hook->event :file-modified
  [_hook-type payload]
  [:file/modified payload])

;; =============================================================================
;; Error Events
;; =============================================================================

(defmethod hook->event :error
  [_hook-type payload]
  [:error/occurred payload])

(defmethod hook->event :error-occurred
  [_hook-type payload]
  [:error/occurred payload])

;; =============================================================================
;; Memory Events
;; =============================================================================

(defmethod hook->event :memory-added
  [_hook-type payload]
  [:memory/added payload])

;; =============================================================================
;; Crystal/Wrap Events (Option A - Unified wrap path)
;; =============================================================================

(defmethod hook->event :wrap-request
  [_hook-type payload]
  [:crystal/wrap-request payload])

(defmethod hook->event :wrap-crystallize
  [_hook-type payload]
  [:crystal/wrap-request payload])

;; =============================================================================
;; Auto-Wrap Events (on-ling-complete hook)
;; =============================================================================

(defmethod hook->event :ling-ready-for-wrap
  [_hook-type payload]
  [:ling/ready-for-wrap payload])

;; =============================================================================
;; Knowledge Graph Events
;; =============================================================================

(defmethod hook->event :kg-edge-created
  [_hook-type payload]
  [:kg/edge-created payload])

(defmethod hook->event :kg-edge-updated
  [_hook-type payload]
  [:kg/edge-updated payload])

(defmethod hook->event :kg-edge-removed
  [_hook-type payload]
  [:kg/edge-removed payload])

(defmethod hook->event :kg-node-promoted
  [_hook-type payload]
  [:kg/node-promoted payload])

(defmethod hook->event :kg-edges-batch-created
  [_hook-type payload]
  [:kg/edges-batch-created payload])

;; =============================================================================
;; Bridge Dispatch Function
;; =============================================================================

(defn bridge-dispatch!
  "Transform a hook event and dispatch it through hive-events.

   Arities:
   - [hook-type payload] - Uses default dispatch (production)
   - [hook-type payload dispatch-fn] - Uses provided dispatch (testing)

   Requires hive-events system to be initialized.

   Arguments:
   - hook-type: Legacy hook type keyword
   - payload: Hook payload data
   - dispatch-fn: Optional dispatch function for DI/testing

   Returns:
   - Dispatch result if transformation succeeded
   - nil if hook type is unknown

   Example:
   ```clojure
   (bridge-dispatch! :task-complete {:task-id \"123\"})
   ;; Dispatches [:task/complete {:task-id \"123\"}] through hive-events
   
   ;; Testing with custom dispatch:
   (bridge-dispatch! :task-complete {:task-id \"123\"} 
     (fn [event] (println \"Would dispatch:\" event)))
   ```"
   
  ([hook-type payload]
   (bridge-dispatch! hook-type payload
                     (fn [event]
       ;; Require here to avoid circular deps
                       (require '[hive-mcp.events.core :as events])
                       ((resolve 'hive-mcp.events.core/dispatch) event))))
  ([hook-type payload dispatch-fn]
   (when-let [event (hook->event hook-type payload)]
     (dispatch-fn event))))
