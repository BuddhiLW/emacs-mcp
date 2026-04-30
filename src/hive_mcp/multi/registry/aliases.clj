(ns hive-mcp.multi.registry.aliases
  "Param-alias registry — short keys → full keywords.

   Used by the DSL parse path to expand `{\"c\" \"hi\"}` → `{:content \"hi\"}`.
   Owner :multi/core seeds the 9 default aliases from hive-mcp.dsl.verbs/param-aliases."
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private state
  (atom {:by-short {}     ;; "c" → {:full :content :owner kw}
         :by-owner {}}))

(defn register!
  "Register a param alias. Returns :ok | :replaced | :conflict.

   entry shape: {:full keyword?}"
  [owner short-key entry]
  (let [v (assoc entry :owner owner)]
    (let [outcome (atom :ok)]
      (swap! state
             (fn [{:keys [by-short by-owner]}]
               (let [existing (get by-short short-key)]
                 (cond
                   (nil? existing)
                   (do (reset! outcome :ok)
                       {:by-short (assoc by-short short-key v)
                        :by-owner (update by-owner owner (fnil conj #{}) short-key)})

                   (= owner (:owner existing))
                   (do (reset! outcome :replaced)
                       {:by-short (assoc by-short short-key v)
                        :by-owner by-owner})

                   :else
                   (do (reset! outcome :conflict)
                       {:by-short by-short :by-owner by-owner})))))
      (when (= :conflict @outcome)
        (log/warn "[multi.registry.aliases] :multi/registry-conflict"
                  {:short short-key
                   :existing-owner (:owner (get-in @state [:by-short short-key]))
                   :rejected-owner owner}))
      @outcome)))

(defn deregister-by-owner!
  "Remove all aliases registered by `owner`. Returns set of removed short keys."
  [owner]
  (let [removed (atom #{})]
    (swap! state
           (fn [{:keys [by-short by-owner]}]
             (let [shorts (get by-owner owner #{})]
               (reset! removed shorts)
               {:by-short (apply dissoc by-short shorts)
                :by-owner (dissoc by-owner owner)})))
    @removed))

(defn lookup
  "Return {:full kw :owner kw} for a short key, or nil."
  [short-key]
  (get-in @state [:by-short short-key]))

(defn all-aliases
  "Map of {short-key → :full-keyword} across all owners."
  []
  (into {} (map (fn [[s {:keys [full]}]] [s full])) (:by-short @state)))

(defn snapshot []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test! []
  (reset! state {:by-short {} :by-owner {}}))
