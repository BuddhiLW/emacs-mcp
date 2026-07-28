(ns hive-mcp.agent.selfcall-prevention-test
  "Integration tests for headless self-call prevention.

   Tests the guards and registry layers that prevent recursive
   ling spawning in headless mode:
   (a) guards/child-ling? detection via HIVE_MCP_ROLE env
   (b) guards/ling-depth tracking via HIVE_LING_DEPTH env
   (c) guards/child-ling-env generates correct env map
   (d) registry/get-child-ling-tools excludes dangerous tools
   (e) registry/child-ling-excluded? identifies dangerous tools"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.tools.registry :as registry]))

(def ^:private child-ling-excluded? registry/child-ling-excluded?)

;;; =============================================================================
;;; (a) guards/child-ling? — env-based detection
;;; =============================================================================

(deftest test-child-ling?-false-normally
  (testing "child-ling? returns false in normal coordinator process (no env)"
    (with-redefs [guards/get-env-var (fn [_] nil)]
      (is (false? (guards/child-ling?))))))

(deftest test-child-ling?-true-with-role-env
  (testing "child-ling? returns true when HIVE_MCP_ROLE=child-ling"
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_MCP_ROLE" "child-ling"
                                         nil))]
      (is (true? (guards/child-ling?))))))

(deftest test-child-ling?-false-for-other-role
  (testing "child-ling? returns false when HIVE_MCP_ROLE is something else"
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_MCP_ROLE" "coordinator"
                                         nil))]
      (is (false? (guards/child-ling?))))))

;;; =============================================================================
;;; (b) guards/ling-depth — depth tracking
;;; =============================================================================

(deftest test-ling-depth-zero-normally
  (testing "ling-depth returns 0 when HIVE_LING_DEPTH unset"
    (with-redefs [guards/get-env-var (fn [_] nil)]
      (is (= 0 (guards/ling-depth))))))

(deftest test-ling-depth-parses-from-env
  (testing "ling-depth returns parsed integer N from HIVE_LING_DEPTH=N"
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_LING_DEPTH" "3"
                                         nil))]
      (is (= 3 (guards/ling-depth))))))

(deftest test-ling-depth-handles-bad-values
  (testing "ling-depth returns 0 for non-numeric string"
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_LING_DEPTH" "not-a-number"
                                         nil))]
      (is (= 0 (guards/ling-depth)))))

  (testing "ling-depth returns 0 for empty string"
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_LING_DEPTH" ""
                                         nil))]
      (is (= 0 (guards/ling-depth))))))

(deftest test-ling-depth-increments-across-generations
  (testing "depth tracks nesting: coordinator(0) -> child(1) -> grandchild(2)"
    ;; Generation 0 (coordinator)
    (with-redefs [guards/get-env-var (fn [_] nil)]
      (is (= 0 (guards/ling-depth)))
      (let [child-env (guards/child-ling-env)]
        (is (= "1" (get child-env "HIVE_LING_DEPTH")))))

    ;; Generation 1 (first child)
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_LING_DEPTH" "1"
                                         "HIVE_MCP_ROLE" "child-ling"
                                         nil))]
      (is (= 1 (guards/ling-depth)))
      (is (true? (guards/child-ling?)))
      (let [grandchild-env (guards/child-ling-env)]
        (is (= "2" (get grandchild-env "HIVE_LING_DEPTH")))))))

;;; =============================================================================
;;; (c) guards/child-ling-env — correct env map
;;; =============================================================================

(deftest test-child-ling-env-from-coordinator
  (testing "child-ling-env from coordinator sets role and depth=1"
    (with-redefs [guards/get-env-var (fn [_] nil)]
      (let [env (guards/child-ling-env)]
        (is (map? env))
        (is (= "child-ling" (get env "HIVE_MCP_ROLE")))
        (is (= "1" (get env "HIVE_LING_DEPTH")))))))

(deftest test-child-ling-env-increments-existing-depth
  (testing "child-ling-env increments depth from existing child"
    (with-redefs [guards/get-env-var (fn [k]
                                       (case k
                                         "HIVE_LING_DEPTH" "2"
                                         "HIVE_MCP_ROLE" "child-ling"
                                         nil))]
      (let [env (guards/child-ling-env)]
        (is (= "child-ling" (get env "HIVE_MCP_ROLE")))
        (is (= "3" (get env "HIVE_LING_DEPTH")))))))

(deftest test-child-ling-env-always-has-both-keys
  (testing "child-ling-env always contains exactly HIVE_MCP_ROLE and HIVE_LING_DEPTH"
    (with-redefs [guards/get-env-var (fn [_] nil)]
      (let [env (guards/child-ling-env)]
        (is (contains? env "HIVE_MCP_ROLE"))
        (is (contains? env "HIVE_LING_DEPTH"))
        (is (= 2 (count env)))))))

;;; =============================================================================
;;; (d) registry/get-child-ling-tools — excludes dangerous tools
;;; =============================================================================

(deftest test-get-child-ling-tools-excludes-dangerous
  (testing "get-child-ling-tools drops every name in the exclusion set"
    ;; Assert the RULE against the set the production code owns, not a
    ;; hand-copied roster: the tool surface was consolidated (agent / wave /
    ;; workflow / delegate / olympus are now `swarm` SUBCOMMANDS), so pinning
    ;; those names here asserted a taxonomy that no longer exists.
    (let [tool-names (set (map :name (registry/get-child-ling-tools)))]
      (doseq [excluded registry/child-excluded-tool-names]
        (is (not (contains? tool-names excluded))
            (str excluded " must be excluded from child lings"))))))

(deftest test-get-child-ling-tools-keeps-everything-else
  (testing "get-child-ling-tools removes the exclusion set and NOTHING else"
    ;; Registry-independent: which tools are registered differs between a cold
    ;; JVM and a live image, so the invariant is the set DIFFERENCE, not the
    ;; presence of any particular tool.
    (let [all-names   (set (map :name (registry/get-all-tools :include-deprecated? true)))
          child-names (set (map :name (registry/get-child-ling-tools)))]
      (is (= (clojure.set/difference all-names registry/child-excluded-tool-names)
             child-names)
          "child tools = all tools minus exactly the exclusion set"))))

(deftest test-get-child-ling-tools-excludes-dangerous-deprecated-shims
  (testing "deprecated swarm_*/delegate_*/lings_* shims are excluded"
    (let [tools           (registry/get-child-ling-tools)
          ;; Only check deprecated tools — non-deprecated tools with matching
          ;; prefixes (e.g. delegate_drone) are filtered by exact name, not prefix
          deprecated-names (->> tools
                                (filter :deprecated)
                                (map :name)
                                set)]
      (doseq [name deprecated-names]
        (is (not (str/starts-with? name "swarm_"))
            (str "swarm_ deprecated shim should be excluded: " name))
        (is (not (str/starts-with? name "delegate_"))
            (str "delegate_ deprecated shim should be excluded: " name))
        (is (not (str/starts-with? name "lings_"))
            (str "lings_ deprecated shim should be excluded: " name))))))

(deftest test-get-child-ling-tools-keeps-safe-deprecated-shims
  (testing "safe deprecated shims (hivemind_*, mcp_memory_*, magit_*) are kept"
    (let [all-tools       (registry/get-all-tools :include-deprecated? true)
          child-tools     (registry/get-child-ling-tools)
          child-names     (set (map :name child-tools))
          safe-deprecated (filter (fn [{:keys [name deprecated]}]
                                    (and deprecated
                                         (some #(str/starts-with? name %)
                                               ["hivemind_" "mcp_memory_" "magit_" "kg_"])))
                                  all-tools)]
      ;; Every safe deprecated shim from all-tools should appear in child-tools
      (doseq [{:keys [name]} safe-deprecated]
        (is (contains? child-names name)
            (str "safe deprecated shim should be kept: " name))))))

(deftest test-get-child-ling-tools-returns-non-empty-vector
  (testing "get-child-ling-tools returns a non-empty vector of tools"
    (let [tools (registry/get-child-ling-tools)]
      (is (vector? tools))
      (is (pos? (count tools))
          "child ling should have at least some tools available"))))

(deftest test-get-child-ling-tools-fewer-than-all-tools
  (testing "child ling tools are strictly fewer than all tools"
    (let [all-count   (count (registry/get-all-tools :include-deprecated? true))
          child-count (count (registry/get-child-ling-tools))]
      (is (< child-count all-count)
          "child ling should have fewer tools than coordinator"))))

;;; =============================================================================
;;; (e) registry/child-ling-excluded? — identification of dangerous tools
;;; =============================================================================

(deftest test-child-ling-excluded?-all-dangerous-names
  (testing "child-ling-excluded? returns truthy for every name in the exclusion set"
    (doseq [name registry/child-excluded-tool-names]
      (is (child-ling-excluded? {:name name})
          (str name " should be identified as excluded")))))

(deftest test-child-ling-excluded?-safe-names
  (testing "child-ling-excluded? returns falsy for names outside the set"
    (doseq [name ["memory" "hivemind" "kanban" "session" "kg" "magit"
                  "preset" "analysis" "lsp" "cider" "project" "config"]]
      (is (not (child-ling-excluded? {:name name}))
          (str name " should NOT be identified as excluded")))))

(deftest test-child-ling-excluded?-is-exact-name-match-not-prefix
  (testing "exclusion is exact-name membership — a prefix is not enough"
    ;; The deprecated swarm_*/delegate_*/lings_* shims this suite used to pin
    ;; no longer exist; the guard is a set-membership test on the CONSOLIDATED
    ;; root name, so prefixed names must NOT be swept in.
    (doseq [name ["swarm_spawn" "swarm_status" "delegate_drone"
                  "lings_available" "multi_thing" "emacs_ext"]]
      (is (not (child-ling-excluded? {:name name}))
          (str name " is a prefix, not a member — must not be excluded")))
    (is (not (child-ling-excluded? {:name "swarm_something_new"})))
    ;; …and :deprecated is not consulted at all.
    (is (child-ling-excluded? {:name "swarm" :deprecated true}))
    (is (child-ling-excluded? {:name "swarm" :deprecated false}))))

;;; =============================================================================
;;; (f) trifecta — the exclusion predicate IS set membership, for any name
;;; =============================================================================

(def ^:private gen-tool-map
  (gen/fmap (fn [n] {:name n})
            (gen/one-of [(gen/elements (vec registry/child-excluded-tool-names))
                         gen/string-alphanumeric])))

(deftrifecta child-ling-excluded-membership
  #'hive-mcp.tools.registry/child-ling-excluded?
  {:golden-path "test/golden/child_ling_excluded.edn"
   :cases       {:swarm    {:name "swarm"}
                 :multi    {:name "multi"}
                 :emacs    {:name "emacs"}
                 :memory   {:name "memory"}
                 :prefixed {:name "swarm_spawn"}
                 :nil-name {:name nil}}
   :xf          boolean
   :gen         gen-tool-map
   :pred        #(contains? #{true false nil} %)
   :num-tests   200
   :mutations   [["always-true" (fn [_] true)]
                 ["always-false" (fn [_] false)]
                 ["prefix-match" (fn [{:keys [name]}]
                                   (boolean
                                    (and name
                                         (some #(str/starts-with? name %)
                                               registry/child-excluded-tool-names))))]]})
