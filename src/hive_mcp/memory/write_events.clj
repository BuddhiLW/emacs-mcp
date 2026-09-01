(ns hive-mcp.memory.write-events
  "Memory write notifications: one vocabulary for add / update / delete of a
   memory entry, published where consumers can PULL it.

   A writer calls `notify!` with an op keyword and a payload; nothing here
   names a consumer. Three sinks receive the event, in this order:

     listeners               (fn [write]) registered through
                             `register-listener!`; called synchronously, so a
                             read issued after `notify!` returns observes them.
     hive-mcp.channel.core   core.async pub on :type
                             (:memory-added | :memory-updated | :memory-deleted)
     hive-events             [:memory/added | :memory/edited | :memory/deleted payload]
                             only when hive-mcp.events.core is already loaded
                             AND a handler is registered for the event id.

   The channel-type <-> hive-event mapping is owned by
   `hive-mcp.events.bridge/hook->event`; this namespace derives the hive-events
   vector from it and never restates the table.

   Payload keys (all optional): :id :memory-type :tags :project-id :fields."
  (:require [hive-mcp.events.bridge :as bridge]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def op->channel-type
  "Write op -> channel event :type. Closed set."
  {:added   :memory-added
   :updated :memory-updated
   :deleted :memory-deleted})

(def channel-type->op
  "Channel event :type -> write op. Inverse of `op->channel-type`."
  (into {} (map (fn [[op t]] [t op])) op->channel-type))

(def channel-types
  "Every channel event :type a write can produce, in op order."
  (vec (vals op->channel-type)))

(def ^:private payload-keys
  [:id :memory-type :tags :project-id :fields])

(defn ->event
  "Channel event map for `op` + `payload`: the payload keys plus :type."
  [op payload]
  (assoc (select-keys payload payload-keys)
         :type (op->channel-type op)))

(defn event->write
  "Inverse of `->event`: a channel event map -> {:op ... payload-keys...}.
   Returns nil when the event :type is not a write op."
  [event]
  (when-let [op (channel-type->op (:type event))]
    (assoc (select-keys event payload-keys) :op op)))

(defonce ^:private listeners
  ^{:doc "{key -> (fn [write])}, each called synchronously by notify! before the buses."}
  (atom {}))

(defn register-listener!
  "Install `f` (fn [write]) under `key`; a later registration under the same
   key replaces it. `f` runs synchronously inside `notify!`, before the buses
   are published to, with the map `event->write` builds. Returns key."
  [key f]
  {:pre [(fn? f)]}
  (swap! listeners assoc key f)
  key)

(defn unregister-listener!
  "Remove the listener under `key`. No-op when absent. Returns nil."
  [key]
  (swap! listeners dissoc key)
  nil)

(defn listener-keys
  "Keys of the registered listeners."
  []
  (set (keys @listeners)))

(defn- call-listeners!
  [event]
  (when-let [write (event->write event)]
    (doseq [[k f] @listeners]
      (try
        (f write)
        (catch Throwable t
          (log/warn t "write-events: listener threw" k))))))

(defn- publish-channel!
  [event]
  (rescue nil
    (when-let [publish! (requiring-resolve 'hive-mcp.channel.core/publish!)]
      (publish! event)
      true)))

(defn- dispatch-hive-event!
  "Bridge to hive-events through the bridge table. Pulls only from an events
   core that is ALREADY loaded: a write never forces the event subsystem in."
  [event]
  (rescue nil
    (when-let [ev-ns (find-ns 'hive-mcp.events.core)]
      (let [registered? (ns-resolve ev-ns 'handler-registered?)
            dispatch    (ns-resolve ev-ns 'dispatch)
            hive-event  (bridge/hook->event (:type event) (dissoc event :type))]
        (when (and registered? dispatch hive-event
                   (registered? (first hive-event)))
          (dispatch hive-event)
          true)))))

(defn notify!
  "Announce a memory write. `op` is :added, :updated or :deleted; `payload`
   carries any of :id :memory-type :tags :project-id :fields. Listeners run
   first, synchronously; then the channel bus and hive-events receive the
   event. Never throws; returns nil."
  [op payload]
  (if-not (contains? op->channel-type op)
    (log/warn "write-events/notify!: unknown op" op)
    (let [event (->event op payload)]
      (call-listeners! event)
      (publish-channel! event)
      (dispatch-hive-event! event)))
  nil)
