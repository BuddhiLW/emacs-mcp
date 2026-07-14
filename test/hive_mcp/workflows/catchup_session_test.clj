(ns hive-mcp.workflows.catchup-session-test
  "Tests for the catchup session FSM workflow.

   Tests the FSM state machine behavior with mock resources,
   verifying state transitions, handler logic, and error paths.
   Follows wrap_session_test.clj patterns."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.workflows.catchup-session :as catchup]))

;; =============================================================================
;; Test Resources (mock side-effect functions)
;; =============================================================================

(def test-axioms
  [{:id "ax-1" :content "Axiom 1" :type "axiom"}
   {:id "ax-2" :content "Axiom 2" :type "axiom"}])

(def test-priority-conventions
  [{:id "pc-1" :content "Priority conv 1" :type "convention"}])

(def test-sessions
  [{:id "sess-1" :content "Session summary" :type "note"}])

(def test-decisions
  [{:id "dec-1" :content "Decision 1" :type "decision"}])

(def test-conventions
  [{:id "conv-1" :content "Convention 1" :type "convention"}])

(def test-snippets
  [{:id "snip-1" :content "Snippet 1" :type "snippet"}])

(def test-expiring
  [{:id "exp-1" :content "Expiring 1"}])

(defn make-test-resources
  "Create mock resources for testing.
   Resource keys match the catchup.edn handler docstrings."
  ([] (make-test-resources {}))
  ([overrides]
   (merge
    {:chroma-check-fn      (constantly true)
     :scope-fn             (fn [_dir] "hive-mcp")
     :project-name-fn      (fn [_dir] "hive-mcp")
     :query-fn             (fn [type tags _pid _limit]
                             (case type
                               "convention" (if (= tags ["catchup-priority"])
                                              test-priority-conventions
                                              test-conventions)
                               "note"       test-sessions
                               "decision"   test-decisions
                               "snippet"    test-snippets
                               []))
     :query-axioms-fn      (fn [_pid] test-axioms)
     :query-conventions-fn (fn [_pid _ax-ids _pc-ids] test-conventions)
     :query-expiring-fn    (fn [_pid _limit] test-expiring)
     :git-fn               (fn [_dir] {:branch "main" :uncommitted false :last-commit "abc123 - test"})
     :entry->meta-fns      {:axiom   (fn [e] (assoc e :T "axiom"))
                            :priority (fn [e] (assoc e :T "priority"))
                            :catchup  (fn [e] (assoc e :T "catchup"))}
     :addon-fn             (fn [_data] {:addon-data {:processed true}})
     :permeate-fn          (fn [_dir] {:permeated 2 :agents ["ling-1" "ling-2"]})
     :tree-scan-fn         (fn [_dir] {:scanned true :projects 3})
     :disc-decay-fn        (fn [_pid] {:updated 5 :skipped 0 :errors 0})
     :piggyback-fn         (fn [_aid _entries _refs] nil)
     :context-store-fn     (fn [_data _tags _ttl] (str "ctx-" (rand-int 99999)))
     :build-scopes-fn      (fn [pn _pid] [(str "scope:project:" pn)])
     :build-response-fn    nil
     :error-response-fn    nil}
    overrides)))

;; =============================================================================
;; Happy Path Tests
;; =============================================================================

(deftest test-catchup-session-happy-path
  (testing "Full catchup session completes all phases"
    (let [resources (make-test-resources)
          result (catchup/run-catchup-session
                  resources
                  {:directory "/test/project"})]
      (is (= "hive-mcp" (:project-id result)))
      (is (some? (:git-info result)))
      (is (= "main" (get-in result [:git-info :branch])))
      (is (seq (:axioms-meta result)))
      (is (seq (:priority-meta result)))
      (is (seq (:decisions-meta result)))
      (is (seq (:conventions-meta result)))
      (is (some? (:permeation result)))
      (is (= 2 (get-in result [:permeation :permeated])))
      (is (true? (:piggyback-enqueued? result))))))

(deftest test-catchup-session-with-custom-response-builder
  (testing "Custom build-response-fn controls final output"
    (let [resources (make-test-resources
                     {:build-response-fn (fn [data]
                                           {:custom true
                                            :project (:project-id data)
                                            :axiom-count (count (:axioms-meta data))})})
          result (catchup/run-catchup-session
                  resources
                  {:directory "/test/project"})]
      (is (true? (:custom result)))
      (is (= "hive-mcp" (:project result)))
      (is (= 2 (:axiom-count result))))))

;; =============================================================================
;; Error Path Tests
;; =============================================================================

(deftest test-catchup-session-chroma-not-configured
  (testing "Chroma not configured transitions to error"
    (let [resources (make-test-resources
                     {:chroma-check-fn (constantly false)})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (catchup/run-catchup-session
                    resources
                    {:directory "/test/project"}))))))

(deftest test-catchup-session-chroma-not-configured-custom-error
  (testing "Chroma not configured with custom error handler"
    (let [resources (make-test-resources
                     {:chroma-check-fn (constantly false)
                      :error-response-fn (fn [err] {:error true :message (str err)})})
          result (catchup/run-catchup-session
                  resources
                  {:directory "/test/project"})]
      (is (true? (:error result))))))

(deftest test-catchup-session-scope-resolve-fails
  (testing "Nil project-id from scope-fn transitions to error"
    (let [resources (make-test-resources
                     {:scope-fn (fn [_dir] nil)})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (catchup/run-catchup-session
                    resources
                    {:directory "/test/project"}))))))

(deftest test-catchup-session-query-graceful-degradation
  (testing "Individual query exceptions degrade gracefully with parallel futures"
    (let [resources (make-test-resources
                     {:query-axioms-fn (fn [_] (throw (ex-info "Chroma down" {})))})
          result (catchup/run-catchup-session
                  resources
                  {:directory "/test/project"})]
      (is (= "hive-mcp" (:project-id result)))
      (is (empty? (:axioms-meta result)))
      (is (seq (:decisions-meta result)))
      (is (seq (:sessions-meta result))))))

;; =============================================================================
;; Handler Unit Tests
;; =============================================================================

(deftest test-handle-start
  (testing "handle-start checks chroma and sets directory"
    (let [resources {:chroma-check-fn (constantly true)}
          result (catchup/handle-start resources {:directory "/foo"})]
      (is (true? (:chroma-configured? result)))
      (is (= "/foo" (:directory result)))
      (is (nil? (:error result)))))
  (testing "handle-start with chroma not configured"
    (let [resources {:chroma-check-fn (constantly false)}
          result (catchup/handle-start resources {:directory "/foo"})]
      (is (false? (:chroma-configured? result))))))

(deftest test-handle-start-directory-from-resources
  (testing "handle-start falls back to resources for directory"
    (let [resources {:chroma-check-fn (constantly true)
                     :directory "/res/dir"}
          result (catchup/handle-start resources {})]
      (is (= "/res/dir" (:directory result))))))

(deftest test-handle-scope-resolve
  (testing "handle-scope-resolve derives project-id and scopes"
    (let [resources {:scope-fn (fn [_] "test-proj")
                     :project-name-fn (fn [_] "Test Project")
                     :build-scopes-fn (fn [pn _pid] [(str "scope:" pn)])}
          result (catchup/handle-scope-resolve resources {:directory "/test"})]
      (is (= "test-proj" (:project-id result)))
      (is (= "Test Project" (:project-name result)))
      (is (= ["scope:Test Project"] (:scopes result))))))

(deftest test-handle-query-memory
  (testing "handle-query-memory populates all entry vectors"
    (let [resources {:query-fn (fn [type _tags _pid _limit]
                                 (case type
                                   "convention" [{:id "c1"}]
                                   "note" [{:id "n1"}]
                                   "decision" [{:id "d1"}]
                                   "snippet" [{:id "s1"}]
                                   []))
                     :query-axioms-fn (fn [_] [{:id "a1"}])
                     :query-conventions-fn (fn [_ _ _] [{:id "rc1"}])
                     :query-expiring-fn (fn [_ _] [{:id "e1"}])}
          result (catchup/handle-query-memory resources {:project-id "p1"})]
      (is (= [{:id "a1"}] (:axioms result)))
      (is (false? (:query-failed? result)))
      (is (seq (:sessions result)))
      (is (seq (:decisions result)))
      (is (seq (:snippets result)))
      (is (seq (:expiring result))))))

(deftest test-handle-query-memory-graceful-degradation
  (testing "handle-query-memory degrades gracefully when individual queries throw"
    (let [resources {:query-axioms-fn (fn [_] (throw (ex-info "boom" {})))}
          result (catchup/handle-query-memory resources {:project-id "p1"})]
      ;; Parallel futures catch individual query exceptions via safe-deref.
      ;; The query phase succeeds with empty axioms (graceful degradation).
      (is (false? (:query-failed? result)))
      (is (= [] (:axioms result)))
      ;; Other categories still populated (empty because no query-fn provided)
      (is (some? (:sessions result)))
      (is (some? (:decisions result))))))

(deftest test-handle-transform
  (testing "handle-transform transforms entries to metadata"
    (let [resources {:git-fn (fn [_] {:branch "dev"})
                     :entry->meta-fns {:axiom (fn [e] (assoc e :meta true))
                                       :priority (fn [e] (assoc e :meta true))
                                       :catchup (fn [e] (assoc e :meta true))}}
          data {:directory "/test"
                :axioms [{:id "a1"}]
                :priority-conventions [{:id "pc1"}]
                :sessions [{:id "s1"}]
                :decisions [{:id "d1"}]
                :conventions [{:id "c1"}]
                :snippets [{:id "sn1"}]
                :expiring [{:id "e1"}]}
          result (catchup/handle-transform resources data)]
      (is (= {:branch "dev"} (:git-info result)))
      (is (every? :meta (:axioms-meta result)))
      (is (every? :meta (:priority-meta result)))
      (is (every? :meta (:sessions-meta result)))
      (is (every? :meta (:snippets-meta result)))
      (is (every? :meta (:decisions-base result)))
      (is (every? :meta (:conventions-base result))))))

(deftest test-handle-addon-pass
  (testing "addon-pass merges addon result into data"
    (let [resources {:addon-fn (fn [data] {:addon-data {:count 1}})}
          data {:project-id "p1"}
          result (catchup/handle-addon-pass resources data)]
      (is (= {:count 1} (:addon-data result)))))

  (testing "addon-pass is pass-through when no addon-fn"
    (let [resources {}
          data {:project-id "p1" :decisions-base [{:id "d1"}]}
          result (catchup/handle-addon-pass resources data)]
      (is (= data result))))

  (testing "addon-pass degrades gracefully when addon throws"
    (let [resources {:addon-fn (fn [_] (throw (ex-info "unavailable" {})))}
          data {:project-id "p1"}
          result (catchup/handle-addon-pass resources data)]
      (is (= data result)))))

(deftest test-handle-maintenance
  (testing "handle-maintenance runs all maintenance tasks"
    (let [resources {:permeate-fn (fn [_] {:permeated 3 :agents ["a"]})
                     :tree-scan-fn (fn [_] {:scanned true})
                     :disc-decay-fn (fn [_] {:updated 2 :skipped 0})}
          result (catchup/handle-maintenance resources
                                             {:directory "/test" :project-id "p1"})]
      (is (= 3 (get-in result [:permeation :permeated])))
      (is (true? (get-in result [:project-tree-scan :scanned])))
      (is (= 2 (get-in result [:disc-decay :updated]))))))

(deftest test-handle-maintenance-graceful-failure
  (testing "handle-maintenance catches exceptions from individual tasks"
    (let [resources {:permeate-fn (fn [_] (throw (ex-info "boom" {})))
                     :tree-scan-fn (fn [_] (throw (ex-info "boom" {})))
                     :disc-decay-fn (fn [_] (throw (ex-info "boom" {})))}
          result (catchup/handle-maintenance resources
                                             {:directory "/test" :project-id "p1"})]
      ;; Should not throw, should return error markers
      (is (some? (:permeation result)))
      (is (some? (:disc-decay result))))))

(deftest test-handle-deliver
  (testing "handle-deliver enqueues piggyback and caches in context-store"
    (let [piggyback-called (atom false)
          store-calls (atom [])
          resources {:piggyback-fn (fn [_aid _entries _refs]
                                     (reset! piggyback-called true))
                     :context-store-fn (fn [data tags _ttl]
                                         (swap! store-calls conj {:data-count (count data) :tags tags})
                                         (str "ctx-" (count @store-calls)))}
          data {:axioms test-axioms
                :priority-conventions test-priority-conventions
                :sessions test-sessions
                :decisions test-decisions
                :conventions test-conventions
                :snippets test-snippets
                :project-id "hive-mcp"}
          result (catchup/handle-deliver resources data)]
      (is (true? (:piggyback-enqueued? result)))
      (is (true? @piggyback-called))
      (is (some? (:context-refs result)))
      (is (= 6 (count (:context-refs result))))))) ;; 6 categories

(deftest test-handle-deliver-empty-entries
  (testing "handle-deliver with no entries doesn't enqueue"
    (let [piggyback-called (atom false)
          resources {:piggyback-fn (fn [& _] (reset! piggyback-called true))
                     :context-store-fn (fn [_ _ _] "ctx-x")}
          data {:axioms [] :priority-conventions [] :sessions []
                :decisions [] :conventions [] :snippets []
                :project-id "p1"}
          result (catchup/handle-deliver resources data)]
      (is (false? (:piggyback-enqueued? result)))
      (is (false? @piggyback-called)))))

(deftest test-handle-end-default
  (testing "handle-end returns selected keys when no build-response-fn"
    (let [result (catchup/handle-end
                  {}
                  {:data {:project-id "p1"
                          :project-name "P1"
                          :axioms-meta [{:id "a1"}]
                          :git-info {:branch "main"}
                          :extra-key "should-appear"}})]
      (is (= "p1" (:project-id result)))
      (is (= "P1" (:project-name result)))
      (is (= [{:id "a1"}] (:axioms-meta result))))))

(deftest test-handle-end-custom-builder
  (testing "handle-end delegates to build-response-fn"
    (let [result (catchup/handle-end
                  {:build-response-fn (fn [data] {:custom (:project-id data)})}
                  {:data {:project-id "p1"}})]
      (is (= {:custom "p1"} result)))))

(deftest test-handle-error-default
  (testing "handle-error throws ExceptionInfo by default"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Catchup workflow error"
                          (catchup/handle-error
                           {}
                           {:error "something broke"
                            :data {:directory "/test" :project-id "p1"}})))))

(deftest test-handle-error-custom
  (testing "handle-error delegates to error-response-fn"
    (let [result (catchup/handle-error
                  {:error-response-fn (fn [err] {:error-handled true :msg (str err)})}
                  {:error "oops" :data {:directory "/test"}})]
      (is (true? (:error-handled result))))))

;; =============================================================================
;; Compilation Tests
;; =============================================================================

(deftest test-compile-catchup-idempotent
  (testing "compile-catchup produces a reusable compiled FSM"
    (let [compiled (catchup/compile-catchup)
          resources (make-test-resources)]
      ;; Can run multiple times with same compiled FSM
      (let [r1 (catchup/run-catchup compiled resources {:directory "/test1"})
            r2 (catchup/run-catchup compiled resources {:directory "/test2"})]
        (is (= "hive-mcp" (:project-id r1)))
        (is (= "hive-mcp" (:project-id r2)))
        (is (some? (:axioms-meta r1)))
        (is (some? (:axioms-meta r2)))))))

;; =============================================================================
;; Dispatch Predicate Tests
;; =============================================================================

(deftest test-always-predicate
  (testing "always returns true for any data"
    (is (catchup/always {}))
    (is (catchup/always {:anything true}))
    (is (catchup/always nil))))
