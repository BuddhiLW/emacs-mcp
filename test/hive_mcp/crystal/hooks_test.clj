(ns hive-mcp.crystal.hooks-test
  "Tests for crystal/hooks.clj crystallize-session xpoll wiring (P2.7).

   Verifies:
   - xpoll-stats appears in return map for both no-content and content paths
   - Xpoll failure does NOT block crystallization
   - Correct args passed to lifecycle/run-xpoll-cycle!"
  (:require [clojure.test :refer [deftest testing is are]]
            [hive-mcp.crystal.hooks :as hooks]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-spi.memory.registry :as registry]
            [hive-mcp.agent.context :as ctx]
            [clojure.string]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(def ^:private base-harvested
  "Minimal harvested data for testing crystallize-session."
  {:progress-notes []
   :completed-tasks []
   :git-commits []
   :directory "/tmp/test-project"
   :recalls {}
   :summary {:progress-count 0
             :task-count 0
             :commit-count 0
             :recall-count 0}})

(defmacro with-crystallize-mocks
  "Bind mocks for all crystallize-session dependencies.

   opts keys:
     :summary       - return value for crystal/summarize-session-progress (nil = no-content path)
     :promotion     - return value for :ch/a extension
     :decay         - return value for :ch/b extension
     :xpoll         - return value for :ch/c extension
     :xpoll-fn      - custom fn for xpoll (overrides :xpoll)
     :memory-decay   - return value for :ch/d extension
     :memory-decay-fn - custom fn for memory decay (overrides :memory-decay)
     :entry-id      - return value for chroma/index-memory-entry!
     :project-id    - return value for scope/get-current-project-id"
  [opts & body]
  `(let [opts# ~opts
         xpoll-calls# (atom [])
         memory-decay-calls# (atom [])]
     ;; Register extension mocks for lifecycle operations
     (ext/register! :ch/a
                    (fn [_#] (get opts# :promotion
                                  {:promoted 0 :skipped 0 :below 0 :evaluated 0})))
     (ext/register! :ch/b
                    (fn [_#] (get opts# :decay
                                  {:decayed 0 :pruned 0 :fresh 0 :evaluated 0})))
     (ext/register! :ch/c
                    (if-let [custom-fn# (:xpoll-fn opts#)]
                      (fn [args#]
                        (swap! xpoll-calls# conj args#)
                        (custom-fn# args#))
                      (fn [args#]
                        (swap! xpoll-calls# conj args#)
                        (get opts# :xpoll
                             {:promoted 0 :candidates 0 :total-scanned 0}))))
     (ext/register! :ch/d
                    (if-let [custom-fn# (:memory-decay-fn opts#)]
                      (fn [args#]
                        (swap! memory-decay-calls# conj args#)
                        (custom-fn# args#))
                      (fn [args#]
                        (swap! memory-decay-calls# conj args#)
                        (get opts# :memory-decay
                             {:decayed 0 :expired 0 :total-scanned 0}))))
     (stub/install! (stub/->stub nil {:id-fn (constantly (get opts# :entry-id "entry-test-001"))}))
     (try
       (with-redefs [crystal/summarize-session-progress
                     (fn [& _#] (:summary opts#))

                     crystal/session-id
                     (fn [] "test-session-123")

                     scope/get-current-project-id
                     (fn [_#] (get opts# :project-id "test-project"))

                     scope/inject-project-scope
                     (fn [tags# _pid#] tags#)

                     dur/calculate-expires
                     (fn [_#] "2026-02-13T00:00:00Z")

                     ctx/current-directory
                     (fn [] "/tmp/test-project")]
         (let [result# (do ~@body)]
           {:result result#
            :xpoll-calls @xpoll-calls#
            :memory-decay-calls @memory-decay-calls#}))
       (finally
         (registry/reset-registry!)
         (ext/deregister! :ch/a)
         (ext/deregister! :ch/b)
         (ext/deregister! :ch/c)
         (ext/deregister! :ch/d)))))

;; =============================================================================
;; No-content path tests
;; =============================================================================

(deftest crystallize-session-no-content-includes-xpoll-stats
  (testing "No-content path includes :xpoll-stats in return map"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :xpoll {:promoted 0 :candidates 0 :total-scanned 50}}
            (hooks/crystallize-session base-harvested))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (contains? result :xpoll-stats) "Return map must include :xpoll-stats")
      (is (= 0 (:promoted (:xpoll-stats result))))
      (is (= 0 (:candidates (:xpoll-stats result))))
      (is (= 50 (:total-scanned (:xpoll-stats result)))))))

(deftest crystallize-session-no-content-xpoll-with-promotions
  (testing "No-content path reports xpoll promotions correctly"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :xpoll {:promoted 3 :candidates 5 :total-scanned 100}}
            (hooks/crystallize-session base-harvested))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (= 3 (:promoted (:xpoll-stats result))))
      (is (= 5 (:candidates (:xpoll-stats result))))
      (is (= 100 (:total-scanned (:xpoll-stats result)))))))

(deftest crystallize-session-no-content-xpoll-failure-non-blocking
  (testing "No-content path: xpoll failure does NOT block crystallization"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :xpoll-fn (fn [_] (throw (Exception. "Chroma connection refused")))}
            (hooks/crystallize-session base-harvested))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (contains? result :xpoll-stats) "Must still have :xpoll-stats key")
      (is (string? (:error (:xpoll-stats result))) "Should contain error message")
      (is (= 0 (:promoted (:xpoll-stats result))) "Promoted should be 0 on error"))))

(deftest crystallize-session-no-content-xpoll-receives-directory
  (testing "No-content path: xpoll receives correct directory arg"
    (let [{:keys [xpoll-calls]}
          (with-crystallize-mocks
            {:summary nil}
            (hooks/crystallize-session
             (assoc base-harvested :directory "/home/test/my-project")))]
      (is (= 1 (count xpoll-calls)) "xpoll should be called exactly once")
      (is (= "/home/test/my-project" (:directory (first xpoll-calls)))
          "Should pass directory from harvested data")
      (is (= 100 (:limit (first xpoll-calls)))
          "Should pass limit 100"))))

;; =============================================================================
;; Content path tests
;; =============================================================================

(deftest crystallize-session-content-includes-xpoll-stats
  (testing "Content path includes :xpoll-stats in return map"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary {:content "Session summary" :tags ["wrap"]}
             :entry-id "entry-abc-123"
             :xpoll {:promoted 2 :candidates 4 :total-scanned 80}}
            (hooks/crystallize-session
             (assoc base-harvested
                    :progress-notes [{:content "did stuff"}]
                    :git-commits ["abc1234 feat: something"])))]
      (is (not (:skipped result)) "Should NOT be skipped (content path)")
      (is (= "entry-abc-123" (:summary-id result)))
      (is (contains? result :xpoll-stats) "Return map must include :xpoll-stats")
      (is (= 2 (:promoted (:xpoll-stats result))))
      (is (= 4 (:candidates (:xpoll-stats result))))
      (is (= 80 (:total-scanned (:xpoll-stats result)))))))

(deftest crystallize-session-content-xpoll-failure-non-blocking
  (testing "Content path: xpoll failure does NOT block crystallization"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary {:content "Session summary" :tags ["wrap"]}
             :entry-id "entry-def-456"
             :xpoll-fn (fn [_] (throw (Exception. "Embedding model unavailable")))}
            (hooks/crystallize-session
             (assoc base-harvested
                    :progress-notes [{:content "work done"}])))]
      (is (= "entry-def-456" (:summary-id result))
          "Summary should still be created despite xpoll failure")
      (is (contains? result :xpoll-stats) "Must still have :xpoll-stats key")
      (is (string? (:error (:xpoll-stats result))))
      (is (= 0 (:promoted (:xpoll-stats result)))))))

(deftest crystallize-session-content-xpoll-receives-directory
  (testing "Content path: xpoll receives correct directory arg"
    (let [{:keys [xpoll-calls]}
          (with-crystallize-mocks
            {:summary {:content "Summary content" :tags ["wrap"]}
             :entry-id "entry-ghi-789"}
            (hooks/crystallize-session
             (assoc base-harvested
                    :directory "/home/user/other-project"
                    :progress-notes [{:content "progress"}])))]
      (is (= 1 (count xpoll-calls)) "xpoll should be called exactly once")
      (is (= "/home/user/other-project" (:directory (first xpoll-calls))))
      (is (= 100 (:limit (first xpoll-calls)))))))

;; =============================================================================
;; Cross-path consistency tests
;; =============================================================================

(deftest crystallize-session-xpoll-stats-keys-consistent
  (testing "Both paths return same xpoll-stats keys via select-keys"
    (let [{:keys [result] :as no-content}
          (with-crystallize-mocks
            {:summary nil
             :xpoll {:promoted 1 :candidates 2 :total-scanned 10
                     :entries [{:id "x" :promoted true}]}}
            (hooks/crystallize-session base-harvested))
          no-content-keys (set (keys (:xpoll-stats result)))

          {content-result :result}
          (with-crystallize-mocks
            {:summary {:content "Some content" :tags []}
             :entry-id "eid-1"
             :xpoll {:promoted 1 :candidates 2 :total-scanned 10
                     :entries [{:id "x" :promoted true}]}}
            (hooks/crystallize-session
             (assoc base-harvested :progress-notes [{:content "x"}])))
          content-keys (set (keys (:xpoll-stats content-result)))]
      (is (= no-content-keys content-keys)
          "Both paths should select-keys the same fields")
      (is (= #{:promoted :candidates :total-scanned} no-content-keys)
          "Should only include :promoted :candidates :total-scanned (no :entries)")
      ;; :entries should be filtered out by select-keys
      (is (not (contains? no-content-keys :entries))
          ":entries should NOT leak through select-keys"))))

(deftest crystallize-session-xpoll-error-in-stats-keys
  (testing "When xpoll has :error, it appears in xpoll-stats"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :xpoll {:promoted 0 :candidates 0 :total-scanned 0
                     :error "chroma-not-configured"}}
            (hooks/crystallize-session base-harvested))]
      (is (contains? (:xpoll-stats result) :error))
      (is (= "chroma-not-configured" (:error (:xpoll-stats result)))))))

;; =============================================================================
;; Ordering: xpoll runs AFTER co-access promotion and edge decay
;; =============================================================================

(deftest crystallize-session-runs-every-lifecycle-extension
  (testing "Every registered lifecycle extension is invoked exactly once"
    (let [call-order (atom [])]
      (ext/register! :ch/a (fn [_] (swap! call-order conj :ch-a)
                             {:promoted 0 :skipped 0 :below 0 :evaluated 0}))
      (ext/register! :ch/b (fn [_] (swap! call-order conj :ch-b)
                             {:decayed 0 :pruned 0 :fresh 0 :evaluated 0}))
      (ext/register! :ch/c (fn [_] (swap! call-order conj :ch-c)
                             {:promoted 0 :candidates 0 :total-scanned 0}))
      (ext/register! :ch/d (fn [_] (swap! call-order conj :ch-d)
                             {:decayed 0 :expired 0 :total-scanned 0}))
      (try
        (with-redefs [crystal/summarize-session-progress (fn [& _] nil)
                      crystal/session-id (fn [] "test-session")
                      scope/get-current-project-id (fn [_] "test-proj")
                      ctx/current-directory (fn [] "/tmp")]
          (hooks/crystallize-session base-harvested))
        (is (= #{:ch-a :ch-b :ch-c :ch-d} (set @call-order))
            "All four lifecycle extensions run")
        (is (= 4 (count @call-order))
            "Each lifecycle extension runs exactly once")
        (finally
          (ext/deregister! :ch/a)
          (ext/deregister! :ch/b)
          (ext/deregister! :ch/c)
          (ext/deregister! :ch/d))))))

;; =============================================================================
;; P0.3: Memory decay (L2 time-decay) tests
;; =============================================================================

(deftest crystallize-session-no-content-includes-memory-decay-stats
  (testing "No-content path includes :memory-decay-stats in return map"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :memory-decay {:decayed 5 :expired 2 :total-scanned 50}}
            (hooks/crystallize-session base-harvested))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (contains? result :memory-decay-stats) "Return map must include :memory-decay-stats")
      (is (= 5 (:decayed (:memory-decay-stats result))))
      (is (= 2 (:expired (:memory-decay-stats result))))
      (is (= 50 (:total-scanned (:memory-decay-stats result)))))))

(deftest crystallize-session-content-includes-memory-decay-stats
  (testing "Content path includes :memory-decay-stats in return map"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary {:content "Session summary" :tags ["wrap"]}
             :entry-id "entry-decay-001"
             :memory-decay {:decayed 3 :expired 1 :total-scanned 30}}
            (hooks/crystallize-session
             (assoc base-harvested
                    :progress-notes [{:content "did stuff"}])))]
      (is (not (:skipped result)) "Should NOT be skipped (content path)")
      (is (contains? result :memory-decay-stats) "Return map must include :memory-decay-stats")
      (is (= 3 (:decayed (:memory-decay-stats result))))
      (is (= 1 (:expired (:memory-decay-stats result)))))))

(deftest crystallize-session-memory-decay-failure-non-blocking
  (testing "Memory decay failure does NOT block crystallization"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :memory-decay-fn (fn [_] (throw (Exception. "Chroma timeout")))}
            (hooks/crystallize-session base-harvested))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (contains? result :memory-decay-stats) "Must still have :memory-decay-stats key")
      (is (string? (:error (:memory-decay-stats result))) "Should contain error message")
      (is (= 0 (:decayed (:memory-decay-stats result))) "Decayed should be 0 on error"))))

(deftest crystallize-session-memory-decay-receives-directory
  (testing "Memory decay receives correct directory arg"
    (let [{:keys [memory-decay-calls]}
          (with-crystallize-mocks
            {:summary nil}
            (hooks/crystallize-session
             (assoc base-harvested :directory "/home/test/my-project")))]
      (is (= 1 (count memory-decay-calls)) "memory-decay should be called exactly once")
      (is (= "/home/test/my-project" (:directory (first memory-decay-calls)))
          "Should pass directory from harvested data")
      (is (= 50 (:limit (first memory-decay-calls)))
          "Should pass limit 50"))))

(deftest crystallize-session-memory-decay-stats-keys-consistent
  (testing "Both paths return same memory-decay-stats keys via select-keys"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil
             :memory-decay {:decayed 1 :expired 2 :total-scanned 10
                            :extra-field "should-not-leak"}}
            (hooks/crystallize-session base-harvested))
          no-content-keys (set (keys (:memory-decay-stats result)))

          {content-result :result}
          (with-crystallize-mocks
            {:summary {:content "Content" :tags []}
             :entry-id "eid-2"
             :memory-decay {:decayed 1 :expired 2 :total-scanned 10
                            :extra-field "should-not-leak"}}
            (hooks/crystallize-session
             (assoc base-harvested :progress-notes [{:content "x"}])))
          content-keys (set (keys (:memory-decay-stats content-result)))]
      (is (= no-content-keys content-keys)
          "Both paths should select-keys the same fields")
      (is (= #{:decayed :expired :total-scanned} no-content-keys)
          "Should only include :decayed :expired :total-scanned (no extra fields)")
      (is (not (contains? no-content-keys :extra-field))
          ":extra-field should NOT leak through select-keys"))))

;; =============================================================================
;; Session Timestamp Tracking tests
;; =============================================================================

(deftest crystallize-session-includes-session-timing-no-content
  (testing "No-content path includes :session-timing in return map"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil}
            (hooks/crystallize-session
             (assoc base-harvested
                    :session-timing {:session-start "2026-02-11T10:00:00Z"
                                     :session-end "2026-02-11T11:30:00Z"
                                     :duration-minutes 90})))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (contains? result :session-timing) "Return map must include :session-timing")
      (is (= "2026-02-11T10:00:00Z" (:session-start (:session-timing result))))
      (is (= "2026-02-11T11:30:00Z" (:session-end (:session-timing result))))
      (is (= 90 (:duration-minutes (:session-timing result)))))))

(deftest crystallize-session-includes-session-timing-with-content
  (testing "Content path includes :session-timing in return map"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary {:content "Session summary" :tags ["wrap"]}
             :entry-id "entry-timing-001"}
            (hooks/crystallize-session
             (assoc base-harvested
                    :progress-notes [{:content "work done"}]
                    :session-timing {:session-start "2026-02-11T14:00:00Z"
                                     :session-end "2026-02-11T15:45:00Z"
                                     :duration-minutes 105})))]
      (is (not (:skipped result)) "Should NOT be skipped (content path)")
      (is (= "entry-timing-001" (:summary-id result)))
      (is (contains? result :session-timing) "Return map must include :session-timing")
      (is (= "2026-02-11T14:00:00Z" (:session-start (:session-timing result))))
      (is (= 105 (:duration-minutes (:session-timing result)))))))

(deftest crystallize-session-timing-fallback-when-missing
  (testing "When harvested data has no :session-timing, fallback provides end time"
    (let [{:keys [result]}
          (with-crystallize-mocks
            {:summary nil}
            (hooks/crystallize-session base-harvested))]
      (is (:summary-id result)
          "No-content path persists a minimal wrap summary")
      (is (contains? result :session-timing) "Must have :session-timing even without input")
      (is (nil? (:session-start (:session-timing result)))
          "Session start should be nil when not tracked")
      (is (string? (:session-end (:session-timing result)))
          "Session end should still be populated")
      (is (= 0 (:duration-minutes (:session-timing result)))
          "Duration should be 0 when start is nil"))))

;; =============================================================================
;; Session start tracker (crystal/core.clj) — per-agent tests
;; =============================================================================

(deftest session-start-tracker-idempotent
  (testing "record-session-start! is idempotent per agent - only records first call"
    (crystal/reset-session-start!)
    (let [first-start (crystal/record-session-start! "ling-1")
          _ (Thread/sleep 10)
          second-start (crystal/record-session-start! "ling-1")]
      (is (some? first-start) "First call should return an Instant")
      (is (= first-start second-start) "Second call should return same Instant")
      (crystal/reset-session-start!))))

(deftest session-start-tracker-per-agent-isolation
  (testing "Different agents get independent session timestamps"
    (crystal/reset-session-start!)
    (let [start-a (crystal/record-session-start! "ling-a")
          _ (Thread/sleep 10)
          start-b (crystal/record-session-start! "ling-b")]
      (is (some? start-a) "Agent A should have a start time")
      (is (some? start-b) "Agent B should have a start time")
      (is (not= start-a start-b) "Different agents should have different timestamps")
      (is (= start-a (crystal/get-session-start "ling-a"))
          "Agent A's start should be retrievable")
      (is (= start-b (crystal/get-session-start "ling-b"))
          "Agent B's start should be retrievable")
      (crystal/reset-session-start!))))

(deftest session-start-tracker-reset-per-agent
  (testing "reset-session-start! with agent-id only clears that agent"
    (crystal/reset-session-start!)
    (crystal/record-session-start! "ling-1")
    (crystal/record-session-start! "ling-2")
    (is (some? (crystal/get-session-start "ling-1")))
    (is (some? (crystal/get-session-start "ling-2")))
    ;; Reset only ling-1
    (crystal/reset-session-start! "ling-1")
    (is (nil? (crystal/get-session-start "ling-1"))
        "Agent 1 should be nil after per-agent reset")
    (is (some? (crystal/get-session-start "ling-2"))
        "Agent 2 should still have start time")
    (crystal/reset-session-start!)))

(deftest session-start-tracker-reset-all
  (testing "reset-session-start! with no args clears all agents"
    (crystal/reset-session-start!)
    (crystal/record-session-start! "ling-1")
    (crystal/record-session-start! "ling-2")
    (crystal/reset-session-start!)
    (is (nil? (crystal/get-session-start "ling-1")) "Agent 1 should be nil after full reset")
    (is (nil? (crystal/get-session-start "ling-2")) "Agent 2 should be nil after full reset")))

(deftest session-start-tracker-fallback-to-global
  (testing "get-session-start falls back to _global when agent-specific not found"
    (crystal/reset-session-start!)
    (let [global-start (crystal/record-session-start!)] ;; no agent-id → _global
      (is (= global-start (crystal/get-session-start "unknown-ling"))
          "Unknown agent should fall back to _global start")
      (crystal/reset-session-start!))))

(deftest session-timing-metadata-computation
  (testing "session-timing-metadata computes correct duration"
    (let [start (java.time.Instant/parse "2026-02-11T10:00:00Z")
          end (java.time.Instant/parse "2026-02-11T11:30:00Z")
          result (crystal/session-timing-metadata start end)]
      (is (= "2026-02-11T10:00:00Z" (:session-start result)))
      (is (= "2026-02-11T11:30:00Z" (:session-end result)))
      (is (= 90 (:duration-minutes result)))))
  (testing "session-timing-metadata handles nil start gracefully"
    (let [end (java.time.Instant/parse "2026-02-11T11:30:00Z")
          result (crystal/session-timing-metadata nil end)]
      (is (nil? (:session-start result)))
      (is (= "2026-02-11T11:30:00Z" (:session-end result)))
      (is (= 0 (:duration-minutes result))))))

;; =============================================================================
;; Step-5: Temporal metadata embedded in wrap summary content
;; =============================================================================

(deftest crystallize-session-embeds-temporal-block-in-content
  (testing "Content path appends temporal metadata block to stored content"
    (let [store (stub/install! (stub/->stub))]
      (with-redefs [crystal/summarize-session-progress
                    (fn [& _] {:content "## Session Summary: test\n\nSome work" :tags ["wrap"]})
                    crystal/session-id (fn [] "test-session")
                    scope/get-current-project-id (fn [_] "test-project")
                    scope/inject-project-scope (fn [tags _] tags)
                    dur/calculate-expires (fn [_] "2026-02-13T00:00:00Z")
                    ctx/current-directory (fn [] "/tmp/test")]
        (hooks/crystallize-session
         (assoc base-harvested
                :progress-notes [{:content "work done"}]
                :session-timing {:session-start "2026-02-11T10:00:00Z"
                                 :session-end "2026-02-11T11:30:00Z"
                                 :duration-minutes 90}))
        (is (= 1 (count (stub/entries store))) "Should have stored one entry")
        (let [stored-content (:content (first (vals (stub/entries store))))]
          (is (clojure.string/includes? stored-content "Temporal Metadata")
              "Stored content should include temporal block header")
          (is (clojure.string/includes? stored-content "2026-02-11T10:00:00Z")
              "Stored content should include session start")
          (is (clojure.string/includes? stored-content "2026-02-11T11:30:00Z")
              "Stored content should include session end")
          (is (clojure.string/includes? stored-content "90 minutes")
              "Stored content should include duration"))))))

(deftest crystallize-session-adds-autokg-tags
  (testing "Content path adds auto-kg, session-wrap, temporal tags"
    (let [store (stub/install! (stub/->stub))]
      (with-redefs [crystal/summarize-session-progress
                    (fn [& _] {:content "Summary" :tags ["wrap"]})
                    crystal/session-id (fn [] "test-session")
                    scope/get-current-project-id (fn [_] "test-project")
                    scope/inject-project-scope (fn [tags _] tags)
                    dur/calculate-expires (fn [_] "2026-02-13T00:00:00Z")
                    ctx/current-directory (fn [] "/tmp/test")]
        (hooks/crystallize-session
         (assoc base-harvested
                :progress-notes [{:content "work"}]
                :session-timing {:session-start "2026-02-11T10:00:00Z"
                                 :session-end "2026-02-11T11:00:00Z"
                                 :duration-minutes 60}))
        (let [stored-tags (:tags (first (vals (stub/entries store))))]
          (is (some #{"auto-kg"} stored-tags)
              "Tags should include auto-kg")
          (is (some #{"session-wrap"} stored-tags)
              "Tags should include session-wrap")
          (is (some #{"temporal"} stored-tags)
              "Tags should include temporal")
          (is (some #{"wrap"} stored-tags)
              "Original summary tags should be preserved"))))))

(deftest crystallize-session-temporal-includes-memory-ids
  (testing "Temporal block includes memory-ids counts when available from harvest"
    (let [store (stub/install! (stub/->stub))]
      (with-redefs [crystal/summarize-session-progress
                    (fn [& _] {:content "Summary" :tags ["wrap"]})
                    crystal/session-id (fn [] "test-session")
                    scope/get-current-project-id (fn [_] "test-project")
                    scope/inject-project-scope (fn [tags _] tags)
                    dur/calculate-expires (fn [_] "2026-02-13T00:00:00Z")
                    ctx/current-directory (fn [] "/tmp/test")]
        (hooks/crystallize-session
         (assoc base-harvested
                :progress-notes [{:content "work"}]
                :session-timing {:session-start "2026-02-11T10:00:00Z"
                                 :session-end "2026-02-11T11:00:00Z"
                                 :duration-minutes 60}
                :memory-ids-created ["id-1" "id-2" "id-3"]
                :memory-ids-accessed ["id-4" "id-5"]))
        (let [stored-content (:content (first (vals (stub/entries store))))]
          (is (clojure.string/includes? stored-content "Memory entries created: 3")
              "Should include created count")
          (is (clojure.string/includes? stored-content "Memory entries accessed: 2")
              "Should include accessed count"))))))

(deftest crystallize-session-temporal-no-memory-ids-graceful
  (testing "Temporal block works without memory-ids (nil-safe for steps 2-4)"
    (let [store (stub/install! (stub/->stub))]
      (with-redefs [crystal/summarize-session-progress
                    (fn [& _] {:content "Summary" :tags ["wrap"]})
                    crystal/session-id (fn [] "test-session")
                    scope/get-current-project-id (fn [_] "test-project")
                    scope/inject-project-scope (fn [tags _] tags)
                    dur/calculate-expires (fn [_] "2026-02-13T00:00:00Z")
                    ctx/current-directory (fn [] "/tmp/test")]
        (hooks/crystallize-session
         (assoc base-harvested
                :progress-notes [{:content "work"}]
                :session-timing {:session-start "2026-02-11T10:00:00Z"
                                 :session-end "2026-02-11T11:00:00Z"
                                 :duration-minutes 60}))
        (let [stored-content (:content (first (vals (stub/entries store))))]
          (is (clojure.string/includes? stored-content "Temporal Metadata")
              "Temporal block should still be present")
          (is (not (clojure.string/includes? stored-content "Memory entries created"))
              "Should NOT include created when absent")
          (is (not (clojure.string/includes? stored-content "Memory entries accessed"))
              "Should NOT include accessed when absent"))))))

;; =============================================================================
;; Step-4: harvest-all :session-temporal, :memory-ids-created, :memory-ids-accessed
;; =============================================================================

(deftest harvest-all-includes-session-temporal
  (testing "harvest-all returns :session-temporal as alias for :session-timing"
    (with-redefs [ctx/current-directory (fn [] "/tmp/test")
                  hooks/harvest-session-progress (fn [_] {:notes [] :count 0})
                  hooks/harvest-completed-tasks (fn [_] {:tasks [] :count 0})
                  hooks/harvest-git-commits (fn [_] {:commits [] :count 0})
                  hive-mcp.crystal.recall/get-buffered-recalls (fn [] {})
                  hive-mcp.crystal.recall/flush-created-ids! (fn [& _] [])
                  crystal/get-session-start (fn [& _] (java.time.Instant/parse "2026-02-11T10:00:00Z"))
                  crystal/session-timing-metadata
                  (fn [start end]
                    {:session-start (some-> start .toString)
                     :session-end (.toString end)
                     :duration-minutes 90})
                  crystal/session-id (fn [] "test-session")]
      (let [result (hooks/harvest-all {:directory "/tmp/test"})]
        (is (contains? result :session-temporal)
            "harvest-all must include :session-temporal")
        (is (= (:session-timing result) (:session-temporal result))
            ":session-temporal should be identical to :session-timing")
        (is (map? (:session-temporal result))
            ":session-temporal should be a map")))))

(deftest harvest-all-includes-memory-ids-created
  (testing "harvest-all returns :memory-ids-created from flush-created-ids!"
    (with-redefs [ctx/current-directory (fn [] "/tmp/test")
                  hooks/harvest-session-progress (fn [_] {:notes [] :count 0})
                  hooks/harvest-completed-tasks (fn [_] {:tasks [] :count 0})
                  hooks/harvest-git-commits (fn [_] {:commits [] :count 0})
                  hive-mcp.crystal.recall/get-buffered-recalls (fn [] {})
                  hive-mcp.crystal.recall/flush-created-ids!
                  (fn [& _] [{:id "note-abc" :timestamp "2026-02-11T10:05:00Z"}
                             {:id "note-def" :timestamp "2026-02-11T10:10:00Z"}])
                  crystal/get-session-start (fn [& _] nil)
                  crystal/session-timing-metadata
                  (fn [_ end] {:session-start nil :session-end (.toString end) :duration-minutes 0})
                  crystal/session-id (fn [] "test-session")]
      (let [result (hooks/harvest-all {:directory "/tmp/test"})]
        (is (contains? result :memory-ids-created)
            "harvest-all must include :memory-ids-created")
        (is (= 2 (count (:memory-ids-created result)))
            "Should have 2 created IDs")
        (is (= "note-abc" (:id (first (:memory-ids-created result))))
            "First created ID should be note-abc")
        (is (= 2 (get-in result [:summary :created-count]))
            "Summary :created-count should be 2")))))

(deftest harvest-all-includes-memory-ids-accessed
  (testing "harvest-all returns :memory-ids-accessed from recall buffer keys"
    (with-redefs [ctx/current-directory (fn [] "/tmp/test")
                  hooks/harvest-session-progress (fn [_] {:notes [] :count 0})
                  hooks/harvest-completed-tasks (fn [_] {:tasks [] :count 0})
                  hooks/harvest-git-commits (fn [_] {:commits [] :count 0})
                  hive-mcp.crystal.recall/get-buffered-recalls
                  (fn [] {"entry-111" [{:context :explicit-reference}]
                          "entry-222" [{:context :cross-session}]
                          "entry-333" [{:context :catchup-structural}]})
                  hive-mcp.crystal.recall/flush-created-ids! (fn [& _] [])
                  crystal/get-session-start (fn [& _] nil)
                  crystal/session-timing-metadata
                  (fn [_ end] {:session-start nil :session-end (.toString end) :duration-minutes 0})
                  crystal/session-id (fn [] "test-session")]
      (let [result (hooks/harvest-all {:directory "/tmp/test"})]
        (is (contains? result :memory-ids-accessed)
            "harvest-all must include :memory-ids-accessed")
        (is (= 3 (count (:memory-ids-accessed result)))
            "Should have 3 accessed IDs")
        (is (every? string? (:memory-ids-accessed result))
            "All accessed IDs should be strings")
        (is (= (set (:memory-ids-accessed result))
               #{"entry-111" "entry-222" "entry-333"})
            "Should contain exact accessed entry IDs")
        (is (= 3 (get-in result [:summary :accessed-count]))
            "Summary :accessed-count should be 3")))))

(deftest harvest-all-empty-buffers-return-empty-collections
  (testing "harvest-all returns empty collections when no IDs created/accessed"
    (with-redefs [ctx/current-directory (fn [] "/tmp/test")
                  hooks/harvest-session-progress (fn [_] {:notes [] :count 0})
                  hooks/harvest-completed-tasks (fn [_] {:tasks [] :count 0})
                  hooks/harvest-git-commits (fn [_] {:commits [] :count 0})
                  hive-mcp.crystal.recall/get-buffered-recalls (fn [] {})
                  hive-mcp.crystal.recall/flush-created-ids! (fn [& _] [])
                  crystal/get-session-start (fn [& _] nil)
                  crystal/session-timing-metadata
                  (fn [_ end] {:session-start nil :session-end (.toString end) :duration-minutes 0})
                  crystal/session-id (fn [] "test-session")]
      (let [result (hooks/harvest-all {:directory "/tmp/test"})]
        (is (= [] (:memory-ids-created result))
            "Empty created buffer returns []")
        (is (= [] (:memory-ids-accessed result))
            "Empty recall buffer returns []")
        (is (= 0 (get-in result [:summary :created-count]))
            "Summary :created-count should be 0")
        (is (= 0 (get-in result [:summary :accessed-count]))
            "Summary :accessed-count should be 0")))))

(deftest harvest-all-flush-created-ids-failure-non-blocking
  (testing "flush-created-ids! failure returns [] via safe-effect"
    (with-redefs [ctx/current-directory (fn [] "/tmp/test")
                  hooks/harvest-session-progress (fn [_] {:notes [] :count 0})
                  hooks/harvest-completed-tasks (fn [_] {:tasks [] :count 0})
                  hooks/harvest-git-commits (fn [_] {:commits [] :count 0})
                  hive-mcp.crystal.recall/get-buffered-recalls (fn [] {})
                  hive-mcp.crystal.recall/flush-created-ids!
                  (fn [& _] (throw (Exception. "Atom corrupted")))
                  crystal/get-session-start (fn [& _] nil)
                  crystal/session-timing-metadata
                  (fn [_ end] {:session-start nil :session-end (.toString end) :duration-minutes 0})
                  crystal/session-id (fn [] "test-session")]
      (let [result (hooks/harvest-all {:directory "/tmp/test"})]
        (is (= [] (:memory-ids-created result))
            "Should fall back to [] on exception")
        (is (= 0 (get-in result [:summary :created-count]))
            "Summary :created-count should be 0 on failure")))))

(deftest harvest-all-catastrophic-error-includes-defaults
  (testing "Catastrophic harvest-all error includes default values for new fields"
    (with-redefs [ctx/current-directory (fn [] (throw (Exception. "No directory")))]
      (let [result (hooks/harvest-all nil)]
        (is (contains? result :session-temporal)
            "Error fallback must include :session-temporal")
        (is (= [] (:memory-ids-created result))
            "Error fallback :memory-ids-created should be []")
        (is (= [] (:memory-ids-accessed result))
            "Error fallback :memory-ids-accessed should be []")
        (is (= 0 (get-in result [:summary :created-count]))
            "Error fallback :created-count should be 0")
        (is (= 0 (get-in result [:summary :accessed-count]))
            "Error fallback :accessed-count should be 0")))))
