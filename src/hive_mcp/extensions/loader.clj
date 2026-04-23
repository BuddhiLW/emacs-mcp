(ns hive-mcp.extensions.loader
  "Extension loader — resolves and registers optional capabilities at startup.

   This is the single point where external namespace symbols are resolved.
   All other code uses the opaque registry keys from extensions.registry.

   Called once at system startup (init.clj).
   Graceful degradation: if resolution fails, no extensions are registered
   and all consumers fall back to their defaults."
  (:require [hive-mcp.addons.core :as addon-core]
            [hive-mcp.addons.manifest :as manifest]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.tools.composite :as composite]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Resolution Helpers
;; =============================================================================

(defn- try-resolve
  "Attempt to resolve a fully-qualified symbol.
   Returns the var if available, nil otherwise."
  [sym]
  (rescue nil
          (requiring-resolve sym)))

;; =============================================================================
;; Extension Manifests (removed — addons self-register via init!)
;; =============================================================================
;;
;; Extension groups previously lived here as fallback manifests mapping
;; [fn-name registry-key] for gap-fill resolution. These have been removed
;; to eliminate IP coupling between core and addon namespaces.
;;
;; Addons now self-register all capabilities via their init-as-addon! or
;; init! entry points, discovered through META-INF/hive-addons/*.edn
;; classpath manifests. See ADR-0007 and Addon-Classpath-Discovery wiki.

;; =============================================================================
;; Extension Self-Registration
;; =============================================================================
;;
;; Extension projects provide their own init! functions that self-register
;; into the registry via META-INF/hive-addons/*.edn classpath manifests.
;; This list is a backward-compat fallback for addons not yet using manifests.

(def ^:private extension-namespaces
  "Extension namespace symbols for self-registration (fallback).
   For each namespace, tries init-as-addon! (multiplexer protocol) first,
   then falls back to init! (legacy self-registration).
   Each init fn must be zero-arg and return {:registered [...] :total N}.
   Prefer classpath manifests (META-INF/hive-addons/) over this list."
  [])

(defn- try-call-initializer
  "Attempt to initialize an extension namespace.
   Strategy: try init-as-addon! first (new multiplexer protocol),
   then fall back to init! (legacy self-registration).
   Returns result map or nil."
  [ns-sym]
  (let [addon-sym (symbol (str ns-sym) "init-as-addon!")
        legacy-sym (symbol (str ns-sym) "init!")]
    (or
     ;; Strategy 1: New multiplexer protocol (IAddon from addons.protocol)
     (rescue nil
             (when-let [addon-fn (try-resolve addon-sym)]
               (let [result (addon-fn)]
                 (log/info "Extension" ns-sym "initialized via IAddon (multiplexer):"
                           (:total result 0) "capabilities")
                 result)))
     ;; Strategy 2: Legacy self-registration
     (rescue nil
             (when-let [init-fn (try-resolve legacy-sym)]
               (let [result (init-fn)]
                 (log/info "Extension initializer" legacy-sym "registered"
                           (:total result 0) "capabilities")
                 result))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- discover-addon-manifests
  "Scan classpath for addon manifests, validate, and topo-sort.
   Returns {:ordered [manifest...] :errors [...] :init-ns-set #{sym...}}."
  []
  (let [{:keys [manifests errors]} (manifest/scan-classpath-manifests)]
    (when (seq errors)
      (log/warn "Addon manifest scan errors" {:count (count errors) :errors errors}))
    (if (seq manifests)
      (let [{:keys [ordered cycles]} (manifest/manifests-load-order manifests)
            init-ns-set (into #{} (map (comp symbol :addon/init-ns)) ordered)]
        (when (seq cycles)
          (log/warn "Cyclic addon dependencies detected" {:cycles cycles}))
        (log/info "Discovered" (count ordered) "addon manifest(s) on classpath"
                  {:ids (mapv :addon/id ordered)})
        {:ordered ordered :errors errors :init-ns-set init-ns-set})
      {:ordered [] :errors errors :init-ns-set #{}})))

(defn load-extensions!
  "Resolve and register all available extensions.
   Called once at startup. Thread-safe, idempotent.

   Strategy:
   1. Scan classpath for addon manifests (META-INF/hive-addons/*.edn)
   2. Merge discovered init-ns with hardcoded extension-namespaces (dedup)
   3. Try extension self-registration (init! functions) — preferred path
   4. For manifests whose init-ns failed, try init-from-manifest! (constructor)
   5. Core overrides — hive-mcp-owned handlers that must win over addons

   Addons self-register all capabilities via their init! functions.
   No fallback manifest gap-fill — core has zero knowledge of addon internals.

   Returns map of {:registered [keys...] :total count :sources {...}}."
  []
  (let [;; Step 1: Scan classpath for addon manifests
        {:keys [ordered init-ns-set]}
        (discover-addon-manifests)

        ;; Step 2: Merge with hardcoded list, dedup by init-ns
        all-init-ns (into (vec (distinct
                                (concat
                                 (map (comp symbol :addon/init-ns) ordered)
                                 extension-namespaces)))
                          [])
        _ (when (seq init-ns-set)
            (log/debug "Init namespaces (merged)" {:count (count all-init-ns)
                                                   :ns all-init-ns}))

        ;; Step 3: Try self-registration for each init-ns
        init-results (into {}
                           (map (fn [ns-sym]
                                  [ns-sym (try-call-initializer ns-sym)]))
                           all-init-ns)
        successful-ns (into #{} (keep (fn [[ns-sym result]]
                                        (when result ns-sym)))
                            init-results)
        init-total (reduce + 0 (keep :total (vals init-results)))

        ;; Step 4: For manifests whose init-ns failed, try init-from-manifest!
        manifest-init-count
        (atom 0)
        _ (doseq [m ordered
                  :let [ns-sym (symbol (:addon/init-ns m))]
                  :when (not (contains? successful-ns ns-sym))
                  :when (not (addon-core/addon-registered? (:addon/id m)))]
            (when-let [result (manifest/init-from-manifest!
                               m
                               addon-core/register-addon!
                               addon-core/init-addon!)]
              (when (:success? result)
                (swap! manifest-init-count inc))))

        total-registered (count (ext/registered-keys))]

    (if (pos? total-registered)
      (log/info "Extensions loaded:" total-registered "total capabilities"
                "(init!:" init-total
                ", classpath-manifests:" (count ordered)
                ", manifest-fallback:" @manifest-init-count ")")
      (log/debug "No extensions found on classpath — all capabilities will use defaults"))

    ;; Step 5: Core overrides — must run AFTER addon self-registration so
    ;; hive-mcp-owned handlers win via contribute-commands! merge semantics.
    ;; Currently: enriched `analysis bridge-status` (kanban 20260423132050-0b5d09a6).
    (rescue nil
            (require 'hive-mcp.tools.analysis-bridge)
            (when-let [install! (resolve 'hive-mcp.tools.analysis-bridge/install!)]
              (install!)))

    ;; Build composite tools from addon command contributions
    (let [composite-tools (composite/build-all-composite-tools
                           {"analysis" "Code analysis"})]
      (doseq [t composite-tools]
        (ext/register-tool! t)
        ;; Also register in agent registry for drone agentic loop
        (rescue nil
                (when-let [reg-fn (requiring-resolve 'hive-mcp.agent.registry/register!)]
                  (reg-fn [t]))))
      (when (seq composite-tools)
        (log/info "Built composite tools:" (mapv :name composite-tools))))

    {:registered (vec (ext/registered-keys))
     :total total-registered
     :sources {:initializers init-total
               :classpath-manifests (count ordered)
               :manifest-fallback @manifest-init-count}}))
