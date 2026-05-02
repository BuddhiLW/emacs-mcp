(ns hive-mcp.tools.agent.helpers
  "Shared helper functions for agent tool handlers."
  (:require [hive-mcp.tools.swarm.core :as swarm-core]
            [hive-mcp.agent.type-registry :as agent-type-registry]
            [hive-mcp.emacs-ext.client :as ec]
            [taoensso.timbre :as log]
            [clojure.data.json :as json]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn generate-agent-id
  "Generate unique agent ID with type prefix."
  [agent-type]
  (str (name agent-type) "-" (java.util.UUID/randomUUID)))

(defn format-agent
  "Format agent data for response."
  [agent-data]
  (when agent-data
    (let [base {:id (:slave/id agent-data)
                :status (:slave/status agent-data)
                :type (agent-type-registry/depth->agent-type (:slave/depth agent-data))
                :cwd (:slave/cwd agent-data)
                :project-id (:slave/project-id agent-data)}]
      (cond-> base
        (:slave/parent agent-data) (assoc :parent (:slave/parent agent-data))
        (:slave/presets agent-data) (assoc :presets (:slave/presets agent-data))
        (:slave/created-at agent-data) (assoc :created-at (:slave/created-at agent-data))))))

(defn format-agents
  "Format a list of agents for response."
  [agents]
  (let [formatted (->> agents
                       (map format-agent)
                       (remove nil?)
                       vec)]
    {:agents formatted
     :count (count formatted)
     :by-type (frequencies (map :type formatted))
     :by-status (frequencies (map :status formatted))}))

(defn query-elisp-lings
  "Query elisp for lings that may not be in DataScript."
  []
  (when (swarm-core/swarm-addon-available?)
    (let [{:keys [success result timed-out]}
          (ec/eval-elisp-with-timeout
           "(json-encode (hive-mcp-swarm-list-lings))" 3000)]
      (when (and success (not timed-out))
        (try
          (let [parsed (json/read-str result :key-fn keyword)]
            (when (sequential? parsed)
              (->> parsed
                   (map (fn [ling]
                          {:slave/id (or (:slave-id ling) (:slave_id ling))
                           :slave/name (:name ling)
                           :slave/status (keyword (or (:status ling) "idle"))
                           :slave/depth 1  ;; lings are depth 1
                           :slave/cwd (:cwd ling)
                           :slave/project-id (:project-id ling)
                           :slave/presets (:presets ling)}))
                   (filter :slave/id))))  ;; filter out invalid entries
          (catch Exception e
            (log/debug "Failed to parse elisp lings:" (ex-message e))
            []))))))

(defn merge-with-elisp-lings
  "Merge DataScript agents with elisp lings, DataScript taking precedence."
  [ds-agents]
  (try
    (let [elisp-lings (or (query-elisp-lings) [])
          ds-ids (set (map :slave/id ds-agents))
          new-lings (remove #(ds-ids (:slave/id %)) elisp-lings)]
      (log/debug "Merging agents: DataScript=" (count ds-agents)
                 "elisp-only=" (count new-lings))
      (concat ds-agents new-lings))
    (catch Exception e
      (log/warn "Failed to merge elisp lings (returning DataScript only):" (ex-message e))
      ds-agents)))
