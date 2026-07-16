(ns build
  "hive-mcp build: runnable uberjar (server) + source jar / Clojars deploy.

   VERSION AUTOBUMP (in build.clj, no manual VERSION file): the version is
   0.{minor}.{next-free-patch}, where {minor} comes from ./version.edn and
   {next-free-patch} is the lowest patch NOT yet published on Clojars — so the
   very first publish of the 0.{minor} line is 0.{minor}.0 and each release
   thereafter increments the patch (0.18.0 -> 0.18.1 -> ...) with zero manual
   edits. This keeps hive-mcp's semantic line (unlike raw git-commit-count) while
   still autobumping. release.yml reads it via `clojure -T:build print-version`
   and tags v{version}, so the git-tag and Clojars coords match 1:1.

   Tasks (clojure -T:build <task>):
     clean          remove target/
     uber           runnable uberjar (server) — clj -T:build uber [:profile k8s-headless]
     print-version  echo the computed version (CI captures this to tag the release)
     jar            source/library jar under target/
     install        jar + install to the local ~/.m2 (offline verification)
     deploy         jar + push to Clojars (needs CLOJARS_USERNAME / CLOJARS_PASSWORD)"
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [deps-deploy.deps-deploy :as dd]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private cfg (edn/read-string (slurp "version.edn")))
(def lib (:lib cfg))
(def ^:private minor (:minor cfg 0))
(def ^:private class-dir "target/classes")
(def ^:private src-dirs (:src-dirs cfg ["src"]))
(def ^:private main-ns 'hive-mcp.server.core)
(def ^:private uber-file "target/hive-mcp.jar")

(defn- published?
  "True iff this exact lib+version jar is already on Clojars (immutable repo)."
  [v]
  (let [[grp art] (str/split (str lib) #"/")
        url (format "https://repo.clojars.org/%s/%s/%s/%s-%s.jar"
                    (str/replace grp "." "/") art v art v)]
    (try
      (let [conn (doto ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))
                   (.setRequestMethod "HEAD")
                   (.setConnectTimeout 8000)
                   (.setReadTimeout 8000))]
        (= 200 (.getResponseCode conn)))
      (catch Throwable _ false))))

;; Deferred so `clean`/`uber` never touch the network. First deref probes
;; Clojars for the lowest unpublished 0.{minor}.{patch}.
(def ^:private version*
  (delay (loop [p 0]
           (let [v (format "0.%s.%s" minor p)]
             (if (published? v) (recur (inc p)) v)))))

(defn- version [] @version*)
(defn- jar-file [] (format "target/%s-%s.jar" (name lib) (version)))

(defn clean [_] (b/delete {:path "target"}))

(defn print-version
  "Echo the computed version — release.yml captures this to tag v{version}."
  [_]
  (println (version)))

(defn uber
  "Build a runnable uberjar.

   Options:
     :profile - deps.edn alias to merge (e.g. 'k8s-headless').
                Resolved as keyword alias for extra-deps/paths."
  [{:keys [profile] :as opts}]
  (clean opts)
  (let [aliases (cond-> [:mcp]
                  profile (conj (keyword profile)))
        basis   (b/create-basis {:project "deps.edn"
                                 :aliases aliases})]
    (println "Building uberjar with aliases:" aliases)
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis      basis
                    :src-dirs   ["src"]
                    :class-dir  class-dir
                    :ns-compile [main-ns]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns})
    (println "Uberjar built:" uber-file)))

(defn- write-pom []
  (b/write-pom
   {:class-dir class-dir
    :lib       lib
    :version   (version)
    :basis     (b/create-basis {:project "deps.edn"})
    ;; pom :src references source roots only (not resource dirs)
    :src-dirs  (vec (remove #{"resources"} src-dirs))
    :scm       {:url (:scm-url cfg)
                :tag (b/git-process {:git-args "rev-parse HEAD"})}
    :pom-data  [[:licenses
                 [:license
                  [:name (get-in cfg [:license :name] "AGPL-3.0-or-later")]
                  [:url  (get-in cfg [:license :url]
                                 "https://www.gnu.org/licenses/agpl-3.0.txt")]]]]}))

(defn jar
  "Build the source/library jar (pom + copied sources) under target/."
  [_]
  (clean nil)
  (write-pom)
  (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file (jar-file)})
  (println "Built" (str lib) (version) "->" (jar-file)))

(defn install
  "Build + install to the local ~/.m2 repository (offline; for verification)."
  [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact  (jar-file)
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Installed" (str lib) (version) "to ~/.m2"))

(defn deploy
  "Build + deploy to Clojars. Requires CLOJARS_USERNAME and CLOJARS_PASSWORD
   (a deploy token, not the account password) in the environment. The computed
   version is by construction the next unpublished patch, so this deploys a fresh
   release on every main push; a defensive re-check no-ops on the rare race."
  [_]
  (if (published? (version))
    (println "Skip:" (str lib) (version) "already on Clojars.")
    (do
      (jar nil)
      (dd/deploy {:installer :remote
                  :artifact  (jar-file)
                  :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
      (println "Deployed" (str lib) (version) "to Clojars"))))
