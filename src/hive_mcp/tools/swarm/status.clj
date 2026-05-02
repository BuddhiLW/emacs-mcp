(ns hive-mcp.tools.swarm.status
  "Swarm status handlers - status, lings-available, broadcast, and list-presets."
  (:require [hive-mcp.tools.swarm.core :as core]
            [hive-mcp.tools.swarm.registry :as registry]
            [hive-mcp.tools.swarm.state :as state]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.dns.validation :as v]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [hive-mcp.dns.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn query-elisp-lings
  "Query elisp for list of lings when Clojure registry is empty."
  []
  (when (core/swarm-addon-available?)
    (let [{:keys [success result timed-out]}
          (ec/eval-elisp-with-timeout
           "(json-encode (hive-mcp-swarm-list-lings))" 3000)]
      (when (and success (not timed-out))
        (rescue nil
                (let [parsed (json/read-str result :key-fn keyword)]
                  (when (sequential? parsed)
                    parsed)))))))

(defn format-lings-for-response
  "Format lings data for MCP response."
  [lings-data]
  (cond
    (map? lings-data)
    (into {}
          (map (fn [[id info]]
                 [id (assoc info
                            :age-minutes (quot (- (System/currentTimeMillis)
                                                  (or (:spawned-at info) 0))
                                               60000))])
               lings-data))

    (sequential? lings-data)
    (into {}
          (map (fn [ling]
                 [(or (:slave-id ling) (:slave_id ling))
                  {:name (:name ling)
                   :presets (:presets ling)
                   :cwd (:cwd ling)
                   :project-id (:project-id ling)
                   :status (:status ling)
                   :age-minutes nil}])
               lings-data))

    :else {}))

(defn handle-swarm-status
  "Get swarm status including all slaves and their states."
  [{:keys [slave_id]}]
  (core/with-swarm
    (let [elisp (if slave_id
                  (format "(json-encode (hive-mcp-swarm-status \"%s\"))" slave_id)
                  "(json-encode (hive-mcp-swarm-api-status))")
          {:keys [success result error timed-out]} (ec/eval-elisp-with-timeout elisp 5000)]
      (cond
        timed-out
        (core/mcp-timeout-error "Status check")

        success
        (try
          (let [parsed (json/read-str result :key-fn keyword)
                slaves-detail (:slaves-detail parsed)
                merged-slaves (state/merge-hivemind-into-slaves slaves-detail)
                merged-parsed (if merged-slaves
                                (assoc parsed :slaves-detail merged-slaves)
                                parsed)]
            (core/mcp-success merged-parsed))
          (catch Exception e
            (log/warn "Failed to merge hivemind status:" (.getMessage e))
            (core/mcp-success result)))

        :else
        (core/mcp-error (str "Error: " error))))))

(defn handle-lings-available
  "List all available lings with their metadata."
  [_]
  (let [registry-lings (registry/get-available-lings)
        lings-data (if (empty? registry-lings)
                     (or (query-elisp-lings) {})
                     registry-lings)
        formatted (format-lings-for-response lings-data)
        source (if (empty? registry-lings)
                 (if (seq lings-data) "elisp-fallback" "empty")
                 "registry")]
    (core/mcp-success {:count (count formatted)
                       :lings formatted
                       :source source})))

(defn handle-swarm-broadcast
  "Broadcast a prompt to all slaves."
  [{:keys [prompt]}]
  (core/with-swarm
    (let [elisp (format "(json-encode (hive-mcp-swarm-broadcast \"%s\"))"
                        (v/escape-elisp-string prompt))
          {:keys [success result error timed-out]} (ec/eval-elisp-with-timeout elisp 5000)]
      (cond
        timed-out
        (core/mcp-timeout-error "Broadcast operation")

        success
        (try
          (let [task-ids (json/read-str result :key-fn keyword)
                delivered-count (count task-ids)]
            (if (zero? delivered-count)
              (core/mcp-error-json
               {:error "no-targets"
                :message "No slaves available to broadcast to. Spawn slaves first with swarm_spawn."
                :delivered-count 0
                :task-ids []})
              (core/mcp-success
               {:delivered-count delivered-count
                :task-ids task-ids
                :message (format "Broadcast delivered to %d slave(s)" delivered-count)})))
          (catch Exception e
            (log/warn "Failed to parse broadcast result:" (.getMessage e))
            (core/mcp-success result)))

        :else
        (core/mcp-error (str "Error: " error))))))

(defn handle-swarm-list-presets
  "List available swarm presets."
  [_]
  (core/with-swarm
    (let [{:keys [success result error timed-out]}
          (ec/eval-elisp-with-timeout "(json-encode (hive-mcp-swarm-api-list-presets))" 5000)]
      (cond
        timed-out
        (core/mcp-timeout-error "List presets")

        success
        (core/mcp-success result)

        :else
        (core/mcp-error (str "Error: " error))))))
