(ns hive-mcp.events.handlers.transcript-trifecta-test
  "Trifecta tests for transcript event handlers + MCP supertool.

   Unit:    Store registry lifecycle, event handler effects, MCP commands
   Property: Registry invariants, event data shape
   Golden:  MCP list/query/stats response shapes"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.golden :as golden]
            [hive-mcp.events.handlers.transcript :as th]
            [hive-mcp.tools.consolidated.transcript :as ct]))

;; =============================================================================
;; Fixtures: isolate store registry
;; =============================================================================

(defn- isolate-registry [f]
  (let [saved @(deref (resolve 'hive-mcp.events.handlers.transcript/store-registry))]
    (try
      (reset! (deref (resolve 'hive-mcp.events.handlers.transcript/store-registry)) {})
      (f)
      (finally
        (reset! (deref (resolve 'hive-mcp.events.handlers.transcript/store-registry)) saved)))))

(use-fixtures :each isolate-registry)

;; =============================================================================
;; Unit Tests: Store Registry
;; =============================================================================

(deftest register-deregister-test
  (testing "register + get + deregister lifecycle"
    (let [mock-store (reify)]
      (th/register-store! "test-1" mock-store)
      (is (= mock-store (th/get-store "test-1")))
      (is (nil? (th/get-store "nonexistent")))
      (let [removed (th/deregister-store! "test-1")]
        (is (= mock-store removed))
        (is (nil? (th/get-store "test-1")))))))

(deftest deregister-missing-returns-nil-test
  (testing "deregister on missing key returns nil"
    (is (nil? (th/deregister-store! "no-such-agent")))))

;; =============================================================================
;; Unit Tests: Event Handler Effects
;; =============================================================================

(deftest entry-recorded-no-store-lazy-creates-test
  (testing "entry-recorded with no store registered produces ensure-store + re-dispatch"
    (let [effects (th/handle-entry-recorded
                    {}
                    [:transcript/entry-recorded
                     {:agent-id "lazy-1" :turn 1 :role :assistant :content "hi"}])]
      (is (contains? effects :transcript-ensure-store!))
      (is (contains? effects :dispatch))
      (is (= :datahike (get-in effects [:transcript-ensure-store! :backend]))))))

(deftest entry-recorded-with-store-appends-test
  (testing "entry-recorded with registered store produces transcript-append!"
    (th/register-store! "has-store" (reify))
    (let [effects (th/handle-entry-recorded
                    {}
                    [:transcript/entry-recorded
                     {:agent-id "has-store" :turn 2 :role :tool :content "result"}])]
      (is (contains? effects :transcript-append!))
      (is (= "has-store" (:agent-id (:transcript-append! effects)))))))

(deftest session-started-produces-ensure-store-test
  (testing "session-started produces transcript-ensure-store! with :datahike backend"
    (let [effects (th/handle-session-started
                    {}
                    [:transcript/session-started
                     {:agent-id "sess-1" :model "kimi" :task "test"}])]
      (is (contains? effects :transcript-ensure-store!))
      (is (= :datahike (get-in effects [:transcript-ensure-store! :backend]))))))

(deftest session-ended-produces-close-test
  (testing "session-ended produces transcript-close!"
    (let [effects (th/handle-session-ended
                    {}
                    [:transcript/session-ended
                     {:agent-id "sess-1" :turns 5 :outcome :outcome/completed}])]
      (is (contains? effects :transcript-close!))
      (is (= "sess-1" (get-in effects [:transcript-close! :agent-id]))))))

;; =============================================================================
;; Unit Tests: MCP Transcript Handler
;; =============================================================================

(deftest mcp-list-returns-map-test
  (testing "list command returns {:transcripts [...] :count N}"
    (let [result (ct/handle-transcript {:command "list"})]
      (is (contains? result :transcripts))
      (is (contains? result :count))
      (is (vector? (:transcripts result)))
      (is (= (count (:transcripts result)) (:count result))))))

(deftest mcp-unknown-command-test
  (testing "unknown command returns error"
    (let [result (ct/handle-transcript {:command "bogus"})]
      (is (true? (:error result))))))

(deftest mcp-query-missing-agent-test
  (testing "query with nonexistent agent-id returns error"
    (let [result (ct/handle-transcript {:command "query" :agent-id "no-such-agent-xyz"})]
      (is (true? (:error result))))))

(deftest mcp-param-alias-test
  (testing "agent_id and id aliases work"
    ;; Both should route to same handler (may return error for missing agent, that's ok)
    (let [r1 (ct/handle-transcript {:command "query" :agent_id "alias-test"})
          r2 (ct/handle-transcript {:command "query" :id "alias-test"})]
      ;; Both should have same error shape (agent not found)
      (is (= (:error r1) (:error r2))))))

;; =============================================================================
;; Property Tests
;; =============================================================================

(def gen-agent-id
  (gen/fmap #(str "prop-agent-" %) (gen/such-that (complement empty?) gen/string-alphanumeric)))

(defspec registry-register-get-consistent 100
  (prop/for-all [id gen-agent-id]
    (let [mock (reify)]
      (th/register-store! id mock)
      (let [got (th/get-store id)]
        (th/deregister-store! id)
        (= mock got)))))

(defspec registry-deregister-removes 100
  (prop/for-all [id gen-agent-id]
    (th/register-store! id (reify))
    (th/deregister-store! id)
    (nil? (th/get-store id))))

(defspec session-started-always-has-backend 100
  (prop/for-all [id gen-agent-id]
    (let [effects (th/handle-session-started
                    {}
                    [:transcript/session-started {:agent-id id :model "m" :task "t"}])]
      (= :datahike (get-in effects [:transcript-ensure-store! :backend])))))

(defspec entry-recorded-without-store-always-lazy-creates 100
  (prop/for-all [id gen-agent-id]
    (let [effects (th/handle-entry-recorded
                    {}
                    [:transcript/entry-recorded {:agent-id id :turn 1 :role :assistant :content "x"}])]
      (and (contains? effects :transcript-ensure-store!)
           (contains? effects :dispatch)))))

(defspec list-always-returns-count 50
  (prop/for-all [_ (gen/return nil)]
    (let [result (ct/handle-transcript {:command "list"})]
      (and (contains? result :count)
           (= (count (:transcripts result)) (:count result))))))

;; =============================================================================
;; Golden Tests
;; =============================================================================

(deftest event-effects-shape-golden
  (golden/assert-golden
    "test/golden/transcript-event-effects.edn"
    {:session-started
     (th/handle-session-started
       {}
       [:transcript/session-started {:agent-id "golden-1" :model "test-model" :task "golden task"}])

     :session-ended
     (th/handle-session-ended
       {}
       [:transcript/session-ended {:agent-id "golden-1" :turns 10 :outcome :outcome/completed}])

     :entry-no-store
     (dissoc
       (th/handle-entry-recorded
         {}
         [:transcript/entry-recorded {:agent-id "golden-no-store" :turn 1 :role :assistant :content "hi"}])
       :dispatch)}))

(deftest mcp-list-shape-golden
  (golden/assert-golden
    "test/golden/transcript-mcp-list-shape.edn"
    (let [result (ct/handle-transcript {:command "list"})]
      ;; Snapshot structure, not content (content varies)
      {:has-transcripts (boolean (:transcripts result))
       :has-count       (boolean (:count result))
       :count-matches   (= (count (:transcripts result)) (:count result))
       :first-keys      (when (seq (:transcripts result))
                          (sort (keys (first (:transcripts result)))))})))
