(ns hive-mcp.hivemind.messaging
  "Hivemind messaging — shout, ask, respond, and piggyback registration.

   M1 (protocol-first): Shouts publish via IEventBackbone (NATS, Redis, etc.).
   Fallback fanout uses IDeliveryChannel registry — no hardcoded transports.
   Local state (atom, DataScript) still updated synchronously for consistency."

  (:require [hive-mcp.hivemind.state :as state]
            [hive-mcp.hivemind.event-registry :as event-registry]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.protocols.event-backbone :as eb]
            [hive-mcp.protocols.delivery-channel :as dc]
            [hive-mcp.protocols.vessel :as vessel]
            [hive-mcp.swarm.protocol :as proto]
            [hive-mcp.swarm.datascript.registry :as registry]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.tools.memory.scope :as mem-scope]
            [clojure.core.async :as async :refer [>!! chan timeout alt!!]]
            [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bput! bget bounded-swap!]])
  (:import [java.lang Exception]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- all-hivemind-messages
  "Return all hivemind messages for piggyback module."
  []
  (mapcat (fn [[_agent-id entry]]
            (let [{:keys [messages]} (:data entry)]
              (for [{:keys [event-type message task timestamp project-id shout-id]} messages]
                (cond-> {:agent-id _agent-id
                         :event-type event-type
                         :message (or message task "")
                         :timestamp timestamp
                         :project-id (or project-id "global")}
                  shout-id (assoc :shout-id shout-id)))))
          @(:atom state/agent-registry)))

(piggyback/register-message-source! all-hivemind-messages)

(defn- event-type->slave-status
  "Map hivemind event type to valid DataScript slave status.
   Derives from event-registry — no hardcoded case statement."
  [event-type]
  (event-registry/slave-status event-type))

(defn- publish-shout-to-backbone!
  "Publish shout via IEventBackbone. Subscribers handle fanout.
   Uses requiring-resolve for bridge to avoid circular dep."
  [payload]
  (try
    (when-let [publish-fn (requiring-resolve 'hive-mcp.nats.bridge/publish-shout!)]
      (publish-fn payload))
    (catch Exception e
      (log/debug "[Backbone] Shout publish failed (non-fatal):" (.getMessage e)))))

(defn- fanout-shout-direct!
  "Direct fanout via IDeliveryChannel registry when backbone is unavailable.
   Protocol-mediated — no hardcoded transport calls."
  [payload]
  (dc/fanout! payload))

(defn shout!
  "Broadcast a message to the hivemind coordinator.

   M1 Architecture (protocol-first):
   - Local state (atom + DataScript) updated synchronously
   - If backbone connected: single publish → backbone subscribers handle fanout
   - If backbone disconnected: direct fanout via IDeliveryChannel registry
   - Domain events (:ling/completed) are NOT dispatched here — callers
     that need domain side-effects dispatch them explicitly. This avoids
     a feedback loop: shout! → :ling/completed handler → :shout effect → shout!"
  [agent-id event-type data]
  (let [now (System/currentTimeMillis)
        shout-id (str (random-uuid))
        resolved-slave (queries/get-slave-by-name-or-id agent-id)
        resolved-slave-id (or (:slave/id resolved-slave) agent-id)
        explicit-project-id (:project-id data)
        directory (:directory data)
        ;; IVessel resolution: query all registered vessels for agent context.
        ;; Vessel delegates to DataScript (slave/cwd, slave/project-id) — the
        ;; formal answer to the project-id coupling bug (vessel owns context).
        vessel-ctx (vessel/resolve-agent-context resolved-slave-id)
        ;; Priority: explicit > vessel > directory > global
        project-id (or explicit-project-id
                       (:project-id vessel-ctx)
                       (when directory (mem-scope/get-current-project-id directory))
                       "global")
        message (cond-> {:event-type event-type
                         :timestamp now
                         :project-id project-id
                         :shout-id shout-id
                         :data (dissoc data :task :message :directory :project-id)}
                  (:task data) (assoc :task (:task data))
                  (:message data) (assoc :message (:message data)))
        ;; Backbone payload — flat, self-contained, no internal references
        ;; shout-id enables cross-path dedup (atom + backbone deliver same shout)
        backbone-payload {:agent-id agent-id
                          :event-type event-type
                          :timestamp now
                          :project-id project-id
                          :shout-id shout-id
                          :message (:message data)
                          :task (:task data)
                          :data (dissoc data :task :message :directory :project-id)}]
    ;; 1. Local state — always (bounded-atom for piggyback reads)
    (let [current (or (bget state/agent-registry agent-id) {:messages [] :last-seen nil})
          messages (or (:messages current) [])
          new-messages (vec (take-last 10 (conj messages message)))]
      (bput! state/agent-registry agent-id
             {:messages new-messages
              :last-seen now}))
    ;; 2. DataScript slave status — always
    (when resolved-slave
      (proto/update-slave! registry/default-registry resolved-slave-id
                           {:slave/status (event-type->slave-status event-type)}))
    ;; 3. Backbone publish OR direct fanout (protocol-mediated)
    (let [backbone (eb/get-backbone)]
      (if (eb/connected? backbone)
        (publish-shout-to-backbone! backbone-payload)
        (fanout-shout-direct! backbone-payload)))
    ;; 4. Log
    (log/info "Hivemind shout:" agent-id event-type "project:" project-id)
    true))

(defn ask!
  "Request a decision from the human coordinator, blocking until response or timeout."
  [agent-id question options & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [ask-id (str (random-uuid))
        response-chan (chan 1)
        ask-event {:type :hivemind-ask
                   :ask-id ask-id
                   :agent-id agent-id
                   :question question
                   :options options
                   :timestamp (System/currentTimeMillis)}]
    (swap! state/pending-asks assoc ask-id {:question question
                                            :options options
                                            :agent-id agent-id
                                            :response-chan response-chan})
    (channel/broadcast! ask-event)
    (log/info "Hivemind ask:" agent-id question)
    (let [result (alt!!
                   response-chan ([v] v)
                   (timeout timeout-ms) {:timeout true :ask-id ask-id})]
      (swap! state/pending-asks dissoc ask-id)
      result)))

(defn respond-ask!
  "Respond to a pending ask from an agent."
  [ask-id decision & {:keys [by] :or {by "human"}}]
  (if-let [{:keys [response-chan]} (get @state/pending-asks ask-id)]
    (do
      (>!! response-chan {:decision decision :by by :ask-id ask-id})
      (log/info "Hivemind response:" ask-id decision)
      true)
    (do
      (log/warn "No pending ask for id:" ask-id)
      false)))
