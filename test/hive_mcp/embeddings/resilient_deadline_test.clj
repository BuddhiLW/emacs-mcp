(ns hive-mcp.embeddings.resilient-deadline-test
  "The chain must always answer inside its total budget.

   Regression: the convention/decision chain is
   [ollama-qwen3-8b openrouter-qwen3 venice-qwen3]. With a per-attempt budget
   and no total deadline, a slow primary cascaded into two remote providers —
   3 x 12s = 36s against a 30s caller budget. The caller timed out first, so the
   failure was never reported and the memory entry was silently dropped."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.embeddings.protocol :as proto]
            [hive-mcp.embeddings.resilient :as res]))

;; =============================================================================
;; Test doubles
;; =============================================================================

(defrecord SlowProvider [sleep-ms dimension]
  proto/EmbeddingProvider
  (embed-text [_ _] (Thread/sleep sleep-ms) [1.0 2.0 3.0])
  (embed-batch [_ ts] (Thread/sleep sleep-ms) (mapv (constantly [1.0 2.0 3.0]) ts))
  (embedding-dimension [_] dimension))

(defrecord InstantProvider [tag dimension]
  proto/EmbeddingProvider
  (embed-text [_ _] [tag])
  (embed-batch [_ ts] (mapv (constantly [tag]) ts))
  (embedding-dimension [_] dimension))

(defn- entry [k p] {:provider p :provider-key k})

(defn- elapsed-ms [f]
  (let [t0 (System/nanoTime)
        r  (try {:ok (f)} (catch Throwable e {:ex e}))]
    [(long (/ (- (System/nanoTime) t0) 1e6)) r]))

;; =============================================================================
;; The regression — the chain must not outlive its total budget
;; =============================================================================

(deftest chain-of-slow-providers-aborts-within-total-budget
  (testing "three slow providers, per-attempt 300ms, total 800ms"
    (let [chain    [(entry :slow-1 (->SlowProvider 5000 3))
                    (entry :slow-2 (->SlowProvider 5000 3))
                    (entry :slow-3 (->SlowProvider 5000 3))]
          embedder (res/resilient-embedder chain 300 800)
          [ms r]   (elapsed-ms #(proto/embed-text embedder "x"))]
      (is (:ex r) "every provider is slower than its budget — the chain must fail")
      (is (< ms 2000)
          (str "chain took " ms "ms; without a total deadline it would run "
               "3 x per-attempt regardless of the caller's budget"))
      (let [d (ex-data (:ex r))]
        (is (= :embedder/chain-exhausted (:error d)))
        (is (= :deadline (:exhausted-by d))
            "and it must say WHY — a silent drop is the bug we are fixing")))))

(deftest deadline-exhaustion-names-what-it-never-tried
  (let [chain    [(entry :slow-1 (->SlowProvider 5000 3))
                  (entry :remote-a (->SlowProvider 5000 3))
                  (entry :remote-b (->SlowProvider 5000 3))]
        embedder (res/resilient-embedder chain 300 500)
        [_ r]    (elapsed-ms #(proto/embed-text embedder "x"))
        d        (ex-data (:ex r))]
    (is (seq (:untried d))
        "the providers the deadline skipped are reported, not silently ignored")
    (is (= 500 (:total-budget-ms d)))))

;; =============================================================================
;; Failover still works — the deadline must not break the feature it bounds
;; =============================================================================

(deftest failover-reaches-a-healthy-provider
  (let [chain    [(entry :slow    (->SlowProvider 5000 3))
                  (entry :healthy (->InstantProvider 42.0 3))]
        embedder (res/resilient-embedder chain 200 5000)
        [ms r]   (elapsed-ms #(proto/embed-text embedder "x"))]
    (is (= [42.0] (:ok r)) "the slow primary is abandoned and the sibling answers")
    (is (< ms 2000) (str "took " ms "ms"))))

(deftest healthy-primary-is-not-penalised
  (let [chain    [(entry :healthy (->InstantProvider 7.0 3))
                  (entry :never   (->SlowProvider 5000 3))]
        embedder (res/resilient-embedder chain 200 5000)
        [ms r]   (elapsed-ms #(proto/embed-text embedder "x"))]
    (is (= [7.0] (:ok r)))
    (is (< ms 500) "a healthy primary answers immediately; the chain stops there")))

(deftest exhausting-every-provider-is-distinguishable-from-running-out-of-time
  (testing "providers all fail FAST — budget is ample, so the chain ends on :providers"
    (let [boom     (reify proto/EmbeddingProvider
                     (embed-text [_ _] (throw (ex-info "boom" {})))
                     (embed-batch [_ _] (throw (ex-info "boom" {})))
                     (embedding-dimension [_] 3))
          chain    [(entry :boom-1 boom) (entry :boom-2 boom)]
          embedder (res/resilient-embedder chain 1000 10000)
          [_ r]    (elapsed-ms #(proto/embed-text embedder "x"))
          d        (ex-data (:ex r))]
      (is (= :embedder/chain-exhausted (:error d)))
      (is (= :providers (:exhausted-by d))
          "ran out of PROVIDERS, not TIME — the two failures need different fixes")
      (is (= 2 (count (:failures d)))))))
