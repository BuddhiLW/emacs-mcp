(ns hive-mcp.server.core
  "MCP server entry point.

   Thin orchestrator delegating to sub-modules:
   - lifecycle: hooks, shutdown, configuration
   - transport: nREPL, WebSocket, channel servers
   - init: service initialization (embedding, events, hot-reload)
   - routes: tool dispatch, handler wrappers, server spec"
  (:require [io.modelcontext.clojure-sdk.stdio-server :as io-server]
            [io.modelcontext.clojure-sdk.server :as sdk-server]
            [jsonrpc4clj.server :as jsonrpc-server]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.server.lifecycle :as lifecycle]
            [hive-mcp.server.transport :as transport]
            [hive-mcp.server.init :as init]
            [hive-mcp.server.guards :as guards]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [meta-merge.core :refer [meta-merge]]
            [taoensso.timbre :as log])
  (:gen-class))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Server State (defonce atoms for lifecycle management)
;; =============================================================================

;; Store nREPL server reference for shutdown
(defonce ^:private nrepl-server-atom (atom nil))

;; Store MCP server context for hot-reload capability
(defonce ^:private server-context-atom (atom nil))

;; Global hooks registry for event-driven workflows
(defonce ^:private hooks-registry-atom (atom nil))

;; Track if shutdown hook is registered
(defonce ^:private shutdown-hook-registered? (atom false))

;; Store coordinator-id for graceful shutdown
(defonce ^:private coordinator-id-atom (atom nil))

;; WebSocket channel monitor for auto-healing
(defonce ^:private ws-channel-monitor (atom nil))

;; Configure Timbre to write to stderr instead of stdout
;; This is CRITICAL for MCP servers - stdout is the JSON-RPC channel
(log/merge-config!
 {:appenders
  {:println {:enabled? true
             :async? false
             :fn (fn [data]
                   (let [{:keys [output_]} data]
                     (binding [*out* *err*]
                       (println (force output_)))))}}})

;; =============================================================================
;; Profile-Aware Config Loading (Integrant T11)
;; =============================================================================

(defn resolve-profile
  "Resolve profile keyword from CLI --profile arg, HIVE_PROFILE env, or :desktop.
   Precedence: explicit arg > env > default."
  ([] (resolve-profile nil))
  ([cli-profile]
   (keyword (or cli-profile
                (System/getenv "HIVE_PROFILE")
                "desktop"))))

(defn read-base-config
  "Read and parse the base system.edn config with Integrant readers."
  []
  (if-let [r (io/resource "hive/system.edn")]
    (ig/read-string (slurp r))
    (throw (ex-info "Base system.edn not found on classpath"
                    {:resource "hive/system.edn"
                     :hint "Ensure resources/ is on :paths"}))))

(defn read-profile-config
  "Read profile overlay EDN. Returns {} if profile file not found."
  [profile]
  (let [path (str "hive/profiles/" (name profile) ".edn")]
    (if-let [r (io/resource path)]
      (ig/read-string (slurp r))
      (do (log/warn "Profile" path "not found, using base config only")
          {}))))

(defn load-system-config
  "Load base system.edn merged with profile overlay via meta-merge.
   Profile nil keys are removed (Integrant convention for exclusion)."
  ([] (load-system-config (resolve-profile)))
  ([profile]
   (let [base    (read-base-config)
         overlay (read-profile-config profile)
         merged  (meta-merge base overlay)]
     ;; Remove keys set to nil by profile (Integrant convention for exclusion)
     (->> merged
          (remove (fn [[_ v]] (nil? v)))
          (into {})))))

;; =============================================================================
;; Server Lifecycle - Thin orchestrator
;; =============================================================================

(defn start!
  "Start the MCP server.

   Orchestrates startup by delegating to sub-modules in correct order:
   1. Guards + Hooks (lifecycle)
   2. Events + Coordinator (init)
   3. Network servers (transport)
   4. Services: embedding + memory (init)
   5. Channels + Sync (transport + init)
   6. Hot-reload + Registry sync (init)
   7. MCP stdio server (must be last - blocks)

   Accepts optional profile keyword (:desktop, :k8s-headless, :k8s-minimal).
   When T8 lands, profile drives Integrant system init. Until then, logged."
  [& {:keys [profile] :or {profile nil}}]
  (let [server-id (random-uuid)
        profile   (resolve-profile profile)
        _config   (load-system-config profile)]
    (log/info "Starting hive-mcp server:" server-id "profile:" profile)
    (when-let [sock (System/getenv "EMACS_SOCKET_NAME")]
      (log/info "Targeting Emacs daemon:" sock))

    ;; Phase 1: Guards + Hooks
    (guards/mark-coordinator-running!)
    (lifecycle/init-hooks! hooks-registry-atom shutdown-hook-registered? coordinator-id-atom)

    ;; Phase 2: Events + Coordinator registration
    (init/init-events!)
    (init/register-coordinator! coordinator-id-atom)

    ;; Phase 3: Transport (network servers)
    (transport/start-embedded-nrepl! nrepl-server-atom)
    (transport/start-websocket-server!)
    (init/init-nats!)

    ;; Phase 4: Services (embedding, memory store, tool delegation)
    (init/init-embedding-provider!)
    (init/warmup-embedding!)
    (init/wire-memory-store!)
    (routes/register-tools-for-delegation!)

    ;; Phase 4.4: Forge belt defaults (extensions can override)
    (init/register-forge-belt-defaults!)

    ;; Phase 4.45: Global config (must load BEFORE extensions — addons need API keys/config)
    (try
      (require 'hive-mcp.config.core)
      (require 'hive-mcp.tools.hive-project)
      (let [load-config! (resolve 'hive-mcp.config.core/load-global-config!)
            scan! (resolve 'hive-mcp.tools.hive-project/scan-and-generate-missing!)]
        (load-config!)
        (let [result (scan!)]
          (log/info "Phase 4.45: Global config loaded + auto-gen .hive-project.edn:" result)))
      (catch Exception e
        (log/warn "Phase 4.45: Config/auto-gen scan failed (non-fatal):" (.getMessage e))))

    ;; Phase 4.5: Extension loading (classpath addon discovery)
    ;; Must run AFTER embedding/memory (extensions may use Chroma).
    ;; Must run AFTER config (extensions need API keys from config.edn).
    ;; Must run BEFORE workflow engine (handlers may use extensions).
    ;; hive-claude auto-discovered here via META-INF manifest (registers :claude terminal)
    ;; NOTE: Addons self-register capabilities during initialize! lifecycle.
    ;; E.g. hive-emacs registers EmacsVessel in IVessel registry here.
    (init/load-extensions!)

    ;; Phase 5: Channels + Sync
    (transport/start-ws-channel-with-healing! ws-channel-monitor)
    (transport/start-olympus-ws!)
    (transport/start-a2a-gateway!)
    (transport/start-legacy-channel!)
    (init/init-channel-bridge!)
    (init/start-swarm-sync!)

    ;; Phase 5.7: FSM Workflow Engine (registry + IWorkflowEngine wiring)
    ;; Must run after services (handlers use memory/kanban at runtime).
    (init/init-workflow-engine!)

    ;; Phase 6: Hot-reload + Registry sync
    (init/init-hot-reload-watcher! server-context-atom (lifecycle/read-project-config))
    (init/start-registry-sync!)

    ;; Phase 6.5: Decay Scheduler (periodic memory/edge/disc decay)
    ;; Must run after config loaded (Phase 5.5) and embedding provider (Phase 4).
    ;; Daemon thread -- dies with JVM, no explicit shutdown needed.
    (init/start-decay-scheduler!)

    ;; Phase 6.6: Housekeeping Scheduler (gc-fix-5: periodic GC sweep + cleanup)
    ;; Runs bounded atom GC sweep + stale resource cleanup every 5 minutes.
    ;; Daemon thread -- dies with JVM. Also stoppable via init/stop-housekeeping-scheduler!
    (init/start-housekeeping-scheduler!)

    ;; Phase 7: Start MCP server (must be last - blocks on stdio)
    ;; NOTE: routes/build-server-spec must be called AFTER init-embedding-provider!
    ;; to get accurate Chroma availability for capability-based tool switching
    (let [spec (assoc (routes/build-server-spec) :server-id server-id)
          log-ch (async/chan (async/sliding-buffer 20))
          server (io-server/stdio-server {:log-ch log-ch})
          ;; Create context and store for hot-reload capability
          context (assoc (sdk-server/create-context! spec) :server server)]
      (reset! server-context-atom context)
      (log/info "Server context stored for hot-reload capability")
      ;; Start the JSON-RPC server and block on join promise.
      ;; When stdin EOF occurs (Emacs parent exits), the ChanServer pipeline
      ;; detects channel closure and delivers :done to the join promise.
      ;; We deref to block the main thread, then trigger clean JVM shutdown
      ;; which fires the registered shutdown hook (Olympus stop, coordinator
      ;; marking, session-end/auto-wrap hooks).
      (let [join (jsonrpc-server/start server context)]
        @join
        (log/info "MCP server stdin closed - initiating clean shutdown")
        (System/exit 0)))))

(defn- parse-cli-args
  "Parse CLI args for --profile flag. Returns map with :profile (or nil)."
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [[flag val & rest] args]
        (if (= flag "--profile")
          (recur rest (assoc opts :profile val))
          (recur (next args) opts))))))

(defn -main
  "Entry point for the MCP server.

   Usage:
     java -jar hive-mcp.jar                          ; desktop (default)
     java -jar hive-mcp.jar --profile k8s-headless   ; headless K8s
     HIVE_PROFILE=k8s-minimal java -jar hive-mcp.jar ; env-based

   Profile precedence: --profile flag > HIVE_PROFILE env > desktop"
  [& args]
  (let [{:keys [profile]} (parse-cli-args args)]
    (start! :profile profile)))

(comment
  ;; For REPL development
  (start!)
  (start! :profile :k8s-headless))
