(ns hive-mcp.multi.registry
  "Façade over the four child registries (tools / verbs / aliases / batchables).

   Single SOLID-clean dispatch surface for the IAddon hooks-walk:
   `addons/core.clj` routes any `(hooks [this])` map entry whose key
   namespace is `\"multi\"` here via `register-by-key!` / `deregister-by-key!`.

   Owner = addon-id keyword (or :multi/core for boot-seeded entries).
   Per-owner ownership tagging means `deregister-by-owner!` is O(owner-keys)
   and never clobbers another addon's entries.

   Resolve chain (collapsed from 3 layers to 2):
     1. registry.tools/lookup    — covers core seed AND addon contributions
     2. flat-tool fallback        — for non-consolidated legacy tools

   Decision: 20260429230453-7e7627cc"
  (:require [hive-mcp.multi.registry.tools :as r-tools]
            [hive-mcp.multi.registry.verbs :as r-verbs]
            [hive-mcp.multi.registry.aliases :as r-aliases]
            [hive-mcp.multi.registry.batchables :as r-batchables]
            [hive-mcp.multi.batchable-adapter :as adapter]
            [hive-dsl.result :as r :refer [rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Hook-key dispatch (called from addons/core.clj namespace-routed walk)
;; =============================================================================

(defn- register-tool-entry! [owner {:keys [tool-name handler batchable]}]
  (let [outcome (r-tools/register! owner tool-name {:handler handler :batchable batchable})]
    (when batchable
      (r-batchables/register! owner tool-name {:record batchable}))
    outcome))

(defn- register-verb-entry! [owner {:keys [code tool command]}]
  (r-verbs/register! owner code {:tool tool :command command}))

(defn- register-alias-entry! [owner {:keys [short full]}]
  (r-aliases/register! owner short {:full full}))

(defn- register-batchable-entry! [owner {:keys [tool-name record]}]
  (r-batchables/register! owner tool-name {:record record}))

(defn register-by-key!
  "Route an IAddon `(hooks)` map entry whose key namespace is \"multi\"
   to the right child registry.

   `entries` is a vector of RegistryEntry maps (per multi.types/RegistryEntry).
   Owner stamp is the addon-id passed by the lifecycle.

   Returns a vector of per-entry outcomes (`:ok` | `:replaced` | `:conflict`)."
  [owner k entries]
  (let [entries (cond (sequential? entries) entries
                      (map? entries)        [entries]
                      :else                  nil)]
    (when (nil? entries)
      (log/warn "[multi.registry] non-vector value for hook key — skipping"
                {:owner owner :key k}))
    (mapv (fn [entry]
            (case k
              :multi/tool         (register-tool-entry! owner entry)
              :multi/verb         (register-verb-entry! owner entry)
              :multi/param-alias  (register-alias-entry! owner entry)
              :multi/batchable    (register-batchable-entry! owner entry)
              (do (log/warn "[multi.registry] unknown :multi/* key — ignored"
                            {:owner owner :key k})
                  :ignored)))
          (or entries []))))

(defn deregister-by-key!
  "Remove every entry an addon registered under a particular :multi/* key.

   Currently the per-owner deregister is keyed by registry, not by hook key —
   addons rarely register two `:multi/*` keys mixed across registries, and the
   addon's full set is cleared on shutdown via `deregister-by-owner!` anyway.

   Provided for symmetry with register-by-key!. Calling it is equivalent to
   `deregister-by-owner! owner` scoped to the relevant child registry."
  [owner k]
  (case k
    :multi/tool         (r-tools/deregister-by-owner! owner)
    :multi/verb         (r-verbs/deregister-by-owner! owner)
    :multi/param-alias  (r-aliases/deregister-by-owner! owner)
    :multi/batchable    (r-batchables/deregister-by-owner! owner)
    nil))

(defn deregister-by-owner!
  "Clear every entry across all four child registries owned by `owner`.

   Called from the IAddon shutdown lifecycle for full cleanup."
  [owner]
  {:tools       (r-tools/deregister-by-owner! owner)
   :verbs       (r-verbs/deregister-by-owner! owner)
   :aliases     (r-aliases/deregister-by-owner! owner)
   :batchables  (r-batchables/deregister-by-owner! owner)})

;; =============================================================================
;; Resolve chain
;; =============================================================================

(defn resolve-tool-handler
  "Resolve a tool name to its handler fn.

   Order:
     1. multi.registry.tools (covers :multi/core seed + addon contributions)
     2. Flat-tool fallback via hive-mcp.tools/get-tool-by-name (legacy)

   Returns nil if neither layer has the tool."
  [tool-name]
  (or
   ;; 1. Registry hit
   (some-> (r-tools/lookup tool-name) :handler)

   ;; 2. Flat-tool fallback (legacy non-consolidated tools)
   (rescue nil
           (when-let [resolver (requiring-resolve 'hive-mcp.tools.registry/get-tool-by-name)]
             (when-let [tool-def (resolver tool-name)]
               (:handler tool-def))))))

(defn lookup-batchable-or-default
  "Return the explicit Batchable record for a tool, or a DefaultBatchableAdapter
   bound to `resolve-tool-handler` so the caller cannot tell which path is in use
   (LSP guarantee)."
  ([tool-name]
   (lookup-batchable-or-default tool-name nil))
  ([tool-name emit-fx]
   (or (some-> (r-batchables/lookup tool-name) :record)
       (adapter/make-default-adapter tool-name resolve-tool-handler emit-fx))))

;; =============================================================================
;; Snapshot — pure value for deterministic plan compilation
;; =============================================================================

(defn snapshot
  "Immutable snapshot across all four child registries.

   `:version` is a hash that callers can stamp onto compiled plans; replaying
   a plan against a registry whose hash differs raises :multi/registry-stale."
  []
  (let [tools (r-tools/snapshot)
        verbs (r-verbs/snapshot)
        aliases (r-aliases/snapshot)
        batchables (r-batchables/snapshot)]
    {:tools tools :verbs verbs :aliases aliases :batchables batchables
     :version (hash [(:version tools) (:version verbs)
                     (:version aliases) (:version batchables)])}))

(defn reset-for-test!
  "Clear all four child registries. Test-only."
  []
  (r-tools/reset-for-test!)
  (r-verbs/reset-for-test!)
  (r-aliases/reset-for-test!)
  (r-batchables/reset-for-test!))

;; =============================================================================
;; Bootstrap: seed :multi/core owner before first registration arrives
;; =============================================================================
;;
;; Lazy `require` (not top-level :require) breaks the load-order cycle:
;;   core-seed -> registry  (top-level)
;;   registry -> core-seed  (deferred until registry is fully loaded)
;;
;; This defonce fires exactly once when the registry namespace finishes
;; loading. Any path that reaches us via `requiring-resolve` (the addons
;; lifecycle, tools/multi.clj re-export, tests, REPL) is guaranteed to see
;; a populated registry on the very next call.

(defonce ^:private __core-seeded__
  (rescue
   {:status :failed :reason "core-seed load threw — :multi/core entries absent"}
   (require 'hive-mcp.multi.core-seed)
   {:status :ok}))
