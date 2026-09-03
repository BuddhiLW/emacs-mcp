(ns hive-mcp.saa.registry.tool-intents
  "neutral tool-intent keyword → tool set registry.

   One ns, one shape (SRP). Owner = addon-id keyword. Synthetic owner
   :saa/core seeds the neutral DEFAULT intents at boot.

   Unlike the singleton provider/scorer/planner registries, tool-intents
   are ACCUMULATIVE: every owner contributing to an intent unions its tools
   into that intent's set. deregister-by-owner! removes only that owner's
   slice, leaving other owners' contributions intact.

   Storage:
     :by-intent  intent-kw → {owner-kw → #{tool ...}}
     :by-owner   owner-kw  → #{intent-kw ...}")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private state
  (atom {:by-intent {}    ;; :read → {:saa/core #{"read" "grep"}}
         :by-owner  {}})) ;; kw → #{:read :search}

(defn register!
  "Add `owner`'s tools to the intent's per-owner slice. Returns :ok.

   Accumulative union — re-registering the same owner/intent replaces that
   owner's slice and merges with other owners' slices on lookup.

   entry shape: {:tools coll}"
  [owner intent entry]
  (let [tools (set (:tools entry))]
    (swap! state
           (fn [{:keys [by-intent by-owner]}]
             {:by-intent (assoc-in by-intent [intent owner] tools)
              :by-owner  (update by-owner owner (fnil conj #{}) intent)}))
    :ok))

(defn deregister-by-owner!
  "Remove only `owner`'s slice from every intent it contributed to.
   Returns set of removed intent keywords."
  [owner]
  (let [removed (atom #{})]
    (swap! state
           (fn [{:keys [by-intent by-owner]}]
             (let [intents (get by-owner owner #{})]
               (reset! removed intents)
               {:by-intent (reduce (fn [bi intent]
                                     (let [slice (dissoc (get bi intent) owner)]
                                       (if (seq slice)
                                         (assoc bi intent slice)
                                         (dissoc bi intent))))
                                   by-intent
                                   intents)
                :by-owner  (dissoc by-owner owner)})))
    @removed))

(defn lookup
  "Return {:tools [...] :owner kw} for an intent, or nil.

   :tools is the accumulative UNION across all owners; :owner names the
   originating seed owner (:saa/core) when present, else the first owner."
  [intent]
  (when-let [slices (get-in @state [:by-intent intent])]
    (when (seq slices)
      {:tools (vec (sort (reduce into #{} (vals slices))))
       :owner (if (contains? slices :saa/core) :saa/core (first (keys slices)))})))

(defn lookup-owner-slice
  "Return the tool set `owner` contributed to `intent`, or nil.
   Provider-scoped resolution uses this to read one owner's slice."
  [owner intent]
  (get-in @state [:by-intent intent owner]))

(defn all-intents
  "Sorted vector of all registered intent keywords."
  []
  (vec (sort (keys (:by-intent @state)))))

(defn snapshot []
  (let [s @state]
    {:version (hash s) :data s}))

(defn reset-for-test! []
  (reset! state {:by-intent {} :by-owner {}}))
