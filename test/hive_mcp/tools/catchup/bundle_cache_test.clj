(ns hive-mcp.tools.catchup.bundle-cache-test
  "Cold tests for the bundle cache: single-flight, stale-while-revalidate,
   write invalidation, the content tier and sweeper eviction. No store."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.tools.catchup.bundle-cache :as bc])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each (fn [t] (bc/reset-cache!) (t) (bc/reset-cache!)))

(defn- bundle [n] {:axioms [{:id (str "a" n)}] :decisions []})

(defn- wait-until
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

;; =============================================================================
;; Single flight
;; =============================================================================

(deftest single-flight-collapses-concurrent-computes-test
  (testing "N callers arriving while one compute is in flight share its result"
    (let [computes (atom 0)
          release  (CountDownLatch. 1)
          started  (CountDownLatch. 1)
          compute  (fn []
                     (swap! computes inc)
                     (.countDown started)
                     (.await release 5 TimeUnit/SECONDS)
                     (bundle 1))
          callers  (mapv (fn [_] (future (bc/cached-bundle "p" compute))) (range 8))]
      (is (.await started 5 TimeUnit/SECONDS) "first caller entered compute")
      (is (wait-until #(= 1 (:in-flight (bc/stats))) 1000) "one computation in flight")
      (Thread/sleep 100)
      (.countDown release)
      (is (every? #(= (bundle 1) (deref % 5000 ::timeout)) callers))
      (is (= 1 @computes)))))

(deftest compute-failure-is-not-cached-test
  (is (thrown? clojure.lang.ExceptionInfo
               (bc/cached-bundle "p" (fn [] (throw (ex-info "boom" {}))))))
  (is (= (bundle 1) (bc/cached-bundle "p" (fn [] (bundle 1))))))

;; =============================================================================
;; Stale-while-revalidate
;; =============================================================================

(deftest stale-while-revalidate-test
  (let [clock    (atom 1000000)
        computes (atom 0)
        compute  (fn [] (bundle (swap! computes inc)))]
    (with-redefs [bc/now-ms (fn [] @clock)]
      (is (= (bundle 1) (bc/cached-bundle "p" compute)))
      (is (= 1 @computes))

      (testing "fresh: served, no recompute"
        (swap! clock + 1000)
        (is (= (bundle 1) (bc/cached-bundle "p" compute)))
        (is (= 1 @computes)))

      (testing "stale: served immediately, refreshed in the background"
        (swap! clock + bc/fresh-ttl-ms)
        (is (= (bundle 1) (bc/cached-bundle "p" compute)))
        (is (wait-until #(= 2 @computes) 5000) "background refresh ran")
        (is (wait-until #(= (bundle 2) (bc/cached-bundle "p" compute)) 5000)
            "refreshed value is served")
        (is (= 2 @computes)))

      (testing "past max-age: cold, computed synchronously"
        (swap! clock + bc/max-age-ms)
        (is (= (bundle 3) (bc/cached-bundle "p" compute)))
        (is (= 3 @computes))))))

(deftest empty-bundle-is-not-cached-test
  (let [computes (atom 0)
        compute  (fn [] (swap! computes inc) {:axioms [] :decisions []})]
    (bc/cached-bundle "p" compute)
    (bc/cached-bundle "p" compute)
    (is (= 2 @computes))
    (is (zero? (:bundles (bc/stats))))))

;; =============================================================================
;; Write invalidation
;; =============================================================================

(deftest write-invalidation-test
  (let [computes (atom 0)
        compute  (fn [] (bundle (swap! computes inc)))]
    (bc/cached-bundle "p" compute)
    (bc/cached-bundle "q" compute)
    (is (= 2 @computes))

    (testing "an add of a non-bucket type leaves every bundle alone"
      (bc/invalidate! {:op :added :id "k1" :memory-type "kanban"})
      (bc/cached-bundle "p" compute)
      (is (= 2 @computes)))

    (testing "an add of a bucket type drops every bundle"
      (bc/invalidate! {:op :added :id "d1" :memory-type "decision"})
      (bc/cached-bundle "p" compute)
      (bc/cached-bundle "q" compute)
      (is (= 4 @computes)))

    (testing "a write of unknown type is treated as relevant"
      (bc/invalidate! {:op :deleted :id "x"})
      (bc/cached-bundle "p" compute)
      (is (= 5 @computes)))))

;; =============================================================================
;; Content tier
;; =============================================================================

(deftest content-tier-fetches-only-misses-test
  (let [fetches (atom [])
        fetch   (fn [ids]
                  (swap! fetches conj (vec ids))
                  (mapv (fn [id] {:id id :content (str "c-" id)}) ids))]
    (is (= {"a" {:id "a" :content "c-a"} "b" {:id "b" :content "c-b"}}
           (bc/cached-entries ["a" "b"] fetch)))
    (is (= [["a" "b"]] @fetches))

    (testing "only the unseen id is fetched"
      (is (= 3 (count (bc/cached-entries ["a" "b" "c"] fetch))))
      (is (= [["a" "b"] ["c"]] @fetches)))

    (testing "an update evicts only that id"
      (bc/invalidate! {:op :updated :id "b" :memory-type "decision"})
      (bc/cached-entries ["a" "b" "c"] fetch)
      (is (= [["a" "b"] ["c"] ["b"]] @fetches)))

    (testing "ids the fetch does not return are absent and never cached"
      (is (= {} (bc/cached-entries ["zzz"] (fn [_] []))))
      (bc/cached-entries ["zzz"] fetch)
      (is (= ["zzz"] (last @fetches))))

    (testing "a throwing fetch yields only the cached ids"
      (is (= #{"a" "b" "c"}
             (set (keys (bc/cached-entries ["a" "b" "c" "new"] (fn [_] (throw (ex-info "down" {})))))))))))

;; =============================================================================
;; Sweeper eviction
;; =============================================================================

(deftest evict-stale-drops-past-max-age-test
  (let [clock (atom 5000000)]
    (with-redefs [bc/now-ms (fn [] @clock)]
      (bc/cached-bundle "p" (fn [] (bundle 1)))
      (bc/cached-entries ["a"] (fn [ids] (mapv (fn [id] {:id id}) ids)))
      (is (= 0 (bc/evict-stale!)))
      (swap! clock + bc/max-age-ms)
      (is (= 2 (bc/evict-stale!)))
      (is (= 0 (:bundles (bc/stats))))
      (is (= 0 (:content (bc/stats)))))))
