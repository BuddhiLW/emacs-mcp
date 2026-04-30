(ns hive-mcp.multi.registry.tools
  "Tool-name → handler registry — the dispatch source for `multi`.

   One ns, one shape (SRP). Two indices per swap! to make
   deregister-by-owner! O(owner-keys).

   Owner = addon-id keyword. Synthetic owner :multi/core represents
   the consolidated/multi tool-handlers seeded at boot.

   Conflict policy:
     same key + same owner   → idempotent silent replace
     same key + different owner → :multi/registry-conflict warn-log,
                                   first-write-wins"
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private state
  (atom {:by-name  {}    ;; "memory" → {:handler fn :owner kw :registered-at inst}
         :by-owner {}})) ;; kw → #{"memory" "memory_v2"}

(defn register!
  "Register a tool handler under owner. Returns :ok | :replaced | :conflict.

   entry shape: {:handler ifn :batchable (some-fn nil? any?)}"
  [owner tool-name entry]
  (let [now #?(:clj (java.time.Instant/now) :default nil)
        v   (assoc entry :owner owner :registered-at now)]
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
        (log/warn "[multi.registry.tools] :multi/registry-conflict"
                  {:tool-name tool-name :existing-owner (:owner (get-in @state [:by-name tool-name]))
                   :rejected-owner owner}))
      @outcome)))

(defn deregister-by-owner!
  "Remove every tool registered by `owner`. Returns set of removed names."
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
  "Return the registered entry for a tool-name, or nil."
  [tool-name]
  (get-in @state [:by-name tool-name]))

(defn all-names
  "Return a sorted vector of registered tool names."
  []
  (vec (sort (keys (:by-name @state)))))

(defn snapshot
  "Immutable snapshot for deterministic plan compilation.

   :version is `(hash @state)` so plans serialized with this version
   can be replayed against a registry whose hash matches."
  []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test!
  "Test-only — clear all entries. Do NOT call from production code."
  []
  (reset! state {:by-name {} :by-owner {}}))
