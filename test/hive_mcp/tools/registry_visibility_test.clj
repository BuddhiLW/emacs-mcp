(ns hive-mcp.tools.registry-visibility-test
  "Trifecta + property tests for the MCP tool-surface visibility gate.

   `apply-visibility-gate` marks every tool whose :name is not in the
   configured allowlist as :deprecated, so tools/list hides it while
   tools/call keeps it callable (back-compat). Also covers the new `web`
   consolidated root's fetch/search delegation."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as tc-prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.tools.registry :as reg]
            [hive-mcp.tools.consolidated.web :as web]
            [hive-mcp.tools.consolidated.swarm :as swarm]
            [hive-mcp.tools.consolidated.project :as project]
            [hive-mcp.tools.consolidated.code :as code]
            [hive-mcp.extensions.registry :as ext]))

;; =============================================================================
;; Fixtures / helpers
;; =============================================================================

(def target-allowlist
  "The neat 9: the 8 substrate generators (classifier-kernel partition
   f: tool → primary substrate) + `multi`, the universal entry-point spine.
   auth/events are cross-cutting (not substrates) and folded out of the visible
   surface; folded roots (preset, migrate_kanban, transcript) are re-exposed as
   subdomains of swarm/project. All stay gated-hidden but callable."
  #{"fs" "code" "memory" "project" "swarm" "git" "emacs" "web" "multi"})

(defn- mk-tool
  "Minimal tool-def: a name + a no-op handler (and optional :deprecated)."
  ([nm] (mk-tool nm false))
  ([nm dep?]
   (cond-> {:name nm :handler (fn [_] {:type "text" :text nm})}
     dep? (assoc :deprecated true))))

(defn visible-names-after-gate
  "1-arg fn under test (for trifecta): given a seq of tool-defs, return the
   sorted vec of names that remain VISIBLE (non-deprecated) after gating
   with `target-allowlist`."
  [tool-defs]
  (->> (reg/apply-visibility-gate tool-defs target-allowlist)
       (remove :deprecated)
       (map :name)
       sort
       vec))

;; =============================================================================
;; 1. Trifecta: visibility gate (golden + property + mutations)
;; =============================================================================

(deftrifecta visibility-gate
  hive-mcp.tools.registry-visibility-test/visible-names-after-gate
  {:golden-path "test/golden/registry/visibility-gate.edn"
   :cases       {:roots-only     [(mk-tool "fs") (mk-tool "code") (mk-tool "git")]
                 :mixed          [(mk-tool "fs") (mk-tool "codebase-map")
                                  (mk-tool "kg") (mk-tool "web")]
                 :all-folded     [(mk-tool "analysis") (mk-tool "clojure")
                                  (mk-tool "read_file")]
                 :pre-deprecated [(mk-tool "code") (mk-tool "multi" true)]
                 :empty          []
                 :dup-names      [(mk-tool "memory") (mk-tool "memory")]}
   :gen         (gen/vector
                 (gen/fmap mk-tool
                           (gen/elements ["fs" "code" "memory" "web" "auth"
                                          "analysis" "codebase-map" "kg" "multi"
                                          "read_file" "todo_write"]))
                 0 8)
   :pred        (fn [out]
                  (and (vector? out)
                       (every? string? out)
                       ;; every visible name MUST be in the allowlist
                       (every? target-allowlist out)))
   :num-tests   100
   :mutations   [["keep-all" (fn [tool-defs]
                               (->> tool-defs (map :name) sort vec))]
                 ["drop-all" (fn [_] [])]
                 ["invert"   (fn [tool-defs]
                               (->> tool-defs
                                    (remove #(target-allowlist (:name %)))
                                    (map :name) sort vec))]]})

;; =============================================================================
;; 2. Property: apply-visibility-gate invariants
;; =============================================================================

(def gen-tool
  (gen/fmap (fn [[nm dep?]]
              (cond-> {:name nm :handler identity}
                dep? (assoc :deprecated true)))
            (gen/tuple
             (gen/elements ["fs" "code" "memory" "web" "kg" "analysis"
                            "multi" "todo_write" "edit" "read_file"])
             gen/boolean)))

(def gen-tools (gen/vector gen-tool 0 12))

(defspec gate-preserves-count 100
  (tc-prop/for-all [ts gen-tools]
    (= (count ts) (count (reg/apply-visibility-gate ts target-allowlist)))))

(defspec gate-preserves-handlers 100
  (tc-prop/for-all [ts gen-tools]
    (every? :handler (reg/apply-visibility-gate ts target-allowlist))))

(defspec gate-deprecates-non-allowlisted 100
  (tc-prop/for-all [ts gen-tools]
    (every? (fn [t]
              (or (target-allowlist (:name t))
                  (:deprecated t)))
            (reg/apply-visibility-gate ts target-allowlist))))

(defspec gate-visible-subset-of-allowlist 100
  (tc-prop/for-all [ts gen-tools]
    (every? #(target-allowlist (:name %))
            (remove :deprecated (reg/apply-visibility-gate ts target-allowlist)))))

;; =============================================================================
;; 3. Mutation/invariant deftests
;; =============================================================================

(deftest gate-nil-or-empty-allowlist-is-identity
  (testing "nil/empty allowlist ⇒ no gating (legacy behavior)"
    (let [ts [(mk-tool "fs") (mk-tool "codebase-map")]]
      (is (= ts (reg/apply-visibility-gate ts nil)))
      (is (= ts (reg/apply-visibility-gate ts #{}))))))

(deftest gate-preserves-pre-existing-deprecation
  (testing "an already-deprecated allowlisted tool stays deprecated"
    (let [ts [(mk-tool "code" true)]
          [g] (reg/apply-visibility-gate ts target-allowlist)]
      (is (:deprecated g)))))

;; =============================================================================
;; 4. web root delegation (new consolidated tool)
;; =============================================================================

(deftest web-fetch-delegates-to-web_fetch
  (testing "web command=fetch routes to the standalone web_fetch handler"
    (with-redefs [ext/get-registered-tools
                  (fn [] [{:name "web_fetch"
                           :handler (fn [p] {:type "text" :text (str "FETCHED:" (:url p))})}
                          {:name "web_search"
                           :handler (fn [_] {:type "text" :text "SEARCHED"})}])]
      (let [h (:handler web/tool-def)
            r (h {:command "fetch" :url "http://x"})]
        (is (= "FETCHED:http://x" (:text r)))))))

(deftest web-search-delegates-to-web_search
  (testing "web command=search routes to the standalone web_search handler"
    (with-redefs [ext/get-registered-tools
                  (fn [] [{:name "web_search"
                           :handler (fn [p] {:type "text" :text (str "Q:" (:query p))})}])]
      (let [h (:handler web/tool-def)
            r (h {:command "search" :query "clojure"})]
        (is (= "Q:clojure" (:text r)))))))

(deftest web-missing-addon-returns-error
  (testing "web fetch with no web_fetch addon registered returns an error"
    (with-redefs [ext/get-registered-tools (fn [] [])]
      (let [h (:handler web/tool-def)
            r (h {:command "fetch" :url "http://x"})]
        (is (:isError r))))))

;; =============================================================================
;; 5. Folded subdomain nesting (ergonomic re-wiring of gated roots)
;; =============================================================================

(deftest folded-roots-nested-as-subdomains
  (testing "core folded roots are reachable as subdomains of their substrate"
    ;; preset → swarm (flat leaf map, lazy-resolved)
    (is (contains? swarm/canonical-handlers :preset))
    ;; migrate-kanban + transcript → project
    (is (contains? project/canonical-handlers :migrate-kanban))
    (is (contains? project/canonical-handlers :transcript))
    ;; analysis + codebase-map → code (addon-delegating)
    (is (contains? code/canonical-handlers :analysis))
    (is (contains? code/canonical-handlers :codebase-map))))

(deftest code-analysis-subdomain-strips-prefix-and-delegates
  (testing "`code analysis <cmd>` strips the prefix and delegates to the
            standalone analysis handler with the inner command"
    (with-redefs [ext/get-registered-tools
                  (fn [] [{:name "analysis"
                           :handler (fn [p] {:type "text" :text (str "A:" (:command p))})}])]
      (let [h (:handler code/tool-def)
            r (h {:command "analysis impact" :namespace "x"})]
        ;; prefix "analysis " stripped → inner router sees just "impact"
        (is (= "A:impact" (:text r)))))))

(deftest code-codebase-map-subdomain-strips-prefix-and-delegates
  (testing "`code codebase-map <cmd>` strips the prefix and delegates"
    (with-redefs [ext/get-registered-tools
                  (fn [] [{:name "codebase-map"
                           :handler (fn [p] {:type "text" :text (str "CM:" (:command p))})}])]
      (let [h (:handler code/tool-def)
            r (h {:command "codebase-map ns" :namespace "x"})]
        (is (= "CM:ns" (:text r)))))))

(deftest code-analysis-missing-addon-returns-error
  (testing "`code analysis ...` with no analysis addon loaded errors cleanly"
    (with-redefs [ext/get-registered-tools (fn [] [])]
      (let [h (:handler code/tool-def)
            r (h {:command "analysis impact"})]
        (is (:isError r))))))
