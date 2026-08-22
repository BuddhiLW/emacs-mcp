(ns hive-mcp.tools.consolidated.hot
  "Consolidated `hot` tool — hot-reload of mounted IAddon instances.

   `hive hot reload <addon-id>` rebuilds one addon from its mount manifest and
   cascades to every addon that was handed its instance at mount time. The work
   itself lives in hive-addon.hot; this namespace is only the MCP edge:
   resolve which addons are actually mounted, drive the bridge, render a report.

   hive-addon.hot is resolved SOFTLY. hive-mcp pins hive-addon by version, and
   the bridge lands in a later release than the pin — a hard `:require` would
   make this namespace fail to compile against the pinned jar. Until the pin is
   bumped every command answers with an actionable :unavailable message instead
   of breaking the tool surface.

   Only addons that are CURRENTLY REGISTERED in the host are reloadable. The
   classpath manifests are the superset; the composer's plug layers may have
   deliberately dropped some of them, and remounting a dropped addon from its raw
   manifest would resurrect something the system chose not to run.

   hive-hot is the namespace-level engine. It MUST be initialized with the addon
   source dirs this system actually derived (`:hot/dirs`) and with the protocol
   interlock (`:no-reload`) — see `ensure-hot-init!` for why letting it
   self-initialize is a bug, not a convenience."
  (:require [hive-mcp.addons.core :as addon-core]
            [hive-mcp.addons.manifest :as manifest]
            [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Soft resolution of the bridge
;; =============================================================================

(defn- soft
  "Resolve a var, or nil. Re-resolved per call so a pin bump takes effect without
   restarting this namespace."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- bridge-available? []
  (some? (soft 'hive-addon.hot/reload-addon!)))

(def ^:private unavailable-msg
  (str "hive-addon.hot is not on the classpath. The hot-reload bridge ships in "
       "hive-addon >= 0.3.5; bump io.github.hive-agi/hive-addon in deps.edn "
       "(or point local.deps.edn at ../hive-addon) and restart the server."))

;; =============================================================================
;; What is actually mounted
;; =============================================================================

(defn- mounted-ids
  "Ids of addons currently in the host registry."
  []
  (into #{} (map :name) (addon-core/list-addons)))

(defn effective-specs
  "MountSpecs for the addons that are actually mounted right now.

   Classpath discovery is the superset; intersecting it with the live registry is
   what keeps a reload from resurrecting an addon the composer dropped."
  []
  (if-let [discover (soft 'hive-addon.mount/discover-specs)]
    (let [live (mounted-ids)]
      (into [] (filter #(contains? live (:addon/id %))) (:specs (discover))))
    []))

(defn- host []
  (when-let [ctor (soft 'hive-mcp.extensions.mount-host/addon-registry-host)]
    (ctor)))

;; =============================================================================
;; hive-hot initialization — the dirs matter
;; =============================================================================

(defn ensure-hot-init!
  "Initialize hive-hot with THIS system's addon source dirs, once.

   Without this, the first reload calls `hive-hot.core/reload!` on an
   uninitialized clj-reload, which self-initializes with its default
   `{:dirs [\"src\"]}` — resolved against the SERVER's working directory, not the
   addon repos. clj-reload then watches the wrong tree: it can neither see the
   addon sources that actually changed nor protect the protocol namespaces, and
   every subsequent reload reports success while reloading nothing relevant.

   The dirs come from `hive-addon.hot/plan`, which derives them from where each
   addon's constructor namespace physically resolves — so only `:local/root`
   addons contribute, which is exactly the set whose bytes can change.
   `:no-reload` carries the protocol interlock.

   Returns {:initialized? bool :dirs [...] :already? bool}. Never throws."
  [plan]
  (let [status (soft 'hive-hot.core/status)
        init!  (soft 'hive-hot.core/init!)]
    (cond
      (nil? init!) {:initialized? false :dirs [] :reason :hive-hot-absent}

      (:initialized? (status)) {:initialized? true :already? true
                                :dirs (vec (:hot/dirs plan))}

      :else
      (try
        (init! {:dirs (vec (:hot/dirs plan))
                :no-reload (:hot/no-reload plan)})
        (log/info "hive-hot initialized for addon hot-reload"
                  {:dirs (count (:hot/dirs plan))})
        {:initialized? true :already? false :dirs (vec (:hot/dirs plan))}
        (catch Throwable t
          (log/warn "hive-hot init failed" {:error (ex-message t)})
          {:initialized? false :dirs [] :reason (ex-message t)})))))

(defn- reload-opts
  "Options handed to the bridge.

   :resolve-config MUST sit under :mount-opts \u2014 that is the key the bridge
   threads into boundary/mount!. Passing it at the top level is silently
   ignored: mount! then falls back to port/resolve-config-default, which returns
   the bare manifest :addon/config with NO config.edn merge and NO
   :runtime/ports. The addon still constructs, still initializes, still reports
   :success? true \u2014 and comes back DEGRADED, having lost the host adapters it
   needs to contribute its own MCP commands. Measured: remounting hive.carto
   that way left it :active with runtime-ports :configured [] and dropped the
   `code carto \u2026` subdomain entirely.

   manifest/prepare-config is what the original mount used; anything less hands
   a remounted addon a thinner config than its first mount received."
  []
  {:mount-opts {:resolve-config manifest/prepare-config}})

(defn- prepared
  "Resolve specs + host + plan and make sure hive-hot is initialized correctly.
   Returns {:specs :host :plan :hot-init} or {:error <mcp-error>}."
  []
  (let [specs (effective-specs)
        h     (host)]
    (cond
      (empty? specs)
      {:error (mcp-error "no mounted addons discovered — is the mount-compose loader enabled?")}

      (nil? h)
      {:error (mcp-error "mount-host adapter unavailable")}

      :else
      (let [plan ((soft 'hive-addon.hot/plan) h specs)]
        {:specs specs :host h :plan plan :hot-init (ensure-hot-init! plan)}))))

(defn- override-strategy
  "Apply an explicit :addon/reload-strategy to the targeted spec."
  [specs addon-id strategy]
  (if-not strategy
    specs
    (mapv (fn [s]
            (cond-> s
              (= addon-id (:addon/id s))
              (assoc :addon/reload-strategy (keyword strategy))))
          specs)))

(defn- summarize
  "Trim a RemountReport to what is worth reading in a terminal."
  [report]
  (cond-> (select-keys report [:hot/strategy :hot/seeds :hot/affected
                               :hot/torn-down :hot/cycles :hot/ns-reloaded :ok? :errors
                               :teardown/data-preserved?])
    (seq (:mounted report))
    (assoc :mounted (mapv #(select-keys % [:addon/id :success? :phase :errors])
                          (:mounted report)))))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn handle-reload
  "Reload one addon (or every addon built from one namespace) plus dependents."
  [{:keys [addon namespace strategy]}]
  (cond
    (not (bridge-available?)) (mcp-error unavailable-msg)
    (and (nil? addon) (nil? namespace))
    (mcp-error "addon (or namespace) is required — e.g. {:command \"reload\" :addon \"hive.carto\"}")

    :else
    (let [{:keys [specs host hot-init error]} (prepared)]
      (if error
        error
        (let [specs  (override-strategy specs addon strategy)
              report (if namespace
                       ((soft 'hive-addon.hot/reload-namespace!) host specs namespace (reload-opts))
                       ((soft 'hive-addon.hot/reload-addon!) host specs addon (reload-opts)))]
          (log/info "hot reload" {:addon addon :namespace namespace
                                  :ok? (:ok? report)
                                  :affected (:hot/affected report)})
          (mcp-json (assoc (summarize report) :hive-hot hot-init)))))))

(defn handle-reload-all
  "Reload every mounted addon, in dependency order."
  [_params]
  (if-not (bridge-available?)
    (mcp-error unavailable-msg)
    (let [{:keys [specs host hot-init error]} (prepared)]
      (if error
        error
        (mcp-json (assoc (summarize ((soft 'hive-addon.hot/reload-all!) host specs (reload-opts)))
                         :hive-hot hot-init))))))

(defn handle-list
  "Per-addon hot-reload readiness: strategy, where its source lives, and whether
   it can be reloaded at all. Effect-free apart from hive-hot init."
  [_params]
  (if-not (bridge-available?)
    (mcp-error unavailable-msg)
    (let [{:keys [plan error]} (prepared)]
      (if error
        error
        (mcp-json
         {:reloadable (mapv #(select-keys % [:addon/id :hot/strategy-id
                                             :addon/init-ns :hot/source-kind])
                            (:hot/registered plan))
          :not-reloadable (mapv #(select-keys % [:addon/id :addon/init-ns
                                                 :hot/source-kind])
                                (:hot/skipped plan))
          :watch-dirs (vec (:hot/dirs plan))
          :never-reload (mapv str (:hot/no-reload plan))
          :hive-hot-available? (:hot/available? plan)})))))

(defn handle-watch
  "Register every reloadable addon with hive-hot and START the file watcher, so
   editing an addon's source remounts it automatically.

   This is the standing-subscription form of `reload`: hive-hot's watcher and
   debouncer detect the change, clj-reload brings the new namespaces in, and the
   registered component callback remounts the affected addons."
  [_params]
  (if-not (bridge-available?)
    (mcp-error unavailable-msg)
    (let [{:keys [specs host plan error]} (prepared)]
      (if error
        error
        (let [report ((soft 'hive-addon.hot/hot!) host specs (reload-opts))
              watch! (soft 'hive-hot.core/init-with-watcher!)]
          (if-not watch!
            (mcp-error "hive-hot is not on the classpath — cannot start the watcher.")
            (let [res (try
                        (watch! {:dirs (vec (:hot/dirs plan))
                                 :no-reload (:hot/no-reload plan)})
                        (catch Throwable t {:error (ex-message t)}))]
              (log/info "hot watch started" {:dirs (count (:hot/dirs plan))
                                             :components (count (:hot/registered report))})
              (mcp-json {:watching (if (map? res) res (str res))
                         :dirs (vec (:hot/dirs plan))
                         :registered (mapv :addon/id (:hot/registered report))
                         :skipped (mapv :addon/id (:hot/skipped report))
                         :never-reload (mapv str (:hot/no-reload plan))
                         :ok? (:ok? report)
                         :errors (:errors report)}))))))))

(defn handle-unwatch
  "Stop the hive-hot file watcher and deregister every addon component."
  [_params]
  (if-not (bridge-available?)
    (mcp-error unavailable-msg)
    (let [specs (effective-specs)
          stop! (soft 'hive-hot.core/stop-watcher!)
          un    ((soft 'hive-addon.hot/unhot!) specs)]
      (mcp-json {:watcher (if stop! (str (stop!)) "hive-hot absent")
                 :unregistered (:hot/unregistered un)}))))

(defn handle-status
  "hive-hot availability + watcher state, the installed strategy chain, and the
   protocol interlock."
  [_params]
  (if-not (bridge-available?)
    (mcp-error unavailable-msg)
    (let [s ((soft 'hive-addon.hot/status))
          watcher (soft 'hive-hot.core/watcher-status)]
      (mcp-json (-> s
                    (update :hot/no-reload #(mapv str %))
                    (assoc :mounted-addon-count (count (effective-specs))
                           :watcher (when watcher (watcher))))))))

(defn handle-strategies
  "The installed reload-strategy chain, in selection order."
  [_params]
  (if-not (bridge-available?)
    (mcp-error unavailable-msg)
    (let [chain ((soft 'hive-addon.hot/installed-strategies))
          sid   (soft 'hive-addon.hot.strategy/-strategy-id)]
      (mcp-json {:ids (mapv sid chain)
                 :note (str "Selection order. A spec may name one explicitly via "
                            ":addon/reload-strategy; modules add their own with "
                            "hive-addon.hot/register-strategy!.")}))))

;; =============================================================================
;; Tool definition
;; =============================================================================

(def canonical-handlers
  {:reload     handle-reload
   :reload-all handle-reload-all
   :watch      handle-watch
   :unwatch    handle-unwatch
   :list       handle-list
   :status     handle-status
   :strategies handle-strategies})

(def handlers canonical-handlers)

(def tool-def
  {:name "hot"
   :consolidated true
   :description
   (str "Hot-reload mounted IAddon instances via hive-hot. reload (rebuild one "
        "addon from its mount manifest and cascade to every addon holding its "
        "instance), reload-all (every mounted addon in dependency order), watch/"
        "unwatch (file-watcher: edit an addon's source and it remounts itself), "
        "list (per-addon strategy + source-kind + whether it is reloadable at "
        "all), status, strategies. Only addons wired as :local/root deps have "
        "reloadable source; jar-backed addons report :restart-required. "
        "Use command='help' to list all.")
   :inputSchema
   {:type "object"
    :properties
    {"command" {:type "string"
                :enum ["reload" "reload-all" "watch" "unwatch" "list" "status" "strategies" "help"]
                :description "Hot-reload operation to perform"}
     "addon" {:type "string"
              :description "[reload] Addon id to reload, e.g. \"hive.carto\". Dependents cascade automatically."}
     "namespace" {:type "string"
                  :description "[reload] Constructor namespace to reload instead of an addon id — seeds every addon built from it."}
     "strategy" {:type "string"
                 :description "[reload] Override the reload strategy for this addon (e.g. \"remount\", \"in-place\", \"inert\"). Omit to let the chain select."}}
    :required ["command"]}
   :handler (composite/build-merged-handler "hot" canonical-handlers)})

(def tools [tool-def])
