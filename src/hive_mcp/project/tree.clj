(ns hive-mcp.project.tree
  "Project tree discovery and persistence for HCR Wave 2.

   Scans filesystem for .hive-project.edn files, builds parent-child
   relationships, and persists to DataScript/Datalevin for fast queries.

   - C: Composition - builds on scope.clj, hive_project.clj
   - L: Layers pure - scan logic separated from persistence
   - A: Abstractions honored - uses IGraphStore protocol
   - R: Represented intent - hierarchical project tree
   - I: Inputs guarded - validates paths and configs
   - T: Traceability - logs discovery and persistence
   - Y: Yield safe failure - graceful handling of missing/invalid configs"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datascript.core :as d]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.knowledge-graph.scope :as scope]
            [hive-weave.parallel :as wp]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; DataScript Schema for Project Hierarchy
;; =============================================================================

(def project-schema
  "DataScript schema for project hierarchy tracking.

   Attributes:
   - :project/id        - Unique project identifier (from .hive-project.edn)
   - :project/path      - Absolute filesystem path
   - :project/type      - Project type keyword (:workspace, :service, :frontend, :library)
   - :project/parent-id - Parent project ID (for hierarchy)
   - :project/tags      - Set of tags from config
   - :project/last-scanned - Timestamp of last scan
   - :project/git-root  - Git repository root (if applicable)
   - :project/config    - Full config EDN as string (for metadata)"
  {:project/id           {:db/unique :db.unique/identity
                          :db/doc "Unique project identifier"}
   :project/path         {:db/doc "Absolute filesystem path"}
   :project/type         {:db/doc "Project type: :workspace, :service, :frontend, :library, :generic"}
   :project/parent-id    {:db/doc "Parent project ID for hierarchy"}
   :project/tags         {:db/cardinality :db.cardinality/many
                          :db/doc "Tags from config"}
   :project/last-scanned {:db/doc "Timestamp of last scan (inst)"}
   :project/git-root     {:db/doc "Git repository root path"}
   :project/config       {:db/doc "Full config EDN as string"}})

;; =============================================================================
;; Connection Management (Separate from Swarm DataScript)
;; =============================================================================

;; Project tree DataScript connection.
;; Separate from swarm connection to avoid schema conflicts.
(defonce ^:private conn (atom nil))

;; Cached project tree structure.
;; Populated by scan-project-tree!, queried by get-cached-tree.
;; Structure: {:roots [...] :by-id {...} :children {...}}
;; Invalidated on rescan.
(defonce ^:private tree-cache (atom nil))

(defn- get-conn
  "Get or create the project tree DataScript connection."
  []
  (or @conn
      (do
        (reset! conn (d/create-conn project-schema))
        (log/info "Created project tree DataScript connection")
        @conn)))

(defn- transact!
  "Transact entities to project tree DataScript."
  [entities]
  (d/transact! (get-conn) entities))

(defn- query
  "Query project tree DataScript."
  ([q]
   (d/q q @(get-conn)))
  ([q & inputs]
   (apply d/q q @(get-conn) inputs)))

;; =============================================================================
;; Filesystem Discovery
;; =============================================================================

(defn- read-hive-project-edn
  "Read and parse .hive-project.edn from directory.
   Returns nil on failure or if file doesn't exist."
  [dir]
  (rescue nil
          (let [config-file (io/file dir ".hive-project.edn")]
            (when (.exists config-file)
              (-> config-file slurp edn/read-string)))))

(defn- find-git-root
  "Find git root directory from path.
   Returns absolute path or nil."
  [path]
  (let [dir (io/file path)]
    (loop [current dir]
      (when current
        (let [git-dir (io/file current ".git")]
          (if (.exists git-dir)
            (.getAbsolutePath current)
            (recur (.getParentFile current))))))))

(def ^:private skip-dirs
  "Directory names to skip during scan — heavy, never contain .hive-project.edn."
  #{"node_modules" "target" ".cpcache" ".git" ".shadow-cljs" ".clj-kondo"
    ".lsp" ".nrepl" "dist" "build" "out" "__pycache__" ".venv" "venv"
    ".gradle" ".m2" "classes" ".gitlibs" "data" "backups"})

(def ^:private default-concurrency
  "Scan parallelism bounded by host CPU count (min 2, max 16)."
  (max 2 (min 16 (.. Runtime getRuntime availableProcessors))))

(defn- scannable-child?
  "Check if a directory should be scanned (not hidden, not in skip-dirs)."
  [^java.io.File dir]
  (let [name (.getName dir)]
    (and (.isDirectory dir)
         (not (str/starts-with? name "."))
         (not (contains? skip-dirs name)))))

(defn- linked-worktree?
  "True when `dir` is a LINKED git worktree — its `.git` entry is a FILE
   holding `gitdir: ...`. The primary checkout carries a `.git` DIRECTORY."
  [^java.io.File dir]
  (let [git (io/file dir ".git")]
    (and (.exists git) (not (.isDirectory git)))))

(defn- scan-dir-shallow
  "Scan a single directory for .hive-project.edn. Non-recursive.
   Returns {:path ... :config ... :linked-worktree? bool} or nil."
  [^java.io.File dir]
  (when-let [config (read-hive-project-edn dir)]
    {:path (.getAbsolutePath dir)
     :config config
     :linked-worktree? (linked-worktree? dir)}))

(defn- scan-subtree
  "Recursively scan a subtree for .hive-project.edn files.
   Single-threaded per subtree — parallelism is at the top level."
  [^java.io.File dir max-depth current-depth]
  (when (and (<= current-depth max-depth) (scannable-child? dir))
    (let [result (scan-dir-shallow dir)
          children (when-let [files (.listFiles dir)]
                     (->> files
                          (filter scannable-child?)
                          (mapcat #(scan-subtree % max-depth (inc current-depth)))))]
      (if result (cons result children) children))))

(defn- discovery-rank
  "Sort key electing the winner among discoveries sharing one :project-id.
   Primary checkouts before linked worktrees, then shortest path, then
   lexicographic. Total and deterministic."
  [{:keys [path linked-worktree?]}]
  [(if linked-worktree? 1 0) (count (str path)) (str path)])

(defn- dedupe-by-project-id
  "Elect one discovery per :project-id.
   Returns {:kept [...] :shadowed [...]}; `shadowed` names every discovery a
   duplicate id displaced so the caller can report it instead of losing it.
   Discoveries carrying no :project-id pass through untouched. Pure."
  [discovered]
  (let [elect (fn [[pid ds]]
                (if (nil? pid)
                  {:kept (vec ds) :shadowed []}
                  (let [[win & lose] (sort-by discovery-rank ds)]
                    {:kept [win] :shadowed (vec lose)})))
        parts (map elect (group-by #(get-in % [:config :project-id]) discovered))]
    {:kept     (vec (mapcat :kept parts))
     :shadowed (vec (mapcat :shadowed parts))}))

(defn- discover-hive-projects
  "Discover .hive-project.edn files from root-path using parallel scanning.

   Strategy: scan root, then fan-out child directories via bounded-pmap
   (hive-weave). Each subtree is scanned single-threaded; parallelism
   is across sibling directories.

   Args:
     root-path - Starting directory for scan
     max-depth - Maximum directory depth (default 5)"
  [root-path & [{:keys [max-depth] :or {max-depth 5}}]]
  (let [root (io/file root-path)]
    (when (.isDirectory root)
      (let [root-result (scan-dir-shallow root)
            children (->> (.listFiles root)
                          (filter scannable-child?)
                          vec)
            child-results (wp/bounded-pmap
                            {:concurrency default-concurrency :timeout-ms 60000 :fallback []}
                            (fn [child] (vec (scan-subtree child max-depth 1)))
                            children)]
        (let [all (cond-> (vec (mapcat identity child-results))
                    root-result (conj root-result))
              {:keys [kept shadowed]} (dedupe-by-project-id all)]
          (doseq [{:keys [path config]} shadowed]
            (log/warn "Duplicate project-id; ignoring shadowed checkout"
                      {:project-id (:project-id config) :shadowed-path path}))
          kept)))))

;; =============================================================================
;; Project Entity Building
;; =============================================================================

(defn- unrendered-template?
  "Detects unrendered Mustache/Handlebars placeholders (e.g. {{artifact/id}})
   leaking in from template skeletons under resources/."
  [s]
  (and (string? s) (str/includes? s "{{")))

(defn- config->entity
  "Convert discovered project config to DataScript entity.
   Includes hierarchy info from :parent-id.
   Skips unrendered template placeholders."
  [{:keys [path config]}]
  (let [project-id (:project-id config)
        parent-id (or (:parent-id config) (:parent config))
        project-type (or (:project-type config) :generic)
        tags (vec (or (:tags config) []))]
    (when (and project-id (not (unrendered-template? project-id)))
      (cond-> {:project/id project-id
               :project/path path
               :project/type (if (keyword? project-type)
                               project-type
                               (keyword project-type))
               :project/last-scanned (java.util.Date.)
               :project/config (pr-str config)}
        parent-id (assoc :project/parent-id (str parent-id))
        (seq tags) (assoc :project/tags tags)
        (find-git-root path) (assoc :project/git-root (find-git-root path))))))

;; =============================================================================
;; Tree Building
;; =============================================================================

(defn build-project-tree
  "Build hierarchical tree from flat list of project entities.
   Returns {:roots [...] :by-id {...} :children {...}}

   - :roots    - Projects without parents
   - :by-id    - Map of project-id to entity
   - :children - Map of project-id to seq of child IDs"
  [entities]
  (let [by-id (into {} (map (fn [e] [(:project/id e) e]) entities))
        children (reduce (fn [acc e]
                           (if-let [parent-id (:project/parent-id e)]
                             (update acc parent-id (fnil conj []) (:project/id e))
                             acc))
                         {}
                         entities)
        roots (filter #(nil? (:project/parent-id %)) entities)]
    {:roots (mapv :project/id roots)
     :by-id by-id
     :children children}))

(defn get-descendants
  "Get all descendant project IDs for a given project.
   Uses tree structure for efficient traversal."
  [{:keys [children]} project-id]
  (loop [queue (vec (get children project-id []))
         result []]
    (if (empty? queue)
      result
      (let [child-id (first queue)
            grandchildren (get children child-id [])]
        (recur (into (vec (rest queue)) grandchildren)
               (conj result child-id))))))

(defn get-ancestors
  "Get all ancestor project IDs for a given project.
   Uses tree structure for efficient traversal."
  [{:keys [by-id]} project-id]
  (loop [current-id project-id
         result []]
    (if-let [entity (get by-id current-id)]
      (if-let [parent-id (:project/parent-id entity)]
        (recur parent-id (conj result parent-id))
        result)
      result)))

;; =============================================================================
;; Persistence
;; =============================================================================

(defn persist-project-entities!
  "Persist project entities to DataScript.
   Uses upsert semantics - existing projects are updated."
  [entities]
  (when (seq entities)
    (try
      ;; Transact to local project tree DataScript
      (transact! entities)
      (log/info "Persisted" (count entities) "project entities to DataScript")
      {:success true :count (count entities)}
      (catch Exception e
        (log/error "Failed to persist project entities:" (.getMessage e))
        {:success false :error (.getMessage e)}))))

(defn query-all-projects
  "Query all project entities from DataScript."
  []
  (rescue []
          (query '[:find [(pull ?e [*]) ...]
                   :where [?e :project/id _]])))

(defn query-project-by-id
  "Query a single project by ID."
  [project-id]
  (first
   (query '[:find [(pull ?e [*]) ...]
            :in $ ?pid
            :where [?e :project/id ?pid]]
          project-id)))

(defn query-project-children
  "Query direct children of a project."
  [project-id]
  (query '[:find [(pull ?e [*]) ...]
           :in $ ?parent-id
           :where [?e :project/parent-id ?parent-id]]
         project-id))

;; =============================================================================
;; Tree Cache (HCR Wave 5: Avoid re-traversal)
;; =============================================================================

(defn get-cached-tree
  "Get the cached project tree, rebuilding from DataScript if cache is empty.
   Returns {:roots [...] :by-id {...} :children {...}} or nil if no data."
  []
  (or @tree-cache
      (let [projects (query-all-projects)]
        (when (seq projects)
          (let [tree (build-project-tree projects)]
            (reset! tree-cache tree)
            tree)))))

(defn invalidate-tree-cache!
  "Clear the cached tree. Called on rescan or when hierarchy changes."
  []
  (reset! tree-cache nil))

(defn has-children?
  "Check if a project has any children in the hierarchy.
   Uses cached tree for O(1) lookup."
  [project-id]
  (when-let [tree (get-cached-tree)]
    (boolean (seq (get (:children tree) project-id)))))

(defn get-descendant-ids
  "Get all descendant project IDs as a set.
   Uses cached tree for efficient traversal.

   Example:
     (get-descendant-ids \"hive\")
     => #{\"hive-mcp\" \"hive-agent-bridge\"}

   Returns empty set if project has no children or tree not populated."
  [project-id]
  (if-let [tree (get-cached-tree)]
    (set (get-descendants tree project-id))
    #{}))

;; =============================================================================
;; HCR Wave 3: Descendant Scope Tags
;; =============================================================================

(defn get-descendant-scope-tags
  "Get all descendant scope tags for a given project-id.

   HCR Wave 3+5: Uses cached tree for O(1) lookup instead of re-querying DataScript.
   This is the inverse of visible-scope-tags (which goes UP to ancestors).

   Args:
     project-id - The project to get descendants for

   Returns:
     Set of scope tags like #{\"scope:project:child1\" \"scope:project:child2\"}
     Returns empty set if project has no children or doesn't exist.

   Example:
     (get-descendant-scope-tags \"hive-mcp\")
     => #{\"scope:project:hive-agent-bridge\"}"
  [project-id]
  (when (and project-id (not= project-id "global"))
    (set (map #(str "scope:project:" %) (get-descendant-ids project-id)))))

(defn get-descendant-scopes
  "Get all descendant project IDs for a given project-id.

   HCR Wave 3+5: Uses cached tree for O(1) lookup instead of re-querying DataScript.
   Lower-level function that returns raw project IDs, not scope tags.
   Use get-descendant-scope-tags for memory query filtering.

   Args:
     project-id - The project to get descendants for

   Returns:
     Vector of project IDs like [\"child1\" \"child2\" \"grandchild1\"]
     Returns empty vector if project has no children."
  [project-id]
  (when (and project-id (not= project-id "global"))
    (vec (get-descendant-ids project-id))))

;; =============================================================================
;; Main Scan Function
;; =============================================================================

(defn scan-project-tree!
  "Scan filesystem for project hierarchy and persist to DataScript.

   This is the main entry point for HCR Wave 2 project discovery.

   Args:
     root-path - Starting directory for scan
     opts      - Options map:
       :max-depth  - Maximum scan depth (default 5)
       :force      - Re-scan even if recently scanned (default false)

   Returns:
     {:success true/false
      :projects-found N
      :roots [project-ids...]
      :tree {...}}

   Side effects:
     - Persists project entities to DataScript
     - Registers configs in scope.clj cache"
  [root-path & [{:keys [max-depth] :or {max-depth 5}}]]
  (log/info "Scanning project tree from:" root-path {:max-depth max-depth})
  (try
    (let [;; Discover all .hive-project.edn files
          discovered (discover-hive-projects root-path {:max-depth max-depth})
          _ (log/debug "Discovered" (count discovered) "projects")

          ;; Convert to entities
          entities (->> discovered
                        (map config->entity)
                        (filter some?))

          ;; Build tree structure and cache for cheap subsequent lookups
          tree (build-project-tree entities)
          _ (reset! tree-cache tree)

          ;; Register configs in scope cache for HCR resolution
          _ (doseq [{:keys [config]} discovered]
              (when-let [project-id (:project-id config)]
                (scope/register-project-config! project-id config)))

          ;; Persist to DataScript
          persist-result (persist-project-entities! entities)]

      (if (:success persist-result)
        {:success true
         :projects-found (count entities)
         :roots (:roots tree)
         :tree tree
         :scan-time (str (Instant/now))}
        {:success false
         :error (:error persist-result)
         :projects-found (count entities)}))

    (catch Exception e
      (log/error "Project tree scan failed:" (.getMessage e))
      {:success false
       :error (.getMessage e)})))

;; =============================================================================
;; Staleness Check for Catchup
;; =============================================================================

(def ^:private staleness-threshold-hours
  "Hours after which project tree is considered stale."
  24)

(defn tree-stale?
  "Check if project tree needs re-scanning.

   Returns true if:
   - No projects in DataScript
   - Any project was scanned > staleness-threshold-hours ago
   - Root path doesn't match any existing project paths"
  [root-path]
  (rescue true
          (let [projects (query-all-projects)]
            (cond
        ;; No projects - definitely stale
              (empty? projects)
              true

        ;; Check if root path is covered
              (not-any? #(str/starts-with? (:project/path %) root-path) projects)
              true

        ;; Check timestamp staleness
              :else
              (let [now (System/currentTimeMillis)
                    threshold-ms (* staleness-threshold-hours 60 60 1000)]
                (some (fn [p]
                        (when-let [scanned (:project/last-scanned p)]
                          (> (- now (.getTime scanned)) threshold-ms)))
                      projects))))))

(defn maybe-scan-project-tree!
  "Scan project tree if stale.
   Called from catchup to ensure hierarchy is fresh.

   Returns {:scanned true/false :result ...}"
  [root-path]
  (if (tree-stale? root-path)
    (do
      (log/info "Project tree stale, rescanning:" root-path)
      {:scanned true
       :result (scan-project-tree! root-path)})
    {:scanned false
     :reason "Project tree is fresh"}))
