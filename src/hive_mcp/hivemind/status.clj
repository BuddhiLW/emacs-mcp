(ns hive-mcp.hivemind.status
  "Hivemind status queries and agent lifecycle management."

  (:require [hive-mcp.hivemind.state :as state]
            [hive-mcp.channel.core :as channel]
            [hive-mcp.channel.websocket :as ws]
            [hive-mcp.swarm.protocol :as proto]
            [hive-mcp.swarm.datascript.registry :as registry]
            [hive-mcp.swarm.logic :as logic]
            [hive-mcp.tools.memory.scope :as mem-scope]
            [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bput! bget bounded-swap! bkeys]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- build-agents-map
  "Build agents map from DataScript merged with message history."
  ([]
   (build-agents-map nil))
  ([project-id]
   (let [ds-slaves (if project-id
                     (proto/get-slaves-by-project registry/default-registry project-id)
                     (proto/get-all-slaves registry/default-registry))
         ;; Unwrap bounded-atom entries to get raw agent-id -> data map
         msg-history (reduce-kv (fn [acc k entry]
                                  (assoc acc k (:data entry)))
                                {}
                                @(:atom state/agent-registry))]
     (reduce
      (fn [acc slave]
        (let [slave-id (:slave/id slave)
              messages (get-in msg-history [slave-id :messages] [])
              last-seen (get-in msg-history [slave-id :last-seen])]
          (assoc acc slave-id
                 {:status (:slave/status slave)
                  :name (:slave/name slave)
                  :presets (vec (:slave/presets slave))
                  :cwd (:slave/cwd slave)
                  :project-id (:slave/project-id slave)
                  :current-task (:slave/current-task slave)
                  :messages messages
                  :last-seen last-seen})))
      {}
      ds-slaves))))

(defn- delivery-channel-snapshot
  "Aggregate availability of every registered IDeliveryChannel.
   Returns {:<channel-id> <available?>}. Resolved lazily so this ns
   stays loadable even if delivery-channel registry is absent."
  []
  (try
    (when-let [get-fn (requiring-resolve 'hive-mcp.protocols.delivery-channel/get-channels)]
      (let [ch-id  (requiring-resolve 'hive-mcp.protocols.delivery-channel/channel-id)
            avail? (requiring-resolve 'hive-mcp.protocols.delivery-channel/available?)]
        (reduce (fn [acc ch]
                  (assoc acc (@ch-id ch) (boolean (@avail? ch))))
                {}
                (@get-fn))))
    (catch Throwable _ {})))

(defn get-status
  "Get current hivemind status, optionally filtered by project-id.

   Status includes:
   - :channel-connected / :ws-connected — legacy Emacs-bound booleans
   - :delivery-channels {channel-id -> available?} — frontend-agnostic
     view across every registered IDeliveryChannel (NATS, file-tail,
     piggyback, websocket, olympus, ...). Use this to decide whether
     delivery is up regardless of which transport is available."
  ([]
   (get-status nil))
  ([project-id]
   (let [channels (delivery-channel-snapshot)]
     {:agents (build-agents-map project-id)
      :project-id project-id
      :pending-asks (mapv (fn [[id {:keys [question options agent-id]}]]
                            {:ask-id id
                             :agent-id agent-id
                             :question question
                             :options options})
                          @state/pending-asks)
      :pending-swarm-prompts (mapv (fn [[slave-id entry]]
                                     (let [{:keys [prompt timestamp session-id received-at]} (:data entry)]
                                       {:slave-id slave-id
                                        :prompt prompt
                                        :timestamp timestamp
                                        :session-id session-id
                                        :received-at received-at}))
                                   @(:atom state/pending-swarm-prompts))
      :channel-connected (channel/server-connected?)
      :ws-connected (ws/connected?)
      :ws-clients (ws/client-count)
      :delivery-channels channels
      :delivery-up? (boolean (some true? (vals channels)))})))

(defn get-agent-messages
  "Get recent messages from a specific agent."
  [agent-id]
  (let [msg-history-entry (bget state/agent-registry agent-id)
        ds-slave (proto/get-slave registry/default-registry agent-id)]
    (cond
      msg-history-entry (:messages msg-history-entry)
      ds-slave []
      :else nil)))

(defn register-agent!
  "Register an agent in the hivemind registry."

  [agent-id metadata]
  (let [now (System/currentTimeMillis)]
    (bput! state/agent-registry agent-id
           {:messages []
            :last-seen now})
    (when-not (proto/get-slave registry/default-registry agent-id)
      (let [cwd (:cwd metadata)
            project-id (when cwd (mem-scope/get-current-project-id cwd))]
        (proto/add-slave! registry/default-registry agent-id
                          {:name (or (:name metadata) agent-id)
                           :status :idle
                           :depth 1
                           :presets (:presets metadata)
                           :cwd cwd
                           :project-id project-id})))
    (log/info "Agent registered in hivemind:" agent-id)
    true))

(defn clear-agent!
  "Remove an agent from the registry and release all claims."
  [agent-id]
  (bounded-swap! state/agent-registry dissoc agent-id)
  (logic/release-claims-for-slave! agent-id)
  (proto/remove-slave! registry/default-registry agent-id)
  (log/info "Agent cleared from hivemind:" agent-id))
