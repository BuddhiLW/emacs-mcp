(ns hive-mcp.saa.registry.phase-providers
  "provider-id → IPhaseProvider registry — the SAA phase-provider dispatch source.

   One ns, one shape (SRP). Two indices per swap! to make
   deregister-by-owner! O(owner-keys).

   Owner = addon-id keyword. Synthetic owner :saa/core represents the
   DefaultPhaseProvider seeded at boot.

   Conflict policy:
     same id + same owner      → idempotent silent replace
     same id + different owner  → :saa/registry-conflict warn-log,
                                   first-write-wins"
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private state
  (atom {:by-id    {}    ;; :saa/default → {:provider rec :owner kw :registered-at inst}
         :by-owner {}})) ;; kw → #{:saa/default}

(defn register!
  "Register an IPhaseProvider under owner. Returns :ok | :replaced | :conflict.

   entry shape: {:provider <IPhaseProvider>}"
  [owner provider-id entry]
  (let [now (java.time.Instant/now)
        v   (assoc entry :owner owner :registered-at now)]
    (let [outcome (atom :ok)]
      (swap! state
             (fn [{:keys [by-id by-owner]}]
               (let [existing (get by-id provider-id)]
                 (cond
                   (nil? existing)
                   (do (reset! outcome :ok)
                       {:by-id    (assoc by-id provider-id v)
                        :by-owner (update by-owner owner (fnil conj #{}) provider-id)})

                   (= owner (:owner existing))
                   (do (reset! outcome :replaced)
                       {:by-id    (assoc by-id provider-id v)
                        :by-owner by-owner})

                   :else
                   (do (reset! outcome :conflict)
                       {:by-id by-id :by-owner by-owner})))))
      (when (= :conflict @outcome)
        (log/warn "[saa.registry.phase-providers] :saa/registry-conflict"
                  {:provider-id provider-id
                   :existing-owner (:owner (get-in @state [:by-id provider-id]))
                   :rejected-owner owner}))
      @outcome)))

(defn deregister-by-owner!
  "Remove every provider registered by `owner`. Returns set of removed ids."
  [owner]
  (let [removed (atom #{})]
    (swap! state
           (fn [{:keys [by-id by-owner]}]
             (let [ids (get by-owner owner #{})]
               (reset! removed ids)
               {:by-id    (apply dissoc by-id ids)
                :by-owner (dissoc by-owner owner)})))
    @removed))

(defn lookup
  "Return the registered entry for a provider-id, or nil."
  [provider-id]
  (get-in @state [:by-id provider-id]))

(defn all-ids
  "Sorted vector of all registered provider-ids."
  []
  (vec (sort (keys (:by-id @state)))))

(defn snapshot []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test! []
  (reset! state {:by-id {} :by-owner {}}))
