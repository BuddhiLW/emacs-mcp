(ns hive-mcp.workflows.wrap-session-test
  "Tests for the wrap session FSM workflow.

   Tests the FSM state machine behavior with mock resources,
   verifying state transitions, handler logic, and error paths."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.workflows.wrap-session :as wrap]))

;; =============================================================================
;; Test Resources (mock side-effect functions)
;; =============================================================================

(defn make-test-resources
  "Create mock resources for testing.
   Resource keys match the handler docstrings:
     :harvest-fn     -- (fn [directory] -> harvested-data)
     :crystallize-fn -- (fn [harvested] -> {:summary-id str, :stats map, ...})
     :kg-edge-fn     -- (fn [summary-id source-ids project-id agent-id] -> {:created-count N})
     :source-ids-fn  -- (fn [harvested] -> [string])
     :notify-fn      -- (fn [agent-id session-id project-id stats] -> nil)
     :evict-fn       -- (fn [agent-id] -> {:evicted N})
     :scope-fn       -- (fn [directory] -> project-id)"
  ([] (make-test-resources {}))
  ([overrides]
   (merge
    {:harvest-fn     (fn [_dir] {:progress-notes [{:id "note-1" :content "did stuff"}]
                                 :completed-tasks [{:id "task-1"}]
                                 :session {:start "2026-02-07T00:00:00Z"}
                                 :summary {:notes 1 :tasks 1}})
     :crystallize-fn (fn [_harvested] {:summary-id "sum-123"
                                       :stats {:promoted 0 :flushed 1}})
     :kg-edge-fn     (fn [_sid _sids _pid _aid] {:created-count 2 :edge-ids ["e1" "e2"]})
     :source-ids-fn  (fn [harvested]
                       (->> (concat (:progress-notes harvested)
                                    (:completed-tasks harvested))
                            (keep :id)
                            vec))
     :notify-fn      (fn [& _args] nil)
     :evict-fn       (fn [_aid] {:evicted 3})
     :scope-fn       (fn [_dir] "hive-mcp")}
    overrides)))

;; =============================================================================
;; Happy Path Tests
;; =============================================================================

(deftest test-wrap-session-happy-path
  (testing "Full wrap session completes all phases"
    (let [resources (make-test-resources)
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "ling-test-123"
                   :directory "/test/project"})]
      (is (= "ling-test-123" (:agent-id result)))
      (is (= "hive-mcp" (:project-id result)))
      (is (= "sum-123" (get-in result [:crystal-result :summary-id])))
      (is (= 2 (get-in result [:kg-result :created-count])))
      (is (true? (:notify-sent? result)))
      (is (= 3 (get-in result [:eviction :evicted]))))))

(deftest test-wrap-session-agent-from-resources
  (testing "Agent-id can come from resources if not in initial data"
    (let [resources (make-test-resources)
          result (wrap/run-wrap-session
                  (assoc resources :agent-id "resource-agent")
                  {:directory "/test/project"})]
      (is (= "resource-agent" (:agent-id result))))))

;; =============================================================================
;; Phase Skip Tests
;; =============================================================================

(deftest test-wrap-session-no-kg-edges-when-no-summary
  (testing "KG edges skipped when no summary-id produced"
    (let [resources (make-test-resources
                     {:crystallize-fn (fn [_] {:summary-id nil :stats {}})})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      (is (true? (get-in result [:kg-result :skipped]))))))

(deftest test-wrap-session-no-kg-edges-when-no-source-ids
  (testing "KG edges skipped when no source IDs"
    (let [resources (make-test-resources
                     {:source-ids-fn (fn [_] [])})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      (is (true? (get-in result [:kg-result :skipped]))))))

(deftest test-wrap-session-evict-skipped-when-no-evict-fn
  (testing "Eviction skipped when no evict-fn in resources"
    (let [resources (make-test-resources {:evict-fn nil})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      (is (= 0 (get-in result [:eviction :evicted])))
      (is (true? (get-in result [:eviction :skipped]))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-wrap-session-harvest-error-degraded
  (testing "Harvest failure is caught; wrap continues in degraded mode"
    ;; Mirrors 'Git Commit Optional in Wrap Flow' decision (20260213005110):
    ;; a failing harvest (e.g. nREPL/HTTP down) must not abort wrap.
    (let [resources (make-test-resources
                     {:harvest-fn (fn [_] (throw (ex-info "Harvest boom" {})))})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      ;; Wrap still succeeds end-to-end.
      (is (some? result))
      (is (= "test-ling" (:agent-id result)))
      ;; Crystallize ran against the degraded harvested map (from make-test-resources
      ;; :crystallize-fn which ignores input) — still produces a summary.
      (is (some? (:crystal-result result))))))

(deftest test-wrap-session-crystallize-error-transitions-to-error
  (testing "Crystallize returning a fatal :error (no :degraded) still transitions to FSM error"
    (let [resources (make-test-resources
                     {:crystallize-fn (fn [_] {:error "Chroma down"})})]
      ;; Explicit domain error without :degraded marker stays fatal.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Wrap session workflow error"
                            (wrap/run-wrap-session
                             resources
                             {:agent-id "test-ling" :directory "/test"}))))))

;; =============================================================================
;; nREPL / HTTP Down — Degraded Mode Tests (task 20260404141548-1c053836)
;; =============================================================================

(deftest test-wrap-session-nrepl-http-down-degraded
  (testing "Wrap still succeeds when crystallize (nREPL HTTP call) throws.
            Pattern ref: 'Git Commit Optional in Wrap Flow' (20260213005110)."
    (let [resources (make-test-resources
                     {:crystallize-fn (fn [_]
                                        (throw (ex-info "HTTP Error: nREPL down"
                                                        {:status 0
                                                         :reason "Connection refused"})))})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      ;; Wrap returns normally (no exception).
      (is (some? result))
      (is (= "test-ling" (:agent-id result)))
      ;; Crystallize step marked degraded/skipped.
      (is (true? (get-in result [:crystal-result :skipped])))
      (is (true? (get-in result [:crystal-result :degraded])))
      (is (re-find #"(?i)nrepl|http" (str (get-in result [:crystal-result :error]))))
      ;; Downstream steps still ran.
      (is (true? (get-in result [:kg-result :skipped])))  ; no summary-id
      (is (true? (:notify-sent? result)))
      (is (= 3 (get-in result [:eviction :evicted]))))))

(deftest test-wrap-session-kg-edges-degraded-when-http-error
  (testing "KG edges failure (e.g. HTTP error) marks step :degraded and continues."
    (let [resources (make-test-resources
                     {:kg-edge-fn (fn [& _]
                                    (throw (ex-info "HTTP Error: nREPL down" {})))})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      (is (some? result))
      (is (true? (get-in result [:kg-result :skipped])))
      (is (true? (get-in result [:kg-result :degraded])))
      ;; Notify and evict still executed.
      (is (true? (:notify-sent? result)))
      (is (= 3 (get-in result [:eviction :evicted]))))))

(deftest test-wrap-session-notify-degraded-when-http-error
  (testing "Notify failure (e.g. HTTP error to hivemind) marks :notify-degraded and continues."
    (let [resources (make-test-resources
                     {:notify-fn (fn [& _]
                                   (throw (ex-info "HTTP Error: hivemind unreachable" {})))})
          result (wrap/run-wrap-session
                  resources
                  {:agent-id "test-ling" :directory "/test"})]
      (is (some? result))
      (is (false? (:notify-sent? result)))
      (is (true? (:notify-degraded result)))
      ;; Evict still ran.
      (is (= 3 (get-in result [:eviction :evicted]))))))

;; =============================================================================
;; Compilation Tests
;; =============================================================================

(deftest test-wrap-session-compile-idempotent
  (testing "compile-wrap produces a reusable compiled FSM"
    (let [compiled (wrap/compile-wrap)
          resources (make-test-resources)]
      ;; Can run multiple times with same compiled FSM
      (let [r1 (wrap/run-wrap compiled resources
                              {:agent-id "ling-1" :directory "/test"})
            r2 (wrap/run-wrap compiled resources
                              {:agent-id "ling-2" :directory "/test"})]
        (is (= "ling-1" (:agent-id r1)))
        (is (= "ling-2" (:agent-id r2)))
        (is (some? (:crystal-result r1)))
        (is (some? (:crystal-result r2)))))))

;; =============================================================================
;; Handler Unit Tests
;; =============================================================================

(deftest test-handle-start
  (testing "handle-start sets up agent-id, directory, project-id"
    (let [resources {:scope-fn (fn [_] "test-project")}
          result (wrap/handle-start resources
                                    {:agent-id "ling-x" :directory "/foo"})]
      (is (= "ling-x" (:agent-id result)))
      (is (= "/foo" (:directory result)))
      (is (= "test-project" (:project-id result)))
      (is (nil? (:error result))))))

(deftest test-handle-start-from-resources
  (testing "handle-start falls back to resources for agent-id and directory"
    (let [resources {:scope-fn (fn [_] "test-proj")
                     :agent-id "res-agent"
                     :directory "/res/dir"}
          result (wrap/handle-start resources {})]
      (is (= "res-agent" (:agent-id result)))
      (is (= "/res/dir" (:directory result)))
      (is (= "test-proj" (:project-id result))))))

(deftest test-handle-gather-success
  (testing "handle-gather stores harvested data"
    (let [resources {:harvest-fn (fn [_] {:notes 5})}
          result (wrap/handle-gather resources {:directory "/test"})]
      (is (= {:notes 5} (:harvested result))))))

(deftest test-handle-crystallize-success
  (testing "handle-crystallize stores crystal result and source-ids"
    (let [resources {:crystallize-fn (fn [_] {:summary-id "s1" :stats {}})
                     :source-ids-fn (fn [_] ["id-1" "id-2"])}
          result (wrap/handle-crystallize resources
                                          {:harvested {:some "data"}})]
      (is (= "s1" (get-in result [:crystal-result :summary-id])))
      (is (= ["id-1" "id-2"] (:source-ids result))))))

(deftest test-handle-kg-edges-success
  (testing "handle-kg-edges creates edges when summary-id and source-ids present"
    (let [called-with (atom nil)
          resources {:kg-edge-fn (fn [sid sids pid aid]
                                    (reset! called-with {:sid sid :sids sids :pid pid :aid aid})
                                    {:created-count 2})}
          result (wrap/handle-kg-edges resources
                                        {:crystal-result {:summary-id "sum-1"}
                                         :source-ids ["n1" "n2"]
                                         :project-id "proj-x"
                                         :agent-id "ling-y"})]
      (is (= 2 (get-in result [:kg-result :created-count])))
      (is (= "sum-1" (:sid @called-with)))
      (is (= ["n1" "n2"] (:sids @called-with))))))

(deftest test-handle-kg-edges-skipped
  (testing "handle-kg-edges skips when no summary-id"
    (let [result (wrap/handle-kg-edges
                  {:kg-edge-fn (fn [& _] {:created-count 99})}
                  {:crystal-result {:summary-id nil}
                   :source-ids ["n1"]
                   :project-id "p"
                   :agent-id "a"})]
      (is (true? (get-in result [:kg-result :skipped]))))))

(deftest test-handle-notify-sends
  (testing "handle-notify calls notify-fn and sets notify-sent?"
    (let [notified (atom nil)
          resources {:notify-fn (fn [aid sid pid stats]
                                   (reset! notified {:aid aid :sid sid :pid pid :stats stats}))}
          result (wrap/handle-notify resources
                                      {:agent-id "ling-x"
                                       :project-id "proj-x"
                                       :crystal-result {:session "sess-1"
                                                        :stats {:promoted 1}}})]
      (is (true? (:notify-sent? result)))
      (is (= "ling-x" (:aid @notified)))
      (is (= "sess-1" (:sid @notified)))
      (is (= {:promoted 1} (:stats @notified))))))

(deftest test-handle-evict-success
  (testing "handle-evict calls evict-fn"
    (let [resources {:evict-fn (fn [_] {:evicted 5})}
          result (wrap/handle-evict resources {:agent-id "ling-x"})]
      (is (= 5 (get-in result [:eviction :evicted]))))))

(deftest test-handle-evict-no-fn
  (testing "handle-evict skips when no evict-fn"
    (let [result (wrap/handle-evict {} {:agent-id "ling-x"})]
      (is (= 0 (get-in result [:eviction :evicted])))
      (is (true? (get-in result [:eviction :skipped]))))))

(deftest test-dispatch-predicates
  (testing "harvested? checks for :harvested key"
    (is (wrap/harvested? {:harvested {:data true}}))
    (is (not (wrap/harvested? {})))
    (is (not (wrap/harvested? {:harvested nil}))))
  (testing "crystallized? checks for crystal-result without error"
    (is (wrap/crystallized? {:crystal-result {:summary-id "x"}}))
    (is (not (wrap/crystallized? {})))
    (is (not (wrap/crystallized? {:crystal-result {:error "bad"}}))))
  (testing "crystal-error? checks for crystal-result with error"
    (is (wrap/crystal-error? {:crystal-result {:error "bad"}}))
    (is (not (wrap/crystal-error? {:crystal-result {:summary-id "x"}})))
    (is (not (wrap/crystal-error? {})))))
