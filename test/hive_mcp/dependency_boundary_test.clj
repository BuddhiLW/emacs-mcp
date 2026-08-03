(ns hive-mcp.dependency-boundary-test
  "Executable architecture guard for the host→addon boundary: hive-mcp must not
   name an addon namespace or carry an addon artifact in :deps."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private forbidden-addon-ns-prefixes
  #{"hive-emacs"})

(defn- clojure-sources
  []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))

(defn- ns-form
  [^java.io.File file]
  (try
    (let [form (read-string {:read-cond :preserve} (slurp file))]
      (when (and (seq? form) (= 'ns (first form))) form))
    (catch Exception _ nil)))

(defn- required-namespaces
  "Every namespace symbol NS-FORM loads at compile time."
  [form]
  (->> form
       (filter #(and (seq? %) (#{:require :use} (first %))))
       (mapcat rest)
       (map #(cond (symbol? %) % (sequential? %) (first %)))
       (filter symbol?)
       (map str)))

(defn- addon-require?
  [ns-name]
  (some #(or (= ns-name %) (str/starts-with? ns-name (str % ".")))
        forbidden-addon-ns-prefixes))

(deftest production-code-never-requires-an-addon-namespace
  (doseq [file (clojure-sources)
          :let [form (ns-form file)]
          :when form
          required (required-namespaces form)]
    (is (not (addon-require? required))
        (str "addon namespace " required " required from " (.getPath ^java.io.File file)))))

(deftest dependency-map-has-no-addon-artifact
  (testing "addons arrive through deployment config, never through :deps"
    (let [deps (edn/read-string (slurp "deps.edn"))
          artifact-names (->> deps :deps keys (map str) set)]
      (doseq [prefix forbidden-addon-ns-prefixes]
        (is (not-any? #(str/includes? % prefix) artifact-names)
            (str prefix " must not be a hive-mcp dependency"))))))
