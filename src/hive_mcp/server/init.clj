(ns hive-mcp.server.init
  "Service initialization: embedding, hot-reload, events, coordinator.

   Bounded context: Service bootstrap and dependency wiring.

   Manages:
   - Embedding provider initialization (Chroma, Ollama, OpenRouter)
   - Hot-reload auto-healing (MCP tool refresh after reload)
   - Event system initialization (re-frame inspired)
   - Coordinator registration in DataScript
   - Memory store wiring (IMemoryStore protocol)
   - Channel bridge + swarm sync + registry sync
   - decay scheduler (periodic memory/edge/disc decay)
   - housekeeping scheduler (periodic GC sweep + stale resource cleanup)"
  (:require [hive-mcp.chroma.core :as chroma]
            [hive-mcp.channel.websocket :as ws-channel]
            [hive-mcp.dns.result :as result]
            [hive-mcp.embeddings.ollama :as ollama]
            [hive-mcp.embeddings.service :as embedding-service]
            [hive-mcp.embeddings.config :as embedding-config]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.events.core :as ev]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.handlers :as ev-handlers]
            [hive-mcp.events.channel-bridge :as channel-bridge]
            [hive-mcp.tools.swarm :as swarm]
            [hive-mcp.memory.store.chroma :as chroma-store]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.swarm.sync :as sync]
            [hive-mcp.swarm.bootstrap.factory :as bootstrap-factory]
            [hive-mcp.swarm.lifecycle.boot-reconcile :as boot-reconcile]
            [hive-mcp.swarm.event-bridge :as swarm-event-bridge]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.channel.instruction-store :as instruction-store]
            [hive-mcp.protocols.event-backbone :as eb]
            [hive-mcp.swarm.logic :as logic]
            [hive-hot.core :as hot]
            [hive-hot.events :as hot-events]
            [taoensso.timbre :as log]
            [clojure.string :as str] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Embedding Warm-Up
;; =============================================================================

(defn warmup-embedding!
  "Fire a background future to warm up the Ollama embedding model.

   First embedding call via ollama/nomic-embed-text takes ~3.5s (cold start
   model loading). Subsequent calls are ~50ms. This pre-loads the model so
   the first real catchup or memory search does not pay the cold start penalty.

   Non-blocking: runs in a future so it does not delay server startup."
  []
  (future
    (try
      (embedding-service/embed-for-collection "hive-mcp-memory" "warmup")
      (log/info "Ollama embedding model warmed up")
      (catch Exception e
        (log/warn "Embedding warmup failed (non-fatal):" (ex-message e))))))

;; =============================================================================
;; Hot-Reload State
;; =============================================================================

;; Track if hot-reload listener is registered (private, module-scoped)
(defonce ^:private hot-reload-listener-registered? (atom false))

;; =============================================================================
;; Embedding Provider Initialization
;; =============================================================================

(defn init-embedding-provider!
  "Initialize embedding providers for semantic memory search.

  Sets up:
  1. Chroma connection (vector database)
  2. EmbeddingService (per-collection routing)
  3. Per-collection embedding configuration:
     - hive-mcp-memory: Ollama (768 dims, fast, local)
     - hive-mcp-presets: OpenRouter (4096 dims, accurate) if API key available
  4. Global fallback provider (Ollama)

  Configuration priority (highest to lowest):
  1. ~/.config/hive-mcp/config.edn :embeddings section
  2. ~/.config/hive-mcp/config.edn :services / :secrets sections
  3. Environment variables (OLLAMA_HOST, OPENROUTER_API_KEY, etc.) as fallback
  4. Built-in defaults"
  []
  (result/rescue false
    ;; Load global config to get :embeddings section
                 (let [cfg (global-config/get-global-config)
                       embed-cfg (get cfg :embeddings {})
                       ollama-cfg (get embed-cfg :ollama {})
                       openrouter-cfg (get embed-cfg :openrouter {})]

      ;; Configure Chroma connection - config.edn :services > env vars > defaults
                   (let [chroma-host (global-config/get-service-value :chroma :host :env "CHROMA_HOST" :default "localhost")
                         chroma-port (global-config/get-service-value :chroma :port :env "CHROMA_PORT" :parse parse-long :default 8000)]
                     (chroma/configure! {:host chroma-host :port chroma-port})
                     (log/info "Chroma configured:" chroma-host ":" chroma-port))

      ;; Initialize EmbeddingService for per-collection routing
                   (embedding-service/init!)

      ;; Read Ollama host/model from :embeddings > :services > env vars > defaults
                   (let [ollama-host (or (:host ollama-cfg)
                                         (global-config/get-service-value :ollama :host
                                                                          :env "OLLAMA_HOST"
                                                                          :default "http://localhost:11434"))
                         ollama-model (or (:model ollama-cfg) "qwen3-embedding:4b")
                         openrouter-model (or (:model openrouter-cfg) "qwen/qwen3-embedding-8b")]

        ;; Configure per-collection embedding providers
        ;; Memory collection: Ollama (fast, local, 768 dims)
                     (result/rescue nil
                                    (embedding-service/configure-collection!
                                     "hive-mcp-memory"
                                     (embedding-config/ollama-config {:host ollama-host :model ollama-model})))

        ;; Presets collection: OpenRouter (accurate, 4096 dims) if API key available
                     (when (global-config/get-secret :openrouter-api-key)
                       (let [configured? (result/rescue false
                                                        (embedding-service/configure-collection!
                                                         "hive-mcp-presets"
                                                         (embedding-config/openrouter-config {:model openrouter-model}))
                                                        true)]
                         (if configured?
                           (log/info "Presets collection configured with OpenRouter (4096 dims)")
              ;; Fallback: use Ollama for presets too
                           (result/rescue nil
                                          (embedding-service/configure-collection!
                                           "hive-mcp-presets"
                                           (embedding-config/ollama-config {:host ollama-host :model ollama-model}))))))

        ;; Plans collection: OpenRouter (4096 dims) for large plan entries (1000-5000+ chars)
        ;; Plans exceed Ollama's ~1500 char embedding limit, so OpenRouter is preferred
                     (if (global-config/get-secret :openrouter-api-key)
                       (let [configured? (result/rescue false
                                                        (embedding-service/configure-collection!
                                                         "hive-mcp-plans"
                                                         (embedding-config/openrouter-config {:model openrouter-model}))
                                                        true)]
                         (if configured?
                           (log/info "Plans collection configured with OpenRouter (4096 dims)")
                           (do
                ;; Fallback: use Ollama (truncation risk for large plans, but works)
                             (result/rescue nil
                                            (embedding-service/configure-collection!
                                             "hive-mcp-plans"
                                             (embedding-config/ollama-config {:host ollama-host :model ollama-model})))
                             (log/warn "Plans collection using Ollama - entries >1500 chars may be truncated"))))
          ;; No OpenRouter key - use Ollama with warning
                       (do
                         (result/rescue nil
                                        (embedding-service/configure-collection!
                                         "hive-mcp-plans"
                                         (embedding-config/ollama-config {:host ollama-host :model ollama-model})))
                         (log/warn "Plans collection using Ollama (no OPENROUTER_API_KEY) - entries >1500 chars may be truncated")))

        ;; Ingest collection: OpenRouter (same model used by hive-ingestor OpenRouterEmbedder)
                     (when (global-config/get-secret :openrouter-api-key)
                       (result/rescue nil
                                      (embedding-service/configure-collection!
                                       "hive-ingest"
                                       (embedding-config/openrouter-config {:model openrouter-model})))
                       (log/info "Ingest collection configured with OpenRouter"))

        ;; Set global fallback provider (Ollama) for backward compatibility
                     (let [provider (ollama/->provider {:host ollama-host})]
                       (chroma/set-embedding-provider! provider)
                       (log/info "Global fallback embedding provider: Ollama at" ollama-host))

                     (log/info "Embedding config from config.edn:" {:ollama-host ollama-host
                                                                    :ollama-model ollama-model
                                                                    :openrouter-model openrouter-model})
                     (log/info "EmbeddingService status:" (embedding-service/status))
                     true))))

;; =============================================================================
;; Hot-Reload Auto-Healing
;; =============================================================================

(defn- emit-mcp-health-event!
  "Emit health event via WebSocket channel after hot-reload.
   Lings can listen for this to confirm MCP is operational."
  [loaded-ns unloaded-ns ms]
  (result/rescue nil
                 (ws-channel/emit! :mcp-health-restored
                                   {:loaded (count loaded-ns)
                                    :unloaded (count unloaded-ns)
                                    :reload-ms ms
                                    :timestamp (System/currentTimeMillis)
                                    :status "healthy"})
                 (log/info "Emitted :mcp-health-restored event after hot-reload")))

(defn- handle-hot-reload-success!
  "Handler for successful hot-reload - refreshes tools and emits health event.


   Parameters:
     server-context-atom - atom containing MCP server context"
  [server-context-atom {:keys [loaded unloaded ms]}]
  (log/info "Hot-reload completed:" (count loaded) "loaded," (count unloaded) "unloaded in" ms "ms")
  ;; Refresh MCP tool handlers to point to new var values
  (result/rescue nil
                 (when @server-context-atom
                   (routes/refresh-tools! server-context-atom)
                   (log/info "MCP tools refreshed after hot-reload")))
  ;; Emit health event for lings
  (emit-mcp-health-event! loaded unloaded ms))

(defn- register-hot-reload-listener!
  "Register listener with hive-hot to auto-heal MCP after reload.

   Only registers once. Safe to call multiple times.

   Parameters:
     server-context-atom - atom containing MCP server context"
  [server-context-atom]
  (when-not @hot-reload-listener-registered?
    (result/rescue nil
                   (require 'hive-hot.core)
                   (let [add-listener! (resolve 'hive-hot.core/add-listener!)]
                     (add-listener! :mcp-auto-heal
                                    (fn [event]
                                      (when (= (:type event) :reload-success)
                                        (handle-hot-reload-success! server-context-atom event))))
                     (reset! hot-reload-listener-registered? true)
                     (log/info "Registered hot-reload listener for MCP auto-healing")))))

;; =============================================================================
;; Event System Initialization
;; =============================================================================

(defn init-events!
  "Initialize hive-events system (re-frame inspired event dispatch).
   EVENTS-01: Event system must init after hooks but before channel."
  []
  (result/rescue nil
                 (ev/init!)
                 (effects/register-effects!)
                 (ev-handlers/register-handlers!)
                 (log/info "hive-events system initialized")))

;; =============================================================================
;; Coordinator Registration
;; =============================================================================

(defn register-coordinator!
  "Register coordinator in DataScript + hivemind (Phase 4).


   Parameters:
     coordinator-id-atom - atom to store coordinator project-id"
  [coordinator-id-atom]
  (result/rescue nil
                 (require 'hive-mcp.swarm.datascript)
                 (require 'hive-mcp.swarm.datascript.lings)
                 (let [register! (resolve 'hive-mcp.swarm.datascript/register-coordinator!)
                       add-slave! (resolve 'hive-mcp.swarm.datascript.lings/add-slave!)
                       project-id (global-config/get-service-value :project :id :env "HIVE_MCP_PROJECT_ID" :default "hive-mcp")
                       cwd (System/getProperty "user.dir")]
                   (register! project-id {:project project-id})
      ;; Also register "coordinator" as a slave (depth 0) for bb-mcp compatibility
      ;; bb-mcp injects agent_id: "coordinator" on all tool calls for piggyback tracking
                   (add-slave! "coordinator" {:name "coordinator"
                                              :status :idle
                                              :depth 0  ;; depth 0 = coordinator (not a ling)
                                              :project-id project-id
                                              :cwd cwd})
                   (reset! coordinator-id-atom project-id)
                   (log/info "Coordinator registered:" project-id "(also as slave for bb-mcp compat)"))))

;; =============================================================================
;; Memory Store Wiring
;; =============================================================================

(defn- resolve-memory-backend
  "Resolve the configured memory backend id.

   Reads, in order:
     1. :services :memory-store :backend   (authoritative — user's config.edn)
     2. :memory :default-store             (legacy key still used by routes)
     3. \"chroma\"                         (default for green-field install)

   Ensures the global config atom is populated — wire-memory-store! is
   invoked in Phase 4, before Phase 5.5's explicit load-global-config!.
   Returns a lowercase string (e.g. \"milvus\", \"chroma\")."
  []
  (result/rescue nil (global-config/load-global-config!))
  (let [cfg (global-config/get-global-config)
        b   (or (get-in cfg [:services :memory-store :backend])
                (get-in cfg [:memory :default-store])
                "chroma")]
    (-> b name clojure.string/lower-case)))

(defn wire-memory-store!
  "Wire the active IMemoryStore backend based on global config.

   Dispatch: :services :memory-store :backend (fallback :memory :default-store).
     - \"milvus\": defer to hive-milvus addon. Its initialize! calls set-store!
       during Phase 4.5 (load-extensions!).
     - anything else: wire ChromaMemoryStore immediately (legacy behavior).

   Must run AFTER init-embedding-provider! since Chroma config is set there.
   A post-extensions fallback in `ensure-memory-store!` guarantees a live
   store even when the selected addon fails to register."
  []
  (result/rescue nil
                 (let [backend (resolve-memory-backend)]
                   (case backend
                     "milvus"
                     (log/info "wire-memory-store!: deferring to hive-milvus addon"
                               {:backend backend})

                     (let [store (chroma-store/create-store)]
                       (mem-proto/set-store! store)
                       (log/info "ChromaMemoryStore wired as active IMemoryStore backend"
                                 {:backend backend}))))))

(defn ensure-memory-store!
  "Guarantee an active IMemoryStore after addon loading.

   Called in Phase 4.6 (after load-extensions!). If the configured backend's
   addon failed to register a store, wire ChromaMemoryStore as a safety
   fallback so memory queries don't throw 'No memory store configured'."
  []
  (result/rescue nil
                 (when-not (mem-proto/store-set?)
                   (log/warn "ensure-memory-store!: no store after extensions; wiring Chroma fallback")
                   (let [store (chroma-store/create-store)]
                     (mem-proto/set-store! store)))))

;; =============================================================================
;; Channel Bridge + Sync
;; =============================================================================

(defn init-channel-bridge!
  "Initialize channel bridge - wires channel events to hive-events dispatch.
   EVENTS-01: Must init after both channel server and event system."
  []
  (result/rescue nil
                 (channel-bridge/init!)
                 (log/info "Channel bridge initialized - channel events will dispatch to hive-events")))

(defn- build-swarm-bootstrap
  "Construct the configured ISwarmBootstrap.
   Honors explicit opts, falling back to `services.swarm-sync.source` in
   config.edn (default :emacs for backwards compatibility)."
  [opts]
  (bootstrap-factory/make-bootstrap
   opts
   (fn [section key & {:as get-opts}]
     (apply global-config/get-service-value section key (mapcat identity get-opts)))))

(defn- resolve-event-backbone
  "Pick the event backbone for swarm-sync: explicit opts > config.edn > :local.
   :local means in-process channel.core only (legacy default).
   :nats means also bridge slave events from the IEventBackbone."
  [opts]
  (or (:event-backbone opts)
      (rescue nil (some-> (global-config/get-service-value :swarm-sync :event-backbone
                                                 :parse keyword)))
      :local))

(defn start-swarm-sync!
  "Start swarm sync — bridges channel events to logic database, rehydrates
   the in-memory registry from the configured ISwarmBootstrap source, and
   (optionally) bridges the IEventBackbone (NATS) into the in-process event
   bus so distributed slave events reach the same handlers.

   Args:
     opts — {:source         :emacs|:datahike|:none
             :event-backbone :local|:nats
             :db-path        string
             :timeout-ms     int}
            (all optional; defaults resolved from config.edn)

   Order matters:
     1. Build + inject bootstrap (durable slave projection)
     2. start-sync! (subscribes to channel.core; runs bootstrap reload)
     3. Start NATS event bridge if requested (NATS → channel.core)"
  ([] (start-swarm-sync! {}))
  ([opts]
   (result/rescue nil
                  (let [bs (build-swarm-bootstrap opts)]
                    (sync/set-swarm-bootstrap! bs))
                  (sync/start-sync!)
                  ;; Retires rehydrated slaves (:zombie + :alive? false) — memory 20260423152822-70fe5631.
                  (rescue nil (boot-reconcile/reconcile-rehydrated-slaves!))
                  (let [eb (resolve-event-backbone opts)]
                    (when (= :nats eb)
                      (let [started? (swarm-event-bridge/start-nats-bridge!)]
                        (if started?
                          (log/info "Swarm sync: NATS event bridge started")
                          (log/warn "Swarm sync: NATS event bridge requested but not started"
                                    " (backbone may be disconnected — check :hive/nats init)")))
                      ;; Rewire the piggyback instruction queue through NATS so
                      ;; coordinators and lings in separate JVMs share a queue.
                      (let [bb (eb/get-backbone)]
                        (if (eb/connected? bb)
                          (do (instruction-store/rewire!
                               piggyback/instruction-queues
                               bb)
                              (log/info "Swarm sync: piggyback instruction store bound to NATS"))
                          (log/warn "Swarm sync: piggyback instruction store staying local"
                                    " (NATS backbone not connected)")))))
                  (log/info "Swarm sync started - logic database will track swarm state"))))

;; =============================================================================
;; Hot-Reload Watcher
;; =============================================================================

(defn init-hot-reload-watcher!
  "Initialize hot-reload watcher with claim-aware coordination.

   ADR: State-based debouncing - claimed files buffer until release.

   Parameters:
     server-context-atom - atom containing MCP server context
     project-config      - map from read-project-config (or nil)"
  [server-context-atom project-config]
  (let [hot-reload-enabled? (get project-config :hot-reload true)]
    (if hot-reload-enabled?
      (result/rescue nil
                     (let [src-dirs (or (global-config/get-service-value :project :src-dirs
                                                                         :env "HIVE_MCP_SRC_DIRS"
                                                                         :parse #(str/split % #":"))
                                        (:watch-dirs project-config)
                                        ["src"])
                           claim-checker (hot-events/make-claim-checker logic/get-all-claims)]
                       (hot/init-with-watcher! {:dirs src-dirs
                                                :claim-checker claim-checker
                                                :debounce-ms 100})
                       (log/info "Hot-reload watcher started:" {:dirs src-dirs})
          ;; Register MCP auto-heal listener to refresh tools after reload
                       (register-hot-reload-listener! server-context-atom)
          ;; Register state protection for DataScript state validation
                       (result/rescue nil
                                      (require 'hive-mcp.hot.state)
                                      (let [register! (resolve 'hive-mcp.hot.state/register-with-hive-hot!)]
                                        (register!)))
          ;; Register SAA Silence strategy for hot-reload aware exploration
                       (result/rescue nil
                                      (require 'hive-mcp.hot.silence)
                                      (let [register! (resolve 'hive-mcp.hot.silence/register-with-hive-hot!)]
                                        (register!)))))
      (log/info "Hot-reload disabled via .hive-project.edn"))))

;; =============================================================================
;; Registry Sync
;; =============================================================================

(defn start-registry-sync!
  "Start lings registry sync - keeps Clojure registry in sync with elisp.
   ADR-001: Event-driven sync for lings_available to return accurate counts."
  []
  (result/rescue nil
                 (swarm/start-registry-sync!)
                 (log/info "Lings registry sync started - lings_available will track elisp lings")))

;; =============================================================================
;; Decay Scheduler
;; =============================================================================

(defn start-decay-scheduler!
  "Start the periodic decay scheduler.
   Runs memory staleness decay, edge confidence decay, and disc certainty
   decay on a configurable interval (default: 60 minutes).

   Configure via config.edn :services :scheduler:
     {:enabled true :interval-minutes 60 :memory-limit 50 :edge-limit 100}

   Non-fatal: if scheduler fails to start, system continues without it.
   Decay still runs on wrap/catchup hooks as before."
  []
  (result/rescue nil
                 (require 'hive-mcp.scheduler.decay)
                 (let [start-fn (resolve 'hive-mcp.scheduler.decay/start!)]
                   (when start-fn
                     (let [result (start-fn)]
                       (if (:started result)
                         (log/info "Decay scheduler started:" result)
                         (log/info "Decay scheduler not started:" (:reason result))))))))

(defn run-recall-canary!
  "Run the golden recall canary once, at boot, before agents ask anything.

   Returns the verdict map (or nil when the canary ns is absent). Non-fatal by
   construction: a faulting canary must not stop the server from starting, or
   an operator loses the very tool that would tell them what is wrong. The
   fault is on the boot log at ERROR and on the scheduler tick thereafter.

   Configure via config.edn :services :recall-canary {:enabled true}."
  []
  (result/rescue
   nil
   (let [enabled? (not (false? (global-config/get-config-value [:services :recall-canary :enabled])))]
     (if-not enabled?
       (log/info "Recall canary disabled by config")
       (when-let [run! (requiring-resolve 'hive-mcp.recall.canary.live/run!)]
         (let [verdict (run!)]
           (if (:ok? verdict)
             (log/info "Recall canary OK at boot:" (select-keys verdict [:ran :passed :skipped]))
             (log/error "RECALL CANARY FAULT AT BOOT — retrieval answers cannot be"
                        "trusted until this clears:" (:faults verdict)))
           verdict))))))

(defn run-recall-canary-async!
  "Run the boot canary on a daemon thread.

   Off-thread because the canary talks to the embedder and the store, and a
   slow provider must not lengthen boot; daemon because a canary must never
   hold the JVM open. Loudness is unaffected — the verdict lands on the log
   either way. Returns the Thread."
  []
  (doto (Thread. ^Runnable (fn [] (run-recall-canary!)) "recall-canary-boot")
    (.setDaemon true)
    (.start)))

(defn stop-decay-scheduler!
  "Stop the periodic decay scheduler. Called during shutdown."
  []
  (result/rescue nil
                 (require 'hive-mcp.scheduler.decay)
                 (when-let [stop-fn (resolve 'hive-mcp.scheduler.decay/stop!)]
                   (stop-fn))))

;; =============================================================================
;; Housekeeping Scheduler (gc-fix-5)
;; =============================================================================

(defn start-housekeeping-scheduler!
  "Start the periodic housekeeping scheduler (gc-fix-5) and the context-store
   TTL reaper.

   Runs bounded atom GC sweep + stale resource cleanup every 5 minutes.
   Starts the context-store reaper unconditionally; both calls are idempotent.

   Configure via config.edn :services :housekeeping:
     {:enabled true :interval-minutes 5}

   Non-fatal: if either fails to start, system continues without it.
   GC sweep still runs on session wrap/complete as before."
  []
  (result/rescue nil
                 (require 'hive-mcp.scheduler.housekeeping)
                 (let [start-fn (resolve 'hive-mcp.scheduler.housekeeping/start!)]
                   (when start-fn
                     (let [result (start-fn)]
                       (if (:started result)
                         (log/info "Housekeeping scheduler started:" result)
                         (log/info "Housekeeping scheduler not started:" (:reason result)))))))
  (result/rescue nil
                 (require 'hive-mcp.channel.context-store)
                 (when-let [reaper-start! (resolve 'hive-mcp.channel.context-store/start-reaper!)]
                   (reaper-start!))))

(defn stop-housekeeping-scheduler!
  "Stop the periodic housekeeping scheduler and the context-store TTL reaper.
   Called during shutdown."
  []
  (result/rescue nil
                 (require 'hive-mcp.scheduler.housekeeping)
                 (when-let [stop-fn (resolve 'hive-mcp.scheduler.housekeeping/stop!)]
                   (stop-fn)))
  (result/rescue nil
                 (require 'hive-mcp.channel.context-store)
                 (when-let [reaper-stop! (resolve 'hive-mcp.channel.context-store/stop-reaper!)]
                   (reaper-stop!))))

;; =============================================================================
;; NATS Initialization
;; =============================================================================

(defn init-delivery-channels!
  "Register every IDeliveryChannel impl chosen by `register-default-channels!`.
   Run UNCONDITIONALLY at startup — independent of NATS, Emacs, or any
   editor frontend. Without this, headless hosts with NATS disabled would
   silently lose all delivery (the historical E2E-2 symptom). Non-fatal:
   factory errors are logged but don't abort init."
  []
  (try
    (when-let [reg-channels! (requiring-resolve 'hive-mcp.delivery.channels/register-default-channels!)]
      (reg-channels!))
    (catch Exception e
      (log/warn "[init] Failed to register delivery channels (non-fatal):" (ex-message e)))))

(defn init-nats!
  "Initialize NATS client + bridge + backbone. Opt-in via config: services.nats.enabled = true.
   Non-fatal: system degrades to NoopBackbone + polling if NATS unavailable.

   Delivery channels are registered separately via init-delivery-channels!
   so headless hosts still get a working delivery surface even when NATS
   is disabled."
  []
  (result/rescue nil
                 (let [nats-config (global-config/get-service-config :nats)]
                   (when (:enabled nats-config)
                     (let [start! (requiring-resolve 'hive-mcp.nats.client/start!)
                           bridge! (requiring-resolve 'hive-mcp.nats.bridge/start-subscriptions!)
                           create-bb (requiring-resolve 'hive-mcp.nats.backbone/create-backbone)
                           set-bb! (requiring-resolve 'hive-mcp.protocols.event-backbone/set-backbone!)]
                       (start! nats-config)
                       (let [bb (create-bb)]
                         (set-bb! bb)
                         (log/info "[init] NatsBackbone set as active IEventBackbone"))
                       (bridge!)
                       (when-let [cb-start (requiring-resolve 'hive-mcp.swarm.callback/start-listener!)]
                         (cb-start)))))))

;; =============================================================================
;; Forge Belt Defaults
;; =============================================================================

(defn register-forge-belt-defaults!
  "Register default implementations for forge belt :fb/* extension points.
   Must run before load-extensions! so extensions can override."
  []
  (try
    (require 'hive-mcp.workflows.forge-belt-defaults)
    (when-let [register! (resolve 'hive-mcp.workflows.forge-belt-defaults/register-forge-belt-defaults!)]
      (register!))
    (catch Throwable e
      (log/warn "register-forge-belt-defaults! failed -- forge-belt extension points will return noop:"
                (.getMessage e)))))

;; =============================================================================
;; Extension Loading
;; =============================================================================

(defn load-extensions!
  "Load optional extension capabilities discovered on the classpath.
   Uses classpath manifest scanning + addon self-registration.
   Non-fatal: system works without extensions (noop defaults).

   Must run AFTER embedding/memory services (extensions may use Chroma)."
  []
  (result/rescue nil
                 (require 'hive-mcp.extensions.loader)
                 (let [load-fn (resolve 'hive-mcp.extensions.loader/load-extensions!)]
                   (when load-fn
                     (let [result (load-fn)]
                       (log/info "Extension loading complete:" result)))))
  ;; Post-init multi-dispatch coherence check (WARN-only)
  (result/rescue nil
                 (let [get-adv (requiring-resolve 'hive-mcp.tools.registry/get-advertised-tools)
                       check!  (requiring-resolve 'hive-mcp.multi.registry/check-dispatch-coherence!)]
                   (when (and get-adv check!)
                     (check! (filter :consolidated (get-adv)))))))

;; =============================================================================
;; Workflow Engine Initialization
;; =============================================================================

(defn init-workflow-engine!
  "Initialize FSM workflow registry and wire FSMWorkflowEngine as active engine.

   1. Calls registry/init! to scan EDN specs and register all built-in handlers
   2. Creates FSMWorkflowEngine and sets it as the active IWorkflowEngine

   Must run AFTER embedding/memory services (handlers may need them at runtime).
   Non-fatal: if initialization fails, NoopWorkflowEngine remains as fallback."
  []
  (result/rescue nil
                 (require 'hive-mcp.workflows.registry)
                 (require 'hive-mcp.workflows.fsm-engine)
                 (require 'hive-mcp.protocols.workflow)
                 (let [registry-init! (resolve 'hive-mcp.workflows.registry/init!)
                       create-engine  (resolve 'hive-mcp.workflows.fsm-engine/create-engine)
                       set-engine!    (resolve 'hive-mcp.protocols.workflow/set-workflow-engine!)]
                   (registry-init!)
                   (set-engine! (create-engine))
                   (log/info "FSM workflow engine initialized and wired as active IWorkflowEngine"))))

;; =============================================================================
;; nREPL-mode initialization (for dev/bb-mcp without full -main)
;; =============================================================================

(defn- tool->registry-entry
  "Convert a make-tool result to a registry entry [name {:tool spec, :handler fn}]."
  [t]
  [(:name t) {:tool (dissoc t :handler)
              :handler (:handler t)}])

(defn populate-server-context!
  "Populate server-context-atom with middleware-wrapped tool handlers.

   CRITICAL: Uses routes/make-tool to wrap handlers with the full middleware
   chain (piggyback, context, normalize, etc.). Without make-tool, bb-mcp
   gets raw handlers and no ---MEMORY---/---HIVEMIND--- blocks are attached."
  []
  (require 'hive-mcp.server.core)
  (require 'hive-mcp.tools.registry)
  (require 'hive-mcp.extensions.registry)
  (let [consolidated ((resolve 'hive-mcp.tools.registry/get-consolidated-tools))
        extensions   ((resolve 'hive-mcp.extensions.registry/get-registered-tools))
        wrapped      (mapv routes/make-tool (concat consolidated extensions))
        tools        (into {} (map tool->registry-entry wrapped))
        ctx-atom     (deref (resolve 'hive-mcp.server.core/server-context-atom))]
    (swap! ctx-atom (fn [_] {:tools (atom tools)}))
    (log/info "server-context populated:" (count tools) "tools (middleware-wrapped)")))

(defn nrepl-init!
  "Initialize essential services for nREPL-mode operation (dev REPL, bb-mcp).
   Runs the subset of -main phases needed for tools to work via nREPL:
   - Phase 4: Embedding provider + memory store
   - Phase 4.5: Extension/addon loading
   - Server context: Populate server-context-atom for bb-mcp dynamic tools

   Safe to call multiple times (idempotent via defonce atoms).
   Called automatically by dev/user.clj on startup."
  []
  (log/info "nrepl-init! starting...")
  ;; Phase 4: Embedding + memory
  (init-embedding-provider!)
  (wire-memory-store!)

  ;; Phase 4.5: Extensions
  (load-extensions!)

  (populate-server-context!)
  (log/info "nrepl-init! complete"))
