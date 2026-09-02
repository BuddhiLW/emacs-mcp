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
            [taoensso.timbre :as log]
            [hive-mcp.extensions.reactive :as reactive]))

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

(defn- boot-timing?
  "Per-addon boot timing is on by default; disable with HIVE_BOOT_TIMING=0.
   Mirrors hive-mcp.server.core/boot-timing? (kept local to avoid a require
   cycle through the init path)."
  []
  (not= "0" (System/getenv "HIVE_BOOT_TIMING")))

(def ^:private host-protocol-namespaces
  "Host protocols an addon may `reify`, loaded before any constructor namespace.

   An addon names `hive-mcp.addons.protocol/IAddon` by qualified symbol and
   cannot `:require` it — the host is not a dependency of its own addons.
   `reify` resolves that symbol while the constructor namespace COMPILES, long
   before any runtime guard in the constructor runs, so the protocol must
   already be in the image or the addon dies with `Syntax error compiling
   reify*` and is silently skipped."
  '[hive-mcp.addons.protocol
    hive-mcp.addons.terminal
    hive-mcp.protocols.vessel])

(defn- preload-host-protocols!
  "Load every namespace in `host-protocol-namespaces`. Returns the ones that
   failed, which is never expected to be non-empty."
  []
  (into []
        (remove (fn [ns-sym] (rescue false (do (require ns-sym) true))))
        host-protocol-namespaces))

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

   On exception inside the init fn, logs at :error level with stacktrace
   + ns + strategy name, then returns nil. Previously these failures were
   silently swallowed by `rescue nil`, masking macroexpansion errors and
   broken `:require` lines so whole addon tool surfaces would just
   disappear from the registry without trace (kanban 20260428113129).

   Returns init result map on success, nil on failure."
  [ns-sym]
  (let [t0         (System/nanoTime)
        addon-sym  (symbol (str ns-sym) "init-as-addon!")
        legacy-sym (symbol (str ns-sym) "init!")
        try-call   (fn [strategy-name init-sym]
                     (try
                       (when-let [init-fn (try-resolve init-sym)]
                         (let [result (init-fn)]
                           (log/info "Extension" ns-sym
                                     "initialized via" strategy-name ":"
                                     (:total result 0) "capabilities")
                           result))
                       (catch Throwable t
                         (log/error t
                                    "Extension init FAILED for" ns-sym
                                    "via" strategy-name
                                    "— addon will be SKIPPED. Cause:"
                                    (.getMessage t))
                         nil)))
        result     (or (try-call "IAddon (multiplexer)" addon-sym)
                       (try-call "init! (legacy)" legacy-sym))]
    ;; Per-addon boot timing — captures first-time require/compile of the
    ;; addon's transitive graph (via requiring-resolve) plus its init cost.
    (when (boot-timing?)
      (log/info (format "[boot-timing] addon %-48s %9.1f ms"
                        (str ns-sym) (/ (- (System/nanoTime) t0) 1e6))))
    result))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- discover-addon-manifests
  "Scan classpath for addon manifests, validate, and topo-sort.
   Returns {:ordered [manifest...] :errors [...] :init-ns-set #{sym...}}."
  []
  (let [scan-t0 (System/nanoTime)
        {:keys [manifests errors]} (manifest/scan-classpath-manifests)
        _ (when (boot-timing?)
            (log/info (format "[boot-timing] classpath manifest scan %9.1f ms (%d manifests)"
                              (/ (- (System/nanoTime) scan-t0) 1e6) (count manifests))))]
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

(defn- roster-status
  "Resolve a manifest entry's load status. Precedence:
     - addon-core registry record present → its :state (:active ✓ / :error ✗)
       (IAddon-protocol addons: hive.knowledge, hive.milvus, …)
     - else loaded via self-registration (init returned non-nil) → ✓
       (libraries/legacy init! that register caps without an addon-core record:
        hive.agent, hive.ttracking)
     - else → ✗ (genuinely failed to load).
   `state-by-id` maps :addon/id → addon-core :state."
  [m successful-ns state-by-id]
  (let [rstate (get state-by-id (:addon/id m))
        ns-sym (symbol (str (:addon/init-ns m)))]
    (cond
      (= :active rstate)               "✓"
      (= :error rstate)                "✗ ERR"
      (some? rstate)                   (str "? " (name rstate))
      (contains? successful-ns ns-sym) "✓"
      :else                            "✗")))

(defn- log-addon-roster!
  "Log discovered addons with their META-INF/hive-addons/*.edn metadata —
   kind (addon|lib), id, version, capabilities, description — and accurate load
   status (see roster-status). :addon/kind is author-declared; '?' when unset.
   Gated on boot-timing? (HIVE_BOOT_TIMING=0 to mute)."
  [ordered successful-ns]
  (when (and (boot-timing?) (seq ordered))
    (let [state-by-id (into {} (map (juxt :name :state))
                           (rescue [] (addon-core/list-addons)))]
      (log/info (format "[addons] roster — %d discovered on classpath:" (count ordered)))
      (doseq [m ordered]
        (let [kind (case (:addon/kind m)
                     :addon   "addon"
                     :library "lib"
                     "?")]
          (log/info (format "[addons]   %-6s [%-5s] %-22s v%-7s caps=%-46s ns=%-26s — %s"
                            (roster-status m successful-ns state-by-id)
                            kind
                            (str (:addon/id m))
                            (str (:addon/version m "—"))
                            (pr-str (or (:addon/capabilities m) #{}))
                            (str (:addon/init-ns m))
                            (str (:addon/description m "")))))))))

(defn mount-event-logger
  "Write one structured hive-addon mount lifecycle event through Timbre."
  [event]
  (let [level (:level event)
        payload (dissoc event :level)]
    (case level
      :debug (log/debug "Addon mount lifecycle" payload)
      :warn  (log/warn "Addon mount lifecycle" payload)
      :error (log/error "Addon mount lifecycle" payload)
      (log/info "Addon mount lifecycle" payload))))

(defn load-extensions-via-mount!
  "Mount manifest-discovered addons through hive-addon.mount.compose (MQ-ADOPT).

   Gate: :addons service config {:mount-compose? true}, overridable by env
   HIVE_MCP_MOUNT_COMPOSE=1|true. Returns nil when disabled or when
   hive-addon.mount.compose / the mount-host adapter are not resolvable —
   the caller then falls back to the legacy self-registration path.

   Delegates discovery + plug-select + topo-order + register!/init! to the
   composer, driven through the IMountHost adapter over addons.core.
   Per-addon base config resolves via manifest/prepare-config (config.edn
   precedence + env-template stripping preserved); plug :config overrides
   merge on top inside the composer. :layer-paths come from the :addons
   service config (missing files are skipped by the composer).

   Returns the composer's :ok map ({:plan .. :report MountReport ..}) on
   success; nil on any failure (logged)."
  []
  (let [svc-cfg  (rescue {} ((requiring-resolve 'hive-mcp.config.core/get-service-config) :addons))
        env-flag (System/getenv "HIVE_MCP_MOUNT_COMPOSE")
        enabled? (if (some? env-flag)
                   (contains? #{"1" "true"} env-flag)
                   (boolean (:mount-compose? svc-cfg)))]
    (when enabled?
      (if-let [compose (try-resolve 'hive-addon.mount.compose/compose-classpath!)]
        (if-let [host-ctor (try-resolve 'hive-mcp.extensions.mount-host/addon-registry-host)]
          (let [opts   (cond-> {:resolve-config manifest/prepare-config
                                :on-event mount-event-logger}
                         (seq (:layer-paths svc-cfg))
                         (assoc :layer-paths (mapv str (:layer-paths svc-cfg))))
                result (rescue nil (compose (host-ctor) opts))]
            (if-let [ok (:ok result)]
              (let [report (:report ok)
                    failed (remove :success? (:mounted report))]
                (log/info "Mount-compose loaded addons"
                          {:order   (:order report)
                           :ok?     (:ok? report)
                           :skipped (:skipped report)
                           :dropped (:dropped ok)})
                (doseq [f failed]
                  (log/warn "Mount-compose: addon failed to mount"
                            {:addon/id (:addon/id f)
                             :phase    (:phase f)
                             :errors   (:errors f)}))
                ok)
              (do (log/warn "Mount-compose failed — falling back to legacy loader"
                            {:error result})
                  nil)))
          (do (log/warn "Mount-compose enabled but mount-host adapter unavailable")
              nil))
        (do (log/debug "Mount-compose enabled but hive-addon.mount.compose not on classpath")
            nil)))))

(defn load-extensions!
  "Resolve and register all available extensions.
   Called once at startup. Thread-safe, idempotent.

   Strategy:
   0. Load the host protocols an addon may reify — before anything loads an
      addon constructor namespace
   1. Scan classpath for addon manifests (META-INF/hive-addons/*.edn)
   1.5 Gated MQ-ADOPT delegate: load-extensions-via-mount! — when it runs,
       manifest addons are mounted by hive-addon.mount.compose and steps 3/4
       only cover the hardcoded extension-namespaces
   2. Merge discovered init-ns with hardcoded extension-namespaces (dedup)
   3. Try extension self-registration (init! functions) — preferred path
   4. For manifests whose init-ns failed, try init-from-manifest! (constructor)
   5. Core overrides — hive-mcp-owned handlers that must win over addons
   6. Build the composite tools from what was contributed, then subscribe the
      reactive surface so a contribution made AFTER this point (hot inject,
      hot reload, a live contribute!) is advertised without a restart

   Addons self-register all capabilities via their init! functions.
   No fallback manifest gap-fill — core has zero knowledge of addon internals.

   Returns map of {:registered [keys...] :total count :sources {...}}."
  []
  (let [;; Step 0: host protocols first. See host-protocol-namespaces — an
        ;; addon that reifies one cannot COMPILE until it is in the image, and
        ;; the composer reports the result as an ordinary mount failure.
        missing-protocols (preload-host-protocols!)
        _ (when (seq missing-protocols)
            (log/error "Host protocol namespaces failed to load — every addon that reifies them will be skipped"
                       {:ns missing-protocols}))

        ;; Step 1: Scan classpath for addon manifests
        {:keys [ordered init-ns-set]}
        (discover-addon-manifests)

        ;; Step 1.5 (MQ-ADOPT): gated mount-compose delegate — nil when disabled
        ;; or unavailable, in which case the legacy path below is authoritative.
        mount-result (load-extensions-via-mount!)
        mounted?     (some? mount-result)
        manifest-ns  (into #{} (map (comp symbol :addon/init-ns)) ordered)

        ;; Step 2: Merge with hardcoded list, dedup by init-ns. When the
        ;; composer ran, manifest-covered nses are already mounted — only the
        ;; hardcoded leftovers self-register.
        all-init-ns (if mounted?
                      (vec (remove manifest-ns extension-namespaces))
                      (into (vec (distinct
                                  (concat
                                   (map (comp symbol :addon/init-ns) ordered)
                                   extension-namespaces)))
                            []))
        _ (when (seq init-ns-set)
            (log/debug "Init namespaces (merged)" {:count (count all-init-ns)
                                                   :ns all-init-ns}))

        ;; Step 3: Try self-registration for each init-ns
        step3-t0     (System/nanoTime)
        init-results (into {}
                           (map (fn [ns-sym]
                                  [ns-sym (try-call-initializer ns-sym)]))
                           all-init-ns)
        _            (when (boot-timing?)
                       (log/info (format "[boot-timing] addon self-registration total %9.1f ms (%d ns)"
                                         (/ (- (System/nanoTime) step3-t0) 1e6)
                                         (count all-init-ns))))
        successful-ns (into #{} (keep (fn [[ns-sym result]]
                                        (when result ns-sym)))
                            init-results)
        init-total (reduce + 0 (keep :total (vals init-results)))

        ;; Step 4: For manifests whose init-ns failed, try init-from-manifest!
        ;; (skipped when the composer already mounted the manifests)
        manifest-init-count
        (atom 0)
        _ (when-not mounted?
            (doseq [m ordered
                    :let [ns-sym (symbol (:addon/init-ns m))]
                    :when (not (contains? successful-ns ns-sym))
                    :when (not (addon-core/addon-registered? (:addon/id m)))]
              (when-let [result (manifest/init-from-manifest!
                                 m
                                 addon-core/register-addon!
                                 addon-core/init-addon!)]
                (when (:success? result)
                  (swap! manifest-init-count inc)))))

        total-registered (count (ext/registered-keys))]

    ;; Roster: which addons were discovered, their META-INF metadata + status
    (log-addon-roster! ordered successful-ns)

    (if (pos? total-registered)
      (log/info "Extensions loaded:" total-registered "total capabilities"
                "(init!:" init-total
                ", classpath-manifests:" (count ordered)
                ", mount-compose:" (if mounted? (count (get-in mount-result [:report :order])) 0)
                ", manifest-fallback:" @manifest-init-count ")")
      (log/debug "No extensions found on classpath — all capabilities will use defaults"))

    ;; Step 5: Core overrides — must run AFTER addon self-registration so
    ;; hive-mcp-owned handlers win via contribute-commands! / tool-registry
    ;; merge semantics.
    ;;   - `analysis bridge-status`   kanban 20260423132050-0b5d09a6
    ;;   - `clojure discover` fallback kanban 20260423132055-27af713a
    (rescue nil
            (require 'hive-mcp.tools.analysis-bridge)
            (when-let [install! (resolve 'hive-mcp.tools.analysis-bridge/install!)]
              (install!)))
    (rescue nil
            (require 'hive-mcp.tools.clojure-discover)
            (when-let [install! (resolve 'hive-mcp.tools.clojure-discover/install!)]
              (install!)))

    ;; Step 6: Build composite tools from addon command contributions, then
    ;; subscribe the reactive surface for everything contributed after this.
    (let [composite-tools (composite/build-all-composite-tools
                           reactive/composite-descriptions)]
      (doseq [t composite-tools]
        (ext/register-tool! t)
        ;; Also register in agent registry for drone agentic loop
        (rescue nil
                (when-let [reg-fn (requiring-resolve 'hive-mcp.agent.registry/register!)]
                  (reg-fn [t]))))
      (when (seq composite-tools)
        (log/info "Built composite tools:" (mapv :name composite-tools))))
    (rescue nil (reactive/install!))

    {:registered (vec (ext/registered-keys))
     :total total-registered
     :sources {:initializers init-total
               :classpath-manifests (count ordered)
               :mount-compose (if mounted? (count (get-in mount-result [:report :order])) 0)
               :manifest-fallback @manifest-init-count}}))
