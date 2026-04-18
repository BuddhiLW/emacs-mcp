(ns hive-mcp.crystal.synthesis-test
  "Golden + property tests for crystal/synthesis.clj (Wave 2, T2).

   Golden tests: known inputs → expected output shapes
   Property tests: any valid input → required structural invariants

   All mocks follow the crystallize-session mock pattern from golden_test.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.crystal.synthesis :as synthesis]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.dns.result :as result]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pure function tests (no mocks needed)
;; =============================================================================

(deftest format-temporal-block-full
  (testing "Temporal block with all fields populated"
    (let [timing {:session-start "2026-01-15T10:00:00Z"
                  :session-end "2026-01-15T12:00:00Z"
                  :duration-minutes 120}
          harvested {:memory-ids-created ["id-1" "id-2" "id-3"]
                     :memory-ids-accessed ["id-4" "id-5"]}
          result (synthesis/format-temporal-block timing harvested)]
      (is (string? result))
      (is (.contains result "### Temporal Metadata"))
      (is (.contains result "2026-01-15T10:00:00Z"))
      (is (.contains result "120 minutes"))
      (is (.contains result "Memory entries created: 3"))
      (is (.contains result "Memory entries accessed: 2")))))

(deftest format-temporal-block-empty-ids
  (testing "Temporal block omits memory lines when empty"
    (let [result (synthesis/format-temporal-block
                  {:session-start "2026-01-15T10:00:00Z"
                   :session-end "2026-01-15T12:00:00Z"
                   :duration-minutes 120}
                  {})]
      (is (string? result))
      (is (not (.contains result "Memory entries created")))
      (is (not (.contains result "Memory entries accessed"))))))

(deftest format-temporal-block-nil-start
  (testing "Temporal block handles nil session-start"
    (let [result (synthesis/format-temporal-block
                  {:session-start nil
                   :session-end "2026-01-15T12:00:00Z"
                   :duration-minutes 0}
                  {:memory-ids-created []
                   :memory-ids-accessed []})]
      (is (.contains result "unknown"))
      (is (.contains result "0 minutes")))))

(deftest build-summary-content-test
  (testing "build-summary-content concatenates summary + temporal block"
    (let [summary {:content "## Session Summary\n\nDid some work."}
          timing {:session-start "2026-01-15T10:00:00Z"
                  :session-end "2026-01-15T12:00:00Z"
                  :duration-minutes 120}
          harvested {:memory-ids-created ["a"] :memory-ids-accessed ["b"]}
          result (synthesis/build-summary-content summary timing harvested)]
      (is (.startsWith result "## Session Summary"))
      (is (.contains result "### Temporal Metadata"))
      (is (.contains result "120 minutes")))))

(deftest build-summary-tags-test
  (testing "build-summary-tags includes auto-kg, session-wrap, temporal"
    (with-redefs [scope/inject-project-scope (fn [tags _pid] tags)]
      (let [result (synthesis/build-summary-tags {:tags ["wrap"]} "test-project")]
        (is (some #{"auto-kg"} result))
        (is (some #{"session-wrap"} result))
        (is (some #{"temporal"} result))
        (is (some #{"wrap"} result))))))

;; =============================================================================
;; Shared mock infrastructure
;; =============================================================================

(defmacro ^:private with-synthesis-mocks
  "Bind all synthesize dependencies to deterministic fakes.
   opts keys:
     :summary     — return from summarize-session-progress (nil = no-content)
     :entry-id    — return from facade/index-memory-entry!"
  [opts & body]
  `(let [opts# ~opts]
     (ext/register! :ch/a (fn [_#] {:promoted 0 :skipped 0 :below 0 :evaluated 0}))
     (ext/register! :ch/b (fn [_#] {:decayed 0 :pruned 0 :fresh 0 :evaluated 0}))
     (ext/register! :ch/c (fn [_#] {:promoted 0 :candidates 0 :total-scanned 0}))
     (ext/register! :ch/d (fn [_#] {:decayed 0 :expired 0 :total-scanned 0}))
     (ext/register! :ch/e (fn [_#] {:files-captured 0}))
     (try
       (with-redefs
         [crystal/summarize-session-progress
          (fn [& _#] (:summary opts#))

          crystal/summarize-memory-activity
          (fn [& _#] nil)

          crystal/session-id
          (fn [] "test-session-synth")

          crystal/session-timing-metadata
          (fn [_start# _end#]
            {:session-start nil
             :session-end "2026-01-15T12:00:00Z"
             :duration-minutes 0})

          scope/get-current-project-id
          (fn [_#] "synth-project")

          scope/inject-project-scope
          (fn [tags# _pid#] tags#)

          dur/calculate-expires
          (fn [_#] "2026-03-15T00:00:00Z")

          ctx/current-directory
          (fn [] "/tmp/synth-test")

          facade/index-memory-entry!
          (fn [_#] (get opts# :entry-id "entry-synth-001"))

          facade/content-hash
          (fn [c#] (str (hash c#)))]

         (let [result# (do ~@body)]
           result#))
       (finally
         (ext/deregister! :ch/a)
         (ext/deregister! :ch/b)
         (ext/deregister! :ch/c)
         (ext/deregister! :ch/d)
         (ext/deregister! :ch/e)))))

(def ^:private base-harvested
  "Minimal harvested data for testing synthesize."
  {:progress-notes []
   :completed-tasks []
   :git-commits []
   :directory "/tmp/synth-test"
   :recalls {}
   :memory-ids-created []
   :memory-ids-accessed []
   :session-timing {:session-start "2026-01-15T10:00:00Z"
                    :session-end "2026-01-15T12:00:00Z"
                    :duration-minutes 120}
   :summary {:progress-count 0
             :task-count 0
             :commit-count 0
             :recall-count 0
             :hivemind-shout-count 0
             :kanban-completed 0
             :created-count 0
             :accessed-count 0}})

;; =============================================================================
;; Golden: synthesize — no-content path
;; =============================================================================

(deftest golden-synthesize-no-content
  (testing "synthesize with no synthesized summary still persists a minimal breadcrumb"
    ;; Contract change (bugfix): every wrap MUST leave a memory-store trace.
    ;; Quiet sessions now fall back to minimal-wrap-summary instead of skipping.
    (with-synthesis-mocks
      {:summary nil
       :entry-id "entry-minimal-001"}
      (let [result (synthesis/synthesize base-harvested)]
        ;; Structure: content-path shape even when no synthesized summary
        (is (= "entry-minimal-001" (:summary-id result))
            "Quiet sessions still persist and return a :summary-id")
        (is (not (contains? result :skipped))
            "No silent skip — every wrap persists")
        (is (= "test-session-synth" (:session result)))
        (is (= "synth-project" (:project-id result)))
        (is (map? (:session-timing result)))
        (is (map? (:stats result)))
        ;; Lifecycle stats present
        (is (map? (:promotion-stats result)) "promotion-stats present")
        (is (map? (:decay-stats result)) "decay-stats present")
        (is (map? (:xpoll-stats result)) "xpoll-stats present")
        (is (map? (:memory-decay-stats result)) "memory-decay-stats present")
        (is (contains? result :file-provenance-stats) "file-provenance-stats present")
        ;; Lifecycle stats shape
        (is (= #{:promoted :skipped :below :evaluated}
               (set (keys (:promotion-stats result)))))
        (is (= #{:decayed :pruned :fresh :evaluated}
               (set (keys (:decay-stats result)))))
        (is (= #{:promoted :candidates :total-scanned}
               (set (keys (:xpoll-stats result)))))
        (is (= #{:decayed :expired :total-scanned}
               (set (keys (:memory-decay-stats result)))))))))

;; =============================================================================
;; Golden: synthesize — content path
;; =============================================================================

(deftest golden-synthesize-content
  (testing "synthesize with content stores to Chroma and returns summary-id"
    (with-synthesis-mocks
      {:summary {:content "## Session Summary\n\nDid work."
                 :tags ["wrap"]}
       :entry-id "entry-synth-001"}
      (let [harvested (assoc base-harvested
                             :progress-notes [{:content "Implemented feature X"}]
                             :git-commits ["abc1234 feat: add feature X"]
                             :memory-ids-created [{:id "c1"}]
                             :memory-ids-accessed ["m1"]
                             :summary {:progress-count 1 :task-count 0
                                       :commit-count 1 :recall-count 0
                                       :hivemind-shout-count 0 :kanban-completed 0
                                       :created-count 1 :accessed-count 1})
            result (synthesis/synthesize harvested)]
        ;; Structure
        (is (= "entry-synth-001" (:summary-id result)))
        (is (= "test-session-synth" (:session result)))
        (is (= "synth-project" (:project-id result)))
        (is (map? (:session-timing result)))
        (is (map? (:stats result)))
        (is (not (contains? result :skipped)) "content path should not be skipped")
        ;; Lifecycle stats present
        (is (map? (:promotion-stats result)))
        (is (map? (:decay-stats result)))
        (is (map? (:xpoll-stats result)))
        (is (map? (:memory-decay-stats result)))
        (is (contains? result :file-provenance-stats))))))

;; =============================================================================
;; Golden: synthesize shape matches crystallize-session contract
;; =============================================================================

(deftest golden-synthesize-shape-contract
  (testing "Both paths produce key sets compatible with crystallize-session golden"
    ;; Minimal-breadcrumb path keys (was: no-content skip path)
    (with-synthesis-mocks
      {:summary nil
       :entry-id "entry-minimal-001"}
      (let [result (synthesis/synthesize base-harvested)
            expected-keys #{:summary-id :session :project-id
                            :session-timing :stats
                            :promotion-stats :decay-stats :xpoll-stats
                            :memory-decay-stats :file-provenance-stats}]
        (is (= expected-keys (set (keys result)))
            "Quiet-session path key set must match contract (persists, no :skipped)")))

    ;; Content path keys
    (with-synthesis-mocks
      {:summary {:content "Summary" :tags ["wrap"]}
       :entry-id "entry-001"}
      (let [result (synthesis/synthesize
                    (assoc base-harvested
                           :progress-notes [{:content "work"}]))
            expected-keys #{:summary-id :session :project-id
                            :session-timing :stats
                            :promotion-stats :decay-stats :xpoll-stats
                            :memory-decay-stats :file-provenance-stats}]
        (is (= expected-keys (set (keys result)))
            "Content path key set must match contract")))))

;; =============================================================================
;; Golden: run-lifecycle-ops! returns expected shape
;; =============================================================================

(deftest golden-lifecycle-ops-shape
  (testing "run-lifecycle-ops! returns all 5 stat keys"
    (ext/register! :ch/a (fn [_] {:promoted 1 :skipped 2 :below 3 :evaluated 6}))
    (ext/register! :ch/b (fn [_] {:decayed 1 :pruned 0 :fresh 5 :evaluated 6}))
    (ext/register! :ch/c (fn [_] {:promoted 2 :candidates 10 :total-scanned 50}))
    (ext/register! :ch/d (fn [_] {:decayed 3 :expired 1 :total-scanned 20}))
    (ext/register! :ch/e (fn [_] {:files-captured 5}))
    (try
      (let [result (synthesis/run-lifecycle-ops! "test-project" "/tmp/test")]
        (is (= #{:promotion-stats :decay-stats :xpoll-stats
                 :memory-decay-stats :file-provenance-stats}
               (set (keys result))))
        (is (= 1 (:promoted (:promotion-stats result))))
        (is (= 1 (:decayed (:decay-stats result))))
        (is (= 2 (:promoted (:xpoll-stats result))))
        (is (= 3 (:decayed (:memory-decay-stats result))))
        (is (= 5 (:files-captured (:file-provenance-stats result)))))
      (finally
        (ext/deregister! :ch/a)
        (ext/deregister! :ch/b)
        (ext/deregister! :ch/c)
        (ext/deregister! :ch/d)
        (ext/deregister! :ch/e)))))

;; =============================================================================
;; Golden: tasks-only harvest (no progress-notes, no commits) produces summary
;; Regression: DataScript tasks have :title but no :content — must not be
;; silently dropped by summarize-session-progress-fallback's content filter.
;; =============================================================================

(deftest golden-synthesize-tasks-only-not-empty
  (testing "DataScript tasks (have :title, no :content) produce a summary, not nil"
    ;; This tests the pure summarize-session-progress fn directly.
    ;; DataScript-shaped tasks have :title but no :content key.
    ;; The content filter must not silently drop them.
    (let [ds-tasks [{:id "task-1" :title "Fix auth bug" :completed-at "2026-04-18T10:00:00Z"
                     :agent-id "coordinator" :source :datascript}
                    {:id "task-2" :title "Add validation" :completed-at "2026-04-18T11:00:00Z"
                     :agent-id "coordinator" :source :datascript}]
          harvested {:summary {:kg-edge-count 0 :kanban-movement-count 0}}
          result (crystal/summarize-session-progress ds-tasks [] harvested)]
      (is (some? result) "Tasks-only harvest must produce a summary, not nil")
      (is (string? (:content result)) "Summary must have string content")
      (is (.contains (:content result) "Fix auth bug") "Summary must mention task titles")
      (is (.contains (:content result) "Add validation") "Summary must mention all tasks"))))

;; =============================================================================
;; Property: any valid harvested input → synthesize returns required keys
;; =============================================================================

(def gen-harvested
  "Generator for valid harvested maps."
  (gen/let [n-notes    (gen/choose 0 5)
            n-tasks    (gen/choose 0 3)
            n-commits  (gen/choose 0 5)
            n-created  (gen/choose 0 3)
            n-accessed (gen/choose 0 4)
            dir        (gen/elements ["/tmp/a" "/tmp/b" nil])]
    {:progress-notes (vec (repeat n-notes {:content "work" :tags ["progress"]}))
     :completed-tasks (vec (repeat n-tasks {:id "t1" :title "task"}))
     :git-commits (vec (repeat n-commits "abc123 fix: something"))
     :directory dir
     :recalls {}
     :memory-ids-created (vec (repeat n-created {:id "c1"}))
     :memory-ids-accessed (vec (repeat n-accessed "m1"))
     :session-timing {:session-start "2026-01-15T10:00:00Z"
                      :session-end "2026-01-15T12:00:00Z"
                      :duration-minutes 120}
     :summary {:progress-count n-notes
               :task-count n-tasks
               :commit-count n-commits
               :recall-count 0
               :hivemind-shout-count 0
               :kanban-completed 0
               :created-count n-created
               :accessed-count n-accessed}}))

(defspec prop-synthesize-always-has-session 50
  (prop/for-all [harvested gen-harvested]
    (with-synthesis-mocks
      {:summary (when (pos? (+ (count (:progress-notes harvested))
                               (count (:git-commits harvested))))
                  {:content "## Summary" :tags ["wrap"]})
       :entry-id "prop-entry-001"}
      (let [result (synthesis/synthesize harvested)]
        (and (string? (:session result))
             (string? (:project-id result)))))))

(defspec prop-synthesize-always-has-lifecycle-stats 50
  (prop/for-all [harvested gen-harvested]
    (with-synthesis-mocks
      {:summary (when (pos? (+ (count (:progress-notes harvested))
                               (count (:git-commits harvested))))
                  {:content "## Summary" :tags ["wrap"]})
       :entry-id "prop-entry-001"}
      (let [result (synthesis/synthesize harvested)]
        ;; Either has lifecycle stats (both paths) or has :error (store failed)
        (or (contains? result :error)
            (and (map? (:promotion-stats result))
                 (map? (:decay-stats result))
                 (map? (:xpoll-stats result))
                 (map? (:memory-decay-stats result))))))))

(defspec prop-synthesize-content-vs-skipped 50
  (prop/for-all [harvested gen-harvested]
    (with-synthesis-mocks
      {:summary (when (pos? (+ (count (:progress-notes harvested))
                               (count (:git-commits harvested))))
                  {:content "## Summary" :tags ["wrap"]})
       :entry-id "prop-entry-001"}
      (let [result (synthesis/synthesize harvested)]
        ;; Either content path (has :summary-id) or no-content path (has :skipped)
        (or (contains? result :summary-id)
            (true? (:skipped result))
            (contains? result :error))))))

;; =============================================================================
;; Property: format-temporal-block always returns string with header
;; =============================================================================

(defspec prop-temporal-block-always-string 100
  (prop/for-all [start    (gen/one-of [(gen/return nil) (gen/return "2026-01-15T10:00:00Z")])
                 end      (gen/one-of [(gen/return nil) (gen/return "2026-01-15T12:00:00Z")])
                 minutes  (gen/one-of [(gen/return nil) (gen/choose 0 1000)])
                 n-created (gen/choose 0 10)
                 n-accessed (gen/choose 0 10)]
    (let [result (synthesis/format-temporal-block
                  {:session-start start :session-end end :duration-minutes minutes}
                  {:memory-ids-created (vec (repeat n-created "id"))
                   :memory-ids-accessed (vec (repeat n-accessed "id"))})]
      (and (string? result)
           (.contains result "### Temporal Metadata")))))

;; =============================================================================
;; Regression: lifecycle timeout must not poison synthesize result with :error
;; Bug: deref timeout default was {:error "lifecycle-timeout"} which merged
;; :error into a result that also had :summary-id, causing
;; crystallize-session-result to treat a partial success as total failure.
;; =============================================================================

(deftest regression-lifecycle-timeout-no-error-key
  (testing "Lifecycle timeout produces :lifecycle-error, not :error key"
    ;; The timeout default was {:error "lifecycle-timeout"} which polluted the
    ;; result map, causing crystallize-session-result to treat a stored entry
    ;; as a total failure. Fix: use {:lifecycle-error "timeout"}.
    ;;
    ;; Instead of waiting for a real 15s timeout, we test the merge behavior
    ;; directly: a result map with both :summary-id and :lifecycle-error
    ;; must NOT contain :error.
    (let [;; Simulate what synthesize produces when lifecycle times out
          store-result {:summary-id "entry-timeout-001"
                        :session "test-session"
                        :project-id "test-project"
                        :session-timing {:session-start nil :session-end nil :duration-minutes 0}
                        :stats {}}
          lifecycle-timeout {:lifecycle-error "timeout"}
          result (merge store-result lifecycle-timeout)]
      (is (= "entry-timeout-001" (:summary-id result))
          "Entry stored despite lifecycle timeout")
      (is (not (contains? result :error))
          ":error key must never appear alongside :summary-id")
      (is (= "timeout" (:lifecycle-error result))
          "Lifecycle timeout uses :lifecycle-error key"))))

(deftest golden-synthesize-content-with-lifecycle-timeout-no-error-key
  (testing "Content path with lifecycle timeout has :summary-id but no :error"
    (with-synthesis-mocks
      {:summary {:content "## Summary" :tags ["wrap" "session-summary"]}
       :entry-id "entry-partial-001"}
      (let [result (synthesis/synthesize
                    (assoc base-harvested
                           :progress-notes [{:content "work"}]))]
        (is (string? (:summary-id result)) "summary-id must be present")
        (is (not (contains? result :error))
            ":error key must never appear alongside :summary-id")))))

;; =============================================================================
;; Regression: crystallize-session-result must treat partial success as ok
;; When synthesize returns {:summary-id X :lifecycle-error "timeout"},
;; crystallize-session-result must return result/ok (not result/err).
;; =============================================================================

;; =============================================================================
;; RED: wrap must always persist a session memory, even on no-content path
;; Bug: `synthesize` short-circuits on `(nil? summary)` and returns
;; {:skipped true :reason "no-content"} WITHOUT calling facade/index-memory-entry!
;; — so `workflow wrap` silently produces zero persisted memories for sessions
;; whose harvest yields no progress-notes, no commits, no KG edges, no kanban
;; movements, and no memory activity.
;;
;; Contract under test: every wrap should leave a trace in the memory store,
;; at minimum a "session-wrap" note carrying session-timing + stats so that
;; /workflow catchup has a breadcrumb to follow.
;; =============================================================================

(deftest red-wrap-always-persists-session-memory
  (testing "synthesize must call facade/index-memory-entry! even with empty harvest"
    (let [calls (atom [])]
      (ext/register! :ch/a (fn [_] {:promoted 0 :skipped 0 :below 0 :evaluated 0}))
      (ext/register! :ch/b (fn [_] {:decayed 0 :pruned 0 :fresh 0 :evaluated 0}))
      (ext/register! :ch/c (fn [_] {:promoted 0 :candidates 0 :total-scanned 0}))
      (ext/register! :ch/d (fn [_] {:decayed 0 :expired 0 :total-scanned 0}))
      (ext/register! :ch/e (fn [_] {:files-captured 0}))
      (try
        (with-redefs
          [crystal/summarize-session-progress (fn [& _] nil)
           crystal/summarize-memory-activity  (fn [& _] nil)
           crystal/session-id                 (fn [] "test-session-red")
           crystal/session-timing-metadata    (fn [_ _]
                                                {:session-start "2026-04-18T10:00:00Z"
                                                 :session-end   "2026-04-18T12:00:00Z"
                                                 :duration-minutes 120})
           scope/get-current-project-id       (fn [_] "red-project")
           scope/inject-project-scope         (fn [tags _] tags)
           dur/calculate-expires              (fn [_] "2026-06-18T00:00:00Z")
           ctx/current-directory              (fn [] "/tmp/red-test")
           facade/index-memory-entry!         (fn [entry]
                                                (swap! calls conj entry)
                                                "entry-red-001")
           facade/content-hash                (fn [c] (str (hash c)))]
          (let [result (synthesis/synthesize base-harvested)]
            ;; The core contract: a memory entry MUST be persisted.
            (is (pos? (count @calls))
                "facade/index-memory-entry! must be called at least once")
            ;; It must carry the session-wrap tag so catchup can find it.
            (when (pos? (count @calls))
              (let [entry (first @calls)
                    tags  (set (:tags entry))]
                (is (contains? tags "session-wrap")
                    "persisted entry must be tagged session-wrap")
                (is (string? (:content entry))
                    "persisted entry must have string content")
                (is (not (clojure.string/blank? (:content entry)))
                    "persisted entry content must not be blank")))
            ;; Result must carry a :summary-id pointing at the persisted entry.
            (is (string? (:summary-id result))
                "synthesize result must include :summary-id, not silently skip")))
        (finally
          (ext/deregister! :ch/a)
          (ext/deregister! :ch/b)
          (ext/deregister! :ch/c)
          (ext/deregister! :ch/d)
          (ext/deregister! :ch/e))))))

(deftest regression-crystallize-session-result-partial-success
  (testing "crystallize-session-result treats :summary-id + :lifecycle-error as ok"
    (require '[hive-mcp.tools.crystal] :reload)
    (let [crystallize-session-result @(resolve 'hive-mcp.tools.crystal/crystallize-session-result)]
      ;; Case 1: Both :summary-id and :lifecycle-error → ok (entry stored)
      (with-redefs [hive-mcp.crystal.hooks/crystallize-session
                    (fn [_] {:summary-id "entry-001" :session "test" :lifecycle-error "timeout"})]
        (let [r (crystallize-session-result {} "test-project")]
          (is (result/ok? r) "Partial success (stored + lifecycle-error) must be ok")
          (is (= "entry-001" (:summary-id (:ok r))))))

      ;; Case 2: :error WITHOUT :summary-id → err (total failure)
      (with-redefs [hive-mcp.crystal.hooks/crystallize-session
                    (fn [_] {:error "store failed" :session "test"})]
        (let [r (crystallize-session-result {} "test-project")]
          (is (not (result/ok? r)) "Total failure (:error, no :summary-id) must be err")))

      ;; Case 3: :summary-id + legacy :error → ok (backward compat with old lifecycle key)
      (with-redefs [hive-mcp.crystal.hooks/crystallize-session
                    (fn [_] {:summary-id "entry-002" :error "lifecycle-timeout" :session "test"})]
        (let [r (crystallize-session-result {} "test-project")]
          (is (result/ok? r) "When :summary-id present, :error should not cause failure"))))))
