(ns hive-mcp.knowledge-graph.conn-init-test
  "Single-init concurrency guarantee for IConnInit (ENGINE-L1.2a).

   The contract under test: `open-once!` must invoke `open-fn` at most
   once per IConnInit, even when many threads race the first call."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.conn-init :as ci]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest atom-conn-init-opens-once-single-thread
  (testing "open-fn runs once and result is cached"
    (let [call-count (atom 0)
          init (ci/atom-conn-init)
          v1 (ci/open-once! init #(do (swap! call-count inc) :resource))
          v2 (ci/open-once! init #(do (swap! call-count inc) :resource))]
      (is (= :resource v1))
      (is (identical? v1 v2))
      (is (= 1 @call-count) "open-fn ran exactly once across calls"))))

(deftest atom-conn-init-snapshot-and-clear
  (testing "snapshot is nil until init, then reflects cached value"
    (let [init (ci/atom-conn-init)]
      (is (nil? (ci/snapshot init)))
      (ci/open-once! init (constantly :v))
      (is (= :v (ci/snapshot init)))
      (ci/clear! init)
      (is (nil? (ci/snapshot init)))))
  (testing "after clear!, open-once! reopens"
    (let [calls (atom 0)
          init (ci/atom-conn-init)]
      (ci/open-once! init #(do (swap! calls inc) :v))
      (ci/clear! init)
      (ci/open-once! init #(do (swap! calls inc) :v))
      (is (= 2 @calls)))))

(deftest atom-conn-init-concurrent-open-runs-once
  (testing "under N concurrent first-callers, open-fn runs exactly once"
    (let [n-threads 64
          call-count (atom 0)
          gate (java.util.concurrent.CountDownLatch. 1)
          init (ci/atom-conn-init)
          open-fn (fn []
                    ;; Sleep inside the critical section to widen the
                    ;; race window — without `locking`, multiple threads
                    ;; would observe nil and all increment.
                    (swap! call-count inc)
                    (Thread/sleep 25)
                    :resource)
          futures (doall
                   (repeatedly n-threads
                               #(future
                                  (.await gate)
                                  (ci/open-once! init open-fn))))]
      (.countDown gate)
      (let [results (mapv deref futures)]
        (is (= 1 @call-count)
            (str "open-fn must run once under contention; observed "
                 @call-count " invocations"))
        (is (every? #(= :resource %) results)
            "every concurrent caller observes the same resource")))))

(deftest atom-conn-init-accepts-seeded-atom
  (testing "pre-seeded atom is honoured — open-fn never runs"
    (let [seed (atom :pre-existing)
          init (ci/atom-conn-init seed)
          v (ci/open-once! init #(throw (ex-info "must not run" {})))]
      (is (= :pre-existing v))
      (is (= :pre-existing (ci/snapshot init))))))
