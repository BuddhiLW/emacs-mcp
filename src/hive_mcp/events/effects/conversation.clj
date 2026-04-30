(ns hive-mcp.events.effects.conversation
  "Effect handlers for the Inter-Ling Conversation Protocol.

   These are the BOUNDARY-side translators: pure event handlers (in
   `hive-mcp.events.handlers.conversation`) emit named effects; this
   namespace executes the I/O side (NATS publish, atom-piggyback fallback,
   promise-chan delivery, registry timeout sweep).

   Stratification (decision 20260415135102-1d300fdc):
     - Action ring (boundary). Effects only translate event-handler output
       into side-effect dispatches. They never call back into the event bus.
     - Pure protocol logic + ask-id correlation lives in
       `hive-mcp.hivemind.conversation` (Calculations / Data).

   Effects registered (matching keys emitted by handlers/conversation.clj):
     - :conversation/publish-tell      — fire-and-forget delivery (NATS
                                         + per-agent inbox fallback)
     - :conversation/publish-ask       — same as tell, but the pending-ask
                                         promise-chan is registered first
     - :conversation/publish-respond   — NATS publish on the per-ask reply
                                         subject + local promise delivery
     - :conversation/register-ask      — explicit registry hook (idempotent
                                         pair with publish-ask)
     - :conversation/deliver-response  — deliver answer locally to the
                                         awaiting promise-chan
     - :conversation/inbox-push        — push envelope into the receiver's
                                         per-agent piggyback inbox
     - :conversation/timeout-ask       — defensive cleanup of stale asks

   Aliases (legacy / convenience, registered as the same handlers):
     - :conversation/deliver-tell      → :conversation/publish-tell
     - :conversation/deliver-ask       → :conversation/publish-ask
     - :conversation/deliver-respond   → :conversation/publish-respond"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.hivemind.conversation :as conv]
            [hive-mcp.channel.conversation-inbox :as inbox]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; NATS bridge resolution (lazy + non-fatal)
;; =============================================================================
;;
;; The NATS conversation bridge is resolved via `requiring-resolve` to avoid
;; a hard dependency cycle (effects → bridge → events). When NATS is not
;; configured, publishes degrade to atom-piggyback only (the inbox push) and
;; the layer logs at debug level.

(defn- resolve-nats-fn
  "Look up a fn in hive-mcp.messaging.nats-conversation, or nil if missing.
   Cached per-call only; intentionally cheap (resolve is a hashmap lookup)."
  [sym]
  (try
    (requiring-resolve (symbol "hive-mcp.messaging.nats-conversation" (name sym)))
    (catch Throwable _ nil)))

(defn- nats-publish-tell!
  [envelope]
  (when-let [f (resolve-nats-fn 'publish-tell!)]
    (try (f envelope)
         (catch Throwable e
           (log/debug "[conv-fx] NATS publish-tell! failed (non-fatal):"
                      (.getMessage e))))))

(defn- nats-publish-ask!
  [envelope]
  (when-let [f (resolve-nats-fn 'publish-ask!)]
    (try (f envelope)
         (catch Throwable e
           (log/debug "[conv-fx] NATS publish-ask! failed (non-fatal):"
                      (.getMessage e))))))

(defn- nats-publish-respond!
  [envelope]
  (when-let [f (resolve-nats-fn 'publish-respond!)]
    (try (f envelope)
         (catch Throwable e
           (log/debug "[conv-fx] NATS publish-respond! failed (non-fatal):"
                      (.getMessage e))))))

;; =============================================================================
;; Effect: :conversation/publish-tell  (a.k.a. :conversation/deliver-tell)
;; =============================================================================

(defn handle-publish-tell
  "Fire-and-forget delivery of a :conversation/tell envelope.
   Best-effort NATS publish; the per-agent inbox push is the durable fallback
   and is performed by the handler's :conversation/inbox-push effect."
  [{:keys [envelope] :as data}]
  (let [env (or envelope data)]
    (when env
      (nats-publish-tell! env)
      (log/debug "[conv-fx] tell dispatched"
                 (select-keys env [:from :to])))))

;; =============================================================================
;; Effect: :conversation/publish-ask  (a.k.a. :conversation/deliver-ask)
;; =============================================================================

(defn handle-publish-ask
  "Deliver an :conversation/ask envelope.
   - Idempotently ensure the pending promise-chan is registered (the protocol
     handler also emits :conversation/register-ask which calls register-ask!
     directly; we double-check here to be robust to direct callers).
   - Best-effort NATS publish on hive.conv.ask.<to>."
  [{:keys [envelope ask-id] :as data}]
  (let [env (or envelope data)
        aid (or ask-id (:ask-id env))]
    (when env
      (when (and aid (nil? (conv/pending-ask aid)))
        (conv/register-ask! env))
      (nats-publish-ask! env)
      (log/debug "[conv-fx] ask dispatched"
                 (select-keys env [:from :to :ask-id])))))

;; =============================================================================
;; Effect: :conversation/publish-respond  (a.k.a. :conversation/deliver-respond)
;; =============================================================================

(defn handle-publish-respond
  "Publish a :conversation/respond envelope to NATS on the per-ask subject.
   Local promise delivery is handled by :conversation/deliver-response so the
   two paths stay independent (NATS may be down; local sender may already
   have its promise resolved)."
  [{:keys [envelope] :as data}]
  (let [env (or envelope data)]
    (when env
      (nats-publish-respond! env)
      (log/debug "[conv-fx] respond dispatched"
                 (select-keys env [:from :to :ask-id])))))

;; =============================================================================
;; Effect: :conversation/register-ask
;; =============================================================================

(defn handle-register-ask
  "Register a pending ask in the conversation-promise registry. Idempotent —
   no-op if already registered. Emitted by handlers/conversation handle-ask."
  [{:keys [envelope]}]
  (when-let [aid (:ask-id envelope)]
    (when (nil? (conv/pending-ask aid))
      (conv/register-ask! envelope)
      (log/debug "[conv-fx] ask registered" aid))))

;; =============================================================================
;; Effect: :conversation/deliver-response
;; =============================================================================

(defn handle-deliver-response
  "Deliver an answer to a locally-pending ask's promise-chan. No-op if no
   such ask is registered (e.g. respond arrived from NATS for a sender on
   another node — that node delivers, this one drops)."
  [{:keys [ask-id answer]}]
  (when ask-id
    (conv/deliver-response! ask-id answer)))

;; =============================================================================
;; Effect: :conversation/inbox-push
;; =============================================================================

(defn handle-inbox-push
  "Append the envelope to the receiver agent's piggyback inbox section.
   This is the always-on, NATS-independent delivery path: even with NATS
   down or no subscriber, the next time the target ling does any tool call
   the ---INBOX--- block surfaces the pending tells/asks/respond-results."
  [{:keys [agent-id envelope]}]
  (when (and agent-id envelope)
    (inbox/push! agent-id envelope)))

;; =============================================================================
;; Effect: :conversation/timeout-ask
;; =============================================================================

(defn handle-timeout-ask
  "Defensive cleanup. Cancel a single ask-id, or sweep all asks older than
   :older-than-ms. Emits no further effects."
  [{:keys [ask-id older-than-ms]}]
  (cond
    ask-id
    (do (conv/cancel-ask! ask-id)
        (log/debug "[conv-fx] ask cancelled" ask-id))

    older-than-ms
    (let [now (System/currentTimeMillis)
          cutoff (- now older-than-ms)
          stale (->> @conv/pending-asks
                     (filter (fn [[_ {:keys [timestamp]}]]
                               (and timestamp (< timestamp cutoff))))
                     (map first)
                     vec)]
      (doseq [aid stale]
        (conv/cancel-ask! aid))
      (when (seq stale)
        (log/info "[conv-fx] swept" (count stale) "stale asks")))

    :else
    (log/debug "[conv-fx] timeout-ask: nothing to do (no :ask-id or :older-than-ms)")))

;; =============================================================================
;; Registration
;; =============================================================================

(defonce ^:private *registered (atom false))

(defn register-conversation-effects!
  "Register all conversation effect handlers. Idempotent."
  []
  (when-not @*registered
    (ev/reg-fx :conversation/publish-tell    handle-publish-tell)
    (ev/reg-fx :conversation/publish-ask     handle-publish-ask)
    (ev/reg-fx :conversation/publish-respond handle-publish-respond)
    (ev/reg-fx :conversation/register-ask    handle-register-ask)
    (ev/reg-fx :conversation/deliver-response handle-deliver-response)
    (ev/reg-fx :conversation/inbox-push      handle-inbox-push)
    (ev/reg-fx :conversation/timeout-ask     handle-timeout-ask)
    ;; Convenience aliases that some callers / decisions reference by name.
    (ev/reg-fx :conversation/deliver-tell    handle-publish-tell)
    (ev/reg-fx :conversation/deliver-ask     handle-publish-ask)
    (ev/reg-fx :conversation/deliver-respond handle-publish-respond)
    (reset! *registered true)
    (log/info "[hive-events] Conversation effects registered:"
              ":publish-tell :publish-ask :publish-respond :register-ask"
              ":deliver-response :inbox-push :timeout-ask"
              "(+ deliver-{tell,ask,respond} aliases)")
    true))

(defn reset-registration!
  "Reset registration state. Test only."
  []
  (reset! *registered false))
