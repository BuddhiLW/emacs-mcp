(ns hive-mcp.tools.catchup.relevance-test
  "Pure-fn tests for catchup axiom relevance scoring."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.catchup.relevance :as rel]))

(def hive-ctx
  (rel/build-context
   {:project-id "hive-mcp"
    :co-loaded-entries
    [{:tags ["clojure" "fp" "decision" "scope:project:hive-mcp"]}
     {:tags ["ddd" "convention" "scope:project:hive-mcp"]}
     {:tags ["agent:coordinator" "session" "scope:project:hive-mcp"]}]}))

(deftest project-keywords-test
  (testing "splits project-id on dash, drops blanks"
    (is (= #{"hive-mcp" "hive" "mcp"} (rel/project-keywords "hive-mcp"))))
  (testing "single-token project"
    (is (contains? (rel/project-keywords "shark") "shark")))
  (testing "global / nil yields empty"
    (is (= #{} (rel/project-keywords nil)))
    (is (= #{} (rel/project-keywords "global")))))

(deftest topic-tags-test
  (testing "drops noise prefixes (agent:, scope:, kg:, qn:, ns:)"
    (is (= #{"clojure" "fp"}
           (rel/topic-tags ["clojure" "fp" "agent:coordinator"
                            "scope:project:hive-mcp" "kg:edge"]))))
  (testing "drops memory-shape and status words"
    (is (= #{"clojure"}
           (rel/topic-tags ["axiom" "clojure" "todo" "permanent" "global"]))))
  (testing "noise-only collection -> empty set"
    (is (= #{} (rel/topic-tags ["agent:coordinator" "scope:global" "axiom"])))))

(deftest axiom-relevance-test
  (testing "catchup-priority always pierces (score 1.0)"
    (is (= 1.0 (rel/axiom-relevance
                {:tags ["catchup-priority" "windows-ntlm"]}
                hive-ctx))))
  (testing "matching scope:project:<current> always pierces"
    (is (= 1.0 (rel/axiom-relevance
                {:tags ["bufferbloat" "scope:project:hive-mcp"]}
                hive-ctx))))
  (testing "off-project axiom with no overlap scores 0.0"
    (is (= 0.0 (rel/axiom-relevance
                {:tags ["windows-ntlm" "credential-security"
                        "scope:project:probe"]}
                hive-ctx))))
  (testing "single-tag project-keyword overlap scores 0.5"
    (is (= 0.5 (rel/axiom-relevance
                {:tags ["mcp" "tooling"]}
                hive-ctx))))
  (testing "multi-tag overlap scores 1.0"
    (is (= 1.0 (rel/axiom-relevance
                {:tags ["clojure" "fp" "general"]}
                hive-ctx))))
  (testing "vocabulary-only match (no project-keyword) still scores"
    (is (>= (rel/axiom-relevance
             {:tags ["ddd" "bounded-context"]}
             hive-ctx)
            0.5))))

(def ^:private fixture
  ;; Mix of scope-pinned, catchup-priority, on-topic, and off-topic axioms.
  [{:id "a1" :type "axiom" :tags ["catchup-priority" "windows-ntlm"]}
   {:id "a2" :type "axiom" :tags ["scope:project:hive-mcp" "swarm"]}
   {:id "a3" :type "axiom" :tags ["clojure" "fp"]}
   {:id "a4" :type "axiom" :tags ["mcp"]}
   {:id "a5" :type "axiom" :tags ["windows-ntlm" "credential-reuse"]}
   {:id "a6" :type "axiom" :tags ["bufferbloat" "tcp-window"]}
   {:id "a7" :type "axiom" :tags ["typography"]}
   {:id "a8" :type "axiom" :tags ["java-memory-model" "jvm"]}])

(deftest filter-by-relevance-default-threshold-test
  (let [kept (rel/filter-by-relevance fixture hive-ctx)]
    (testing "always-pierce + on-topic kept"
      (is (= ["a1" "a2" "a3" "a4"] (mapv :id kept))))
    (testing "noise axioms dropped"
      (is (every? #(not (contains? (set (mapv :id kept)) %))
                  ["a5" "a6" "a7" "a8"])))))

(deftest filter-by-relevance-permissive-threshold-test
  (testing "threshold 0.0 keeps everything"
    (is (= 8 (count (rel/filter-by-relevance fixture hive-ctx 0.0)))))
  (testing "threshold 1.0 keeps only always-pierce + multi-overlap"
    (let [kept (rel/filter-by-relevance fixture hive-ctx 1.0)]
      (is (= #{"a1" "a2" "a3"} (set (mapv :id kept)))))))

(deftest empty-context-graceful-test
  (testing "nil project-id with no vocabulary keeps only catchup-priority"
    (let [empty-ctx (rel/build-context {:project-id nil :co-loaded-entries []})
          kept (rel/filter-by-relevance fixture empty-ctx)]
      (is (= ["a1"] (mapv :id kept))))))
