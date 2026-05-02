(ns hive-mcp.agent.ling.headless-registry
  "Registry mapping headless-mode keywords to IHeadlessBackend instances.

   Addons register during initialize! lifecycle. Two arities:

     ;; Backward-compat: bare registration, no preference metadata.
     (register-headless! :claude-sdk my-sdk-backend)

     ;; Preferred: declarative metadata so `best-headless-for-provider` can
     ;; rank candidates without hive-mcp ever case-matching on backend names.
     (register-headless! :claude-sdk my-sdk-backend
                         {:provides #{:claude} :priority 30})

   Core resolves at spawn time:
     (resolve-headless-strategy :claude-sdk)
     ;; => HeadlessAddonStrategy wrapping the backend (implements ILingStrategy)

   Thread-safety: atom + swap! (all operations atomic).
   Idempotent: Re-registering the same key replaces silently. Backend selection
   is explicit; classpath discovery of an addon must not make that addon
   override the normal agentic backend by priority alone — operators
   configure preference via `~/.config/hive-mcp/config.edn`
   `[:headless :default-backend]` (see hive-mcp.config.headless-defaults).

   See also:
   - hive-mcp.agent.ling.terminal-registry        -- Analogous pattern for terminals
   - hive-mcp.addons.headless                      -- IHeadlessBackend protocol
   - hive-mcp.agent.ling.headless-addon-strategy   -- Bridge adapter"
  (:require [hive-mcp.addons.headless :as headless]
            [hive-mcp.agent.ling.headless-addon-strategy :as headless-strat]
            [taoensso.timbre :as log]
            [hive-mcp.config.headless-defaults :as headless-defaults]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry State
;; =============================================================================

;; Maps keyword headless-id -> IHeadlessBackend instance.
(defonce ^:private registry (atom {}))

;; Maps keyword headless-id -> metadata map {:provides #{providers} :priority N}.
;; Populated by addons via register-headless! 3-arg form. Drives provider-based
;; preference in best-headless-for-provider without hive-mcp ever case-matching
;; on concrete backend keywords.
(defonce ^:private metadata (atom {}))

(def ^:const default-priority
  "Priority assigned to backends registered without explicit :priority.
   Lower than any deliberate priority, so unannotated backends rank last."
  0)

;; =============================================================================
;; Public API
;; =============================================================================

(defn register-headless!
  "Register a headless backend under a keyword identifier.
   Validates that backend satisfies IHeadlessBackend protocol.
   Idempotent: re-registration replaces the previous backend (last-write-wins).

   Optional 3-arg form accepts metadata:
     :provides — set of provider keywords this backend serves (e.g. #{:claude})
     :priority — integer; higher wins in best-headless-for-provider tie-breaks

   Returns {:registered? true  :headless-id id} on success,
           {:registered? false :headless-id id :errors [...]} on failure."
  ([headless-id backend]
   (register-headless! headless-id backend nil))
  ([headless-id backend opts]
   {:pre [(keyword? headless-id)]}
   (if-not (satisfies? headless/IHeadlessBackend backend)
     (do (log/warn "Cannot register headless: does not satisfy IHeadlessBackend"
                   {:headless-id headless-id})
         {:registered? false
          :headless-id headless-id
          :errors ["Object does not satisfy IHeadlessBackend protocol"]})
     (let [{:keys [provides priority]} opts
           meta-entry (cond-> {:priority (or priority default-priority)}
                        (seq provides) (assoc :provides (set provides)))]
       (swap! registry assoc headless-id backend)
       (swap! metadata assoc headless-id meta-entry)
       (log/info "Headless backend registered" {:headless-id headless-id
                                                :capabilities (headless/capabilities backend)
                                                :provides    (:provides meta-entry)
                                                :priority    (:priority meta-entry)})
       {:registered? true
        :headless-id headless-id}))))

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

(defn headless-metadata
  "Get the registered metadata map for a headless-id, or nil.
   Shape: {:provides #{providers} :priority N}."
  [headless-id]
  (get @metadata headless-id))

(defn deregister-headless!
  "Remove a headless backend from the registry (for addon shutdown).
   Safe to call with unregistered headless-id (no-op).
   Returns {:deregistered? bool :headless-id id}."
  [headless-id]
  (let [had-entry? (contains? @registry headless-id)]
    (swap! registry dissoc headless-id)
    (swap! metadata dissoc headless-id)
    (when had-entry?
      (log/info "Headless backend deregistered" {:headless-id headless-id}))
    {:deregistered? had-entry?
     :headless-id headless-id}))

(defn best-headless-for-provider
  "Return the best headless-id for a provider keyword (:claude, :openai, etc.),
   or for any provider when called with nil.

   Pure metadata-driven selection — hive-mcp source contains no literal
   reference to any concrete backend keyword. Selection rules:

   - With non-nil provider-kw: candidates are filtered by `:provides` set
     declared at registration, then ranked by `:priority` (higher first).
   - With nil provider-kw: ALL registered backends are candidates, ranked
     by `:priority` only. Use this when the caller is provider-agnostic
     (e.g. an LLM-router-fronted backend that handles any provider).

   Ties broken by registration insertion order.

   Returns nil if no candidate exists. With non-nil provider-kw, falls
   back to namespace-match (provider-kw == (namespace headless-id))
   when no metadata-driven candidate exists, preserving the prior
   behavior for ad-hoc provider-namespaced ids."
  [provider-kw]
  (let [meta-snap @metadata
        candidates (->> meta-snap
                        (filter (fn [[_id m]]
                                  (or (nil? provider-kw)
                                      (and (:provides m)
                                           (contains? (:provides m) provider-kw)))))
                        (sort-by (fn [[_id m]] (- (or (:priority m) 0)))))]
    (if-let [picked (ffirst candidates)]
      picked
      ;; No metadata candidate — namespace-match fallback only when a
      ;; provider was actually requested.
      (when provider-kw
        (first (filter #(= (some-> provider-kw name)
                           (namespace %))
                       (registered-headless)))))))

(defn resolve-default-backend
  "Resolve the configured default headless-backend keyword.

   Order of precedence:
     1. HeadlessDefaultsConfig :default-backend if a concrete keyword is
        configured AND that backend is currently registered in this
        process. The keyword is opaque to hive-mcp — it is operator data
        from `~/.config/hive-mcp/config.edn` (or the
        HIVE_HEADLESS_DEFAULT_BACKEND env var).
     2. Fallback to `best-headless-for-provider` when the configured
        value is the :auto sentinel, an unregistered keyword, or
        unresolvable.

   `provider-kw` may be nil when the caller is provider-agnostic (the
   resolved backend's LLM router handles all providers). With nil,
   best-headless-for-provider selects by priority alone.

   This keeps hive-mcp DIP-clean: no concrete backend identifier is
   referenced in source. Addons contribute their backends via classpath
   META-INF discovery and `register-headless!`; the operator picks
   among them in config.edn."
  [provider-kw]
  (let [configured (try (headless-defaults/default-backend)
                        (catch Throwable _ nil))]
    (cond
      ;; :auto sentinel — registry-driven preference.
      (or (nil? configured) (headless-defaults/auto? configured))
      (best-headless-for-provider provider-kw)

      ;; Concrete keyword AND registered — operator's explicit choice.
      (contains? (registered-headless) configured)
      configured

      ;; Configured but not registered (addon not loaded?) — fall back.
      :else
      (do (log/warn "Configured headless backend not registered — falling back"
                    {:configured configured
                     :registered (registered-headless)
                     :provider   provider-kw})
          (best-headless-for-provider provider-kw)))))

(defn clear-registry!
  "Reset the headless registry. Intended for testing only."
  []
  (reset! registry {})
  (reset! metadata {})
  nil)
