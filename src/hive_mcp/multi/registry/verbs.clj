(ns hive-mcp.multi.registry.verbs
  "DSL verb-code → {tool, command} registry.

   Mirrors registry.tools shape. Verbs are the concise sentence-form of
   batch ops: `[\"m+\" {\"c\" \"hello\"}]` → {tool memory command add content hello}.

   Owner :multi/core seeds the existing 36 verbs from hive-mcp.dsl.verbs/verb-table.
   Addons add verbs via the :multi/verb hook key."
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private state
  (atom {:by-code  {}     ;; "m+" → {:tool "memory" :command "add" :owner kw}
         :by-owner {}}))

(defn register!
  "Register a DSL verb. Returns :ok | :replaced | :conflict.

   entry shape: {:tool string :command string}"
  [owner code entry]
  (let [v (assoc entry :owner owner)]
    (let [outcome (atom :ok)]
      (swap! state
             (fn [{:keys [by-code by-owner]}]
               (let [existing (get by-code code)]
                 (cond
                   (nil? existing)
                   (do (reset! outcome :ok)
                       {:by-code  (assoc by-code code v)
                        :by-owner (update by-owner owner (fnil conj #{}) code)})

                   (= owner (:owner existing))
                   (do (reset! outcome :replaced)
                       {:by-code  (assoc by-code code v)
                        :by-owner by-owner})

                   :else
                   (do (reset! outcome :conflict)
                       {:by-code by-code :by-owner by-owner})))))
      (when (= :conflict @outcome)
        (log/warn "[multi.registry.verbs] :multi/registry-conflict"
                  {:code code :existing-owner (:owner (get-in @state [:by-code code]))
                   :rejected-owner owner}))
      @outcome)))

(defn deregister-by-owner!
  "Remove every verb registered by `owner`. Returns set of removed codes."
  [owner]
  (let [removed (atom #{})]
    (swap! state
           (fn [{:keys [by-code by-owner]}]
             (let [codes (get by-owner owner #{})]
               (reset! removed codes)
               {:by-code  (apply dissoc by-code codes)
                :by-owner (dissoc by-owner owner)})))
    @removed))

(defn lookup
  "Return {:tool :command :owner} for a verb code, or nil."
  [code]
  (get-in @state [:by-code code]))

(defn all-codes
  "Sorted vector of all registered verb codes."
  []
  (vec (sort (keys (:by-code @state)))))

(defn snapshot []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test! []
  (reset! state {:by-code {} :by-owner {}}))
