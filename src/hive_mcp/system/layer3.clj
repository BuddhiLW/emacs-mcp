(ns hive-mcp.system.layer3
  "Integrant key implementations — Layer 3: services.

   These keys manage core service dependencies:
     - :hive/embedding       — embedding provider (Chroma, Ollama, OpenRouter)
     - :hive/memory-store    — IMemoryStore wiring (Chroma or Proximum backend)
     - :hive/tool-delegation — tool delegation registry for ling agents
     - :hive/forge-belt      — forge belt default extension points
     - :hive/config          — global config loading + .hive-project.edn scan
     - :hive/extensions      — classpath addon discovery + self-registration

   Each init-key wraps existing functions from:
     - server/init.clj   → init-embedding-provider!, wire-memory-store!, etc.
     - server/routes.clj → register-tools-for-delegation!
     - config/core.clj   → load-global-config!
     - extensions/loader.clj → load-extensions!

   halt-key! reverses init-key where meaningful (addon shutdown, store disconnect)."
  (:require [integrant.core :as ig]
            [hive-mcp.server.init :as init]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; :hive/embedding — Embedding provider initialization (Chroma + Ollama/OpenRouter)
;; =============================================================================

(defmethod ig/init-key :hive/embedding
  [_ _config]
  (log/info ":hive/embedding init — initializing embedding providers")
  (let [result (init/init-embedding-provider!)]
    ;; Fire background warmup (non-blocking future)
    (init/warmup-embedding!)
    {:status (if result :running :degraded)
     :warmup :started}))

(defmethod ig/halt-key! :hive/embedding
  [_ _state]
  (log/info ":hive/embedding halt — resetting EmbeddingService")
  ;; Reset EmbeddingService state (collection configs, initialized? flag)
  ;; so next init-key re-configures all providers cleanly.
  ;; Chroma is a stateless HTTP client — no connection to close.
  (result/rescue nil
    (require 'hive-mcp.embeddings.service)
    (when-let [reset-svc! (resolve 'hive-mcp.embeddings.service/reset-service!)]
      (reset-svc!)
      (log/info ":hive/embedding EmbeddingService reset"))))

;; =============================================================================
;; :hive/memory-store — IMemoryStore backend wiring (Chroma or Proximum)
;; =============================================================================

(defmethod ig/init-key :hive/memory-store
  [_ _config]
  (log/info ":hive/memory-store init — wiring IMemoryStore backend")
  (init/wire-memory-store!)
  {:status :running})

(defmethod ig/halt-key! :hive/memory-store
  [_ _state]
  (log/info ":hive/memory-store halt — clearing active store")
  ;; Clear the global IMemoryStore reference so next init-key re-wires cleanly.
  ;; The underlying store (Chroma/Proximum) is managed by :hive/embedding.
  (result/rescue nil
    (require 'hive-mcp.protocols.memory)
    (when-let [reset! (resolve 'hive-mcp.protocols.memory/reset-active-store!)]
      (reset!)
      (log/info ":hive/memory-store reset"))))

;; =============================================================================
;; :hive/tool-delegation — Tool delegation registry for ling agents
;; =============================================================================

(defmethod ig/init-key :hive/tool-delegation
  [_ _config]
  (log/info ":hive/tool-delegation init — registering tools for agent delegation")
  (let [count (result/rescue 0
                (routes/register-tools-for-delegation!))]
    {:status :running
     :tool-count count}))

(defmethod ig/halt-key! :hive/tool-delegation
  [_ _state]
  ;; Tool delegation is process-global registry — clearing would break
  ;; any in-flight ling sessions. Noop; re-init overwrites cleanly.
  (log/info ":hive/tool-delegation halt — noop (process-global registry)"))

;; =============================================================================
;; :hive/forge-belt — Default forge belt extension points
;; =============================================================================

(defmethod ig/init-key :hive/forge-belt
  [_ _config]
  (log/info ":hive/forge-belt init — registering forge belt defaults")
  (init/register-forge-belt-defaults!)
  {:status :running})

(defmethod ig/halt-key! :hive/forge-belt
  [_ _state]
  ;; Forge belt defaults are overwritten by extensions; no teardown needed.
  (log/info ":hive/forge-belt halt — noop (defaults overwritten by extensions)"))

;; =============================================================================
;; :hive/config — Global config loading + .hive-project.edn auto-generation
;; =============================================================================

(defmethod ig/init-key :hive/config
  [_ _config]
  (log/info ":hive/config init — loading global config + scanning .hive-project.edn")
  (let [config-result
        (result/rescue nil
          (require 'hive-mcp.config.core)
          (require 'hive-mcp.tools.hive-project)
          (let [load-config! (resolve 'hive-mcp.config.core/load-global-config!)
                scan!        (resolve 'hive-mcp.tools.hive-project/scan-and-generate-missing!)]
            (load-config!)
            (let [scan-result (scan!)]
              (log/info ":hive/config global config loaded + auto-gen .hive-project.edn:" scan-result)
              {:config-loaded true :scan-result scan-result})))]
    {:status (if config-result :running :degraded)
     :result config-result}))

(defmethod ig/halt-key! :hive/config
  [_ _state]
  ;; Config is read-once; no resources to release.
  ;; Global config atom persists across resets (re-read on next init-key).
  (log/info ":hive/config halt — noop (config is read-once)"))

;; =============================================================================
;; :hive/extensions — Classpath addon discovery + self-registration
;; =============================================================================

(defmethod ig/init-key :hive/extensions
  [_ _config]
  (log/info ":hive/extensions init — loading classpath extensions + addon discovery")
  (let [result (init/load-extensions!)]
    {:status     :running
     :registered (:registered result)
     :total      (:total result)
     :sources    (:sources result)}))

(defmethod ig/halt-key! :hive/extensions
  [_ state]
  (log/info ":hive/extensions halt — shutting down active addons")
  ;; Shutdown all active addons via addon-core/shutdown-all!
  ;; This calls IAddon/shutdown! on each addon (closes bridges, connections, etc.)
  (let [shutdown-result
        (result/rescue nil
          (require 'hive-mcp.addons.core)
          (let [shutdown-all! (resolve 'hive-mcp.addons.core/shutdown-all!)]
            (shutdown-all!)))]
    (when shutdown-result
      (log/info ":hive/extensions addons shut down:" shutdown-result))
    ;; Clear extension registries (fns, schemas, tools) for clean re-init
    (result/rescue nil
      (require 'hive-mcp.extensions.registry)
      (let [clear-fns!   (resolve 'hive-mcp.extensions.registry/clear-all!)
            clear-tools! (resolve 'hive-mcp.extensions.registry/clear-all-tools!)]
        (when clear-fns!   (clear-fns!))
        (when clear-tools! (clear-tools!))
        (log/info ":hive/extensions registry cleared")))))
