(ns hive-mcp.events.handlers.conversation
  "Event handlers for the Inter-Ling Conversation Protocol.

   Handlers are PURE: they take coeffects + event, return effects.
   All transport (NATS publish, atom mutation, promise delivery) happens in
   `hive-mcp.events.effects.conversation`.

   Handled events:
     - :conversation/tell     — fire-and-forget DM
     - :conversation/ask      — DM that registers a pending response
     - :conversation/respond  — answer to a prior ask, correlated by :ask-id

   Effect surface (consumed by effects/conversation):
     - :conversation/publish-tell      {:envelope ...}
     - :conversation/publish-ask       {:envelope ... :ask-id ...}
     - :conversation/publish-respond   {:envelope ...}
     - :conversation/deliver-response  {:ask-id ... :answer ...}
     - :conversation/inbox-push        {:agent-id ... :envelope ...}
     - :log                            (existing)"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.events.interceptors.conversation :as cix]
            [hive-mcp.hivemind.conversation :as conv]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Handler: :conversation/tell
;; =============================================================================

(defn handle-conversation-tell
  "Pure handler for :conversation/tell.

   Effects:
     - :conversation/publish-tell  → boundary publishes on NATS / inbox
     - :conversation/inbox-push    → adds to receiver's piggyback inbox
     - :log
   "
  [_coeffects [_ payload]]
  (let [envelope (conv/tell-envelope payload)]
    {:conversation/publish-tell {:envelope envelope}
     :conversation/inbox-push   {:agent-id (:to envelope)
                                 :envelope envelope}
     :log {:level :debug
           :message (str "tell " (:from envelope) " → " (:to envelope))}}))

;; =============================================================================
;; Handler: :conversation/ask
;; =============================================================================

(defn handle-conversation-ask
  "Pure handler for :conversation/ask.

   Effects:
     - :conversation/register-ask  → register the promise-chan
     - :conversation/publish-ask   → boundary publishes on NATS / inbox
     - :conversation/inbox-push    → drop into receiver's inbox section
     - :log

   The pure layer does NOT block; the parking happens in the tool-call
   boundary that synchronously calls (await-response! ask-id ...).
   This handler simply prepares state + transport."
  [_coeffects [_ payload]]
  (let [envelope (conv/ask-envelope payload)
        ask-id   (:ask-id envelope)]
    {:conversation/register-ask  {:envelope envelope}
     :conversation/publish-ask   {:envelope envelope :ask-id ask-id}
     :conversation/inbox-push    {:agent-id (:to envelope)
                                  :envelope envelope}
     :log {:level :debug
           :message (str "ask " (:from envelope) " → " (:to envelope)
                         " ask-id=" ask-id)}}))

;; =============================================================================
;; Handler: :conversation/respond
;; =============================================================================

(defn handle-conversation-respond
  "Pure handler for :conversation/respond.

   Uses the :conversation/correlated-ask coeffect injected by the
   `correlate-ask-id` interceptor. If matched, emit deliver-response
   to fulfill the local promise-chan AND publish on NATS so the sender's
   process (possibly remote) can also resolve. If unmatched, log + drop.

   Effects:
     - :conversation/deliver-response (when correlated-ask present)
     - :conversation/publish-respond  (always — remote sender may be parked)
     - :conversation/inbox-push       (so sender can also see the answer in piggyback)
     - :log"
  [coeffects [_ payload]]
  (let [envelope        (conv/respond-envelope payload)
        ask-id          (:ask-id envelope)
        correlated      (:conversation/correlated-ask coeffects)
        base-effects    {:conversation/publish-respond {:envelope envelope}
                         :conversation/inbox-push      {:agent-id (:to envelope)
                                                        :envelope envelope}}]
    (if correlated
      (assoc base-effects
             :conversation/deliver-response
             {:ask-id ask-id :answer (:answer envelope)}
             :log {:level :debug
                   :message (str "respond delivered locally for ask-id " ask-id)})
      (assoc base-effects
             :log {:level :info
                   :message (str "respond received but no local ask-id "
                                 ask-id " — forwarding via publish only")}))))

;; =============================================================================
;; Registration
;; =============================================================================

(defonce ^:private *registered (atom false))

(defn register-handlers!
  "Register conversation event handlers. Idempotent."
  []
  (when-not @*registered
    (ev/reg-event :conversation/tell
                  cix/conversation-chain
                  handle-conversation-tell)

    (ev/reg-event :conversation/ask
                  cix/conversation-chain
                  handle-conversation-ask)

    (ev/reg-event :conversation/respond
                  cix/conversation-chain
                  handle-conversation-respond)

    (reset! *registered true)
    (log/info "[hive-events] Conversation handlers registered: :conversation/tell :conversation/ask :conversation/respond")
    true))

(defn reset-registration!
  "Reset registration state. Test only."
  []
  (reset! *registered false))
