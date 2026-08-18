(ns hive-mcp.addons.di
  "Dependency Injection module for the IAddon addon system.

   Provides:
   1. Service Registry  — thread-safe registry for shared runtime services
   2. Symbol Resolution  — centralized requiring-resolve with rescue
   3. Addon Pipeline     — shared nil-railway eliminating boilerplate from addons
   4. IServiceConsumer   — optional protocol for declarative dep injection

   Problem solved:
   7+ addon init.clj files duplicate identical ~30-line nil-railway pipeline
   (dep-registry, resolve-deps, step-resolve-deps, step-register, step-init,
   step-store-instance, run-addon-pipeline!). All addons scatter try-resolve /
   requiring-resolve at runtime instead of receiving injected services.

   Before (in every addon):
     (defonce dep-registry (atom {:register! 'hive-mcp.addons.core/register-addon! ...}))
     (defn- resolve-deps [registry] ...)
     (defn- step-resolve-deps [ctx] ...)
     (defn- step-register [ctx] ...)
     (defn- step-init [ctx] ...)
     (defn- step-store-instance [ctx] ...)
     (defn- run-addon-pipeline! [initial-ctx] ...)

   After:
     (di/run-addon-pipeline! (make-addon) {:store-atom addon-instance})

   See also:
   - hive-addon.protocol — IAddon protocol definition
   - hive-mcp.addons.core     — Addon registry (register!, init!, shutdown!)
   - hive-mcp.extensions.registry — Opaque extension fn/schema/tool registry"
  (:require [clojure.set :as set]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; A. Service Registry
;; =============================================================================
;;
;; Thread-safe atom-backed registry for shared runtime services.
;; Services are registered by keyword key at system startup and consumed
;; by addons during initialization. Replaces scattered requiring-resolve
;; calls for accessing shared state (KG store, editor, vessel, etc.).

(defonce ^{:doc "Global service registry. {keyword -> service-value}"}
  ^:private service-registry
  (atom {}))

(defn register-service!
  "Register a shared runtime service by keyword key.
   Thread-safe, idempotent. Last-write-wins.

   Standard keys (conventions, not enforced):
     :hive/extensions-registry — extensions.registry accessor fns
     :hive/addon-registry      — addons.core accessor fns
     :hive/kg-store            — Knowledge Graph store accessor
     :hive/editor              — current IEditor instance
     :hive/vessel-registry     — IVessel registry

   Returns the key."
  [key value]
  {:pre [(keyword? key) (some? value)]}
  (swap! service-registry assoc key value)
  (log/debug "Service registered" {:key key})
  key)

(defn register-services!
  "Register multiple services at once from a map of {keyword value}.
   Thread-safe, atomic. Returns the keys."
  [m]
  {:pre [(map? m)]}
  (swap! service-registry merge m)
  (log/debug "Services registered" {:keys (keys m)})
  (vec (keys m)))

(defn get-service
  "Look up a registered service by key.
   Returns the value if registered, or default (nil if not provided)."
  ([key] (get @service-registry key))
  ([key default] (get @service-registry key default)))

(defn require-service
  "Look up a service, throwing ExceptionInfo if not registered.
   Use when the service is mandatory and absence is a programming error."
  [key]
  (if-let [entry (find @service-registry key)]
    (val entry)
    (throw (ex-info (str "Required service not registered: " key)
                    {:key key
                     :available (set (keys @service-registry))}))))

(defn service-registered?
  "Check if a service is registered under the given key."
  [key]
  (contains? @service-registry key))

(defn deregister-service!
  "Remove a service registration. Returns the key."
  [key]
  (swap! service-registry dissoc key)
  (log/debug "Service deregistered" {:key key})
  key)

(defn list-services
  "Return the set of all registered service keys."
  []
  (set (keys @service-registry)))

(defn clear-services!
  "Remove all service registrations. Intended for testing only."
  []
  (reset! service-registry {})
  nil)

;; =============================================================================
;; B. Symbol Resolution (Centralized)
;; =============================================================================
;;
;; Replaces the duplicated try-resolve / resolve-deps functions that every
;; addon copies. One implementation, used by the shared pipeline and
;; available for ad-hoc resolution needs.

(defn resolve-symbol
  "Resolve a fully-qualified symbol via requiring-resolve.
   Returns the var if available, nil otherwise. Never throws."
  [qualified-sym]
  (try
    (requiring-resolve qualified-sym)
    (catch Exception _
      (log/trace "Symbol resolution failed" {:sym qualified-sym})
      nil)))

(defn resolve-symbols
  "Resolve a map of {keyword qualified-symbol} into {keyword resolved-var}.
   Returns the complete context map on success.
   Returns nil if ANY symbol fails to resolve (nil-railway semantics).

   Example:
     (resolve-symbols {:register! 'hive-mcp.addons.core/register-addon!
                       :init!     'hive-mcp.addons.core/init-addon!})
     => {:register! #'hive-mcp.addons.core/register-addon!
         :init!     #'hive-mcp.addons.core/init-addon!}
     ;; or nil if any symbol is not on classpath"
  [sym-map]
  (reduce-kv
   (fn [ctx k sym]
     (if-let [resolved (resolve-symbol sym)]
       (assoc ctx k resolved)
       (do (log/debug "Symbol resolution failed in batch" {:key k :sym sym})
           (reduced nil))))
   {}
   sym-map))

;; =============================================================================
;; C. IServiceConsumer Protocol
;; =============================================================================
;;
;; Optional protocol for addons that want to declaratively state their
;; service dependencies. Backward-compatible — addons that don't implement
;; this are handled gracefully (empty sets assumed).

(defprotocol IServiceConsumer
  "Optional protocol for addons that declare service dependencies.
   Backward-compatible: addons that don't implement this get empty sets.

   Used by the addon pipeline to auto-inject services from the registry
   into the config map passed to IAddon/initialize!."

  (required-services [this]
    "Return a set of service keys that MUST be available.
     Missing required services will cause initialization to fail.")

  (optional-services [this]
    "Return a set of service keys that MAY be used if available.
     Missing optional services are silently skipped."))

(defn safe-required-services
  "Get required services from addon, returning #{} for non-implementing addons."
  [addon]
  (try
    (if (satisfies? IServiceConsumer addon)
      (or (required-services addon) #{})
      #{})
    (catch Exception _ #{})))

(defn safe-optional-services
  "Get optional services from addon, returning #{} for non-implementing addons."
  [addon]
  (try
    (if (satisfies? IServiceConsumer addon)
      (or (optional-services addon) #{})
      #{})
    (catch Exception _ #{})))

(defn check-service-dependencies
  "Check if all required services for an addon are available.
   Returns {:satisfied? bool :missing #{keys} :available #{keys}}."
  [addon]
  (let [required  (safe-required-services addon)
        available (list-services)
        missing   (set/difference required available)]
    {:satisfied? (empty? missing)
     :missing    missing
     :available  (set/intersection required available)}))

;; =============================================================================
;; D. Shared Addon Pipeline
;; =============================================================================
;;
;; The nil-railway pipeline that was duplicated in 7+ addon init.clj files.
;; Each step returns the context map on success, or nil on failure.
;; `some->` chains them: first nil aborts the pipeline.

(def ^:private core-deps
  "Symbol map for the three functions every addon needs for registration.
   Resolved once per pipeline run."
  {:register!   'hive-mcp.addons.core/register-addon!
   :init!       'hive-mcp.addons.core/init-addon!
   :addon-id-fn 'hive-addon.protocol/addon-id})

(defn- step-resolve-core-deps
  "Resolve core addon registration dependencies (register!, init!, addon-id)."
  [ctx]
  (when-let [deps (resolve-symbols core-deps)]
    (merge ctx deps)))

(defn- step-resolve-extra-deps
  "Resolve addon-specific extra dependencies from :extra-deps in ctx."
  [{:keys [extra-deps] :as ctx}]
  (if (seq extra-deps)
    (when-let [deps (resolve-symbols extra-deps)]
      (merge (dissoc ctx :extra-deps) deps))
    ctx))

(defn- step-inject-services
  "Inject requested services from the service registry into :injected-services.
   Combines explicit :service-keys with IServiceConsumer protocol declarations."
  [{:keys [addon service-keys] :as ctx}]
  (let [explicit   (or service-keys #{})
        required   (safe-required-services addon)
        optional   (safe-optional-services addon)
        all-keys   (set/union explicit required optional)
        ;; Check required services first
        missing-required (set/difference required (list-services))]
    (if (seq missing-required)
      (do (log/error "Missing required services for addon"
                     {:missing missing-required})
          nil) ;; nil-railway: abort pipeline
      (if (seq all-keys)
        (let [resolved (reduce
                        (fn [acc k]
                          (if-let [svc (get-service k)]
                            (assoc acc k svc)
                            (if (contains? required k)
                              ;; Already checked above, but defensive
                              (reduced nil)
                              (do (log/debug "Optional service not available" {:key k})
                                  acc))))
                        {}
                        all-keys)]
          (if (nil? resolved)
            nil
            (assoc ctx :injected-services resolved)))
        ctx))))

(defn- step-register
  "Register the addon in the global addon registry.
   Catches exceptions (e.g. AssertionError from preconditions) and
   returns nil to abort the pipeline gracefully."
  [{:keys [addon register!] :as ctx}]
  (try
    (let [result (register! addon)]
      (if (:success? result)
        (assoc ctx :reg-result result)
        (do (log/warn "Addon registration failed" {:result result})
            nil)))
    (catch Throwable t
      (log/warn "Addon registration threw" {:error (.getMessage t)})
      nil)))

(defn- step-init
  "Initialize the registered addon via addons.core/init-addon!.
   Catches exceptions and returns nil to abort the pipeline gracefully."
  [{:keys [addon addon-id-fn init! injected-services] :as ctx}]
  (try
    (let [config (cond-> {}
                   (seq injected-services) (assoc :services injected-services))
          result (init! (addon-id-fn addon) config)]
      (if (:success? result)
        (assoc ctx :init-result result)
        (do (log/warn "Addon init failed" {:result result})
            nil)))
    (catch Throwable t
      (log/warn "Addon init threw" {:error (.getMessage t)})
      nil)))

(defn- step-store-instance
  "Store the addon instance in the provided :store-atom (if any)."
  [{:keys [addon store-atom] :as ctx}]
  (when store-atom
    (reset! store-atom addon))
  ctx)

(defn- step-on-success
  "Call the :on-success callback (if provided)."
  [{:keys [on-success] :as ctx}]
  (when on-success
    (try
      (on-success ctx)
      (catch Exception e
        (log/warn "on-success callback failed" {:error (.getMessage e)}))))
  ctx)

(defn run-addon-pipeline!
  "Shared nil-railway pipeline for addon self-registration.
   Eliminates the ~30 lines of duplicated boilerplate in every addon init.clj.

   Arguments:
     addon — An IAddon-satisfying instance (from make-addon, reify, or defrecord)
     opts  — Optional configuration map:
       :store-atom  — atom to reset! with addon instance on success
       :extra-deps  — {keyword qualified-symbol} additional deps to resolve
       :services    — #{service-key} services to inject into init config
       :on-success  — (fn [ctx]) callback after successful init

   Returns:
     Pipeline context map on success (contains :addon, :reg-result, :init-result).
     nil on any step failure (nil-railway pattern).

   Example (replaces ~30 lines in each addon init.clj):

     ;; Before: 30 lines of dep-registry, resolve-deps, step-*, run-addon-pipeline!
     ;; After:
     (di/run-addon-pipeline!
       (make-addon)
       {:store-atom addon-instance
        :services   #{:hive/kg-store}})"
  ([addon] (run-addon-pipeline! addon {}))
  ([addon opts]
   (let [initial-ctx (merge {:addon addon}
                            (select-keys opts [:store-atom :on-success :extra-deps])
                            (when-let [svcs (:services opts)]
                              {:service-keys svcs}))]
     (some-> initial-ctx
             step-resolve-core-deps
             step-resolve-extra-deps
             step-inject-services
             step-register
             step-init
             step-store-instance
             step-on-success))))

;; =============================================================================
;; E. Convenience: init-as-addon! Helper
;; =============================================================================
;;
;; High-level wrapper combining make-addon + pipeline + result formatting.
;; Covers the most common pattern: create addon, register, init, store, report.

(defn init-as-addon!
  "High-level convenience for the common addon self-registration pattern.
   Combines make-addon + run-addon-pipeline! + result formatting.

   Arguments:
     addon-name — String name for logging (e.g. \"lsp-mcp\")
     make-fn    — Zero-arg fn that returns an IAddon instance (or nil)
     opts       — Options passed to run-addon-pipeline!

   Returns:
     {:registered [addon-id] :total 1} on success
     {:registered [] :total 0} on failure

   Example:
     ;; In lsp-mcp/init.clj — entire init-as-addon! body becomes:
     (di/init-as-addon! \"lsp-mcp\" make-addon {:store-atom addon-instance})"
  ([addon-name make-fn]
   (init-as-addon! addon-name make-fn {}))
  ([addon-name make-fn opts]
   (if-let [result (some-> (make-fn)
                           (run-addon-pipeline! opts))]
     (do
       (log/info (str addon-name " registered as IAddon"))
       {:registered [(get-in result [:reg-result :addon-name])] :total 1})
     (do
       (log/warn (str addon-name " addon registration failed"))
       {:registered [] :total 0}))))
