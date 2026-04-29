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
  "Return all hivemind messages for piggyback module.
   Projection mirrors buffer-backbone-event! normalization so dual-path dedup
   keys remain shape-aligned (both paths carry :task and :shout-id when set)."
  []
  (mapcat (fn [[_agent-id entry]]
            (let [{:keys [messages]} (:data entry)]
              (for [{:keys [event-type message task timestamp project-id shout-id]} messages]
                (cond-> {:agent-id _agent-id
                         :event-type event-type
                         :message (or message task "")
                         :timestamp timestamp
                         :project-id (or project-id "global")}
                  shout-id (assoc :shout-id shout-id)
                  task (assoc :task task)))))
          @(:atom state/agent-registry)))

(piggyback/register-message-source! all-hivemind-messages)

(defn- event-type->slave-status
  "Map hivemind event type to valid DataScript slave status.
   Derives from event-registry — no hardcoded case statement."
  [event-type]
  (event-registry/slave-status event-type))

(def ^:const default-shout-message-cap
  "Fallback cap when config is unloaded or missing :hivemind/:shout-message-cap.
   One bad shout fans out across (per-agent ring × backbone × subscribers), so
   bound it aggressively. Override via config path [:hivemind :shout-message-cap]."
  2048)

(def ^:const ^:private ellipsis "…")

(defn- resolve-shout-cap
  "Pull :shout-message-cap from loaded config, fall back to default.
   Lazy requiring-resolve avoids circular dep at hivemind bootstrap."
  []
  (or (try
        (when-let [f (requiring-resolve 'hive-mcp.config.core/get-in-config)]
          (f [:hivemind :shout-message-cap]))
        (catch Exception _ nil))
      default-shout-message-cap))

(defn cap-message
  "Cap a shout payload string at `cap` characters. Pure helper.

   Behavior:
   - nil             → nil
   - \"\"              → \"\" (empty passes through)
   - (count s) ≤ cap → s  (under-cap passes through verbatim)
   - else            → (subs s 0 (- cap 3)) + \"…\"  (ellipsis suffix)

   Non-strings are `pr-str`'d first so accidental coll/map payloads are still
   bounded. Invariant: (count (cap-message s cap)) ≤ cap for any input when
   cap ≥ 3."
  ([v] (cap-message v (resolve-shout-cap)))
  ([v cap]
   (cond
     (nil? v) nil
     (and (string? v) (zero? (count v))) v
     :else
     (let [s (if (string? v) v (pr-str v))]
       (if (<= (count s) cap)
         s
         (let [head-n (max 0 (- cap 3))]
           (str (subs s 0 head-n) ellipsis)))))))

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

(defn- blank-payload-value?
  "True iff `v` carries no signal: nil, empty string, or empty coll."
  [v]
  (cond
    (nil? v) true
    (string? v) (zero? (count v))
    (coll? v) (empty? v)
    :else false))

(defn empty-shout?
  "Predicate: would this shout carry a zero-information payload?

   A shout is considered empty when *all* of the following hold:
     1. `:task` is missing/nil/blank-string/empty-coll
     2. `:message` is missing/nil/blank-string/empty-coll
     3. The remainder of `data` (after stripping :task :message :directory
        :project-id) has no entries with meaningful values.

   Such shouts get rendered as `[] ()`-shaped no-ops in piggyback HIVEMIND
   blocks during high-throughput batch ops (kanban hygiene, wave dispatch
   side-effects, FSM phase shouts that race past payload assembly).

   `data` may be anything callers pass through — non-map values are treated
   as opaque (so non-nil, non-blank, non-empty data → not empty)."
  [data]
  (cond
    (nil? data) true
    (not (map? data)) (blank-payload-value? data)
    :else
    (let [residual (dissoc data :task :message :directory :project-id)]
      (and (blank-payload-value? (:task data))
           (blank-payload-value? (:message data))
           (every? blank-payload-value? (vals residual))))))

(defn- shout!*
  "Internal shout implementation — assumes payload has been validated
   non-empty by `shout!`."
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
        ;; Cap message/task at canonical ingestion. One bad shout can otherwise
        ;; pollute the per-agent 10-message ring AND every backbone subscriber.
        capped-message (cap-message (:message data))
        capped-task (cap-message (:task data))
        message (cond-> {:event-type event-type
                         :timestamp now
                         :project-id project-id
                         :shout-id shout-id
                         :data (dissoc data :task :message :directory :project-id)}
                  capped-task (assoc :task capped-task)
                  capped-message (assoc :message capped-message))
        ;; Backbone payload — flat, self-contained, no internal references
        ;; shout-id enables cross-path dedup (atom + backbone deliver same shout)
        backbone-payload {:agent-id agent-id
                          :event-type event-type
                          :timestamp now
                          :project-id project-id
                          :shout-id shout-id
                          :message capped-message
                          :task capped-task
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

(defn shout!
  "Broadcast a message to the hivemind coordinator.

   M1 Architecture (protocol-first):
   - Local state (atom + DataScript) updated synchronously
   - If backbone connected: single publish → backbone subscribers handle fanout
   - If backbone disconnected: direct fanout via IDeliveryChannel registry
   - Domain events (:ling/completed) are NOT dispatched here — callers
     that need domain side-effects dispatch them explicitly. This avoids
     a feedback loop: shout! → :ling/completed handler → :shout effect → shout!

   Empty-payload guard (kanban-hygiene-2026-04-27):
   - If the shout carries no task/message/data signal (`empty-shout?` true),
     it is suppressed with a debug log and `false` is returned. This prevents
     the `[] ()` no-op shouts observed during bulk-close cascades where
     side-effect chains race past payload assembly. Callers wanting telemetry
     for the no-op transition should pass at minimum a non-blank :message."
  [agent-id event-type data]
  (if (empty-shout? data)
    (do
      (log/debug "Hivemind shout suppressed (empty payload):"
                 agent-id event-type)
      false)
    (shout!* agent-id event-type data)))

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
