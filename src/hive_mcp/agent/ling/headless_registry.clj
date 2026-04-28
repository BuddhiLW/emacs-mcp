(ns hive-mcp.agent.ling.headless-registry
  "Registry mapping headless-mode keywords to IHeadlessBackend instances.

   Addons register during initialize! lifecycle:
     (register-headless! :claude-sdk my-sdk-backend)

   Core resolves at spawn time:
     (resolve-headless-strategy :claude-sdk)
     ;; => HeadlessAddonStrategy wrapping the backend (implements ILingStrategy)

   Thread-safety: atom + swap! (all operations atomic).
   Idempotent: Re-registering the same key replaces silently. Backend selection
   is explicit; classpath discovery of an addon must not make that addon
   override the normal agentic backend by priority alone.

   See also:
   - hive-mcp.agent.ling.terminal-registry        -- Analogous pattern for terminals
   - hive-mcp.addons.headless                      -- IHeadlessBackend protocol
   - hive-mcp.agent.ling.headless-addon-strategy   -- Bridge adapter"
  (:require [hive-mcp.addons.headless :as headless]
            [hive-mcp.agent.ling.headless-addon-strategy :as headless-strat]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry State
;; =============================================================================

;; Maps keyword headless-id -> IHeadlessBackend instance.
(defonce ^:private registry (atom {}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn register-headless!
  "Register a headless backend under a keyword identifier.
   Validates that backend satisfies IHeadlessBackend protocol.
   Idempotent: re-registration replaces the previous backend (last-write-wins).

   Returns {:registered? true  :headless-id id} on success,
           {:registered? false :headless-id id :errors [...]} on failure."
  [headless-id backend]
  {:pre [(keyword? headless-id)]}
  (if-not (satisfies? headless/IHeadlessBackend backend)
    (do (log/warn "Cannot register headless: does not satisfy IHeadlessBackend"
                  {:headless-id headless-id})
        {:registered? false
         :headless-id headless-id
         :errors ["Object does not satisfy IHeadlessBackend protocol"]})
    (do (swap! registry assoc headless-id backend)
        (log/info "Headless backend registered" {:headless-id headless-id
                                                 :capabilities (headless/capabilities backend)})
        {:registered? true
         :headless-id headless-id})))

(defn resolve-headless-strategy
  "Look up headless-id in registry and wrap as ILingStrategy.
   Returns a HeadlessAddonStrategy instance, or nil if not found."
  [headless-id]
  (when-let [backend (get @registry headless-id)]
    (headless-strat/->headless-addon-strategy backend)))

(defn registered-headless
  "Return the set of registered headless-id keywords."
  []
  (set (keys @registry)))

(defn get-headless-backend
  "Get the raw IHeadlessBackend instance for a headless-id, or nil."
  [headless-id]
  (get @registry headless-id))

(defn headless-capabilities
  "Get declared capabilities for a registered headless backend.
   Returns the capability set, or nil if headless-id not registered."
  [headless-id]
  (when-let [backend (get @registry headless-id)]
    (headless/capabilities backend)))

(defn deregister-headless!
  "Remove a headless backend from the registry (for addon shutdown).
   Safe to call with unregistered headless-id (no-op).
   Returns {:deregistered? bool :headless-id id}."
  [headless-id]
  (let [had-entry? (contains? @registry headless-id)]
    (swap! registry dissoc headless-id)
    (when had-entry?
      (log/info "Headless backend deregistered" {:headless-id headless-id}))
    {:deregistered? had-entry?
     :headless-id headless-id}))

(defn best-headless-for-provider
  "Return the best headless-id for a provider keyword (:claude, :openai, etc.).
   Preference order for :claude: :claude-sdk > :claude-process > :hive-agent.
   :hive-agent stays available for explicit selection; automatic META-INF addon
   discovery must not silently override the transparent Claude agentic path.
   Returns nil if no backend available for the provider."
  [provider-kw]
  (let [registered (registered-headless)]
    (case provider-kw
      :claude (cond
                (contains? registered :claude-sdk) :claude-sdk
                (contains? registered :claude-process) :claude-process
                (contains? registered :hive-agent) :hive-agent
                :else nil)
      ;; Future providers can add their own preference logic here
      (first (filter #(= provider-kw (namespace %)) registered)))))

(defn clear-registry!
  "Reset the headless registry. Intended for testing only."
  []
  (reset! registry {})
  nil)
