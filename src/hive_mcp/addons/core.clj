(ns hive-mcp.addons.core
  "Addon registry — domain operations on addon instances.

   Addons implement the IAddon protocol from addons.protocol.
   This module provides the registry lifecycle:
   - register-addon! / unregister-addon!
   - init-addon! / shutdown-addon! / init-all! / shutdown-all!
   - list-addons / active-addon-tools / addons-with-capability
   - registry-status / reset-registry!"
  (:require [clojure.set]
            [hive-mcp.addons.protocol :as proto]
            [hive-mcp.dns.result :as r]
            [hive-mcp.extensions.registry :as ext]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private addon-registry (atom {}))

(defn register-addon!
  "Register an addon in the global registry."
  [addon]
  {:pre [(satisfies? proto/IAddon addon)]}
  (let [id (proto/addon-id addon)]
    (if (contains? @addon-registry id)
      (do
        (log/warn "Addon already registered" {:addon id})
        {:success? false
         :addon-name id
         :errors [(str "Addon " id " is already registered")]})
      (do
        (swap! addon-registry assoc id
               {:addon addon
                :state :registered
                :registered-at (java.time.Instant/now)
                :init-time nil
                :init-result nil})
        (log/info "Addon registered" {:addon id
                                      :type (proto/addon-type addon)
                                      :capabilities (proto/capabilities addon)})
        {:success? true
         :addon-name id}))))

(defn get-addon
  "Get addon by id from the registry."
  [id]
  (get-in @addon-registry [id :addon]))

(defn get-addon-entry
  "Get full addon registry entry with state metadata."
  [id]
  (get @addon-registry id))

(defn addon-registered?
  "Check if an addon is registered."
  [id]
  (contains? @addon-registry id))

(defn list-addons
  "List all registered addons with their state."
  []
  (->> @addon-registry
       (mapv (fn [[id {:keys [addon state registered-at init-time]}]]
               {:name id
                :type (proto/addon-type addon)
                :state state
                :registered-at registered-at
                :init-time init-time
                :capabilities (proto/capabilities addon)}))))

(defn unregister-addon!
  "Unregister an addon, calling shutdown! first if active."
  [id]
  (if-let [{:keys [addon state]} (get-addon-entry id)]
    (do
      (when (= state :active)
        (let [result (r/try-effect* :addon/shutdown-error (proto/shutdown! addon))]
          (cond
            (r/err? result)
            (log/error "Addon shutdown failed during unregister"
                       {:addon id :error (:message result)})

            (and (r/ok? result) (not (:success? (:ok result))))
            (log/warn "Addon shutdown had errors during unregister"
                      {:addon id :errors (:errors (:ok result))}))))
      (swap! addon-registry dissoc id)
      (log/info "Addon unregistered" {:addon id})
      {:success? true :addon-name id})
    (do
      (log/warn "Addon not found for unregister" {:addon id})
      {:success? false
       :addon-name id
       :errors [(str "Addon " id " is not registered")]})))

(defn init-addon!
  "Initialize a registered addon.

   On success, also registers any extensions returned by the addon's
   `(proto/hooks addon)` into the extension registry, and tracks the
   set of registered keys per-addon so shutdown can remove only the
   hooks owned by this addon."
  [id & [opts]]
  (if-let [{:keys [addon state]} (get-addon-entry id)]
    (if (= state :active)
      (do
        (log/info "Addon already active, skipping init" {:addon id})
        {:success? true :addon-name id :already-active? true})
      (let [init-result
            (r/try-effect* :addon/init-exception
                           (let [start-time (System/nanoTime)
                                 result (proto/initialize! addon (or opts {}))
                                 elapsed-ms (/ (- (System/nanoTime) start-time) 1e6)]
                             (if (:success? result)
                               (do
                                 (swap! addon-registry assoc-in [id :state] :active)
                                 (swap! addon-registry assoc-in [id :init-time]
                                        (java.time.Instant/now))
                                 (swap! addon-registry assoc-in [id :init-result] result)
                    ;; Register schema extensions from protocol method
                                 (let [schema-exts (proto/schema-extensions addon)]
                                   (when (seq schema-exts)
                                     (doseq [[tool-name props] schema-exts]
                                       (ext/register-schema! tool-name props))
                                     (log/debug "Addon registered schema extensions"
                                                {:addon id :tools (keys schema-exts)})))
                    ;; Register extensions from init result metadata (opaque fn registry)
                                 (when-let [exts (:extensions (:metadata result))]
                                   (ext/register-many! exts)
                                   (log/debug "Addon registered extensions"
                                              {:addon id :keys (keys exts)}))
                    ;; Register tools
                                 (doseq [t (proto/tools addon)]
                                   (ext/register-tool! t))
                    ;; Register hooks declared via IAddon `hooks` protocol method.
                    ;; Legacy addons that don't implement `hooks` rescue to {}.
                    ;; Per-addon hook-keys are tracked so shutdown only removes
                    ;; the hooks this addon registered (no clobber across addons).
                                 (let [hooks-map (r/rescue {} (proto/hooks addon))]
                                   (when (seq hooks-map)
                                     (doseq [[k v] hooks-map]
                                       (case (namespace k)
                                         "multi" ((requiring-resolve 'hive-mcp.multi.registry/register-by-key!)
                                                  id k v)
                                         "saa" ((requiring-resolve 'hive-mcp.saa.registry/register-by-key!)
                                                id k v)
                                         "plan" ((requiring-resolve 'hive-mcp.plan.field-registry/register-by-key!)
                                                 id k v)
                                         "wf" ((requiring-resolve 'hive-mcp.workflows.strategy-registry/register-by-key!)
                                               id k v)
                                         (ext/register! k v)))
                                     (swap! addon-registry assoc-in
                                            [id :hook-keys] (set (keys hooks-map)))
                                     (log/debug "Addon registered hooks"
                                                {:addon id :keys (keys hooks-map)})))

                                 (log/info "Addon initialized" {:addon id
                                                                :elapsed-ms elapsed-ms})
                                 (assoc result :addon-name id :elapsed-ms elapsed-ms))
                               (do
                                 (swap! addon-registry assoc-in [id :state] :error)
                                 (swap! addon-registry assoc-in [id :init-result] result)
                                 (log/warn "Addon init failed" {:addon id
                                                                :errors (:errors result)})
                                 (assoc result :addon-name id)))))]
        (if (r/err? init-result)
          (do (swap! addon-registry assoc-in [id :state] :error)
              (log/error "Addon init threw exception"
                         {:addon id :error (:message init-result)})
              {:success? false
               :addon-name id
               :errors [(:message init-result)]})
          (:ok init-result))))
    {:success? false
     :addon-name id
     :errors [(str "Addon " id " is not registered")]}))

(defn shutdown-addon!
  "Shutdown an active addon.

   Deregisters extensions, tools, composite contributions, and hooks
   that were registered for this addon during init. Hook ownership
   is tracked per-addon (entry's `:hook-keys`) so shutdown only
   removes hooks this addon registered."
  [id]
  (if-let [{:keys [addon state init-result hook-keys]} (get-addon-entry id)]
    (if (not= state :active)
      (do
        (log/info "Addon not active, skipping shutdown" {:addon id :state state})
        {:success? true :addon-name id :already-inactive? true})
      (let [shutdown-result
            (r/try-effect* :addon/shutdown-exception
              ;; Deregister extensions stored during init
                           (when-let [exts (:extensions (:metadata init-result))]
                             (doseq [k (keys exts)] (ext/deregister! k))
                             (log/debug "Addon deregistered extensions" {:addon id :keys (keys exts)}))
              ;; Deregister hooks (only those owned by this addon)
                           (when (seq hook-keys)
                             (doseq [k hook-keys]
                               (case (namespace k)
                                 "multi" ((requiring-resolve 'hive-mcp.multi.registry/deregister-by-key!)
                                          id k)
                                 "saa" ((requiring-resolve 'hive-mcp.saa.registry/deregister-by-key!)
                                        id k)
                                 "plan" ((requiring-resolve 'hive-mcp.plan.field-registry/deregister-by-owner!)
                                         id)
                                 "wf" ((requiring-resolve 'hive-mcp.workflows.strategy-registry/deregister-by-owner!)
                                       id)
                                 (ext/deregister! k)))
                             ;; Belt-and-suspenders: clear any leftover :multi/*, :saa/*, :plan/*, :wf/* entries by owner
                             ((requiring-resolve 'hive-mcp.multi.registry/deregister-by-owner!) id)
                             ((requiring-resolve 'hive-mcp.saa.registry/deregister-by-owner!) id)
                             ((requiring-resolve 'hive-mcp.plan.field-registry/deregister-by-owner!) id)
                             ((requiring-resolve 'hive-mcp.workflows.strategy-registry/deregister-by-owner!) id)
                             (log/debug "Addon deregistered hooks" {:addon id :keys hook-keys}))
              ;; Deregister tools
                           (doseq [t (proto/tools addon)]
                             (ext/deregister-tool! (:name t)))
              ;; Retract composite tool contributions
                           (ext/retract-all-by-addon! id)
                           (let [result (proto/shutdown! addon)]
                             (swap! addon-registry assoc-in [id :state] :registered)
                             (swap! addon-registry assoc-in [id :init-time] nil)
                             (swap! addon-registry update id dissoc :hook-keys)
                             (log/info "Addon shut down" {:addon id})
                             (assoc result :addon-name id)))]
        (if (r/err? shutdown-result)
          (do (swap! addon-registry assoc-in [id :state] :error)
              (log/error "Addon shutdown threw exception"
                         {:addon id :error (:message shutdown-result)})
              {:success? false
               :addon-name id
               :errors [(:message shutdown-result)]})
          (:ok shutdown-result))))
    {:success? false
     :addon-name id
     :errors [(str "Addon " id " is not registered")]}))

(defn init-all!
  "Initialize all registered addons that are not yet active."
  [& [opts]]
  (->> @addon-registry
       (filter (fn [[_id {:keys [state]}]] (not= state :active)))
       (map (fn [[id _]]
              [id (init-addon! id opts)]))
       (into {})))

(defn shutdown-all!
  "Shutdown all active addons."
  []
  (->> @addon-registry
       (filter (fn [[_id {:keys [state]}]] (= state :active)))
       (map (fn [[id _]]
              [id (shutdown-addon! id)]))
       (into {})))

(defn- safe-excluded-tools
  "Get excluded-tools set from addon, returning #{} for legacy addons
   that don't implement the method."
  [addon]
  (r/rescue #{} (proto/excluded-tools addon)))

(defn active-addon-tools
  "Get all MCP tools from active addons.
   Respects excluded-tools declarations: when addon A excludes tool name X,
   tools named X from all OTHER addons are filtered out."
  []
  (let [active (->> @addon-registry
                    (filter (fn [[_id {:keys [state addon]}]]
                              (and (= state :active)
                                   (contains? (proto/capabilities addon) :tools)))))
        ;; {tool-name -> declaring-addon-id} — who declared each exclusion
        exclusions (->> active
                        (mapcat (fn [[id {:keys [addon]}]]
                                  (map (fn [tn] [tn id])
                                       (safe-excluded-tools addon))))
                        (into {}))]
    (->> active
         (mapcat (fn [[id {:keys [addon]}]]
                   (->> (proto/tools addon)
                        ;; Remove tools excluded by ANOTHER addon
                        (remove (fn [t]
                                  (when-let [excluder (get exclusions (:name t))]
                                    (not= excluder id))))
                        (map #(assoc % :addon-source id)))))
         vec)))

(defn addon-tools-by-name
  "Get MCP tools contributed by a specific addon."
  [id]
  (if-let [{:keys [addon state]} (get-addon-entry id)]
    (if (and (= state :active)
             (contains? (proto/capabilities addon) :tools))
      (vec (proto/tools addon))
      [])
    []))

(defn addons-with-capability
  "Find all active addons providing a specific capability."
  [capability]
  (->> @addon-registry
       (filter (fn [[_id {:keys [state addon]}]]
                 (and (= state :active)
                      (contains? (proto/capabilities addon) capability))))
       (mapv first)))

(defn all-capabilities
  "Get capability summary from all active addons."
  []
  (let [active (->> @addon-registry
                    (filter (fn [[_id {:keys [state]}]] (= state :active))))]
    (->> active
         (mapcat (fn [[id {:keys [addon]}]]
                   (map (fn [cap] [cap id]) (proto/capabilities addon))))
         (reduce (fn [acc [cap id]]
                   (update acc cap (fnil conj []) id))
                 {}))))

(defn check-dependencies
  "Check if all dependencies for an addon are satisfied.
   Dependencies come from health details: {:details {:dependencies #{...}}}"
  [id]
  (if-let [addon (get-addon id)]
    (let [h (r/guard Exception {} (proto/health addon))
          deps (or (get-in h [:details :dependencies]) #{})
          available (set (keys @addon-registry))
          missing (clojure.set/difference deps available)]
      {:satisfied? (empty? missing)
       :missing missing
       :available (clojure.set/intersection deps available)})
    {:satisfied? false
     :missing #{id}
     :available #{}}))

(defn reset-registry!
  "Reset the addon registry, shutting down active addons first.
   Also clears all composite tool contributions."
  []
  (let [shutdown-results (shutdown-all!)]
    (reset! addon-registry {})
    (log/info "Addon registry reset")
    shutdown-results))

(defn registry-status
  "Get comprehensive status of the addon registry."
  []
  (let [entries (vals @addon-registry)
        by-state (group-by :state entries)]
    {:total (count entries)
     :active (count (:active by-state))
     :registered (count (:registered by-state))
     :error (count (:error by-state))
     :tool-count (count (active-addon-tools))
     :capabilities (all-capabilities)
     :addons (list-addons)}))
