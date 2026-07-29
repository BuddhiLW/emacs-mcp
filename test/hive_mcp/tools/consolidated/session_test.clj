(ns hive-mcp.tools.consolidated.session-test
  "Tests for consolidated session CLI tool.

   Test Coverage:
   1. Backward compat - existing commands (complete, wrap, whoami, help)
   2. Catchup subcommand - delegation to handle-native-catchup
   3. Context store commands - context-put/get/query/evict/stats
   4. Tool definition - schema includes all commands
   5. Handler map completeness
   6. Context auto-eviction on wrap/complete (Task 2.4)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.tools.consolidated.session :as session]
            [hive-mcp.tools.session-complete :as session-handlers]
            [hive-mcp.tools.crystal :as crystal]
            [hive-mcp.tools.catchup :as catchup]
            [hive-mcp.channel.context-store :as ctx-store]
            [hive-mcp.test.stub.extensions :as ext-stub]
            [hive-mcp.chroma.core :as chroma]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn parse-response
  "Parse JSON response from handler."
  [result]
  (when (and result (not (:isError result)) (:text result))
    (try
      (json/read-str (:text result) :key-fn keyword)
      (catch Exception _ nil))))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-context-store-fixture
  "Reset context store before/after each test to prevent cross-test pollution."
  [f]
  (ctx-store/reset-all!)
  (try (f)
       (finally
         (ctx-store/stop-reaper!)
         (ctx-store/reset-all!))))

(use-fixtures :each clean-context-store-fixture)

;; =============================================================================
;; Handler Map Tests
;; =============================================================================

(deftest test-handlers-map-completeness
  (testing "all handlers are registered"
    (is (contains? session/handlers :complete))
    (is (contains? session/handlers :wrap))
    (is (contains? session/handlers :whoami))
    (is (contains? session/handlers :catchup))
    (is (contains? session/handlers :context-put))
    (is (contains? session/handlers :context-get))
    (is (contains? session/handlers :context-query))
    (is (contains? session/handlers :context-evict))
    (is (contains? session/handlers :context-stats))
    (is (contains? session/handlers :context-reconstruct))))

(deftest test-handlers-are-functions
  (testing "all handlers are functions"
    (doseq [[k v] session/handlers]
      (is (fn? v) (str "Handler " k " should be a function")))))

;; =============================================================================
;; CLI Handler Tests (backward compat)
;; =============================================================================

(deftest test-cli-handler-help-command
  (testing "help command returns help text with all commands"
    (let [result (session/handle-session {:command "help"})]
      (is (not (:isError result)))
      (is (= "text" (:type result)))
      (is (str/includes? (:text result) "Available commands"))
      (is (str/includes? (:text result) "complete"))
      (is (str/includes? (:text result) "wrap"))
      (is (str/includes? (:text result) "whoami"))
      (is (str/includes? (:text result) "catchup"))
      (is (str/includes? (:text result) "context-put"))
      (is (str/includes? (:text result) "context-get"))
      (is (str/includes? (:text result) "context-query"))
      (is (str/includes? (:text result) "context-evict"))
      (is (str/includes? (:text result) "context-stats")))))

(deftest test-cli-handler-unknown-command
  (testing "unknown command returns error"
    (let [result (session/handle-session {:command "bogus"})]
      (is (:isError result))
      (is (str/includes? (:text result) "Unknown command")))))

;; =============================================================================
;; Whoami Handler Tests (backward compat)
;; =============================================================================

(deftest test-whoami-returns-identity
  (testing "whoami returns agent identity context"
    (let [result (session/handle-session {:command "whoami"
                                          :agent_id "test-agent"
                                          :directory "/tmp/test"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= "test-agent" (:agent-id parsed)))
        (is (string? (:cwd parsed)))))))

;; =============================================================================
;; Catchup Handler Tests
;; =============================================================================

(deftest test-catchup-delegates-to-native-catchup
  (testing "catchup handler delegates to handle-native-catchup"
    ;; Mock Chroma to avoid real DB calls
    (with-redefs [catchup/handle-native-catchup
                  (fn [args]
                    {:type "text"
                     :text (json/write-str {:catchup true
                                            :directory (:directory args)})})]
      (let [result (session/handle-session {:command "catchup"
                                            :directory "/tmp/project"})]
        (is (not (:isError result)))
        (let [parsed (parse-response result)]
          (is (:catchup parsed) "should have called catchup handler")
          (is (= "/tmp/project" (:directory parsed))))))))

(deftest test-catchup-handles-exception
  (testing "catchup handler catches exceptions gracefully"
    (with-redefs [catchup/handle-native-catchup
                  (fn [_] (throw (ex-info "Chroma down" {})))]
      (let [result (session/handle-session {:command "catchup"
                                            :directory "/tmp"})]
        (is (:isError result))
        (is (str/includes? (:text result) "Catchup failed"))))))

(deftest test-catchup-without-chroma
  (testing "catchup when Chroma not configured returns error"
    ;; When Chroma is not configured, handle-native-catchup returns error
    (with-redefs [chroma/embedding-configured? (constantly false)
                  catchup/handle-native-catchup
                  (fn [_]
                    {:type "text"
                     :text "Chroma not configured"
                     :isError true})]
      (let [result (session/handle-session {:command "catchup"})]
        (is (:isError result))))))

(deftest test-catchup-passes-all-params
  (testing "catchup passes full params map to delegate"
    (let [received (atom nil)]
      (with-redefs [catchup/handle-native-catchup
                    (fn [args]
                      (reset! received args)
                      {:type "text" :text "{}"})]
        (session/handle-session {:command "catchup"
                                 :directory "/my/project"
                                 :agent_id "my-agent"})
        (is (= "/my/project" (:directory @received)))
        ;; Full params map is passed through
        (is (= "catchup" (:command @received)))))))

;; =============================================================================
;; Context-Put Handler Tests
;; =============================================================================

(deftest test-context-put-returns-ctx-id
  (testing "context-put stores data and returns ctx-id"
    (let [result (session/handle-session {:command "context-put"
                                          :data {"message" "hello" "count" 42}})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (string? (:ctx-id parsed)))
        (is (re-matches #"ctx-\d+-[0-9a-f]{8}" (:ctx-id parsed)))
        (is (= 300000 (:ttl-ms parsed)))))))

(deftest test-context-put-with-tags-and-ttl
  (testing "context-put accepts tags and custom TTL"
    (let [result (session/handle-session {:command "context-put"
                                          :data {"key" "value"}
                                          :tags ["agent-1" "session"]
                                          :ttl_ms 60000})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (string? (:ctx-id parsed)))
        (is (= 60000 (:ttl-ms parsed)))))))

(deftest test-context-put-requires-data
  (testing "context-put without data returns error"
    (let [result (session/handle-session {:command "context-put"})]
      (is (:isError result))
      (is (str/includes? (:text result) "data")))))

;; =============================================================================
;; Context-Get Handler Tests
;; =============================================================================

(deftest test-context-get-roundtrip
  (testing "context-get retrieves stored data"
    (let [put-result (session/handle-session {:command "context-put"
                                              :data {"msg" "hello"}})
          ctx-id (:ctx-id (parse-response put-result))
          get-result (session/handle-session {:command "context-get"
                                              :ctx_id ctx-id})]
      (is (not (:isError get-result)))
      (let [parsed (parse-response get-result)]
        (is (= {:msg "hello"} (:data parsed)))
        (is (= ctx-id (:id parsed)))
        (is (= 1 (:access-count parsed)))))))

(deftest test-context-get-missing-returns-not-found
  (testing "context-get with nonexistent ID returns not-found"
    (let [result (session/handle-session {:command "context-get"
                                          :ctx_id "ctx-0000-deadbeef"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (:not-found parsed))
        (is (= "ctx-0000-deadbeef" (:ctx-id parsed)))))))

(deftest test-context-get-requires-ctx-id
  (testing "context-get without ctx_id returns error"
    (let [result (session/handle-session {:command "context-get"})]
      (is (:isError result))
      (is (str/includes? (:text result) "ctx_id")))))

;; =============================================================================
;; Context-Query Handler Tests
;; =============================================================================

(deftest test-context-query-by-tags
  (testing "context-query filters by tags"
    ;; Store entries with different tags
    (session/handle-session {:command "context-put"
                             :data {"a" 1}
                             :tags ["session" "agent-1"]})
    (session/handle-session {:command "context-put"
                             :data {"b" 2}
                             :tags ["session" "agent-2"]})
    (session/handle-session {:command "context-put"
                             :data {"c" 3}
                             :tags ["other"]})
    (let [result (session/handle-session {:command "context-query"
                                          :tags ["session"]})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= 2 (:count parsed)))
        (is (= 2 (count (:entries parsed))))))))

(deftest test-context-query-with-limit
  (testing "context-query respects limit"
    (dotimes [i 5]
      (session/handle-session {:command "context-put"
                               :data {"i" i}
                               :tags ["bulk"]}))
    (let [result (session/handle-session {:command "context-query"
                                          :tags ["bulk"]
                                          :limit 3})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= 3 (:count parsed)))))))

(deftest test-context-query-no-tags
  (testing "context-query without tags returns all entries"
    (session/handle-session {:command "context-put"
                             :data {"x" 1}})
    (session/handle-session {:command "context-put"
                             :data {"y" 2}})
    (let [result (session/handle-session {:command "context-query"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= 2 (:count parsed)))))))

;; =============================================================================
;; Context-Evict Handler Tests
;; =============================================================================

(deftest test-context-evict-removes-entry
  (testing "context-evict removes entry and returns true"
    (let [put-result (session/handle-session {:command "context-put"
                                              :data {"temp" "data"}})
          ctx-id (:ctx-id (parse-response put-result))
          evict-result (session/handle-session {:command "context-evict"
                                                :ctx_id ctx-id})]
      (is (not (:isError evict-result)))
      (let [parsed (parse-response evict-result)]
        (is (true? (:evicted parsed)))
        (is (= ctx-id (:ctx-id parsed))))
      ;; Verify entry is gone
      (let [get-result (session/handle-session {:command "context-get"
                                                :ctx_id ctx-id})]
        (is (:not-found (parse-response get-result)))))))

(deftest test-context-evict-nonexistent
  (testing "context-evict on missing ID returns false"
    (let [result (session/handle-session {:command "context-evict"
                                          :ctx_id "ctx-0000-deadbeef"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (false? (:evicted parsed)))))))

(deftest test-context-evict-requires-ctx-id
  (testing "context-evict without ctx_id returns error"
    (let [result (session/handle-session {:command "context-evict"})]
      (is (:isError result))
      (is (str/includes? (:text result) "ctx_id")))))

;; =============================================================================
;; Context-Stats Handler Tests
;; =============================================================================

(deftest test-context-stats-empty
  (testing "context-stats on empty store"
    (let [result (session/handle-session {:command "context-stats"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= 0 (:total parsed)))
        (is (nil? (:oldest parsed)))
        (is (nil? (:newest parsed)))))))

(deftest test-context-stats-with-entries
  (testing "context-stats reflects stored entries"
    (session/handle-session {:command "context-put" :data {"a" 1}})
    (session/handle-session {:command "context-put" :data {"b" 2}})
    (let [result (session/handle-session {:command "context-stats"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= 2 (:total parsed)))
        (is (number? (:oldest parsed)))
        (is (number? (:newest parsed)))
        (is (number? (:bytes-approx parsed)))
        (is (<= (:oldest parsed) (:newest parsed)))))))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

(deftest test-tool-definition-structure
  (testing "tool-def has required fields"
    (is (= "session" (:name session/tool-def)))
    (is (string? (:description session/tool-def)))
    (is (map? (:inputSchema session/tool-def)))
    (is (fn? (:handler session/tool-def)))))

(deftest test-tool-definition-includes-all-commands
  (testing "tool-def enum includes all commands"
    (let [enum (get-in session/tool-def [:inputSchema :properties "command" :enum])]
      (is (some #(= "complete" %) enum))
      (is (some #(= "wrap" %) enum))
      (is (some #(= "whoami" %) enum))
      (is (some #(= "catchup" %) enum))
      (is (some #(= "help" %) enum))
      (is (some #(= "context-put" %) enum))
      (is (some #(= "context-get" %) enum))
      (is (some #(= "context-query" %) enum))
      (is (some #(= "context-evict" %) enum))
      (is (some #(= "context-stats" %) enum))
      (is (some #(= "context-reconstruct" %) enum)))))

(deftest test-tool-definition-has-context-params
  (testing "tool-def schema includes context store params"
    (let [props (get-in session/tool-def [:inputSchema :properties])]
      (is (contains? props "data") "should have data param")
      (is (contains? props "ctx_id") "should have ctx_id param")
      (is (contains? props "tags") "should have tags param")
      (is (contains? props "ttl_ms") "should have ttl_ms param")
      (is (contains? props "limit") "should have limit param"))))

(deftest test-tool-description-mentions-context
  (testing "tool description mentions context store commands"
    (is (str/includes? (:description session/tool-def) "context-put"))
    (is (str/includes? (:description session/tool-def) "context-get"))))

(deftest test-tools-vector
  (testing "tools vector contains tool-def"
    (is (= 1 (count session/tools)))
    (is (= session/tool-def (first session/tools)))))

;; =============================================================================
;; Context-Reconstruct Handler Tests
;; =============================================================================

(deftest test-context-reconstruct-with-refs
  (testing "context-reconstruct reconstructs from context-store refs"
    ;; Reconstruction is the :cr/i extension's job (hive-knowledge implements it
    ;; live). Register a RECORDING stub at that seam so the test drives the real
    ;; branch and can also assert what session handed the collaborator.
    (let [ax-id (ctx-store/context-put!
                 [{:id "ax-1" :content "Rule 1"} {:id "ax-2" :content "Rule 2"}]
                 :tags #{"catchup" "axioms"})
          dec-id (ctx-store/context-put!
                  [{:id "dec-1" :content "Decision 1"}]
                  :tags #{"catchup" "decisions"})
          seen (atom nil)
          rendered (atom nil)]
      (ext-stub/with-extensions
        {:cr/i (fn [ctx-refs kg-node-ids scope]
                 (reset! seen {:ctx-refs ctx-refs :kg-node-ids kg-node-ids :scope scope})
                 (reset! rendered
                         (str "## Reconstructed Context (Compressed)\n\n"
                              (str/join "\n"
                                        (for [[category ctx-id] (sort-by key ctx-refs)]
                                          (str "### " (name category) ": "
                                               (count (:data (ctx-store/context-get ctx-id)))
                                               " entries"))))))}
        (fn []
          (let [result (session/handle-session {:command "context-reconstruct"
                                                :ctx_refs {"axioms" ax-id
                                                           "decisions" dec-id}
                                                :scope "test-project"})]
            (is (not (:isError result)))
            ;; refs reach the extension keywordized, with the scope forwarded
            (is (= {:ctx-refs {:axioms ax-id :decisions dec-id}
                    :kg-node-ids []
                    :scope "test-project"}
                   @seen))
            (let [parsed (parse-response result)]
              (is (string? (:reconstructed parsed)))
              ;; the tool returns the extension's output verbatim, counted
              (is (= @rendered (:reconstructed parsed)))
              (is (= (count @rendered) (:chars parsed)))
              (is (pos? (:chars parsed)))
              (is (= 2 (:refs-count parsed)))
              (is (str/includes? (:reconstructed parsed) "Reconstructed Context")))))))))

(deftest test-context-reconstruct-empty-params
  (testing "context-reconstruct with no refs returns minimal context"
    (let [result (session/handle-session {:command "context-reconstruct"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (string? (:reconstructed parsed)))
        (is (= 0 (:refs-count parsed)))
        (is (= 0 (:kg-nodes-count parsed)))))))

(deftest test-context-reconstruct-bounded-output
  (testing "context-reconstruct output is bounded"
    (let [;; Store a large payload
          big-data (mapv (fn [i] {:id (str "ax-" i)
                                  :content (apply str (repeat 500 "x"))})
                         (range 50))
          big-id (ctx-store/context-put! big-data :tags #{"catchup" "axioms"})
          result (session/handle-session {:command "context-reconstruct"
                                          :ctx_refs {"axioms" big-id}})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        ;; 3000 chars max + some tolerance
        (is (<= (:chars parsed) 3020))))))

(deftest test-context-reconstruct-with-ctx-id
  (testing "context-reconstruct resolves refs from a single ctx_id"
    ;; Store a refs map as context-store entry
    (let [ax-id (ctx-store/context-put! [{:id "ax-1" :content "Test axiom"}]
                                        :tags #{"catchup" "axioms"})
          refs-id (ctx-store/context-put! {:axioms ax-id}
                                          :tags #{"refs"})
          result (session/handle-session {:command "context-reconstruct"
                                          :ctx_id refs-id})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (string? (:reconstructed parsed)))
        ;; The refs-map had one key (:axioms -> ax-id)
        (is (= 1 (:refs-count parsed)))))))

;; =============================================================================
;; Context Auto-Eviction Tests (Task 2.4)
;; =============================================================================

(deftest test-evict-agent-context-basic
  (testing "evict-agent-context! removes entries tagged with agent ID"
    (let [evict-fn @#'session/evict-agent-context!]
      ;; Store entries with agent tags (simulates catchup context caching)
      (ctx-store/context-put! {:axioms "data"} :tags #{"agent:swarm-test-ling-42" "catchup"})
      (ctx-store/context-put! {:convs "data"} :tags #{"agent:swarm-test-ling-42" "catchup"})
      (ctx-store/context-put! {:sessions "data"} :tags #{"session:swarm-test-ling-42"})
      ;; Entry from another agent - should NOT be evicted
      (ctx-store/context-put! {:other "data"} :tags #{"agent:swarm-other-99"})
      (is (= 4 (:total (ctx-store/context-stats))))
      (let [result (evict-fn "swarm-test-ling-42")]
        (is (= 3 (:evicted result)))
        (is (= 1 (:total (ctx-store/context-stats))))))))

(deftest test-evict-agent-context-nil-agent
  (testing "evict-agent-context! returns nil for nil agent-id"
    (let [evict-fn @#'session/evict-agent-context!]
      (is (nil? (evict-fn nil))))))

(deftest test-evict-agent-context-unknown-agent
  (testing "evict-agent-context! returns nil for 'unknown' agent-id"
    (let [evict-fn @#'session/evict-agent-context!]
      (is (nil? (evict-fn "unknown"))))))

(deftest test-evict-agent-context-coordinator
  (testing "evict-agent-context! returns nil for 'coordinator' agent-id"
    (let [evict-fn @#'session/evict-agent-context!]
      (is (nil? (evict-fn "coordinator"))))))

(deftest test-evict-agent-context-no-matching-entries
  (testing "evict-agent-context! returns {:evicted 0} when no entries match"
    (let [evict-fn @#'session/evict-agent-context!]
      (ctx-store/context-put! {:x "data"} :tags #{"agent:other-ling"})
      (let [result (evict-fn "swarm-nonexistent-ling")]
        (is (= 0 (:evicted result)))
        ;; Original entry still exists
        (is (= 1 (:total (ctx-store/context-stats))))))))

(deftest test-wrap-evicts-context
  (testing "wrap background work evicts agent context after crystallization"
    (ctx-store/context-put! {:axioms "cached"} :tags #{"agent:swarm-wrap-test-1" "catchup"})
    (ctx-store/context-put! {:convs "cached"} :tags #{"agent:swarm-wrap-test-1" "catchup"})
    (is (= 2 (:total (ctx-store/context-stats))))
    (with-redefs [crystal/handle-wrap-crystallize
                  (fn [_] {:type "text" :text "{\"status\":\"ok\"}"})]
      (let [result (deref (#'session/wrap-async!
                           "swarm-wrap-test-1"
                           "/tmp/test")
                          2000
                          ::timeout)]
        (is (not= ::timeout result))
        (is (= 0 (:total (ctx-store/context-stats))))))))

(deftest test-complete-evicts-context
  (testing "handle-complete evicts agent context after session completion"
    ;; Store entries tagged with agent ID
    (ctx-store/context-put! {:data "cached"} :tags #{"agent:swarm-complete-test-1" "catchup"})
    (is (= 1 (:total (ctx-store/context-stats))))
    ;; Mock session-complete to avoid real git/kanban calls
    (with-redefs [session-handlers/handle-session-complete
                  (fn [_] {:type "text" :text "{\"status\":\"ok\"}"})]
      (session/handle-session {:command "complete"
                               :commit_msg "test: eviction"
                               :agent_id "swarm-complete-test-1"
                               :directory "/tmp/test"})
      ;; Context should be evicted after complete
      (is (= 0 (:total (ctx-store/context-stats)))))))
