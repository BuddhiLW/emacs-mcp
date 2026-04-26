(ns hive-mcp.events.effects.dispatch
  "Event chaining effect handlers for the hive-mcp event system.

   Effects implemented:
   - :dispatch   - Chain to another event (event composition)
   - :dispatch-n - Dispatch multiple events in sequence (File Claim Cascade)

   Usage:
   ```clojure
   (require '[hive-mcp.events.effects.dispatch :as dispatch-effects])
   (dispatch-effects/register-dispatch-effects!)
   ```"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.concurrency.pool :as pool]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Effect: :dispatch (Event chaining)
;; =============================================================================

(defn- handle-dispatch
  "Execute a :dispatch effect - dispatch another event.

   Enables event chaining where one handler can trigger another.
   Used for composition (e.g., :ling/completed dispatches :session/end).

   Expected data: An event vector like [:event-id data]

   Example:
   {:dispatch [:session/end {:slave-id \"ling-123\"}]}

   Note: Uses async dispatch via future to prevent stack overflow on deep chains."
  [event]
  (when (and (vector? event) (keyword? (first event)))
    ;; Use bounded event pool to prevent stack overflow and thread explosion
    (pool/with-event
      (try
        (ev/dispatch event)
        (catch Throwable e
          (log/error "[EVENT] Dispatch chain failed:" (.getMessage e)))))))

;; =============================================================================
;; Effect: :dispatch-n (File Claim Cascade)
;; =============================================================================

(defn- handle-dispatch-n
  "Execute a :dispatch-n effect - dispatch multiple events in sequence.

   Like :dispatch but accepts a vector of events to dispatch.
   Events are dispatched sequentially using async futures.
   Useful when one event needs to trigger multiple follow-up events.

   Expected data: Vector of event vectors.

   Example:
   {:dispatch-n [[:claim/notify-waiting {:target \"ling-1\" :file \"a.clj\"}]
                 [:claim/notify-waiting {:target \"ling-2\" :file \"a.clj\"}]]}

   Note: Uses async dispatch via futures to prevent stack overflow."
  [events]
  (when (and (sequential? events) (seq events))
    (doseq [event events]
      (when (and (vector? event) (keyword? (first event)))
        ;; Use bounded event pool to prevent stack overflow and thread explosion
        (pool/with-event
          (try
            (ev/dispatch event)
            (catch Throwable e
              (log/error "[EVENT] Dispatch-n chain failed for" (first event) ":" (.getMessage e)))))))))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-dispatch-effects!
  "Register all event chaining effect handlers.

   Effects registered:
   - :dispatch   - Chain to another event
   - :dispatch-n - Dispatch multiple events (File Claim Cascade)

   Called from hive-mcp.events.effects/register-effects!"
  []
  (ev/reg-fx :dispatch handle-dispatch)
  (ev/reg-fx :dispatch-n handle-dispatch-n)
  (log/info "[hive-events.dispatch] Dispatch effects registered: :dispatch :dispatch-n"))
