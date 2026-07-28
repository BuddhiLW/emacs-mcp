(ns hive-mcp.channel.memory-piggyback-test
  "Unit tests for the memory piggyback buffer module."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.channel.memory-piggyback :as mp]
            [hive-mcp.channel.context-store :as ctx]))

;; Reset buffer state between tests
(use-fixtures :each
  (fn [f]
    (mp/reset-all!)
    (f)
    (mp/reset-all!)))

;; =============================================================================
;; Enqueue + Drain Basic Flow
;; =============================================================================

(deftest enqueue-and-drain-basic-test
  (testing "enqueue entries then drain returns them"
    (let [entries [{:id "ax-1" :type "axiom" :content "Rule one" :severity "INVIOLABLE" :tags ["axiom"]}
                   {:id "p-1" :type "convention" :content "Convention one" :tags ["catchup-priority"]}]]
      (mp/enqueue! "agent-1" entries)
      (is (mp/has-pending? "agent-1"))

      (let [result (mp/drain! "agent-1")]
        (is (= 2 (count (:batch result))))
        (is (= 2 (:total result)))
        (is (= 2 (:delivered result)))
        (is (= 0 (:remaining result)))
        (is (true? (:done result)))
        (is (= 1 (:seq result)))

        ;; Verify compact format
        (let [first-entry (first (:batch result))]
          (is (= "ax-1" (:id first-entry)))
          (is (= "axiom" (:T first-entry)))
          (is (= "INVIOLABLE" (:S first-entry)))
          (is (= "Rule one" (:C first-entry)))
          (is (= ["axiom"] (:tags first-entry)))))))

  (testing "drain returns nil when no entries pending"
    (is (nil? (mp/drain! "agent-1"))))

  (testing "has-pending? returns false after full drain"
    (mp/enqueue! "agent-2"
                 [{:id "x" :type "note" :content "test"}])
    (mp/drain! "agent-2")
    (is (not (mp/has-pending? "agent-2")))))

;; =============================================================================
;; 32K Char Budget Batching
;; =============================================================================

(deftest char-budget-batching-test
  (testing "large entries are split across batches"
    ;; Create entries that exceed 32K when combined
    (let [big-content (apply str (repeat 10000 "x"))
          entries (mapv (fn [i]
                          {:id (str "big-" i)
                           :type "axiom"
                           :content big-content
                           :tags []})
                        (range 5))]
      (mp/enqueue! "agent-b" entries)

      ;; First drain should get some entries (within budget)
      (let [r1 (mp/drain! "agent-b")]
        (is (pos? (count (:batch r1))))
        (is (< (count (:batch r1)) 5))
        (is (pos? (:remaining r1)))
        (is (not (:done r1)))

        ;; Second drain should get more
        (let [r2 (mp/drain! "agent-b")]
          (is (pos? (count (:batch r2))))
          (is (= 2 (:seq r2)))

          ;; Eventually should be done
          (loop [remaining (:remaining r2)]
            (when (pos? remaining)
              (let [r (mp/drain! "agent-b")]
                (recur (:remaining r))))))))))

(deftest at-least-one-entry-per-drain-test
  (testing "drain always returns at least one entry even if over budget"
    (let [huge-content (apply str (repeat 50000 "y"))
          entries [{:id "huge-1" :type "axiom" :content huge-content :tags []}]]
      (mp/enqueue! "agent-h" entries)
      (let [result (mp/drain! "agent-h")]
        (is (= 1 (count (:batch result))))
        (is (true? (:done result)))))))

;; =============================================================================
;; Cursor Tracking
;; =============================================================================

(deftest cursor-tracking-test
  (testing "sequential drains advance cursor correctly"
    (let [entries (mapv (fn [i]
                          {:id (str "e-" i) :type "note"
                           :content (apply str (repeat 12000 "a"))
                           :tags []})
                        (range 4))]
      (mp/enqueue! "agent-c" entries)

      ;; First drain
      (let [r1 (mp/drain! "agent-c")]
        (is (= 1 (:seq r1)))
        (is (pos? (:delivered r1)))
        (let [first-batch-count (count (:batch r1))]

          ;; Second drain picks up where first left off
          (let [r2 (mp/drain! "agent-c")]
            (is (= 2 (:seq r2)))
            (is (= (+ first-batch-count (count (:batch r2)))
                   (:delivered r2)))))))))

;; =============================================================================
;; Idempotent Enqueue
;; =============================================================================

(deftest merge-enqueue-test
  (testing "a re-catchup merges: already-delivered entries are not re-sent"
    (let [entries-1 [{:id "a1" :type "axiom" :content "First" :tags []}]
          entries-2 [{:id "a1" :type "axiom" :content "First" :tags []}
                     {:id "a2" :type "axiom" :content "Second" :tags []}
                     {:id "a3" :type "axiom" :content "Third" :tags []}]]
      (mp/enqueue! "agent-i" entries-1)
      (let [d1 (mp/drain! "agent-i")]
        (is (= ["a1"] (mapv :id (:batch d1))))
        (is (true? (:done d1))))
      (mp/enqueue! "agent-i" entries-2)
      (let [d2 (mp/drain! "agent-i")]
        (is (= ["a2" "a3"] (mapv :id (:batch d2)))
            "a1 was already delivered this session and must not be re-sent")
        (is (true? (:done d2))))
      (is (nil? (mp/drain! "agent-i")))))

  (testing "a re-catchup while entries are still queued discards nothing"
    (let [mk (fn [id] {:id id :type "note" :content id :tags []})]
      (mp/enqueue! "agent-m" [(mk "m1") (mk "m2")])
      (mp/enqueue! "agent-m" [(mk "m2") (mk "m3")])
      (let [d (mp/drain! "agent-m")]
        (is (= ["m1" "m2" "m3"] (mapv :id (:batch d)))
            "duplicate m2 deduped, m3 appended, original order preserved")))))

;; =============================================================================
;; Caller Isolation
;; =============================================================================

(deftest caller-isolation-test
  (testing "different callers have independent buffers"
    (mp/enqueue! "agent-1"
                 [{:id "x1" :type "note" :content "for agent-1" :tags []}])
    (mp/enqueue! "agent-2"
                 [{:id "x2" :type "note" :content "for agent-2" :tags []}])
    (mp/enqueue! "agent-3"
                 [{:id "x3" :type "note" :content "for agent-3" :tags []}])

    (let [r1 (mp/drain! "agent-1")
          r2 (mp/drain! "agent-2")
          r3 (mp/drain! "agent-3")]
      (is (= "x1" (:id (first (:batch r1)))))
      (is (= "x2" (:id (first (:batch r2)))))
      (is (= "x3" (:id (first (:batch r3))))))))

;; =============================================================================
;; Reset
;; =============================================================================

(deftest reset-test
  (testing "reset! clears specific buffer"
    (mp/enqueue! "agent-r"
                 [{:id "r1" :type "note" :content "test" :tags []}])
    (is (mp/has-pending? "agent-r"))
    (mp/clear-buffer! "agent-r")
    (is (not (mp/has-pending? "agent-r")))
    (is (nil? (mp/drain! "agent-r"))))

  (testing "reset-all! clears all buffers"
    (mp/enqueue! "a1" [{:id "1" :type "note" :content "t" :tags []}])
    (mp/enqueue! "a2" [{:id "2" :type "note" :content "t" :tags []}])
    (mp/reset-all!)
    (is (not (mp/has-pending? "a1")))
    (is (not (mp/has-pending? "a2")))))

;; =============================================================================
;; Nil/Default Handling
;; =============================================================================

(deftest nil-defaults-test
  (testing "nil caller-id defaults to 'coordinator'"
    (mp/enqueue! nil
                 [{:id "n1" :type "note" :content "test" :tags []}])
    (is (mp/has-pending? nil))
    (let [result (mp/drain! nil)]
      (is (= 1 (count (:batch result))))))

  (testing "explicit 'coordinator' matches nil caller-id"
    (mp/enqueue! nil
                 [{:id "n2" :type "note" :content "test" :tags []}])
    (is (mp/has-pending? "coordinator"))
    (let [result (mp/drain! "coordinator")]
      (is (= 1 (count (:batch result)))))))

;; =============================================================================
;; Entry Format
;; =============================================================================

(deftest entry-format-test
  (testing "entries without severity omit :S key"
    (mp/enqueue! "agent-f"
                 [{:id "f1" :type "convention" :content "Do X" :tags ["priority"]}])
    (let [result (mp/drain! "agent-f")
          entry (first (:batch result))]
      (is (= "convention" (:T entry)))
      (is (= "Do X" (:C entry)))
      (is (= ["priority"] (:tags entry)))
      (is (not (contains? entry :S)))))

  (testing "entries without tags omit :tags key"
    (mp/enqueue! "agent-g"
                 [{:id "g1" :type "note" :content "plain" :tags []}])
    (let [result (mp/drain! "agent-g")
          entry (first (:batch result))]
      (is (not (contains? entry :tags))))))

;; =============================================================================
;; Context-Refs Dual-Write (Task 2.1)
;; =============================================================================

(deftest enqueue-with-context-refs-test
  (testing "2-arity enqueue (backward compat) works without context-refs"
    (mp/enqueue! "agent-3a"
                 [{:id "bc-1" :type "axiom" :content "Backward compat" :tags []}])
    (let [result (mp/drain! "agent-3a")]
      (is (= 1 (count (:batch result))))
      (is (not (contains? result :context-refs)))))

  (testing "3-arity enqueue with nil context-refs omits :context-refs from buffer"
    (mp/enqueue! "agent-4n"
                 [{:id "nil-1" :type "axiom" :content "Nil refs" :tags []}]
                 nil)
    (let [result (mp/drain! "agent-4n")]
      (is (= 1 (count (:batch result))))
      (is (not (contains? result :context-refs)))))

  (testing "3-arity enqueue with context-refs stores them in buffer"
    (let [refs {:axioms "ctx-123-abc" :sessions "ctx-456-def"}]
      (mp/enqueue! "agent-4r"
                   [{:id "ref-1" :type "axiom" :content "With refs" :tags []}]
                   refs)
      (is (mp/has-pending? "agent-4r")))))

(deftest drain-context-refs-first-batch-only-test
  (testing "context-refs appear in first drain batch (seq=1) only"
    (let [refs {:axioms "ctx-aaa-111" :decisions "ctx-bbb-222"}
          ;; Create entries that will span multiple batches
          big-content (apply str (repeat 12000 "z"))
          entries (mapv (fn [i]
                          {:id (str "mb-" i) :type "axiom"
                           :content big-content :tags []})
                        (range 4))]
      (mp/enqueue! "agent-dr" entries refs)

      ;; First drain: should have context-refs
      (let [r1 (mp/drain! "agent-dr")]
        (is (= 1 (:seq r1)))
        (is (contains? r1 :context-refs))
        (is (= refs (:context-refs r1))))

      ;; Second drain: should NOT have context-refs
      (when (mp/has-pending? "agent-dr")
        (let [r2 (mp/drain! "agent-dr")]
          (is (> (:seq r2) 1))
          (is (not (contains? r2 :context-refs)))))))

  (testing "context-refs appear even when all entries fit in one batch"
    (let [refs {:snippets "ctx-ccc-333"}]
      (mp/enqueue! "agent-s1"
                   [{:id "s-1" :type "note" :content "small" :tags []}]
                   refs)
      (let [result (mp/drain! "agent-s1")]
        (is (= 1 (:seq result)))
        (is (true? (:done result)))
        (is (= refs (:context-refs result)))))))

(deftest context-refs-with-context-store-integration-test
  (testing "context-refs from context-store can be round-tripped through piggyback"
    ;; Clean context-store
    (ctx/reset-all!)
    (try
      ;; Simulate what catchup.clj does: put data in context-store, get refs
      (let [axiom-data [{:id "ax-1" :content "Rule 1"} {:id "ax-2" :content "Rule 2"}]
            session-data [{:id "sess-1" :content "Session summary"}]
            ax-ref (ctx/context-put! axiom-data :tags #{"catchup" "axioms"} :ttl-ms 60000)
            sess-ref (ctx/context-put! session-data :tags #{"catchup" "sessions"} :ttl-ms 60000)
            refs {:axioms ax-ref :sessions sess-ref}]

        ;; Enqueue with refs (what catchup.clj does)
        (mp/enqueue! "agent-int"
                     [{:id "ax-1" :type "axiom" :content "Rule 1" :tags []}
                      {:id "ax-2" :type "axiom" :content "Rule 2" :tags []}]
                     refs)

        ;; Drain and verify refs are present
        (let [result (mp/drain! "agent-int")]
          (is (map? (:context-refs result)))
          (is (string? (:axioms (:context-refs result))))
          (is (string? (:sessions (:context-refs result))))

          ;; Verify the refs actually resolve in context-store
          (let [ax-entry (ctx/context-get (:axioms (:context-refs result)))
                sess-entry (ctx/context-get (:sessions (:context-refs result)))]
            (is (some? ax-entry))
            (is (some? sess-entry))
            (is (= axiom-data (:data ax-entry)))
            (is (= session-data (:data sess-entry))))))
      (finally
        (ctx/reset-all!)))))
