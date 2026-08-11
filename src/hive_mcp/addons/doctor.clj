(ns hive-mcp.addons.doctor
  "Evidence-driven, read-only diagnostics for one live IAddon.

   The doctor composes hive-addon's manifest/solver/contract seams with the
   hive-mcp registry and Emacs extension port. Stage failures are evidence, not
   transport failures: a completed diagnostic returns (r/ok report) with
   :ok? false. Only invalid input or an invalid report shape returns r/err.

   Live lifecycle state is never mutated. In particular, the doctor does not
   initialize, shut down, re-register, or construct an addon."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [hive-addon.mount :as mount]
            [hive-addon.protocol :as addon]
            [hive-addon.schema :as addon-schema]
            [hive-dsl.result :as r]
            [hive-mcp.addons.core :as registry]
            [hive-mcp.emacs-ext.client :as emacs]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.io PushbackReader]
           [java.time Instant]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Malli contracts
;; -----------------------------------------------------------------------------

(def FeatureName
  "Elisp feature symbol encoded without the leading quote."
  [:and
   [:string {:min 1 :max 256}]
   [:fn {:error/message "must be a safe Emacs feature symbol"}
    #(boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9+*./_:@~-]*" %))]])

(def DoctorInput
  "Normalized input for run-doctor. Open so host boundary metadata may pass."
  [:map {:closed false}
   [:addon-id [:string {:min 1 :max 128}]]
   [:directory {:optional true} [:string {:min 1}]]
   [:emacs-features {:optional true} [:sequential FeatureName]]
   [:timeout-ms {:optional true} [:int {:min 1 :max 30000}]]])

(def StageName
  [:enum :manifest-discovery
   :constructor-resolution
   :dependency-boundary
   :lifecycle-smoke
   :capability-comparison
   :emacs-features])

(def Stage
  [:map {:closed false}
   [:stage StageName]
   [:status [:enum :pass :fail :skip]]
   [:summary :string]
   [:evidence [:map {:closed false}]]])

(def DoctorReport
  "Versioned, JSON-safe evidence report returned by the doctor."
  [:map {:closed false}
   [:report/type [:= :addon-doctor]]
   [:schema-version [:= 1]]
   [:observed-at :string]
   [:addon/id [:string {:min 1}]]
   [:request [:map {:closed false}]]
   [:ok? :boolean]
   [:summary [:map
              [:pass [:int {:min 0}]]
              [:fail [:int {:min 0}]]
              [:skip [:int {:min 0}]]]]
   [:stages [:vector Stage]]])

(def ^:private valid-input? (m/validator DoctorInput))
(def ^:private valid-report? (m/validator DoctorReport))

(defn report-valid?
  "True when x conforms to the reusable doctor report contract."
  [x]
  (valid-report? x))

;; -----------------------------------------------------------------------------
;; JSON-safe evidence helpers
;; -----------------------------------------------------------------------------

(defn- ordered
  [xs]
  (->> xs (sort-by str) vec))

(defn- json-safe
  "Fold arbitrary addon evidence into values clojure.data.json can encode.
   Unknown live objects become strings; functions and host records never leak
   through a doctor report."
  [x]
  (cond
    (or (nil? x) (string? x) (boolean? x) (number? x) (keyword? x)) x
    (symbol? x) (str x)
    (map? x) (into {}
                   (map (fn [[k v]]
                          [(if (or (string? k) (keyword? k)) k (str k))
                           (json-safe v)]))
                   x)
    (set? x) (mapv json-safe (sort-by str x))
    (sequential? x) (mapv json-safe x)
    :else (str x)))

(defn- manifest-evidence
  "Select non-secret manifest fields. :addon/config is intentionally omitted."
  [spec]
  (cond-> (select-keys spec [:addon/id :addon/kind :addon/type :addon/version
                             :addon/init-ns :addon/init-fn :addon/description])
    true (assoc :addon/capabilities (ordered (:addon/capabilities spec #{}))
                :addon/dependencies (ordered (:addon/dependencies spec #{}))
                :addon/requires-capabilities
                (ordered (:addon/requires-capabilities spec #{})))))

(defn- throwable-message
  [^Throwable t]
  (or (ex-message t) (.getName (class t))))

(defn- stage
  [stage-name status summary evidence]
  {:stage stage-name
   :status status
   :summary summary
   :evidence (json-safe (or evidence {}))})

(defn- skipped
  [stage-name summary]
  (stage stage-name :skip summary {}))

;; -----------------------------------------------------------------------------
;; Manifest discovery + constructor resolution
;; -----------------------------------------------------------------------------

(defn- discovery-stage
  [addon-id discover-fn]
  (try
    (let [{:keys [specs errors]} (or (discover-fn) {})
          specs (vec (or specs []))
          matches (filterv #(= addon-id (:addon/id %)) specs)
          status (if (= 1 (count matches)) :pass :fail)
          summary (case (count matches)
                    0 "target manifest was not discovered"
                    1 "target manifest discovered exactly once"
                    "target manifest was discovered more than once")]
      {:spec (first matches)
       :specs specs
       :stage (stage :manifest-discovery status summary
                     (cond-> {:target-count (count matches)
                              :discovered-count (count specs)
                              :discovered-ids (ordered (map :addon/id specs))
                              :discovery-errors (vec (or errors []))}
                       (= 1 (count matches))
                       (assoc :manifest (manifest-evidence (first matches)))))})
    (catch Throwable t
      {:spec nil
       :specs []
       :stage (stage :manifest-discovery :fail
                     "manifest discovery threw"
                     {:error (throwable-message t)
                      :class (.getName (class t))})})))

(defn- resolve-constructor
  [spec]
  (some-> (requiring-resolve
           (symbol (:addon/init-ns spec) (:addon/init-fn spec)))
          var-get))

(defn- constructor-stage
  [spec resolve-fn]
  (if-not spec
    (skipped :constructor-resolution "no target manifest to resolve")
    (let [sym (str (:addon/init-ns spec) "/" (:addon/init-fn spec))]
      (try
        (let [constructor (resolve-fn spec)
              resolved? (ifn? constructor)]
          (stage :constructor-resolution
                 (if resolved? :pass :fail)
                 (if resolved?
                   "constructor resolved to an invokable value"
                   "constructor did not resolve to an invokable value")
                 {:symbol sym :resolved? resolved?}))
        (catch Throwable t
          (stage :constructor-resolution :fail "constructor resolution threw"
                 {:symbol sym
                  :error (throwable-message t)
                  :class (.getName (class t))}))))))

;; -----------------------------------------------------------------------------
;; Project dependency-boundary scan
;; -----------------------------------------------------------------------------

(def ^:private source-extensions #{".clj" ".cljc" ".cljs"})
(def ^:private max-source-files 2000)
(def ^:private reader-eof (Object.))

(defn- forbidden-host-lib?
  [x]
  (and (symbol? x)
       (or (= "hive-mcp" (name x))
           (str/ends-with? (str x) "/hive-mcp"))))

(defn- coordinate-findings
  [form]
  (letfn [(walk [path x]
            (cond
              (map? x)
              (mapcat (fn [[k v]]
                        (concat
                         (when (forbidden-host-lib? k)
                           [{:lib (str k)
                             :path (mapv pr-str (conj path k))}])
                         (walk (conj path k) v)))
                      x)

              (sequential? x)
              (mapcat (fn [[idx v]] (walk (conj path idx) v))
                      (map-indexed vector x))

              :else []))]
    (->> (walk [] form)
         distinct
         (sort-by (juxt :lib :path))
         vec)))

(defn- source-file?
  [^java.io.File f]
  (and (.isFile f)
       (some #(str/ends-with? (.getName f) %) source-extensions)))

(defn- read-ns-form
  [^java.io.File f]
  (let [features (if (str/ends-with? (.getName f) ".cljs") #{:cljs} #{:clj})]
    (with-open [reader (PushbackReader. (io/reader f))]
      (binding [*read-eval* false]
        (loop [forms-read 0]
          (if (>= forms-read 16)
            nil
            (let [form (clojure.core/read {:eof reader-eof
                                           :read-cond :allow
                                           :features features}
                                          reader)]
              (cond
                (identical? reader-eof form) nil
                (and (seq? form) (= 'ns (first form))) form
                :else (recur (inc forms-read))))))))))

(defn- forbidden-host-namespace?
  [x]
  (when (symbol? x)
    (let [s (str x)]
      (or (= s "hive-mcp")
          (str/starts-with? s "hive-mcp.")))))

(defn- relative-path
  [^java.io.File root ^java.io.File f]
  (str (.relativize (.toPath root) (.toPath f))))

(defn- scan-source-boundaries
  [^java.io.File root]
  (let [src-dir (io/file root "src")]
    (if-not (.isDirectory src-dir)
      {:source-file-count 0
       :findings []
       :errors ["src directory not found"]}
      (let [files (->> (file-seq src-dir)
                       (filter source-file?)
                       (take (inc max-source-files))
                       vec)
            over-limit? (> (count files) max-source-files)
            files (take max-source-files files)
            scanned
            (reduce
             (fn [acc f]
               (try
                 (let [ns-form (read-ns-form f)
                       refs (->> (tree-seq coll? seq ns-form)
                                 (filter forbidden-host-namespace?)
                                 (map str)
                                 distinct
                                 sort)]
                   (update acc :findings into
                           (map (fn [ref]
                                  {:file (relative-path root f)
                                   :namespace ref})
                                refs)))
                 (catch Throwable t
                   (update acc :errors conj
                           (str (relative-path root f) ": "
                                (throwable-message t))))))
             {:findings [] :errors []}
             files)]
        (cond-> (assoc scanned :source-file-count (count files))
          over-limit?
          (update :errors conj
                  (str "source file limit exceeded: " max-source-files)))))))

(defn scan-project-boundary
  "Read-only scan of a Clojure addon project.

   Detects direct hive-mcp dependency coordinates anywhere in deps.edn and
   production ns forms under src/ that reference hive-mcp.*. Returns a stage
   fragment {:status :pass|:fail|:skip :evidence {...}}."
  [directory]
  (if (str/blank? directory)
    {:status :skip
     :evidence {:reason "directory was not supplied"}}
    (try
      (let [root (.getCanonicalFile (io/file directory))]
        (if-not (.isDirectory root)
          {:status :fail
           :evidence {:directory (.getPath root)
                      :scan-errors ["directory does not exist"]}}
          (let [deps-file (io/file root "deps.edn")
                deps-read (if (.isFile deps-file)
                            (try
                              {:value (binding [*read-eval* false]
                                        (edn/read-string (slurp deps-file)))}
                              (catch Throwable t
                                {:error (str "deps.edn: " (throwable-message t))}))
                            {:error "deps.edn not found"})
                dependency-findings (if-let [deps (:value deps-read)]
                                      (coordinate-findings deps)
                                      [])
                source-scan (scan-source-boundaries root)
                scan-errors (cond-> (vec (:errors source-scan))
                              (:error deps-read) (conj (:error deps-read)))
                fail? (or (seq dependency-findings)
                          (seq (:findings source-scan))
                          (seq scan-errors))]
            {:status (if fail? :fail :pass)
             :evidence {:directory (.getPath root)
                        :deps-file (.getPath deps-file)
                        :source-file-count (:source-file-count source-scan)
                        :forbidden-dependencies dependency-findings
                        :forbidden-source-namespaces (vec (:findings source-scan))
                        :scan-errors scan-errors}})))
      (catch Throwable t
        {:status :fail
         :evidence {:directory directory
                    :scan-errors [(throwable-message t)]
                    :class (.getName (class t))}}))))

(defn- dependency-stage
  [addon-id spec specs directory solve-fn scan-project-fn]
  (if-not spec
    (skipped :dependency-boundary "no target manifest to analyze")
    (try
      (let [plan (solve-fn specs)
            project (scan-project-fn directory)]
        (if (r/err? plan)
          (stage :dependency-boundary :fail "dependency solver failed"
                 {:solver-error (:error plan)
                  :solver-message (:message plan)
                  :project (:evidence project)})
          (let [graph {:missing (ordered (get (:missing plan) addon-id #{}))
                       :cycle? (contains? (:cycles plan #{}) addon-id)
                       :unmet-capabilities
                       (ordered (get (:unmet-capabilities plan) addon-id #{}))
                       :duplicate-count (get (:duplicates plan) addon-id 0)
                       :mount-order (vec (map :addon/id (:ordered plan)))}
                graph-fail? (or (seq (:missing graph))
                                (:cycle? graph)
                                (seq (:unmet-capabilities graph))
                                (pos? (:duplicate-count graph)))
                fail? (or graph-fail? (= :fail (:status project)))]
            (stage :dependency-boundary
                   (if fail? :fail :pass)
                   (if fail?
                     "dependency boundary has findings"
                     "dependency graph and project boundary are clean")
                   {:graph graph
                    :project-status (:status project)
                    :project (:evidence project)}))))
      (catch Throwable t
        (stage :dependency-boundary :fail "dependency scan threw"
               {:error (throwable-message t)
                :class (.getName (class t))})))))

;; -----------------------------------------------------------------------------
;; Live registry, contract, capability, and Emacs probes
;; -----------------------------------------------------------------------------

(defn- contract-evidence
  [result]
  (if (r/ok? result)
    {:valid? true}
    (cond-> {:valid? false :error (:error result)}
      (:message result) (assoc :message (:message result))
      (:method result) (assoc :method (:method result))
      (:explanation result) (assoc :explanation (:explanation result)))))

(defn- lifecycle-stage
  [addon-id get-entry-fn validate-addon-fn health-fn]
  (try
    (if-let [{:keys [addon state registered-at init-time init-result]} (get-entry-fn addon-id)]
      (let [contract (validate-addon-fn addon)
            health (try
                     (health-fn addon)
                     (catch Throwable t
                       {:status :down
                        :details {:probe-error (throwable-message t)}}))
            pass? (and (= :active state)
                       (r/ok? contract)
                       (= :ok (:status health)))]
        (stage :lifecycle-smoke
               (if pass? :pass :fail)
               (if pass?
                 "live addon is active, contract-valid, and healthy"
                 "live addon failed lifecycle or contract checks")
               {:registered? true
                :state state
                :registered-at (some-> registered-at str)
                :initialized-at (some-> init-time str)
                :init-result (select-keys (or init-result {})
                                          [:success? :errors
                                           :already-initialized?])
                :contract (contract-evidence contract)
                :health health}))
      (stage :lifecycle-smoke :fail "addon is absent from the live registry"
             {:registered? false}))
    (catch Throwable t
      (stage :lifecycle-smoke :fail "lifecycle probe threw"
             {:error (throwable-message t)
              :class (.getName (class t))}))))

(defn- capability-stage
  [addon-id spec get-entry-fn capabilities-fn addon-id-fn addon-type-fn]
  (if-not spec
    (skipped :capability-comparison "no target manifest to compare")
    (try
      (if-let [instance (:addon (get-entry-fn addon-id))]
        (let [declared (set (:addon/capabilities spec #{}))
              runtime (set (capabilities-fn instance))
              missing (set/difference declared runtime)
              extra (set/difference runtime declared)
              runtime-id (addon-id-fn instance)
              runtime-type (addon-type-fn instance)
              identity-match? (= addon-id runtime-id)
              type-match? (= (:addon/type spec) runtime-type)
              pass? (and (empty? missing) (empty? extra)
                         identity-match? type-match?)]
          (stage :capability-comparison
                 (if pass? :pass :fail)
                 (if pass?
                   "manifest and runtime identity/capabilities match"
                   "manifest and runtime identity/capabilities differ")
                 {:declared (ordered declared)
                  :runtime (ordered runtime)
                  :missing-at-runtime (ordered missing)
                  :runtime-only (ordered extra)
                  :identity-match? identity-match?
                  :type-match? type-match?
                  :runtime-id runtime-id
                  :runtime-type runtime-type}))
        (skipped :capability-comparison "addon is absent from the live registry"))
      (catch Throwable t
        (stage :capability-comparison :fail "capability comparison threw"
               {:error (throwable-message t)
                :class (.getName (class t))})))))

(defn- feature-names
  [input spec]
  (let [features (if (contains? input :emacs-features)
                   (:emacs-features input)
                   (get-in spec [:addon/doctor :emacs/features] []))]
    (->> features
         (map #(cond
                 (keyword? %) (name %)
                 (symbol? %) (str %)
                 :else %))
         distinct
         sort
         vec)))

(defn- feature-probe
  [feature timeout-ms emacs-eval-fn]
  (if-not (and (string? feature)
               (re-matches #"[A-Za-z0-9][A-Za-z0-9+*./_:@~-]*" feature))
    {:feature (str feature)
     :reachable? false
     :loaded? false
     :error "invalid Emacs feature symbol"}
    (try
      (let [{:keys [success result error timed-out]} (emacs-eval-fn
                                                      (format "(featurep '%s)" feature)
                                                      timeout-ms)
            loaded? (and success (= "t" (str/trim (str result))))]
        (cond-> {:feature feature
                 :reachable? (boolean success)
                 :loaded? (boolean loaded?)}
          timed-out (assoc :timed-out? true)
          (and (not loaded?) error) (assoc :error (str error))))
      (catch Throwable t
        {:feature feature
         :reachable? false
         :loaded? false
         :error (throwable-message t)
         :class (.getName (class t))}))))

(defn- emacs-stage
  [input spec emacs-eval-fn]
  (let [features (feature-names input spec)]
    (if (empty? features)
      (skipped :emacs-features "no Emacs feature expectations were declared")
      (let [timeout-ms (:timeout-ms input 3000)
            probes (mapv #(feature-probe % timeout-ms emacs-eval-fn) features)
            pass? (every? :loaded? probes)]
        (stage :emacs-features
               (if pass? :pass :fail)
               (if pass?
                 "all expected features are loaded in live Emacs"
                 "one or more expected features are unavailable")
               {:timeout-ms timeout-ms
                :features probes})))))

;; -----------------------------------------------------------------------------
;; Composition root
;; -----------------------------------------------------------------------------

(defn- default-emacs-eval
  [code timeout-ms]
  (emacs/eval-elisp-with-timeout code timeout-ms))

(def ^:private default-ports
  {:discover-fn mount/discover-specs
   :solve-fn mount/solve
   :resolve-constructor-fn resolve-constructor
   :scan-project-fn scan-project-boundary
   :get-entry-fn registry/get-addon-entry
   :validate-addon-fn addon-schema/validate-addon
   ;; Resolve protocol method Vars at invocation time. `extend-type` rebinds
   ;; the protocol dispatch fns as addons load; storing the function values in
   ;; this map would freeze the pre-addon dispatchers during startup.
   :health-fn (fn [addon] (addon/health addon))
   :capabilities-fn (fn [addon] (addon/capabilities addon))
   :addon-id-fn (fn [addon] (addon/addon-id addon))
   :addon-type-fn (fn [addon] (addon/addon-type addon))
   :emacs-eval-fn default-emacs-eval
   :now-fn #(Instant/now)})

(defn run-doctor
  "Run all addon doctor stages and return a hive-dsl Result.

   The optional ports map is a DIP seam for deterministic tests and alternate
   hosts. A diagnostic finding is represented by report :ok? false inside an
   r/ok; r/err is reserved for invalid input/report contracts."
  ([input] (run-doctor input {}))
  ([input port-overrides]
   (if-not (valid-input? input)
     (r/err :addon-doctor/invalid-input
            {:message "Invalid addon doctor input"
             :explanation (me/humanize (m/explain DoctorInput input))})
     (let [{:keys [discover-fn solve-fn resolve-constructor-fn scan-project-fn
                   get-entry-fn validate-addon-fn health-fn capabilities-fn
                   addon-id-fn addon-type-fn emacs-eval-fn now-fn]}
           (merge default-ports port-overrides)
           addon-id (:addon-id input)
           {:keys [spec specs] discovery :stage}
           (discovery-stage addon-id discover-fn)
           stages [discovery
                   (constructor-stage spec resolve-constructor-fn)
                   (dependency-stage addon-id spec specs (:directory input)
                                     solve-fn scan-project-fn)
                   (lifecycle-stage addon-id get-entry-fn
                                    validate-addon-fn health-fn)
                   (capability-stage addon-id spec get-entry-fn capabilities-fn
                                     addon-id-fn addon-type-fn)
                   (emacs-stage input spec emacs-eval-fn)]
           counts (frequencies (map :status stages))
           report {:report/type :addon-doctor
                   :schema-version 1
                   :observed-at (str (now-fn))
                   :addon/id addon-id
                   :request (cond-> {:timeout-ms (:timeout-ms input 3000)
                                     :emacs-features (feature-names input spec)}
                              (:directory input)
                              (assoc :directory (:directory input)))
                   :ok? (zero? (get counts :fail 0))
                   :summary {:pass (get counts :pass 0)
                             :fail (get counts :fail 0)
                             :skip (get counts :skip 0)}
                   :stages stages}]
       (if (valid-report? report)
         (r/ok report)
         (r/err :addon-doctor/invalid-report
                {:message "Addon doctor produced an invalid report"
                 :explanation (me/humanize (m/explain DoctorReport report))}))))))
