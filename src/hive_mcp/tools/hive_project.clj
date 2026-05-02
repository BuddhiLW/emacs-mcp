(ns hive-mcp.tools.hive-project
  "MCP tool for auto-generating .hive-project.edn configuration.

   - C: Composition - builds on projectile_info for detection
   - L: Layers pure - inference logic separated from I/O
   - I: Inputs guarded - validates directory exists
   - Y: Yield safe failure - graceful errors on write failure

   Generates project-specific config:
   - :project-id - stable identifier (survives directory renames)
   - :project-type - detected project type as keyword
   - :watch-dirs - directories to watch for hot-reload
   - :hot-reload - enabled by default for Clojure
   - :presets-path - project-local presets directory"
  (:require [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.emacs-ext.elisp :as el]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.config.core :as config]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [hive-mcp.dns.result :refer [rescue]]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]
           [java.time Instant]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Project Type Inference
;; =============================================================================

(def ^:private type->watch-dirs
  "Map of project types to watch directories for hot-reload.
   Includes test/dev dirs where applicable for Clojure projects."
  {"clojure"     ["src" "test" "dev"]
   "lein"        ["src" "test" "dev"]
   "deps.edn"    ["src" "test" "dev"]
   "clojure-cli" ["src" "test" "dev"]
   "shadow-cljs" ["src" "test" "dev"]
   "npm"         ["src" "lib"]
   "yarn"        ["src" "lib"]
   "pnpm"        ["src" "lib"]
   "cargo"       ["src"]
   "go-mod"      ["cmd" "pkg" "internal"]
   "maven"       ["src/main/java" "src/main/resources"]
   "gradle"      ["src/main/java" "src/main/kotlin"]
   "python"      ["src" "lib"]
   "poetry"      ["src" "lib"]
   "pipenv"      ["src" "lib"]
   "mix"         ["lib"]
   "rebar3"      ["src"]
   "cmake"       ["src"]
   "meson"       ["src"]
   "make"        ["src"]
   "generic"     ["src"]})

(def ^:private clojure-types
  "Project types that benefit from hot-reload."
  #{"clojure" "clojure-cli" "lein" "deps.edn" "shadow-cljs"})

(defn- infer-watch-dirs
  "Infer watch directories based on project type.
   Returns directories to watch for hot-reload."
  [project-type]
  (get type->watch-dirs project-type ["src"]))

(defn- infer-hot-reload?
  "Determine if hot-reload should be enabled by default."
  [project-type]
  (contains? clojure-types project-type))

;; =============================================================================
;; Project ID Generation
;; =============================================================================

(defn- generate-base-project-id
  "Generate a base project ID from project name.
   Format: <name>-<short-hash>
   The hash is based on name + creation timestamp for uniqueness."
  [project-name]
  (let [timestamp (.toString (Instant/now))
        raw-str (str project-name "-" timestamp)
        md (MessageDigest/getInstance "SHA-1")
        hash-bytes (.digest md (.getBytes raw-str "UTF-8"))
        hash-hex (apply str (map #(format "%02x" %) (take 4 hash-bytes)))]
    (str (str/lower-case (str/replace project-name #"[^a-zA-Z0-9]+" "-"))
         "-" hash-hex)))

;; =============================================================================
;; Parent Project Discovery
;; =============================================================================

(defn- read-parent-project-id
  "Read :project-id from a .hive-project.edn file.
   Returns the project-id string or nil on failure."
  [hive-project-file]
  (rescue nil
          (let [content (slurp hive-project-file)
                config (read-string content)]
            (:project-id config))))

(defn- find-parent-project-id
  "Walk up directories from given path looking for parent .hive-project.edn.
   Stops at filesystem root or user home directory.
   Returns the parent's :project-id if found, nil otherwise."
  [project-dir]
  (let [home-dir (System/getProperty "user.home")
        start-dir (io/file project-dir)]
    (loop [current (.getParentFile start-dir)]
      (cond
        ;; No more parents - reached root
        (nil? current)
        nil

        ;; Stop at home directory (don't traverse above)
        (= (.getAbsolutePath current) home-dir)
        (let [hive-file (io/file current ".hive-project.edn")]
          (when (.exists hive-file)
            (read-parent-project-id hive-file)))

        ;; Check for .hive-project.edn in this directory
        :else
        (let [hive-file (io/file current ".hive-project.edn")]
          (if (.exists hive-file)
            (read-parent-project-id hive-file)
            (recur (.getParentFile current))))))))

(defn- generate-project-id
  "Generate a hierarchical project ID.
   If a parent project exists, returns <parent-id>:<child-id>.
   Otherwise returns just <child-id>."
  [project-name project-dir]
  (let [base-id (generate-base-project-id project-name)
        parent-id (find-parent-project-id project-dir)]
    (if parent-id
      (str parent-id ":" base-id)
      base-id)))

;; =============================================================================
;; EDN Generation
;; =============================================================================

(defn- format-edn-value
  "Format a value for EDN output."
  [v]
  (cond
    (string? v) (str "\"" v "\"")
    (keyword? v) (str v)
    (boolean? v) (if v "true" "false")
    (vector? v) (str "[" (str/join " " (map format-edn-value v)) "]")
    (map? v) (str "{"
                  (str/join "\n "
                            (map (fn [[k vv]]
                                   (str (format-edn-value k) " " (format-edn-value vv)))
                                 v))
                  "}")
    :else (pr-str v)))

(defn- generate-edn-content
  "Generate .hive-project.edn content as formatted string.
   Includes both :parent-id (preferred) and :parent (legacy) for compatibility."
  [{:keys [project-id parent project-type watch-dirs hot-reload presets-path aliases]}]
  (let [lines [(str ";; hive-mcp project configuration")
               (str ";; Generated: " (.toString (Instant/now)))
               (str ";; See: https://github.com/BuddhiLW/hive-mcp")
               ""
               (str "{:project-id \"" project-id "\"")
               ""
               (str " ;; Parent project ID (for hierarchical scoping in Knowledge Graph)")
               (str " ;; Knowledge Graph uses this for scope inheritance: child sees parent knowledge")
               (str " :parent-id " (if parent
                                     (format-edn-value parent)
                                     "nil"))
               ""
               (str " ;; Legacy field (deprecated - use :parent-id)")
               (str " :parent " (if parent
                                  (format-edn-value parent)
                                  "nil"))
               ""
               (str " ;; Detected project type")
               (str " :project-type " (format-edn-value project-type))
               ""
               (str " ;; Watch directories for hot-reload")
               (str " :watch-dirs " (format-edn-value watch-dirs))
               ""
               (str " ;; Enable/disable hot-reload")
               (str " :hot-reload " (if hot-reload "true" "false"))
               ""
               (str " ;; Project-local presets directory (optional)")
               (str " :presets-path " (if presets-path
                                        (format-edn-value presets-path)
                                        "nil"))]]
    (str (str/join "\n" lines)
         (when (seq aliases)
           (str "\n\n ;; Previous project IDs for migration\n"
                " :aliases " (format-edn-value aliases)))
         "}\n")))

;; =============================================================================
;; Native Project Type Detection (No Emacs/Projectile Required)
;; =============================================================================

(def ^:private marker-file->type
  "Map of marker files to project type strings.
   Checked in priority order (first match wins).
   Only checked when .git directory exists (confirmed project root)."
  [["deps.edn"       "clojure-cli"]
   ["project.clj"    "lein"]
   ["shadow-cljs.edn" "shadow-cljs"]
   ["package.json"   "npm"]
   ["Cargo.toml"     "cargo"]
   ["go.mod"         "go-mod"]
   ["pom.xml"        "maven"]
   ["build.gradle"   "gradle"]
   ["build.gradle.kts" "gradle"]
   ["pyproject.toml" "python"]
   ["setup.py"       "python"]
   ["Pipfile"        "pipenv"]
   ["mix.exs"        "mix"]
   ["rebar.config"   "rebar3"]
   ["CMakeLists.txt" "cmake"]
   ["meson.build"    "meson"]
   ["Makefile"       "make"]])

(defn detect-project-type-native
  "Detect project type by checking for marker files in directory.
   No Emacs/Projectile dependency — pure filesystem checks.

   Requires .git directory to confirm this is a project root.
   Returns project type string (e.g. 'clojure-cli', 'npm', 'cargo')
   or 'generic' if .git exists but no known marker files found.
   Returns nil if directory doesn't exist or has no .git."

  [directory]
  (when directory
    (let [dir (io/file directory)]
      (when (and (.exists dir) (.isDirectory dir))
        (let [git-dir (io/file dir ".git")]
          (when (.exists git-dir)
            (or (some (fn [[marker-file project-type]]
                        (when (.exists (io/file dir marker-file))
                          project-type))
                      marker-file->type)
                "generic")))))))

(defn generate-hive-project-headless!
  "Generate .hive-project.edn without Emacs dependency.
   Uses native project type detection and config.clj for parent rules.

   INVARIANT: NEVER overwrites existing .hive-project.edn.
   project-id = directory basename (NOT hash) for Chroma backward-compat.
   parent-id resolved via config.clj's get-parent-for-path.


   Returns:
   - {:success true :path ... :config ...} on successful generation
   - {:skipped true :reason ...} if file exists or detection fails
   - nil on error"
  [directory]
  (rescue nil
          (let [dir (io/file directory)
                config-path (io/file dir ".hive-project.edn")]
      ;; INVARIANT: Never overwrite existing
            (if (.exists config-path)
              {:skipped true
               :reason "existing"
               :path (.getAbsolutePath config-path)}

        ;; Detect project type natively
              (if-let [project-type (detect-project-type-native directory)]
                (let [;; project-id = directory basename (for Chroma compat)
                      project-id (.getName dir)
                ;; Resolve parent via config.clj parent-rules
                      parent-id (config/get-parent-for-path (.getAbsolutePath dir))
                ;; Build config
                      watch-dirs (infer-watch-dirs project-type)
                      hot-reload (infer-hot-reload? project-type)
                      edn-config {:project-id project-id
                                  :parent parent-id
                                  :project-type (keyword project-type)
                                  :watch-dirs watch-dirs
                                  :hot-reload hot-reload
                                  :presets-path (when hot-reload ".hive/presets")}
                      edn-content (generate-edn-content edn-config)]
            ;; Write file
                  (spit (.getAbsolutePath config-path) edn-content)
                  (log/info "Generated .hive-project.edn (headless):"
                            (.getAbsolutePath config-path)
                            {:project-id project-id
                             :project-type project-type
                             :parent-id parent-id})
                  {:success true
                   :path (.getAbsolutePath config-path)
                   :config edn-config})

          ;; No .git found or not a valid project
                {:skipped true
                 :reason "no-git"
                 :path (.getAbsolutePath dir)})))))

;; =============================================================================
;; Startup Scan: Find and Generate Missing .hive-project.edn
;; =============================================================================

(defn- find-git-dirs-without-edn
  "Walk project-roots and find directories containing .git but no .hive-project.edn.
   Only scans one level deep (direct children of project-roots).
   Returns a sequence of directory File objects."
  [project-roots]
  (->> project-roots
       (mapcat (fn [root]
                 (let [root-file (io/file root)]
                   (when (and (.exists root-file) (.isDirectory root-file))
                     (->> (.listFiles root-file)
                          (filter #(.isDirectory %))
                          (filter #(.exists (io/file % ".git")))
                          (remove #(.exists (io/file % ".hive-project.edn"))))))))
       (distinct)))

(defn scan-and-generate-missing!
  "Scan project-roots from config.clj and generate .hive-project.edn
   for any .git directory that lacks one.

   Designed for server.clj Phase 5.5 (after services, before hot-reload).
   Uses config/get-project-roots for root directories.

   Returns {:scanned N :generated N :skipped N :errors N :details [...]}"
  []
  (let [roots (config/get-project-roots)]
    (if (empty? roots)
      (do
        (log/info "scan-and-generate-missing!: no project-roots configured, skipping")
        {:scanned 0 :generated 0 :skipped 0 :errors 0 :details []})

      (let [dirs (find-git-dirs-without-edn roots)
            results (doall
                     (for [dir dirs]
                       (let [path (.getAbsolutePath dir)
                             result (generate-hive-project-headless! path)]
                         (merge {:directory path} result))))
            generated (count (filter :success results))
            skipped (count (filter :skipped results))
            errors (count (filter nil? results))]
        (log/info "scan-and-generate-missing! complete:"
                  {:scanned (count dirs)
                   :generated generated
                   :skipped skipped
                   :errors errors})
        {:scanned (count dirs)
         :generated generated
         :skipped skipped
         :errors errors
         :details results}))))

;; =============================================================================
;; Projectile Integration
;; =============================================================================

(defn- get-projectile-info
  "Get project info from Projectile via Emacs.
   Returns {:name \"...\" :root \"...\" :type \"...\"} or nil on error."
  [directory]
  (let [elisp (if directory
                (format "(let ((default-directory %s))
                          (require 'hive-mcp-projectile)
                          (hive-mcp-projectile-api-project-info))"
                        (pr-str (str directory "/")))
                (el/require-and-call-json 'hive-mcp-projectile
                                          'hive-mcp-projectile-api-project-info))
        {:keys [success result]} (ec/eval-elisp elisp)]
    (when success
      (rescue nil
              (json/read-str result :key-fn keyword)))))

;; =============================================================================
;; Handler
;; =============================================================================

(defn handle-generate-hive-project
  "Generate .hive-project.edn based on Projectile detection.

   Parameters:
   - directory: Optional project directory (uses current project if nil)
   - force: Overwrite existing file (default: false)
   - project-id: Custom project ID (auto-generated if nil)

   CTX Migration: Uses request context for directory extraction.
   Fallback chain: explicit param → ctx binding → nil.

   Returns generated config or error."
  [{:keys [directory force project_id]}]
  ;; CTX Migration: Use request context fallback for directory
  ;; Fallback chain: explicit param → ctx binding → nil
  (let [effective-dir (or directory (ctx/current-directory))]
    (log/info "generate-hive-project" {:directory effective-dir :force force})

    ;; Get projectile info
    (if-let [proj-info (get-projectile-info effective-dir)]
      (let [{:keys [name root type]} proj-info
            project-root (or root effective-dir)
            config-path (str project-root "/.hive-project.edn")
            existing? (.exists (io/file config-path))]

        ;; Check for existing file
        (if (and existing? (not force))
          (mcp-json {:error "File already exists. Use force=true to overwrite."
                     :path config-path
                     :hint "Existing .hive-project.edn found"})

          ;; Generate config
          (let [project-type-str (or type "generic")
                project-type-kw (keyword project-type-str)
                parent-id (find-parent-project-id project-root)
                generated-id (or project_id (generate-project-id name project-root))
                config {:project-id generated-id
                        :parent parent-id
                        :project-type project-type-kw
                        :watch-dirs (infer-watch-dirs project-type-str)
                        :hot-reload (infer-hot-reload? project-type-str)
                        :presets-path (when (infer-hot-reload? project-type-str)
                                        ".hive/presets")}
                edn-content (generate-edn-content config)]

            ;; Write file
            (try
              (spit config-path edn-content)
              (log/info "Generated .hive-project.edn at:" config-path)
              (mcp-json {:success true
                         :path config-path
                         :config config
                         :project-type project-type-str
                         :project-name name
                         :parent parent-id
                         :hierarchical (boolean parent-id)})
              (catch Exception e
                (log/error e "Failed to write .hive-project.edn")
                (mcp-error (str "Failed to write config: " (.getMessage e))))))))

      ;; No projectile info available
      (mcp-error "Could not detect project. Ensure directory is a valid project root."))))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tools
  "REMOVED: Flat hive-project tools no longer exposed. Use consolidated `project` tool."
  [])
