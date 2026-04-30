(ns hive-mcp.multi.registry.batchables
  "Tool-name → Batchable record registry.

   When a tool ships an explicit hive-mcp.batch.protocol/Batchable impl,
   addons register it here so the multi engine can substitute the optimized
   single-store-call path for the LSP-clean DefaultBatchableAdapter fallback.

   Per the LSP guarantee, every tool — registered or not — is satisfied by
   SOMETHING that implements Batchable; lookup-or-default in the façade returns
   the default adapter for any tool without an explicit record."
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private state
  (atom {:by-name  {}     ;; "memory" → {:record record :owner kw}
         :by-owner {}}))

(defn register!
  "Register a Batchable record for tool-name. Returns :ok | :replaced | :conflict.

   entry shape: {:record any? (must satisfy Batchable)}"
  [owner tool-name entry]
  (let [v (assoc entry :owner owner)]
    (let [outcome (atom :ok)]
      (swap! state
             (fn [{:keys [by-name by-owner]}]
               (let [existing (get by-name tool-name)]
                 (cond
                   (nil? existing)
                   (do (reset! outcome :ok)
                       {:by-name  (assoc by-name tool-name v)
                        :by-owner (update by-owner owner (fnil conj #{}) tool-name)})

                   (= owner (:owner existing))
                   (do (reset! outcome :replaced)
                       {:by-name  (assoc by-name tool-name v)
                        :by-owner by-owner})

                   :else
                   (do (reset! outcome :conflict)
                       {:by-name by-name :by-owner by-owner})))))
      (when (= :conflict @outcome)
        (log/warn "[multi.registry.batchables] :multi/registry-conflict"
                  {:tool-name tool-name
                   :existing-owner (:owner (get-in @state [:by-name tool-name]))
                   :rejected-owner owner}))
      @outcome)))

(defn deregister-by-owner!
  "Remove all batchables registered by `owner`. Returns set of removed names."
  [owner]
  (let [removed (atom #{})]
    (swap! state
           (fn [{:keys [by-name by-owner]}]
             (let [names (get by-owner owner #{})]
               (reset! removed names)
               {:by-name  (apply dissoc by-name names)
                :by-owner (dissoc by-owner owner)})))
    @removed))

(defn lookup
  "Return {:record :owner} for a tool-name, or nil."
  [tool-name]
  (get-in @state [:by-name tool-name]))

(defn snapshot []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test! []
  (reset! state {:by-name {} :by-owner {}}))
