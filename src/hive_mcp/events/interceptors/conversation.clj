(ns hive-mcp.events.interceptors.conversation
  "Interceptors for the Inter-Ling Conversation Protocol.

   One job each (Stratification): each interceptor owns a single seam in the
   CPPB chain (Collect → Promote → Pipeline → Boundary).

   Provided interceptors:
     - validate-conversation : reject events missing :from/:to/:event-type
     - inject-ask-registry   : inject pending-asks state as a coeffect
     - correlate-ask-id      : on :conversation/respond, promote the ask-id
                               and pending entry into the coeffects so the
                               handler can decide delivery vs drop
     - log-conversation      : debug log envelope (CPPB Pipeline)

   Pure: each interceptor is a small map with :id and :before. No side effects
   inside :before — just data shaping. Effects are emitted by the handler and
   executed by `hive-mcp.events.effects.conversation`."

  (:require [hive.events.interceptor :as ix]
            [hive-mcp.hivemind.conversation :as conv]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- event-payload
  "The payload map of a [:event-id payload] vector."
  [event]
  (when (and (vector? event) (>= (count event) 2))
    (nth event 1)))

(defn- conversation-event?
  "True when the event-id is one of the conversation protocol events."
  [[event-id]]
  (and (keyword? event-id)
       (= "conversation" (namespace event-id))))

;; =============================================================================
;; validate-conversation
;; =============================================================================

(def validate-conversation
  "Validate that a conversation event carries :from, :to and a sensible body.
   On failure, drops the queue and assoc's an :error effect via :effects.

   Skipped silently for non-conversation events (so this interceptor is safe
   to add globally if desired)."
  (ix/->interceptor
   :id :validate-conversation
   :before
   (fn [{:keys [coeffects] :as ctx}]
     (let [event   (:event coeffects)
           [eid p] event]
       (if-not (conversation-event? event)
         ctx
         (let [missing (cond-> []
                         (not (string? (:from p))) (conj :from)
                         (not (string? (:to p)))   (conj :to)
                         (and (= eid :conversation/ask)
                              (not (string? (:question p)))) (conj :question)
                         (and (= eid :conversation/tell)
                              (nil? (:message p))) (conj :message)
                         (and (= eid :conversation/respond)
                              (not (string? (:ask-id p)))) (conj :ask-id))]
           (if (seq missing)
             (do
               (log/warn "[conv-ix] invalid" eid "missing" missing)
               (-> ctx
                   (assoc :queue [])
                   (assoc-in [:effects :log]
                             {:level :warn
                              :message (str "conversation event " eid
                                            " missing keys: " missing)})))
             ctx)))))))

;; =============================================================================
;; inject-ask-registry
;; =============================================================================

(def inject-ask-registry
  "Inject a snapshot of `pending-asks` into the coeffects under
   `:conversation/pending-asks`. Read-only — handlers use this to decide
   correlation. The atom is the source of truth; this snapshot is a
   point-in-time read so handlers stay pure."
  (ix/->interceptor
   :id :inject-ask-registry
   :before
   (fn [ctx]
     (assoc-in ctx [:coeffects :conversation/pending-asks]
               @conv/pending-asks))))

;; =============================================================================
;; correlate-ask-id
;; =============================================================================

(def correlate-ask-id
  "On :conversation/respond, promote the matching pending ask (if any) into
   `:coeffects :conversation/correlated-ask`. The handler then decides:
     - matched → emit :conversation/deliver-response effect
     - unmatched → emit :log warn (stale or foreign respond)"
  (ix/->interceptor
   :id :correlate-ask-id
   :before
   (fn [{:keys [coeffects] :as ctx}]
     (let [event (:event coeffects)
           [eid p] event]
       (if (= eid :conversation/respond)
         (let [match (conv/pending-ask (:ask-id p))]
           (assoc-in ctx [:coeffects :conversation/correlated-ask] match))
         ctx)))))

;; =============================================================================
;; log-conversation
;; =============================================================================

(def log-conversation
  "Debug-log the envelope for visibility. Always pass-through."
  (ix/->interceptor
   :id :log-conversation
   :before
   (fn [{:keys [coeffects] :as ctx}]
     (let [event (:event coeffects)]
       (when (conversation-event? event)
         (let [[eid p] event]
           (log/debug "[conv-ix]" eid
                      (select-keys p [:from :to :ask-id])))))
     ctx)))

;; =============================================================================
;; Composite chain
;; =============================================================================

(def conversation-chain
  "Default interceptor stack for conversation event handlers.
   Order matters: validate first (short-circuits on bad input),
   then registry snapshot, then correlate, then log."
  [validate-conversation
   inject-ask-registry
   correlate-ask-id
   log-conversation])
