(ns build
  "Canonical hive library build — normalized across every hive package.

   Coordinates come from ./version.edn, the version from ./VERSION:

     {:lib      io.github.hive-agi/hive-thing
      :minor    1
      :license  {:name \"MIT\" :url \"https://opensource.org/licenses/MIT\"}
      :scm-url  \"https://github.com/hive-agi/hive-thing\"
      :src-dirs [\"src\"]
      :publish  :clojars}            ; :clojars | :gitea | :none

   `:publish` is the ONLY thing that differs between packages — the task names
   are identical everywhere, so one CI workflow drives the whole fleet:

     :clojars  public source jar   -> repo.clojars.org
     :gitea    AOT no-source jar   -> private Gitea Maven registry
     :none     builds, never ships (missing LICENSE, no remote, or not a library)

   Tasks (invoke with `clojure -T:build <task>`):
     clean           delete target/
     jar             source jar + pom
     jar-aot         AOT no-source jar (own .class + resources only)
     install         build + install to ~/.m2 (offline)
     bump            rewrite ./VERSION (:level :patch|:minor|:major)
     verify-license  report LICENSE / version.edn / SPDX agreement (warns)
     deploy          build + publish per :publish (no-op when :none)

   Release flow (what CI runs on a push to main that touches src/deps):
     clojure -T:build bump :level :patch
     clojure -T:build deploy"
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private cfg (edn/read-string (slurp "version.edn")))
(def lib (:lib cfg))
(def publish-target (:publish cfg :none))
(def version
  (let [f (io/file "VERSION")]
    (if (.exists f)
      (str/trim (slurp f))
      (format "0.%s.%s" (:minor cfg 0) (b/git-count-revs nil)))))
(def ^:private class-dir "target/classes")
(def ^:private src-dirs (:src-dirs cfg ["src"]))
(def ^:private jar-file (format "target/%s-%s.jar" (name lib) version))

;; The pom's declared license is IMMUTABLE once published, on Clojars and on the
;; private registry alike. The fallback is deliberately absent: a package with no
;; :license in version.edn must not silently inherit someone else's terms.
(def ^:private pom-data
  [[:licenses
    [:license
     [:name (get-in cfg [:license :name] "UNDECLARED")]
     [:url  (get-in cfg [:license :url] "")]]]])

(defn clean [_] (b/delete {:path "target"}))

(defn- basis [] (b/create-basis {:project "deps.edn" :user :standard}))

(defn- write-pom []
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (basis)
                :src-dirs  (vec (remove #{"resources"} src-dirs))
                :scm       {:url (:scm-url cfg)
                            :tag (b/git-process {:git-args "rev-parse HEAD"})}
                :pom-data  pom-data}))

;; ── License agreement (advisory) ────────────────────────────────────────────

(defn- spdx-headers
  "Distinct SPDX-License-Identifier values declared across the source tree."
  []
  (into #{}
        (comp (filter (fn [^java.io.File f]
                        (and (.isFile f)
                             (re-find #"\.cljc?$|\.cljs$" (.getName f)))))
              ;; Identifier charset only — \S+ would swallow a trailing quote
              ;; from an SPDX line inside a string literal and report a phantom
              ;; conflict against the identical bare identifier.
              (keep (fn [^java.io.File f]
                      (second (re-find #"SPDX-License-Identifier:\s*([A-Za-z0-9.+-]+)"
                                       (slurp f))))))
        (mapcat #(file-seq (io/file %)) src-dirs)))

(defn verify-license
  "Report whether ./LICENSE, version.edn :license and the src SPDX headers agree.
   Advisory: prints and returns findings, never fails the build."
  [_]
  (let [declared (get-in cfg [:license :name])
        has-file (.exists (io/file "LICENSE"))
        headers  (spdx-headers)
        problems (cond-> []
                   (not has-file)     (conj "no ./LICENSE file")
                   (nil? declared)    (conj "version.edn has no :license")
                   (> (count headers) 1)
                   (conj (str "conflicting SPDX headers in src: " headers)))]
    (if (seq problems)
      (do (println "WARNING: license inconsistency in" (str lib))
          (doseq [p problems] (println "  -" p))
          (println "  A published pom can never be retracted.")
          {:ok? false :problems problems})
      (do (println "License OK:" declared) {:ok? true :problems []}))))

;; ── Source jar ─────────────────────────────────────────────────────────────

(defn jar
  "Build the source jar (pom + copied sources) under target/."
  [_]
  (clean nil)
  (write-pom)
  (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built" (str lib) version "->" jar-file))

;; ── AOT no-source jar ──────────────────────────────────────────────────────
;; Ships compiled .class for THIS lib only — no sources, no dependency bytecode.
;; Selection is by munged-namespace-path prefix, which also captures classes
;; emitted by defrecord/deftype/defprotocol under each namespace's package.

(defn- source-roots []
  (filterv (fn [d]
             (some (fn [^java.io.File f]
                     (and (.isFile f) (re-find #"\.cljc?$|\.cljs$" (.getName f))))
                   (file-seq (io/file d))))
           src-dirs))

(defn- resource-roots [] (vec (remove (set (source-roots)) src-dirs)))

(defn- file-ns [f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (loop []
      (let [form (try (read {:read-cond :allow :eof ::eof} r)
                      (catch Exception _ ::eof))]
        (cond
          (= form ::eof)                         nil
          (and (seq? form) (= 'ns (first form))) (second form)
          :else                                  (recur))))))

(defn- source-namespaces []
  (into []
        (comp (filter (fn [^java.io.File f]
                        (and (.isFile f) (re-find #"\.cljc?$" (.getName f)))))
              (keep file-ns))
        (mapcat #(file-seq (io/file %)) (source-roots))))

(defn- ns->path [ns-sym]
  (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")))

(defn- copy-own-classes! [scratch nses]
  (let [prefixes  (mapv ns->path nses)
        root      (io/file scratch)
        root-path (.toPath root)]
    (doseq [^java.io.File f (file-seq root)
            :when (and (.isFile f) (str/ends-with? (.getName f) ".class"))
            :let  [rel (-> (.relativize root-path (.toPath f)) str
                           (str/replace java.io.File/separator "/"))]
            :when (some #(str/starts-with? rel %) prefixes)]
      (let [dest (io/file class-dir rel)]
        (io/make-parents dest)
        (io/copy f dest)))))

(defn jar-aot
  "Build the AOT no-source jar: this lib's own .class files + resources only."
  [_]
  (clean nil)
  (let [scratch "target/aot-classes"
        nses    (source-namespaces)]
    (b/compile-clj {:basis (basis) :src-dirs (source-roots)
                    :ns-compile nses :class-dir scratch})
    (copy-own-classes! scratch nses)
    (when-let [res (seq (resource-roots))]
      (b/copy-dir {:src-dirs (vec res) :target-dir class-dir}))
    (write-pom)
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "Built AOT" (str lib) version "->" jar-file
             (str "(" (count nses) " ns, own .class only)"))))

(defn install
  "Build + install to the local ~/.m2 repository (offline)."
  [_]
  (if (= :gitea publish-target) (jar-aot nil) (jar nil))
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :local
    :artifact  jar-file
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Installed" (str lib) version "to ~/.m2"))

;; ── Version ────────────────────────────────────────────────────────────────

(defn bump
  "Rewrite ./VERSION to the next semantic version and print it.

   :level :patch (default) | :minor | :major
   VERSION is the single source of truth for both the git tag (v{VERSION}) and
   the Maven coordinate. Does not commit, tag, or deploy."
  [{:keys [level] :or {level :patch}}]
  (let [f     (io/file "VERSION")
        _     (when-not (.exists f)
                (throw (ex-info "No ./VERSION file to bump"
                                {:cwd (System/getProperty "user.dir")})))
        cur   (str/trim (slurp f))
        parts (str/split cur #"\.")
        _     (when-not (= 3 (count parts))
                (throw (ex-info "VERSION is not MAJOR.MINOR.PATCH" {:version cur})))
        [maj min' pat] (map #(Long/parseLong %) parts)
        nxt   (case level
                :major (format "%d.0.0" (inc maj))
                :minor (format "%d.%d.0" maj (inc min'))
                :patch (format "%d.%d.%d" maj min' (inc pat))
                (throw (ex-info "level must be :major, :minor or :patch"
                                {:level level})))]
    (spit f (str nxt "\n"))
    (println (format "VERSION %s -> %s (%s)" cur nxt (name level)))
    nxt))

;; ── Publish ────────────────────────────────────────────────────────────────

(defn- required-env [k]
  (let [v (System/getenv k)]
    (if (str/blank? v)
      (throw (ex-info (str k " not set or blank") {:env k}))
      v)))

(defn- published?
  "True when this exact lib+version pom already exists at `url`. Both registries
   are immutable, so deploy no-ops instead of erroring on a re-run."
  [url auth]
  (let [[grp art] (str/split (str lib) #"/")
        pom-url (format "%s/%s/%s/%s/%s-%s.pom"
                        url (str/replace grp "." "/") art version art version)]
    (try
      (let [conn (doto ^java.net.HttpURLConnection
                       (.openConnection (java.net.URL. pom-url))
                   (.setRequestMethod "HEAD")
                   (.setConnectTimeout 10000)
                   (.setReadTimeout 10000))]
        (when auth (.setRequestProperty conn "Authorization" auth))
        (= 200 (.getResponseCode conn)))
      (catch Throwable _ false))))

(defn- deploy-clojars []
  (required-env "CLOJARS_USERNAME")
  (required-env "CLOJARS_PASSWORD")
  (if (published? "https://repo.clojars.org" nil)
    (println "Skip:" (str lib) version "already on Clojars — bump VERSION to release.")
    (do (jar nil)
        ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
         {:installer :remote
          :artifact  jar-file
          :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
        (println "Deployed" (str lib) version "to Clojars"))))

(defn- deploy-gitea []
  (let [env      (fn [k d] (let [v (System/getenv k)] (if (str/blank? v) d v)))
        url      (env "GITEA_MAVEN_URL"
                      "https://gitea.hive-mcp.com/api/packages/hive-agi/maven")
        username (env "GITEA_MAVEN_USERNAME" "buddhilw")
        token    (required-env "GITEA_MAVEN_TOKEN")
        auth     (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                (.getBytes (str username ":" token))))]
    (if (published? url auth)
      (println "Skip:" (str lib) version "already in private registry — bump VERSION to release.")
      (do (jar-aot nil)
          ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
           {:installer  :remote
            :artifact   jar-file
            :pom-file   (b/pom-path {:lib lib :class-dir class-dir})
            :repository {"gitea" {:url url :username username :password token}}})
          (println "Deployed" (str lib) version "to" url)))))

(defn deploy
  "Build + publish according to version.edn :publish.

   :clojars -> public source jar to repo.clojars.org
   :gitea   -> AOT no-source jar to the private Gitea Maven registry
   :none    -> no-op; the package is not shippable and CI stays green"
  [_]
  (verify-license nil)
  (case publish-target
    :clojars (deploy-clojars)
    :gitea   (deploy-gitea)
    :none    (println "Not shippable:" (str lib)
                      "has :publish :none — nothing published.")
    (throw (ex-info "version.edn :publish must be :clojars, :gitea or :none"
                    {:publish publish-target}))))
