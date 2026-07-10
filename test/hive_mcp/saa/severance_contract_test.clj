(ns hive-mcp.saa.severance-contract-test
  "W3 contract suite (C9): static severance of the hive-claude SDK.

   C9 orchestrator.clj contains NO 'hive-claude' occurrence, and no ns under
      src/hive_mcp/saa/ nor src/hive_mcp/agent/saa/ references hive-claude."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private hive-claude-re #"hive-claude")

(def ^:private orchestrator-path
  "src/hive_mcp/agent/saa/orchestrator.clj")

(def ^:private saa-src-dirs
  ["src/hive_mcp/saa" "src/hive_mcp/agent/saa"])

(defn- clj-files
  "Every .clj/.cljc file under `dir` (recursive), or [] if absent."
  [dir]
  (let [root (io/file dir)]
    (if (.isDirectory root)
      (->> (file-seq root)
           (filter #(.isFile %))
           (filter #(re-find #"\.cljc?$" (.getName %))))
      [])))

;; =============================================================================
;; C9 — orchestrator.clj is hive-claude-free
;; =============================================================================

(deftest c9-orchestrator-has-no-hive-claude
  (testing "orchestrator.clj contains no 'hive-claude' occurrence"
    (let [f (io/file orchestrator-path)]
      (is (.exists f) (str orchestrator-path " must exist on the test path"))
      (is (nil? (re-find hive-claude-re (slurp f)))
          "orchestrator.clj must not reference hive-claude"))))

;; =============================================================================
;; C9 — no SAA ns references hive-claude
;; =============================================================================

(deftest c9-no-saa-ns-references-hive-claude
  (testing "no ns under saa/ nor agent/saa/ references hive-claude"
    (let [files   (mapcat clj-files saa-src-dirs)
          offenders (->> files
                         (filter #(re-find hive-claude-re (slurp %)))
                         (mapv #(.getPath %)))]
      (is (seq files) "the SAA source dirs must contain Clojure files")
      (is (= [] offenders)
          (str "these SAA namespaces still reference hive-claude: "
               (str/join ", " offenders))))))
