(ns hive-mcp.messaging.nats-conversation
  "NATS bridge for the Inter-Ling Conversation Protocol
   (decision 20260401222011-493b1a6e).

   Subject hierarchy (v1):
     hive.conv.tell.<to-agent-id>             — fire-and-forget DMs
     hive.conv.ask.<to-agent-id>              — DM that expects a reply
     hive.conv.respond.<ask-id>               — per-ask one-shot reply

   Layering (Stratification, decision 20260415135102-1d300fdc):

     NATS (Boundary, I/O)
       ↓ on receipt: emit event
     events  (Calculation ring)
       ↓ effects map
     effects/conversation  (Action ring)
       ↓ side effect
     hivemind.conversation pure registry / NATS publish

   Anti-pattern guarded against: the NATS subscriber MUST emit an event into
   `hive-mcp.events.core/dispatch`, never call effects or transport
   directly. Each callback below builds [:conversation/<kind> payload] and
   dispatches.

   Helpers exposed for the effects layer to publish outbound:
     publish-tell!     [envelope]
     publish-ask!      [envelope]
     publish-respond!  [envelope]
   Each accepts the raw envelope from `hive-mcp.hivemind.conversation`.

   Resilience:
   - Publish is best-effort: if backbone is disconnected, returns nil and
     logs at debug. Atom-piggyback (per-agent inbox) is the durable path.
   - Subscriptions are no-ops when backbone is disconnected. Caller must
     re-invoke `start-subscriptions!` after backbone is up."

  (:require [hive-mcp.protocols.event-backbone :as eb]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Subject construction
;; =============================================================================

(def ^:private conv-prefix "hive.conv")

(defn tell-subject
  "hive.conv.tell.<to-agent-id>"
  [to-agent-id]
  (str conv-prefix ".tell." to-agent-id))

(defn ask-subject
  "hive.conv.ask.<to-agent-id>"
  [to-agent-id]
  (str conv-prefix ".ask." to-agent-id))

(defn respond-subject
  "hive.conv.respond.<ask-id>"
  [ask-id]
  (str conv-prefix ".respond." ask-id))

(defn tell-wildcard
  "hive.conv.tell.> — listen for tells to any agent on this node."
  []
  (str conv-prefix ".tell.>"))

(defn ask-wildcard
  "hive.conv.ask.> — listen for asks to any agent on this node."
  []
  (str conv-prefix ".ask.>"))

(defn respond-wildcard
  "hive.conv.respond.> — listen for any reply (correlation by :ask-id)."
  []
  (str conv-prefix ".respond.>"))

;; =============================================================================
;; Identity gate
;; =============================================================================
;;
;; A node should only react to messages addressed to lings it actually hosts,
;; otherwise NATS fanout floods every node with every conversation. The gate
;; is configured via `set-self-agent-ids!` (a set of agent-id strings) — a
;; nil/empty set means "accept all" (single-node default).

(defonce ^:private *self-agent-ids
  (atom #{}))

(defn set-self-agent-ids!
  "Tell the bridge which agent-ids this node is hosting. Pass nil to accept
   any (single-node mode)."
  [ids]
  (reset! *self-agent-ids (if (set? ids) ids (set ids))))

(defn add-self-agent-id!
  "Append a single hosted agent-id."
  [id]
  (swap! *self-agent-ids (fnil conj #{}) id))

(defn- addressed-to-self?
  "True if the envelope's :to is in our hosted set, OR if no set is
   configured (accept-all)."
  [{:keys [to]}]
  (let [hosted @*self-agent-ids]
    (or (empty? hosted)
        (contains? hosted to))))

;; =============================================================================
;; Publisher side — called by effects/conversation
;; =============================================================================

(defn- publish*
  "Internal: best-effort publish. Returns true on send, false on drop.
   Never throws — keeps the effect handler boundary noise-free."
  [subject payload]
  (try
    (let [backbone (eb/get-backbone)]
      (if (eb/connected? backbone)
        (do (eb/publish! backbone subject payload)
            (log/debug "[nats-conv] published" subject)
            true)
        (do (log/debug "[nats-conv] backbone offline — drop publish" subject)
            false)))
    (catch Throwable e
      (log/debug "[nats-conv] publish failed (non-fatal):" subject (.getMessage e))
      false)))

(defn publish-tell!
  "Publish a :conversation/tell envelope on hive.conv.tell.<to>."
  [{:keys [to] :as envelope}]
  {:pre [(string? to) (map? envelope)]}
  (publish* (tell-subject to) envelope))

(defn publish-ask!
  "Publish a :conversation/ask envelope on hive.conv.ask.<to>."
  [{:keys [to] :as envelope}]
  {:pre [(string? to) (map? envelope)]}
  (publish* (ask-subject to) envelope))

(defn publish-respond!
  "Publish a :conversation/respond envelope on hive.conv.respond.<ask-id>.
   The receiver subscribes per-ask (or via the > wildcard) and correlates
   by :ask-id."
  [{:keys [ask-id] :as envelope}]
  {:pre [(string? ask-id) (map? envelope)]}
  (publish* (respond-subject ask-id) envelope))

;; =============================================================================
;; Subscriber side — emit events, never call effects directly
;; =============================================================================

(defn- dispatch-event!
  "Promote a NATS-received envelope into the local event bus.
   Lazy-resolved to break the bridge → events compile-time dep cycle."
  [event-id payload]
  (try
    (when-let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
      (dispatch [event-id payload]))
    (catch Throwable e
      (log/warn "[nats-conv] dispatch failed for" event-id ":" (.getMessage e)))))

(defn- on-tell-message
  "NATS callback for hive.conv.tell.> — Promote into [:conversation/tell ...]."
  [envelope]
  (when (addressed-to-self? envelope)
    (log/debug "[nats-conv] inbound tell" (select-keys envelope [:from :to]))
    (dispatch-event! :conversation/tell envelope)))

(defn- on-ask-message
  "NATS callback for hive.conv.ask.> — Promote into [:conversation/ask ...]."
  [envelope]
  (when (addressed-to-self? envelope)
    (log/debug "[nats-conv] inbound ask"
               (select-keys envelope [:from :to :ask-id]))
    (dispatch-event! :conversation/ask envelope)))

(defn- on-respond-message
  "NATS callback for hive.conv.respond.<ask-id> — Promote into
   [:conversation/respond ...]. The conversation interceptor chain
   correlates the :ask-id with any locally-registered pending ask, and
   the effect layer delivers to the promise-chan."
  [envelope]
  ;; Don't gate respond on :to — sender may be on this node even if the
  ;; recipient envelope's :to is the original asker (which is us anyway).
  (log/debug "[nats-conv] inbound respond"
             (select-keys envelope [:from :to :ask-id]))
  (dispatch-event! :conversation/respond envelope))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defonce ^:private *subscribed (atom false))

(defn start-subscriptions!
  "Subscribe to hive.conv.{tell,ask,respond}.> via the configured event
   backbone. Idempotent. No-op when backbone is disconnected — call again
   after the backbone is up.

   Returns true if subscriptions were established, false otherwise."
  []
  (let [backbone (eb/get-backbone)]
    (cond
      @*subscribed
      (do (log/debug "[nats-conv] subscriptions already active") true)

      (not (eb/connected? backbone))
      (do (log/debug "[nats-conv] backbone offline — skip subscribe") false)

      :else
      (do
        (eb/subscribe! backbone (tell-wildcard)    on-tell-message)
        (eb/subscribe! backbone (ask-wildcard)     on-ask-message)
        (eb/subscribe! backbone (respond-wildcard) on-respond-message)
        (reset! *subscribed true)
        (log/info "[nats-conv] subscribed:"
                  (tell-wildcard) (ask-wildcard) (respond-wildcard))
        true))))

(defn stop-subscriptions!
  "Unsubscribe from all hive.conv.* subjects. Safe to call even if not
   subscribed."
  []
  (let [backbone (eb/get-backbone)]
    (try
      (eb/unsubscribe! backbone (tell-wildcard))
      (eb/unsubscribe! backbone (ask-wildcard))
      (eb/unsubscribe! backbone (respond-wildcard))
      (catch Throwable e
        (log/debug "[nats-conv] stop-subscriptions! non-fatal:" (.getMessage e))))
    (reset! *subscribed false)
    (log/info "[nats-conv] subscriptions stopped")))

(defn subscribed?
  "True if conversation subscriptions are currently active."
  []
  @*subscribed)
