(ns hive-mcp.addons.manifest
  "EDN manifest parser and validator for MCP Multiplexer addons.

   Manifests describe addon metadata and configuration in a declarative
   EDN format. They are loaded from files or inline maps, validated
   against a Malli schema, and used by the multiplexer to initialize
   addons.

   Manifest Format:
     {:addon/id       \"bridge.haystack\"
      :addon/type     :mcp-bridge          ;; :native | :mcp-bridge | :external
      :addon/version  \"0.1.0\"
      :addon/init-ns  \"hive-mcp.addons.haystack\"
      :addon/init-fn  \"->haystack-bridge\"
      :addon/config   {:api-key \"${HAYSTACK_API_KEY}\"
                       :timeout-ms 30000}
      :addon/capabilities #{:tools :mcp-bridge}
      :addon/dependencies #{\"hive.memory\"}   ;; Optional: other addon IDs
      :addon/description  \"Haystack document pipeline bridge\"}

   Usage:
     (def raw (slurp \"addons/haystack.edn\"))
     (let [result (parse-manifest raw)]
       (if (:valid? result)
         (initialize-addon! (:manifest result))
         (log/error \"Invalid manifest\" (:errors result))))

   See also:
   - hive-mcp.addons.protocol — IAddon protocol definition"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-mcp.addons.protocol :as proto]
            [hive-mcp.dns.result :as r]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.util.jar JarFile]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Malli Schemas
;; =============================================================================

(def AddonType
  "Valid addon type enum — the *integration mechanism* (native Clojure addon,
   MCP bridge, or external process)."
  [:enum :native :mcp-bridge :external])

(def AddonKind
  "Whether this manifest entry is a true hive IAddon (a hive-native capability
   that implements the IAddon protocol and lives in the addon-core registry) or
   a library that merely ships a thin addon wrapper around general-purpose
   infrastructure (DB clients, terminals, external-tool wrappers, instrumentation).
   Informational — drives roster classification, NOT loading."
  [:enum :addon :library])

(def AddonId
  "Non-empty string addon identifier."
  [:string {:min 1 :max 128}])

(def SemVer
  "Semantic version string (loose — allows \"0.1.0\", \"1.0.0-SNAPSHOT\")."
  [:string {:min 1 :max 64}])

(def NamespaceName
  "Fully-qualified Clojure namespace string."
  [:string {:min 1 :max 256}])

(def FunctionName
  "Clojure function name string (unqualified)."
  [:string {:min 1 :max 128}])

(def Capability
  "A capability keyword."
  :keyword)

(def Manifest
  "Malli schema for addon manifest maps.

   Required fields:
   - :addon/id       Unique addon identifier string
   - :addon/type     Addon type (:native, :mcp-bridge, :external)
   - :addon/init-ns  Namespace containing the constructor fn
   - :addon/init-fn  Constructor function name (unqualified)

   Optional fields:
   - :addon/version       Semantic version string
   - :addon/config        Addon-specific configuration map
   - :addon/capabilities  Set of capability keywords
   - :addon/dependencies  Set of other addon IDs required
   - :addon/description   Human-readable description
   - :addon/author        Author name or org
   - :addon/license       License identifier string"
  [:map
   [:addon/id AddonId]
   [:addon/type AddonType]
   [:addon/init-ns NamespaceName]
   [:addon/init-fn FunctionName]
   [:addon/kind {:optional true} AddonKind]
   [:addon/version {:optional true} SemVer]
   [:addon/config {:optional true :default {}} [:map-of :keyword :any]]
   [:addon/capabilities {:optional true :default #{}} [:set Capability]]
   [:addon/dependencies {:optional true :default #{}} [:set AddonId]]
   [:addon/description {:optional true} [:maybe :string]]
   [:addon/author {:optional true} [:maybe :string]]
   [:addon/license {:optional true} [:maybe :string]]])

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-manifest
  "Validate a manifest map against the Malli schema.

   Returns:
     {:valid?  true/false
      :errors  nil | [{:path [...] :message \"...\"}]}

   Examples:
     (validate-manifest {:addon/id \"bridge.echo\"
                         :addon/type :mcp-bridge
                         :addon/init-ns \"hive-mcp.addons.echo\"
                         :addon/init-fn \"->echo-bridge\"})
     ;=> {:valid? true :errors nil}"
  [manifest]
  (if (m/validate Manifest manifest)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain Manifest manifest))}))

(defn valid-manifest?
  "Predicate: is the manifest map valid?"
  [manifest]
  (m/validate Manifest manifest))

;; =============================================================================
;; Parsing
;; =============================================================================

(defn parse-manifest
  "Parse an EDN string into a validated manifest map.

   Reads the EDN string, validates against the Manifest schema,
   and returns a result map.

   Arguments:
     edn-str - String containing EDN manifest data

   Returns:
     {:valid?   true/false
      :manifest <parsed-map> | nil
      :errors   nil | [{...}]
      :raw      <original-string>}

   On parse failure (malformed EDN), returns :valid? false with
   parse error in :errors."
  [edn-str]
  (let [result (r/try-effect* :manifest/parse-error
                              (let [parsed (edn/read-string edn-str)
                                    validation (validate-manifest parsed)]
                                (if (:valid? validation)
                                  {:valid?   true
                                   :manifest parsed
                                   :errors   nil
                                   :raw      edn-str}
                                  {:valid?   false
                                   :manifest nil
                                   :errors   (:errors validation)
                                   :raw      edn-str})))]
    (if (r/ok? result)
      (:ok result)
      {:valid?   false
       :manifest nil
       :errors   [{:parse-error (:message result)}]
       :raw      edn-str})))

(defn parse-manifest-file
  "Parse a manifest from an EDN file path.

   Arguments:
     path - String path to .edn manifest file

   Returns same format as parse-manifest, with :path added."
  [path]
  (let [result (r/try-effect* :manifest/file-error
                              (let [content (slurp path)
                                    parsed  (parse-manifest content)]
                                (assoc parsed :path path)))]
    (if (r/ok? result)
      (:ok result)
      {:valid?   false
       :manifest nil
       :errors   [{:file-error (:message result)}]
       :path     path})))

;; =============================================================================
;; Manifest Utilities
;; =============================================================================

(defn manifest->init-sym
  "Resolve the fully-qualified constructor symbol from a manifest.

   Returns a symbol like 'hive-mcp.addons.haystack/->haystack-bridge
   suitable for requiring-resolve."
  [manifest]
  (symbol (:addon/init-ns manifest)
          (:addon/init-fn manifest)))

(defn resolve-constructor
  "Resolve the addon constructor function from a manifest.

   Uses requiring-resolve to load the namespace and find the function.
   Returns the function or nil if not found.

   Arguments:
     manifest - Validated manifest map

   Returns:
     Function | nil"
  [manifest]
  (r/rescue nil
            (let [sym (manifest->init-sym manifest)]
              (requiring-resolve sym))))

(defn manifest-summary
  "Return a compact summary of a manifest for logging/display.

   Returns:
     {:id \"bridge.haystack\"
      :type :mcp-bridge
      :version \"0.1.0\"
      :capabilities #{:tools :mcp-bridge}
      :dependencies #{\"hive.memory\"}}"
  [manifest]
  {:id           (:addon/id manifest)
   :type         (:addon/type manifest)
   :version      (:addon/version manifest "0.0.0")
   :capabilities (or (:addon/capabilities manifest) #{})
   :dependencies (or (:addon/dependencies manifest) #{})})

(defn manifests-load-order
  "Sort manifests by dependency order (topological).

   Manifests with no dependencies come first. Cyclic dependencies
   are detected and reported as errors.

   Arguments:
     manifests - Sequence of validated manifest maps

   Returns:
     {:ordered [manifest1 manifest2 ...]
      :cycles  #{} | #{[\"a\" \"b\"]}}  ;; pairs forming cycles"
  [manifests]
  (let [id->manifest (into {} (map (juxt :addon/id identity)) manifests)
        id->deps     (into {} (map (fn [m] [(:addon/id m)
                                            (or (:addon/dependencies m) #{})])
                                   manifests))
        ;; Simple Kahn's algorithm for topological sort
        ;; in-degree[id] = number of deps id has (edges pointing into id)
        ;; Also ensure each dep d is tracked in the map (default 0)
        in-degree    (reduce-kv (fn [acc id deps]
                                  (reduce (fn [a d]
                                            (update a d (fnil identity 0)))
                                          (assoc acc id (count deps))
                                          deps))
                                {} id->deps)
        queue        (into clojure.lang.PersistentQueue/EMPTY
                           (keep (fn [[id deg]] (when (zero? deg) id)))
                           in-degree)]
    (loop [q      queue
           order  []
           remain in-degree]
      (if (empty? q)
        (let [unvisited (into #{} (remove (set (map :addon/id (map id->manifest order))))
                              (keys id->manifest))]
          {:ordered (mapv id->manifest order)
           :cycles  (if (seq unvisited) unvisited #{})})
        (let [id   (peek q)
              q    (pop q)
              ;; Find nodes that depend ON this id (reverse edges)
              dependents (->> id->deps
                              (keep (fn [[did ddeps]]
                                      (when (contains? ddeps id) did))))
              [q' remain']
              (reduce (fn [[q r] dep-id]
                        (let [new-deg (dec (get r dep-id 0))]
                          [(if (zero? new-deg) (conj q dep-id) q)
                           (assoc r dep-id new-deg)]))
                      [q remain]
                      dependents)]
          (recur q' (conj order id) remain'))))))

;; =============================================================================
;; Environment Variable Expansion
;; =============================================================================

(defn- pure-env-template?
  "True when v is a string of the exact shape \"${VAR}\" — a whole-string env-var ref.

   Mixed-template strings (e.g. \"prefix-${VAR}-suffix\") are NOT considered pure;
   they pass through untouched. Pure templates are dropped from manifest configs
   so the addon's hive-di defconfig resolves the value from the env directly,
   gaining typed coercion and blank->nil semantics."
  [v]
  (and (string? v)
       (some? (re-matches #"\s*\$\{[^}]+\}\s*" v))))

(defn strip-env-templates
  "Remove keys whose values are pure ${VAR} templates from a config map.

   Manifests historically embedded \"${MILVUS_HOST}\" placeholders that an
   ad-hoc walker re-resolved via System/getenv. Now those fields are owned
   by the addon's hive-di defconfig (typed source, blank->nil, Result errors).

   Stripping them here means resolve-<Addon>Config sees no override for the
   pure-template fields and falls through to its declared :source/env source.
   Literal manifest values (non-string, or strings without ${VAR}) survive
   as overrides — they remain the authoritative source.

   Arguments:
     config - Manifest config map

   Returns:
     Config map with pure ${VAR} entries removed."
  [config]
  (into (empty config)
        (remove (fn [[_ v]] (pure-env-template? v)))
        config))

(defn- addon-id->service-key
  "Derive config.edn service key from addon ID.
   \"hive.milvus\" → :milvus, \"bridge.foo\" → :foo"
  [addon-id]
  (some-> addon-id
          (str/replace #"^[^.]+\." "")
          keyword))

(defn prepare-config
  "Prepare a manifest's config for addon initialization.

   Applies:
   1. Merge config.edn :services values (authoritative literal overrides)
   2. Strip pure ${VAR} placeholders so the addon's hive-di defconfig resolves
      env-sourced fields directly (typed coercion + blank->nil + Result errors)
   3. Inject :addon/id and :addon/type for context

   Config precedence (highest wins, after addon defconfig resolution):
     manifest :addon/config literal > config.edn :services > env vars > defaults

   Pure ${VAR} placeholders are dropped here, not expanded — env resolution is
   the addon's responsibility via its (defconfig …) field registry. Literal
   non-template values in :addon/config remain authoritative overrides.

   Arguments:
     manifest - Validated manifest map

   Returns:
     Config map ready for (initialize! addon config)"
  [manifest]
  (let [raw-config  (or (:addon/config manifest) {})
        service-key (addon-id->service-key (:addon/id manifest))
        file-config (when service-key
                      (try
                        (require 'hive-mcp.config.core)
                        (let [get-svc (resolve 'hive-mcp.config.core/get-service-config)]
                          (when get-svc
                            (dissoc (get-svc service-key) :mode)))
                        (catch Exception _ nil)))]
    (-> (merge raw-config file-config)
        strip-env-templates
        (assoc :addon/id (:addon/id manifest)
               :addon/type (:addon/type manifest)))))

;; =============================================================================
;; Classpath Manifest Scanner
;; =============================================================================

(def ^:private manifest-resource-path
  "Classpath directory where addon manifests are discovered."
  "META-INF/hive-addons")

(defn- list-edn-files-from-dir
  "List .edn files from a file: URL pointing to a directory."
  [^java.io.File dir]
  (when (.isDirectory dir)
    (->> (.listFiles dir)
         (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
         (mapv #(.toURL ^java.io.File %)))))

(defn- list-edn-files-from-jar
  "List .edn entries under META-INF/hive-addons/ inside a JAR.
   Returns URLs using jar: protocol."
  [^java.net.URL jar-url]
  (r/rescue []
            (let [jar-path (-> (.getPath jar-url)
                       ;; jar:file:/path/to.jar!/META-INF/hive-addons → /path/to.jar
                               (str/replace #"^file:" "")
                               (str/replace #"!.*$" ""))
                  jar-file (JarFile. jar-path)
                  prefix   (str manifest-resource-path "/")
                  entries  (->> (enumeration-seq (.entries jar-file))
                                (filter (fn [e]
                                          (let [n (.getName e)]
                                            (and (str/starts-with? n prefix)
                                                 (str/ends-with? n ".edn")
                                                 (not (.isDirectory e))))))
                                (mapv (fn [e]
                                        (java.net.URL. (str "jar:file:" jar-path "!/" (.getName e))))))]
              (.close jar-file)
              entries)))

(defn- discover-manifest-urls
  "Scan the classpath for all META-INF/hive-addons/*.edn files.
   Uses ClassLoader.getResources to find entries across all JARs and dirs."
  []
  (let [cl   (.getContextClassLoader (Thread/currentThread))
        urls (enumeration-seq (.getResources cl manifest-resource-path))]
    (into []
          (mapcat
           (fn [^java.net.URL url]
             (case (.getProtocol url)
               "file" (list-edn-files-from-dir (io/file url))
               "jar"  (list-edn-files-from-jar url)
               (do (log/debug "Unsupported URL protocol for addon scan" {:url url})
                   []))))
          urls)))

(defn scan-classpath-manifests
  "Scan the entire classpath for addon manifest EDN files.

   Looks in META-INF/hive-addons/*.edn across all classpath entries
   (JARs, dirs). Each file is parsed and validated.

   Returns:
     {:manifests [<validated-manifest-map> ...]
      :errors   [{:url <url> :errors [...]} ...]}"
  []
  (let [urls (discover-manifest-urls)]
    (reduce
     (fn [acc ^java.net.URL url]
       (let [effect (r/try-effect* :manifest/scan-error
                                   (let [content (slurp url)
                                         result  (parse-manifest content)]
                                     (if (:valid? result)
                                       {:manifests [(:manifest result)]}
                                       {:errors [{:url (str url) :errors (:errors result)}]})))]
         (if (r/ok? effect)
           (let [{:keys [manifests errors]} (:ok effect)]
             (cond-> acc
               manifests (update :manifests into manifests)
               errors    (update :errors into errors)))
           (update acc :errors conj {:url (str url) :errors [{:read-error (:message effect)}]}))))
     {:manifests [] :errors []}
     urls)))

;; =============================================================================
;; Manifest-Based Addon Initialization
;; =============================================================================

(defn init-from-manifest!
  "Initialize an addon from its classpath manifest.

   Resolves the constructor, calls it with prepared config,
   and if the result satisfies IAddon, registers and initializes it.

   Arguments:
     manifest       - Validated manifest map
     register-fn!   - fn [addon] that registers in the addon registry
     init-addon-fn! - fn [addon-id] that initializes a registered addon

   Returns:
     {:success? true/false :addon/id \"...\"} or nil on resolution failure."
  [manifest register-fn! init-addon-fn!]
  (let [addon-id (:addon/id manifest)
        effect   (r/try-effect* :manifest/init-error
                                (if-let [ctor (resolve-constructor manifest)]
                                  (let [config (prepare-config manifest)
                                        result (ctor config)]
                                    (if (satisfies? proto/IAddon result)
                                      (do
                                        (register-fn! result)
                                        (let [init-result (init-addon-fn! addon-id config)]
                                          (log/info "Addon initialized from manifest" {:addon/id addon-id})
                                          (assoc init-result :addon/id addon-id :source :manifest)))
                                      (do
                                        (log/warn "Constructor did not return IAddon" {:addon/id addon-id})
                                        {:success? false :addon/id addon-id
                                         :errors ["Constructor did not return IAddon instance"]})))
                                  (do
                                    (log/debug "Could not resolve constructor for manifest" {:addon/id addon-id})
                                    nil)))]
    (if (r/ok? effect)
      (:ok effect)
      (do (log/warn "Failed to init addon from manifest"
                    {:addon/id addon-id :error (:message effect)})
          {:success? false :addon/id addon-id :errors [(:message effect)]}))))
