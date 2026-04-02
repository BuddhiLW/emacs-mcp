(ns hive-mcp.server.core
  "MCP server entry point — Integrant lifecycle orchestrator.

   Replaces the monolithic 7-phase start! with declarative Integrant system.
   All initialization order is determined by #ig/ref dependency edges in
   resources/hive/system.edn + profile overlays.

   Public API:
     start!  — Init Integrant system from config (non-blocking, returns system map)
     stop!   — Halt running system (ig/halt! in reverse init order)
     reset!  — stop! + clj-reload namespace refresh + start! (dev workflow)
     -main   — Entry point: start! + block on keepalive"
  (:refer-clojure :exclude [reset!])
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [meta-merge.core :refer [meta-merge]]
            [taoensso.timbre :as log]
            ;; ── System layer namespaces (load init-key/halt-key! multimethods) ──
            [hive-mcp.system.layer1]
            [hive-mcp.system.layer2]
            [hive-mcp.system.layer3]
            [hive-mcp.system.layer4]
            [hive-mcp.system.layer5]
            [hive-mcp.system.keepalive :as keepalive])
  (:gen-class))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Configure Timbre to write to stderr instead of stdout
;; This is CRITICAL for MCP servers — stdout is the JSON-RPC channel
;; =============================================================================

(log/merge-config!
 {:appenders
  {:println {:enabled? true
             :async? false
             :fn (fn [data]
                   (let [{:keys [output_]} data]
                     (binding [*out* *err*]
                       (println (force output_)))))}}})

;; =============================================================================
;; System State — single defonce atom replaces 6 private atoms
;; =============================================================================

(defonce system (atom nil))

;; Backward-compat bridge for bb-mcp forwarding handlers.
;; bb-mcp resolves this atom to get middleware-wrapped tool handlers.
;; Populated by populate-server-context! (init.clj) after Integrant (go).
(defonce server-context-atom (atom nil))

;; =============================================================================
;; Profile-Aware Config Loading
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
   Profile nil keys are removed (Integrant convention for exclusion).

   Note: meta-merge ignores nil overlay values (keeps base), so we must
   explicitly dissoc keys that the profile sets to nil BEFORE merging."
  ([] (load-system-config (resolve-profile)))
  ([profile]
   (let [base       (read-base-config)
         overlay    (read-profile-config profile)
         ;; Identify keys the profile explicitly sets to nil (exclusion markers)
         nil-keys   (into #{} (comp (filter (fn [[_ v]] (nil? v))) (map key)) overlay)
         ;; Remove nil entries from overlay before meta-merge (meta-merge ignores nil)
         overlay'   (into {} (remove (fn [[_ v]] (nil? v))) overlay)
         ;; Merge remaining overlay into base, then remove excluded keys
         merged     (meta-merge base overlay')]
     (apply dissoc merged nil-keys))))

;; =============================================================================
;; Lifecycle — start! / stop! / reset!
;; =============================================================================

(defn start!
  "Initialize the Integrant system from system.edn + profile overlay.

   Non-blocking — returns the initialized system map. Caller is responsible
   for blocking (see -main which uses keepalive/await-shutdown-or-stdio!).

   Idempotent guard: throws if system already running.

   Accepts optional profile keyword (:desktop, :k8s-headless, :k8s-minimal).
   Profile precedence: explicit :profile > HIVE_PROFILE env > :desktop."
  [& {:keys [profile] :or {profile nil}}]
  (let [profile (resolve-profile profile)]
    (when @system
      (throw (ex-info "System already running. Call (stop!) first or (reset!) to restart."
                      {:profile profile})))
    (log/info "Starting hive-mcp server with Integrant, profile:" profile)
    (when-let [sock (System/getenv "EMACS_SOCKET_NAME")]
      (log/info "Targeting Emacs daemon:" sock))
    (let [config (load-system-config profile)
          sys    (ig/init config)]
      (clojure.core/reset! system sys)
      ;; Populate server-context-atom for bb-mcp forwarding handlers.
      ;; Must run AFTER extensions are loaded (tools registered).
      (try
        (require 'hive-mcp.server.init)
        (when-let [populate! (resolve 'hive-mcp.server.init/populate-server-context!)]
          (populate!))
        (catch Exception e
          (log/warn "populate-server-context! failed (non-fatal):" (ex-message e))))
      (log/info "Integrant system initialized:" (count sys) "keys")
      sys)))

(defn stop!
  "Halt the running Integrant system. Safe to call when no system running.
   Keys are halted in reverse init order (Integrant default)."
  []
  (when-let [sys @system]
    (log/info "Halting Integrant system...")
    (ig/halt! sys)
    (clojure.core/reset! system nil)
    (log/info "Integrant system halted.")
    :halted))

(defn reset!
  "Stop system, reload changed namespaces via clj-reload, re-init system.
   The full REPL-driven development cycle.

   clj-reload is dev-only (not in main deps), so we use requiring-resolve."
  [& {:keys [profile] :or {profile nil}}]
  (let [profile (or profile (resolve-profile))]
    (stop!)
    (log/info "Reloading changed namespaces...")
    (if-let [reload-fn (requiring-resolve 'clj-reload.core/reload)]
      (do (reload-fn)
          (log/info "Namespaces reloaded. Restarting..."))
      (log/warn "clj-reload not available (dev-only dep). Skipping reload."))
    (start! :profile profile)))

;; =============================================================================
;; Blocking — keepalive integration for -main
;; =============================================================================

(defn- block-until-shutdown!
  "Block the calling thread on the keepalive component until shutdown signal.

   In :stdio mode (desktop): races MCP stdio join promise vs shutdown-ch.
   In :promise mode (K8s headless): blocks on shutdown-ch (SIGTERM).

   After unblocking, halts the system and exits."
  [sys]
  (let [keepalive-state (get sys :hive/keepalive)]
    (if-let [mcp-stdio (get sys :hive/mcp-stdio)]
      ;; Desktop: race stdio EOF vs shutdown signal
      (let [[source _val] (keepalive/await-shutdown-or-stdio!
                            keepalive-state (:join mcp-stdio))]
        (log/info "hive-mcp unblocked via" source "— initiating clean shutdown")
        (stop!)
        (System/exit 0))
      ;; Headless: block on shutdown-ch (SIGTERM or halt!)
      (do
        (keepalive/await-shutdown! keepalive-state)
        (log/info "hive-mcp shutdown signal received — initiating clean shutdown")
        (stop!)
        (System/exit 0)))))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

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
  (let [{:keys [profile]} (parse-cli-args args)
        sys (start! :profile profile)]
    (block-until-shutdown! sys)))

(comment
  ;; For REPL development — use dev/user.clj (go)/(halt)/(reset) instead
  (start!)
  (start! :profile :k8s-headless)
  (stop!)
  (reset!)
  @system)
