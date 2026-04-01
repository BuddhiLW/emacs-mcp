(ns clj-kondo-mcp.core
  "Core analysis functions using clj-kondo.
   Pure functions that transform clj-kondo output into useful results."
  (:require [babashka.pods :as pods]))

(defn- log-warn
  "Log warning to stderr (works in both bb standalone and JVM classpath)."
  [& args]
  (binding [*out* *err*]
    (println (str "[WARN] clj-kondo-mcp: " (apply str (interpose " " args))))
    (flush)))

;; Load clj-kondo pod (version from bb.edn :pods)
(pods/load-pod 'clj-kondo/clj-kondo "2026.01.12")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

(def ^:const default-timeout-ms
  "Default timeout for clj-kondo analysis (30 seconds)."
  30000)

(defn run-analysis
  "Run clj-kondo analysis on a path.
   Returns {:findings [...] :analysis {...}}
   Accepts :timeout-ms to bound execution time (default 30s).
   Returns {:findings [] :timed-out true} when timeout is hit."
  [path & {:keys [config timeout-ms] :or {config {} timeout-ms default-timeout-ms}}]
  (let [f (future
            (clj-kondo/run!
             {:lint [path]
              :config (merge {:output {:format :edn}
                              :analysis {:var-usages true
                                         :var-definitions true
                                         :namespace-definitions true
                                         :namespace-usages true
                                         :arglists true
                                         :keywords true
                                         :protocol-impls true}}
                             config)}))
        result (deref f timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do
        (future-cancel f)
        (log-warn "analysis timed out" {:path path :timeout-ms timeout-ms})
        {:findings [] :analysis {} :timed-out true})
      result)))

(defn analyze
  "Analyze a path and return structured analysis data."
  [path]
  (let [{:keys [analysis findings]} (run-analysis path)]
    {:var-definitions (count (:var-definitions analysis))
     :var-usages (count (:var-usages analysis))
     :namespaces (count (:namespace-definitions analysis))
     :findings (count findings)
     :analysis analysis}))

(defn find-callers
  "Find all call sites of a specific var.
   Returns list of {:filename :row :col :from :from-var :arity}"
  [path ns-name var-name]
  (let [{:keys [analysis]} (run-analysis path)
        var-usages (:var-usages analysis)
        target-ns (symbol ns-name)
        target-var (symbol var-name)]
    (->> var-usages
         (filter #(and (= (:to %) target-ns)
                       (= (:name %) target-var)))
         (map #(select-keys % [:filename :row :col :from :from-var :arity]))
         (sort-by (juxt :filename :row)))))

(defn find-calls
  "Find all vars that a function calls.
   Returns list of {:filename :row :col :to :name :arity}"
  [path ns-name var-name]
  (let [{:keys [analysis]} (run-analysis path)
        var-usages (:var-usages analysis)
        source-ns (symbol ns-name)
        source-var (symbol var-name)]
    (->> var-usages
         (filter #(and (= (:from %) source-ns)
                       (= (:from-var %) source-var)))
         (map #(select-keys % [:filename :row :col :to :name :arity]))
         (sort-by (juxt :to :name)))))

(defn find-var
  "Find definition(s) of a var.
   Returns list of {:filename :row :col :ns :name :doc :arglists}"
  [path var-name & [ns-name]]
  (let [{:keys [analysis]} (run-analysis path)
        var-defs (:var-definitions analysis)
        target-var (symbol var-name)
        target-ns (when ns-name (symbol ns-name))]
    (->> var-defs
         (filter #(and (= (:name %) target-var)
                       (or (nil? target-ns)
                           (= (:ns %) target-ns))))
         (map #(select-keys % [:filename :row :col :end-row :end-col
                               :ns :name :doc :arglist-strs :private :macro])))))

(defn namespace-graph
  "Get namespace dependency graph.
   Returns {:nodes [...] :edges [...]} suitable for visualization."
  [path]
  (let [{:keys [analysis]} (run-analysis path)
        ns-defs (:namespace-definitions analysis)
        ns-usages (:namespace-usages analysis)]
    {:nodes (mapv #(select-keys % [:name :filename :doc]) ns-defs)
     :edges (mapv #(hash-map :from (:from %)
                             :to (:to %)
                             :alias (:alias %))
                  ns-usages)}))

(defn lint
  "Lint a path and return findings.
   Level can be :error or :warning.
   Accepts :timeout-ms to bound analysis time (default 30s).
   Returns partial results with :timed-out metadata when timeout is hit."
  [path & {:keys [level timeout-ms] :or {level :warning timeout-ms default-timeout-ms}}]
  (let [{:keys [findings timed-out]} (run-analysis path :timeout-ms timeout-ms)
        filtered (->> findings
                      (filter #(case level
                                 :error (= (:level %) :error)
                                 :warning true))
                      (map #(select-keys % [:filename :row :col :level :type :message])))]
    (if timed-out
      {:findings filtered
       :timed-out true
       :warning (format "Analysis timed out after %dms — results may be incomplete" timeout-ms)}
      filtered)))

(defn unused-vars
  "Find unused private vars in a codebase."
  [path]
  (let [{:keys [analysis]} (run-analysis path)
        var-defs (:var-definitions analysis)
        var-usages (:var-usages analysis)
        ;; Build set of used vars
        used-vars (into #{}
                        (map (fn [{:keys [to name]}]
                               [(symbol (str to)) name])
                             var-usages))
        ;; Find private vars not in used-vars
        private-defs (filter :private var-defs)]
    (->> private-defs
         (remove #(contains? used-vars [(:ns %) (:name %)]))
         (map #(select-keys % [:filename :row :col :ns :name])))))

(comment
  ;; REPL testing
  (analyze "src")
  (find-callers "src" "clj-kondo-mcp.core" "run-analysis")
  (find-calls "src" "clj-kondo-mcp.core" "analyze")
  (find-var "src" "analyze")
  (namespace-graph "src")
  (lint "src")
  (unused-vars "src"))
