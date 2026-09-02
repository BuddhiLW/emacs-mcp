#!/usr/bin/env bb
(ns foss-compliance
  "Compliance sweep over the public hive-agi repositories.

   Enumerates the org from GitHub (never from a curated list), probes each
   local checkout plus its published coordinates, and runs an open registry
   of checks over the resulting facts.

   Usage:
     bb dev/foss_compliance.clj                 # every public repo
     bb dev/foss_compliance.clj lsp-mcp scc-mcp # named repos only
     bb dev/foss_compliance.clj --offline       # skip Clojars/GitHub probes
     bb dev/foss_compliance.clj --edn           # machine-readable output

   Exit code is 1 when any check fails, 0 otherwise."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; boundary: shelling out, reading files, reaching the network
;; ---------------------------------------------------------------------------

(def ^:private manifest-dir "resources/META-INF/hive-addons")

(defn- sh
  "Run `args` in `dir`. Returns {:ok? :out :err}; never throws. A missing
   directory or absent executable is reported as a failed run, not an
   exception, so one unclonable repo cannot abort the sweep."
  [dir & args]
  (if-not (fs/directory? dir)
    {:ok? false :out "" :err (str "no such directory: " dir)}
    (try
      (let [{:keys [exit out err]} (apply process/sh {:dir (str dir) :continue true} args)]
        {:ok? (zero? exit) :out (str/trim (or out "")) :err (str/trim (or err ""))})
      (catch Exception e
        {:ok? false :out "" :err (ex-message e)}))))

(defn- slurp-file
  [path]
  (when (fs/regular-file? path) (slurp (str path))))

(defn- read-edn
  "Parsed EDN at `path`, or {::error msg} when it does not parse."
  [path]
  (when-let [s (slurp-file path)]
    (try (edn/read-string s)
         (catch Exception e {::error (ex-message e)}))))

(defn- org-repos
  "Public, non-archived repo names under `org`, from the GitHub API."
  [org]
  (let [{:keys [ok? out]} (sh "." "gh" "api" (str "orgs/" org "/repos") "--paginate"
                              "-q" ".[] | select(.archived|not) | [.name, .license.spdx_id // \"NONE\"] | @tsv")]
    (when ok?
      (into {} (for [line (str/split-lines out)
                     :when (seq line)
                     :let [[n spdx] (str/split line #"\t")]]
                 [n spdx])))))

(defn- clojars-latest
  "Latest released version of `lib` on Clojars, or nil."
  [lib]
  (try
    (let [url  (str "https://clojars.org/api/artifacts/" (namespace lib) "/" (name lib))
          resp (http/get url {:headers {"Accept" "application/json"} :throw false :timeout 15000})]
      (when (= 200 (:status resp))
        (get (json/parse-string (:body resp) true) :latest_release)))
    (catch Exception _ nil)))

(defn- jar-has-manifest?
  "True when the released jar for `lib`/`version` contains a hive-addons manifest.
   nil when the jar could not be fetched."
  [lib version cache-dir]
  (let [[grp art] [(namespace lib) (name lib)]
        path      (str (str/replace grp "." "/") "/" art "/" version "/" art "-" version ".jar")
        jar       (fs/path cache-dir (str art "-" version ".jar"))]
    (when-not (fs/exists? jar)
      (fs/create-dirs cache-dir)
      (sh "." "curl" "-fsSL" "-o" (str jar) (str "https://repo.clojars.org/" path)))
    (when (fs/exists? jar)
      (let [{:keys [ok? out]} (sh "." "unzip" "-l" (str jar))]
        (when ok? (str/includes? out "META-INF/hive-addons/"))))))

;; ---------------------------------------------------------------------------
;; facts: one map per repo, everything a check may need
;; ---------------------------------------------------------------------------

(defn- manifest-files
  [dir]
  (let [d (fs/path dir manifest-dir)]
    (when (fs/directory? d)
      (vec (fs/glob d "*.edn")))))

(defn- latest-tag
  [dir]
  (let [{:keys [ok? out]} (sh dir "git" "describe" "--tags" "--abbrev=0")]
    (when (and ok? (seq out)) out)))

(defn- workflow-files
  [dir]
  (let [d (fs/path dir ".github" "workflows")]
    (when (fs/directory? d) (mapv fs/file-name (fs/glob d "*.{yml,yaml}")))))

(defn- source-root?
  "True when `root` holds at least one Clojure source file, the same split
   hive-build.promote.project/classify-roots applies to :src-dirs."
  [dir root]
  (let [d (fs/path dir root)]
    (and (fs/directory? d)
         (boolean (seq (fs/glob d "**.{clj,cljc,cljs,cljd}"))))))

(defn repo-facts
  "Everything the checks read about `repo`, probed once."
  [{:keys [root offline? spdx cache-dir]} repo]
  (let [dir      (fs/path root repo)
        vedn     (read-edn (fs/path dir "version.edn"))
        lib      (:lib vedn)
        version  (some-> (slurp-file (fs/path dir "VERSION")) str/trim)
        clojars  (when (and (not offline?) (qualified-symbol? lib) (= :clojars (:publish vedn)))
                   (clojars-latest lib))]
    {:repo          repo
     :dir           (str dir)
     :checkout?     (fs/directory? (fs/path dir ".git"))
     :version.edn   vedn
     :lib           lib
     :version       version
     :src-dirs      (:src-dirs vedn)
     :source-roots  (into #{} (filter #(source-root? dir %)) (:src-dirs vedn))
     :manifests     (manifest-files dir)
     :deps.edn      (read-edn (fs/path dir "deps.edn"))
     :workflows     (workflow-files dir)
     :release-yml   (slurp-file (fs/path dir ".github" "workflows" "release.yml"))
     :license?      (some #(fs/regular-file? (fs/path dir %)) ["LICENSE" "LICENSE.md" "LICENSE.txt"])
     :readme        (slurp-file (fs/path dir "README.md"))
     :go?           (fs/regular-file? (fs/path dir "go.mod"))
     :git-tag       (latest-tag dir)
     :github-spdx   (get spdx repo)
     :clojars       clojars
     :jar-manifest? (when (and clojars (seq (manifest-files dir)) (not offline?))
                      (jar-has-manifest? lib clojars cache-dir))}))

;; ---------------------------------------------------------------------------
;; checks: pure, an open registry; each entry decides whether it applies
;; ---------------------------------------------------------------------------

(defn- verdict
  ([status evidence] {:status status :evidence evidence}))

(defn- packaging
  "An addon manifest only reaches consumers when its root is a RESOURCE root
   in :src-dirs. hive-build copies source roots by compiling them and
   resource roots verbatim, so a root holding only EDN must be declared."
  [{:keys [src-dirs source-roots jar-manifest?]}]
  (let [declared (set src-dirs)
        res-root (first (filter #(and (declared %) (not (source-roots %))
                                      (str/starts-with? manifest-dir %))
                                declared))]
    (cond
      (nil? res-root)
      (verdict :fail (str ":src-dirs " (pr-str (vec src-dirs))
                          " declares no resource root covering " manifest-dir))

      (false? jar-manifest?)
      (verdict :fail (str "released jar carries no META-INF/hive-addons "
                          "(root '" res-root "' is declared; release not cut yet?)"))

      (true? jar-manifest?)
      (verdict :pass (str "root '" res-root "' declared; released jar carries the manifest"))

      :else
      (verdict :warn (str "root '" res-root "' declared; jar not inspected")))))

(defn- mount-contract
  "The manifest's :addon/init-ns must exist as a source file under a declared
   source root. Whether its ctor returns an IAddon is a boot-time claim."
  [{:keys [dir manifests source-roots]}]
  (let [rows (for [m manifests
                   :let [{:addon/keys [id init-ns init-fn]} (read-edn m)
                         rel (str (str/replace (str init-ns) #"[.-]"
                                               {"." "/" "-" "_"}) ".clj")
                         hit (first (filter #(fs/regular-file? (fs/path dir % rel)) source-roots))]]
               {:id id :ns init-ns :fn init-fn :file hit})]
    (if-let [missing (seq (remove :file rows))]
      (verdict :fail (str "init-ns not found under a source root: "
                          (str/join ", " (map :ns missing))))
      (verdict :warn (str (count rows) " manifest(s) resolve; ctor return type is a boot claim: "
                          (str/join ", " (map :id rows)))))))

(defn- version-truth
  "VERSION, the newest git tag and the latest Clojars release must agree."
  [{:keys [version git-tag clojars]}]
  (let [tag (some-> git-tag (str/replace #"^v" ""))
        seen (remove nil? [version tag clojars])]
    (cond
      (nil? version)          (verdict :fail "no VERSION file")
      (apply = seen)          (verdict :pass (str "VERSION=" version " tag=" (or tag "-")
                                                  " clojars=" (or clojars "-")))
      :else                   (verdict :fail (str "VERSION=" version " tag=" (or tag "-")
                                                  " clojars=" (or clojars "-"))))))

(defn- ci
  "A workflow must exist, and a release workflow must run the suite."
  [{:keys [workflows release-yml]}]
  (cond
    (empty? workflows) (verdict :fail "no .github/workflows")
    (and release-yml (not (re-find #"(?m)test|kondo|suite" release-yml)))
    (verdict :fail "release.yml does not gate on the test suite")
    :else (verdict :pass (str/join ", " workflows))))

(defn- license
  "LICENSE on disk, version.edn :license and the GitHub SPDX must agree."
  [{:keys [license? version.edn github-spdx]}]
  (let [declared (get-in version.edn [:license :name])]
    (cond
      (not license?)                       (verdict :fail "no LICENSE file")
      (or (nil? declared) (= "UNDECLARED" declared))
      (verdict :fail (str "version.edn :license " (pr-str declared)))
      (and github-spdx (not (contains? #{"NONE" "NOASSERTION" nil} github-spdx))
           (not= github-spdx declared))
      (verdict :fail (str "version.edn says " declared ", GitHub detects " github-spdx))
      (contains? #{"NONE" "NOASSERTION"} github-spdx)
      (verdict :warn (str declared " on disk; GitHub detects " github-spdx))
      :else (verdict :pass declared))))

(defn- readme-commands
  "Every repo-relative path a README references in a command must exist."
  [{:keys [dir readme]}]
  (if-not readme
    (verdict :fail "no README.md")
    (let [refs (into #{} (map second)
                     (re-seq #"(?m)(?:^|[\s`(])((?:bin|scripts|dev)/[A-Za-z0-9._/-]+)" readme))
          gone (remove #(fs/exists? (fs/path dir %)) refs)]
      (if (seq gone)
        (verdict :fail (str "README names missing paths: " (str/join ", " gone)))
        (verdict :pass (str (count refs) " referenced path(s) exist"))))))

(defn- deps-hygiene
  "A published deps.edn names public coordinates only."
  [{:keys [deps.edn]}]
  (let [locals (for [[lib coord] (:deps deps.edn)
                     :when (:local/root coord)]
                 (str lib))
        repos  (remove #{"central" "clojars"} (keys (:mvn/repos deps.edn)))]
    (cond
      (seq locals) (verdict :fail (str ":local/root deps: " (str/join ", " locals)))
      (seq repos)  (verdict :warn (str "extra :mvn/repos: " (str/join ", " repos)))
      :else        (verdict :pass (str (count (:deps deps.edn)) " public coordinate(s)")))))

(defn- go-build
  "gofmt, go build and go vet over the whole module."
  [{:keys [dir]}]
  (let [fmt   (sh dir "gofmt" "-l" ".")
        build (sh dir "go" "build" "./...")
        vet   (sh dir "go" "vet" "./...")]
    (cond
      (seq (:out fmt))   (verdict :fail (str "gofmt -l: " (str/replace (:out fmt) "\n" " ")))
      (not (:ok? build)) (verdict :fail (str "go build ./...: " (first (str/split-lines (:err build)))))
      (not (:ok? vet))   (verdict :fail (str "go vet ./...: " (first (str/split-lines (:err vet)))))
      :else              (verdict :pass "gofmt/build/vet clean"))))

(def checks
  "Ordered registry. Each entry applies only where its subject exists, so a
   repo that ships no addon is not failed for shipping no manifest."
  [{:id :packaging      :applies? (comp seq :manifests)      :run packaging}
   {:id :mount-contract :applies? (comp seq :manifests)      :run mount-contract}
   {:id :version-truth  :applies? :version.edn               :run version-truth}
   {:id :ci             :applies? (constantly true)          :run ci}
   {:id :license        :applies? :version.edn               :run license}
   {:id :readme         :applies? (constantly true)          :run readme-commands}
   {:id :deps-hygiene   :applies? :deps.edn                  :run deps-hygiene}
   {:id :go             :applies? :go?                       :run go-build}])

(defn run-checks
  "Every applicable check over `facts`, as [{:check :status :evidence}]."
  [facts]
  (into []
        (keep (fn [{:keys [id applies? run]}]
                (when (applies? facts)
                  (try (assoc (run facts) :check id)
                       (catch Exception e
                         {:check id :status :fail :evidence (str "check threw: " (ex-message e))})))))
        checks))

;; ---------------------------------------------------------------------------
;; report
;; ---------------------------------------------------------------------------

(def ^:private marks {:pass "PASS" :fail "FAIL" :warn "WARN"})

(defn- print-repo!
  [{:keys [repo checkout?]} results]
  (if-not checkout?
    (println (format "%-22s  %-4s  %-15s %s" repo "SKIP" "-" "no local checkout"))
    (doseq [{:keys [check status evidence]} results]
      (println (format "%-22s  %-4s  %-15s %s" repo (marks status status) (name check) evidence)))))

(defn- summarize
  [rows]
  (frequencies (map :status rows)))

(defn -main
  [& args]
  (let [flags    (set (filter #(str/starts-with? % "--") args))
        named    (remove #(str/starts-with? % "--") args)
        offline? (contains? flags "--offline")
        root     (str (fs/parent (fs/cwd)))
        spdx     (if offline? {} (or (org-repos "hive-agi") {}))
        repos    (if (seq named) (vec named) (vec (sort (keys spdx))))
        ctx      {:root root :offline? offline? :spdx spdx
                  :cache-dir (fs/path (fs/temp-dir) "hive-foss-jars")}]
    (when (empty? repos)
      (println "No repos to sweep (gh unavailable? pass repo names explicitly).")
      (System/exit 2))
    (let [report (vec (for [r repos
                            :let [facts (repo-facts ctx r)]]
                        {:repo r :facts facts :results (when (:checkout? facts) (run-checks facts))}))
          rows   (mapcat :results report)]
      (if (contains? flags "--edn")
        (prn (mapv #(select-keys % [:repo :results]) report))
        (do (println (format "%-22s  %-4s  %-15s %s" "REPO" "" "CHECK" "EVIDENCE"))
            (doseq [{:keys [facts results]} report] (print-repo! facts results))
            (println)
            (println "summary:" (pr-str (summarize rows)))))
      (System/exit (if (some #(= :fail (:status %)) rows) 1 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
