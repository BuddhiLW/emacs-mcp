(ns hive-mcp.tools.kg
  "MCP tool handlers for Knowledge Graph operations.

   Thin facade dispatching to sub-namespaces:
   - kg.queries:  Read-only operations (traverse, impact, path, subgraph, etc.)
   - kg.commands: Write/mutate operations (edge, promote, reground, backfill)

   Also hosts versioning (Yggdrasil) and migration handlers directly,
   as these are smaller domains not yet warranting separate namespaces.

   CQRS:    Queries and commands cleanly separated.

   Result DSL: Internal logic returns Result maps ({:ok val} or {:error category}).
   Single try-result boundary at each handler level. Zero nested try-catch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.tools.kg.queries :as kg-queries]
            [hive-mcp.tools.kg.commands :as kg-commands]
            [hive-mcp.knowledge-graph.versioning :as versioning]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Re-exports from sub-namespaces (preserve public API)
;;; =============================================================================

;; Query handlers
(def handle-kg-traverse        kg-queries/handle-kg-traverse)
(def handle-kg-impact-analysis kg-queries/handle-kg-impact-analysis)
(def handle-kg-find-path       kg-queries/handle-kg-find-path)
(def handle-kg-subgraph        kg-queries/handle-kg-subgraph)
(def handle-kg-contradictions  kg-queries/handle-kg-contradictions)
(def handle-kg-node-context    kg-queries/handle-kg-node-context)
(def handle-kg-stats           kg-queries/handle-kg-stats)

;; Command handlers
(def handle-kg-add-edge            kg-commands/handle-kg-add-edge)
(def handle-kg-promote             kg-commands/handle-kg-promote)
(def handle-kg-reground            kg-commands/handle-kg-reground)
(def handle-kg-backfill-grounding  kg-commands/handle-kg-backfill-grounding)
(def handle-kg-cleanup-synthetics  kg-commands/handle-kg-cleanup-synthetics)

;;; =============================================================================
;;; Tool Definitions (merged from sub-namespaces)
;;; =============================================================================

(def tools
  "Core KG tools (queries + commands)."
  (into kg-queries/query-tools kg-commands/command-tools))

;;; =============================================================================
;;; Result DSL Helpers (boundary pattern)
;;; =============================================================================

(defn- require-versioning
  "Validate versioning is available. Returns Result."
  []
  (if (versioning/versioning-available?)
    (result/ok true)
    (result/err :kg/versioning-unavailable
                {:message "Versioning not available. Ensure Datahike backend is configured and Yggdrasil is on classpath."})))

(defn- require-non-empty
  "Validate a string parameter is non-nil and non-empty. Returns Result."
  [value param-name]
  (if (or (nil? value) (and (string? value) (empty? value)))
    (result/err :kg/validation-failed
                {:message (str param-name " is required")})
    (result/ok value)))

(defn- try-result
  "Execute thunk f returning Result; catch unexpected exceptions as error Result.
   Unlike try-effect, expects f to return a Result map directly."
  [category f]
  (try
    (f)
    (catch Exception e
      (log/error e (str (name category) " failed"))
      (result/err category {:message (.getMessage e)}))))

(defn- result->mcp
  "Convert Result to MCP response.
   {:ok data} -> (mcp-json data), {:error ...} -> (mcp-error message)."
  [r]
  (if (result/ok? r)
    (mcp-json (:ok r))
    (mcp-error (or (:message r) (:cause r) (str (:error r))))))

;;; =============================================================================
;;; Versioning Logic (pure Result-returning functions)
;;; =============================================================================

(defn- branch* [{:keys [name from]}]
  (result/let-ok [_ (require-versioning)
                  _ (require-non-empty name "name")]
    (let [branch-kw (keyword name)
          r (if from
              (versioning/branch! branch-kw from)
              (versioning/branch! branch-kw))]
      (if r
        (result/ok {:success true
                    :branch (clojure.core/name branch-kw)
                    :from (or from "current")
                    :message (str "Created branch " name)})
        (result/err :kg/branch-failed
                    {:message (str "Failed to create branch " name)})))))

(defn- checkout* [{:keys [name]}]
  (result/let-ok [_ (require-versioning)
                  _ (require-non-empty name "name")]
    (let [branch-kw (keyword name)
          r (versioning/checkout branch-kw)]
      (if r
        (result/ok {:success true
                    :branch (clojure.core/name branch-kw)
                    :snapshot-id (versioning/snapshot-id)
                    :message (str "Switched to branch " name)})
        (result/err :kg/checkout-failed
                    {:message (str "Failed to checkout branch " name ". Branch may not exist.")})))))

(defn- branches* [_]
  (result/let-ok [_ (require-versioning)]
    (let [branches (versioning/branches)
          current (versioning/current-branch)]
      (result/ok {:success true
                  :current-branch (when current (clojure.core/name current))
                  :branches (if branches (mapv clojure.core/name branches) [])
                  :count (count (or branches []))}))))

(defn- snapshot-id* [_]
  (result/let-ok [_ (require-versioning)]
    (let [snap-id (versioning/snapshot-id)
          parent-ids (versioning/parent-ids)
          branch (versioning/current-branch)]
      (result/ok {:success true
                  :snapshot-id snap-id
                  :parent-ids (vec (or parent-ids []))
                  :branch (when branch (clojure.core/name branch))}))))

(defn- history* [{:keys [limit]}]
  (result/let-ok [_ (require-versioning)]
    (let [opts (when limit {:limit limit})
          history (versioning/history opts)
          branch (versioning/current-branch)]
      (result/ok {:success true
                  :branch (when branch (clojure.core/name branch))
                  :count (count history)
                  :commits (vec history)}))))

(defn- merge* [{:keys [source]}]
  (result/let-ok [_ (require-versioning)
                  _ (require-non-empty source "source")]
    (let [source-val (if (and (string? source)
                              (not (re-matches #"^[0-9a-f-]{36}$" source)))
                       (keyword source)
                       source)
          r (versioning/merge! source-val)]
      (if r
        (result/ok {:success true
                    :source (str source)
                    :snapshot-id (versioning/snapshot-id)
                    :message (str "Merged " source " into current branch")})
        (result/err :kg/merge-failed
                    {:message (str "Failed to merge " source)})))))

(defn- versioning-status* [_]
  (let [status (versioning/status)]
    (result/ok {:success true
                :available (:available status)
                :system-id (:system-id status)
                :branch (when (:branch status) (clojure.core/name (:branch status)))
                :snapshot-id (:snapshot-id status)
                :branches (when (:branches status)
                            (mapv clojure.core/name (:branches status)))})))

;;; =============================================================================
;;; Versioning Tool Handlers (boundary layer)
;;; =============================================================================

(defn handle-kg-branch
  "Create a new branch in the versioned Knowledge Graph.

   Arguments:
     name - Branch name (required, e.g., 'experiment', 'agent-1-exploration')
     from - Optional source branch or snapshot-id to branch from"
  [params]
  (log/info "kg_branch" {:name (:name params) :from (:from params)})
  (result->mcp (try-result :kg/branch-failed #(branch* params))))

(defn handle-kg-checkout
  "Switch to a different branch in the versioned Knowledge Graph.

   Arguments:
     name - Branch name to switch to (required)"
  [params]
  (log/info "kg_checkout" {:name (:name params)})
  (result->mcp (try-result :kg/checkout-failed #(checkout* params))))

(defn handle-kg-branches
  "List all branches in the versioned Knowledge Graph."
  [params]
  (log/info "kg_branches")
  (result->mcp (try-result :kg/branches-failed #(branches* params))))

(defn handle-kg-snapshot-id
  "Get the current commit/snapshot ID of the versioned Knowledge Graph."
  [params]
  (log/info "kg_snapshot_id")
  (result->mcp (try-result :kg/snapshot-failed #(snapshot-id* params))))

(defn handle-kg-history
  "Get commit history for the current branch.

   Arguments:
     limit - Maximum number of commits to return (optional, default: 100)"
  [params]
  (log/info "kg_history" {:limit (:limit params)})
  (result->mcp (try-result :kg/history-failed #(history* params))))

(defn handle-kg-merge
  "Merge a source branch or snapshot into the current branch.

   Arguments:
     source - Source branch name or snapshot-id to merge (required)"
  [params]
  (log/info "kg_merge" {:source (:source params)})
  (result->mcp (try-result :kg/merge-failed #(merge* params))))

(defn handle-kg-versioning-status
  "Get the current versioning status of the Knowledge Graph."
  [params]
  (log/info "kg_versioning_status")
  (result->mcp (try-result :kg/status-failed #(versioning-status* params))))

;;; =============================================================================
;;; Versioning Tool Definitions
;;; =============================================================================

(def versioning-tools
  [{:name "kg_branch"
    :description "Create a new branch in the versioned Knowledge Graph. Branches enable parallel exploration of knowledge without affecting the main timeline. Useful for multi-agent scenarios where each agent experiments independently."
    :inputSchema {:type "object"
                  :properties {"name" {:type "string"
                                       :description "Branch name (e.g., 'experiment', 'agent-1-exploration')"}
                               "from" {:type "string"
                                       :description "Source branch or snapshot-id to branch from (optional, default: current)"}}
                  :required ["name"]}
    :handler handle-kg-branch}

   {:name "kg_checkout"
    :description "Switch to a different branch in the versioned Knowledge Graph. Changes the active branch for all subsequent KG operations."
    :inputSchema {:type "object"
                  :properties {"name" {:type "string"
                                       :description "Branch name to switch to"}}
                  :required ["name"]}
    :handler handle-kg-checkout}

   {:name "kg_branches"
    :description "List all branches in the versioned Knowledge Graph. Shows which branches exist and which is currently active."
    :inputSchema {:type "object"
                  :properties {}
                  :required []}
    :handler handle-kg-branches}

   {:name "kg_snapshot_id"
    :description "Get the current commit/snapshot ID of the versioned Knowledge Graph. Returns the unique identifier for the current state, useful for bookmarking or branching from specific points."
    :inputSchema {:type "object"
                  :properties {}
                  :required []}
    :handler handle-kg-snapshot-id}

   {:name "kg_history"
    :description "Get commit history for the current branch. Returns chronological list of snapshot IDs, newest first."
    :inputSchema {:type "object"
                  :properties {"limit" {:type "integer"
                                        :description "Maximum commits to return (optional, default: 100)"}}
                  :required []}
    :handler handle-kg-history}

   {:name "kg_merge"
    :description "Merge a source branch or snapshot into the current branch. Combines knowledge from parallel exploration timelines."
    :inputSchema {:type "object"
                  :properties {"source" {:type "string"
                                         :description "Source branch name or snapshot-id to merge"}}
                  :required ["source"]}
    :handler handle-kg-merge}

   {:name "kg_versioning_status"
    :description "Get the current versioning status of the Knowledge Graph. Shows whether versioning is available, current branch, snapshot, and all branches."
    :inputSchema {:type "object"
                  :properties {}
                  :required []}
    :handler handle-kg-versioning-status}])

;;; =============================================================================
;;; Migration Logic (pure Result-returning functions)
;;; =============================================================================

(defn- migrate* [{:keys [source_backend target_backend dry_run export_path target_db_path]}]
  (require 'hive-mcp.knowledge-graph.migration)
  (let [migrate-fn (resolve 'hive-mcp.knowledge-graph.migration/migrate-store!)
        source-kw (keyword source_backend)
        target-kw (keyword target_backend)
        target-opts (when target_db_path {:db-path target_db_path})
        r (migrate-fn source-kw target-kw
                       {:target-opts target-opts
                        :dry-run (boolean dry_run)
                        :export-path export_path})]
    (result/ok {:success true
                :source-backend (name source-kw)
                :target-backend (name target-kw)
                :dry-run (:dry-run r)
                :exported (:exported r)
                :imported (:imported r)
                :validation (:validation r)
                :errors (when (seq (:errors r))
                          (count (:errors r)))})))

(defn- export* [{:keys [path]}]
  (require 'hive-mcp.knowledge-graph.migration)
  (let [export-fn (resolve 'hive-mcp.knowledge-graph.migration/export-to-file!)
        r (export-fn path)]
    (result/ok {:success true
                :path path
                :counts (:counts r)
                :exported-at (str (:exported-at r))})))

(defn- import* [{:keys [path]}]
  (require 'hive-mcp.knowledge-graph.migration)
  (let [import-fn (resolve 'hive-mcp.knowledge-graph.migration/import-from-file!)
        r (import-fn path)]
    (result/ok {:success true
                :path path
                :imported (:imported r)
                :errors (when (seq (:errors r))
                          {:count (count (:errors r))
                           :first-error (first (:errors r))})})))

(defn- validate-migration* [{:keys [expected_edges expected_disc expected_synthetic]}]
  (require 'hive-mcp.knowledge-graph.migration)
  (let [validate-fn (resolve 'hive-mcp.knowledge-graph.migration/validate-migration)
        expected {:edges (or expected_edges 0)
                  :disc (or expected_disc 0)
                  :synthetic (or expected_synthetic 0)}
        r (validate-fn expected)]
    (result/ok {:success true
                :valid (:valid? r)
                :expected (:expected r)
                :actual (:actual r)
                :missing (:missing r)})))

;;; =============================================================================
;;; Migration Tool Handlers (boundary layer)
;;; =============================================================================

(defn handle-kg-migrate
  "Migrate KG data from one backend to another."
  [params]
  (log/info "kg_migrate" {:source (:source_backend params) :target (:target_backend params) :dry-run (:dry_run params)})
  (result->mcp (try-result :kg/migrate-failed #(migrate* params))))

(defn handle-kg-export
  "Export KG data to EDN file for backup or migration."
  [params]
  (log/info "kg_export" {:path (:path params)})
  (result->mcp (try-result :kg/export-failed #(export* params))))

(defn handle-kg-import
  "Import KG data from EDN file."
  [params]
  (log/info "kg_import" {:path (:path params)})
  (result->mcp (try-result :kg/import-failed #(import* params))))

(defn handle-kg-validate-migration
  "Validate migration by comparing expected vs actual entity counts."
  [params]
  (log/info "kg_validate_migration" {:edges (:expected_edges params) :disc (:expected_disc params) :synthetic (:expected_synthetic params)})
  (result->mcp (try-result :kg/validate-migration-failed #(validate-migration* params))))

;;; =============================================================================
;;; Migration Tool Definitions
;;; =============================================================================

(def migration-tools
  [{:name "kg_migrate"
    :description "Migrate Knowledge Graph data from one backend to another. Supports DataScript (in-memory), Datalevin (persistent), and Datahike (time-travel). Use dry_run=true to preview migration without executing."
    :inputSchema {:type "object"
                  :properties {"source_backend" {:type "string"
                                                 :enum ["datascript" "datalevin" "datahike"]
                                                 :description "Current backend to migrate FROM"}
                               "target_backend" {:type "string"
                                                 :enum ["datascript" "datalevin" "datahike"]
                                                 :description "Target backend to migrate TO"}
                               "dry_run" {:type "boolean"
                                          :description "Preview migration without executing (default: false)"}
                               "export_path" {:type "string"
                                              :description "Optional path to save EDN backup during migration"}
                               "target_db_path" {:type "string"
                                                 :description "Optional storage path for target backend"}}
                  :required ["source_backend" "target_backend"]}
    :handler handle-kg-migrate}

   {:name "kg_export"
    :description "Export all Knowledge Graph data (edges, disc entities, synthetic nodes) to an EDN file. Useful for backup or migration between environments."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "File path to save the EDN export"}}
                  :required ["path"]}
    :handler handle-kg-export}

   {:name "kg_import"
    :description "Import Knowledge Graph data from an EDN file. Use after kg_export to restore or migrate data."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "File path to the EDN export file"}}
                  :required ["path"]}
    :handler handle-kg-import}

   {:name "kg_validate_migration"
    :description "Validate that a migration completed successfully by comparing expected vs actual entity counts. Use after kg_import to verify data integrity."
    :inputSchema {:type "object"
                  :properties {"expected_edges" {:type "integer"
                                                 :description "Expected number of edges"}
                               "expected_disc" {:type "integer"
                                                :description "Expected number of disc entities"}
                               "expected_synthetic" {:type "integer"
                                                     :description "Expected number of synthetic nodes"}}
                  :required []}
    :handler handle-kg-validate-migration}])

;;; =============================================================================
;;; Combined Tool Export
;;; =============================================================================

(def all-tools
  "All KG tools including versioning and migration tools."
  (-> tools
      (into versioning-tools)
      (into migration-tools)))