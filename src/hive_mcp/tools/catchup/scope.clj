(ns hive-mcp.tools.catchup.scope
  "Facade for catchup scope resolution + query orchestration.

   Thin re-export layer that preserves the historical public API while
   the implementation lives in per-concern child namespaces. Production
   callers (tools.catchup, tools.catchup.spawn, workflows.catchup_session)
   and tests (scope_test, query_axioms_regression_test, spawn_test,
   hints_test) bind to the `catchup.scope/*` symbols; this namespace
   forwards each to the canonical implementation.

   Child namespaces:
     - catchup.hierarchy    — project-id resolution + chunked parallel fetch
     - catchup.scope-filter — pure filter/sort/dedupe helpers
     - catchup.axiom-cache  — stale-while-revalidate `type=axiom` cache
     - catchup.hydration    — phase-2 batch-get content pipeline
     - catchup.bundle       — per-type + single-pull query orchestrators

   The only logic still resident here is `get-current-project-name`, which
   resolves the current project from `.hive-project.edn` or the directory
   path — it sits at a different layer (project discovery) from the query
   orchestration below."
  (:require [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.tools.catchup.scope-filter :as sf]
            [hive-mcp.tools.catchup.axiom-cache :as axc]
            [hive-mcp.tools.catchup.bundle :as bundle]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Project discovery (lives here — not a query orchestration concern)
;; =============================================================================

(defn get-current-project-name
  "Get current project name from .hive-project.edn or directory path
   (no Emacs dependency).

   Resolution priority:
     1. :name or :project-id from .hive-project.edn in the exact dir
     2. Walk up to the nearest .hive-project.edn (covers deep subdirs)
     3. Last path segment (legacy fallback)"
  ([] (get-current-project-name nil))
  ([directory]
   (rescue nil
           (when directory
             (or
              ;; Priority 1: :name from .hive-project.edn in exact dir
              (let [config (kg-scope/read-direct-project-config directory)]
                (or (:name config) (:project-id config)))
              ;; Priority 2: walk up for nearest .hive-project.edn
              (let [walked (kg-scope/infer-scope-from-path directory)]
                (when (and walked (not= walked "global")) walked))
              ;; Priority 3: last path segment
              (let [parts (str/split (str directory) #"/")]
                (last parts)))))))

;; =============================================================================
;; Re-exports — scope_filter helpers
;; =============================================================================

(def distinct-by           sf/distinct-by)
(def filter-by-tags        sf/filter-by-tags)
(def entry-expiring-soon?  sf/entry-expiring-soon?)

;; =============================================================================
;; Re-exports — axiom_cache
;; =============================================================================

(def query-axioms              axc/query-axioms)
(def invalidate-axioms-cache!  axc/invalidate-axioms-cache!)

;; =============================================================================
;; Re-exports — bundle orchestrators
;; =============================================================================

(def query-scoped-entries       bundle/query-scoped-entries)
(def query-expiring-entries     bundle/query-expiring-entries)
(def query-regular-conventions  bundle/query-regular-conventions)
(def query-all-scoped           bundle/query-all-scoped)
(def query-catchup-bundle       bundle/query-catchup-bundle)
