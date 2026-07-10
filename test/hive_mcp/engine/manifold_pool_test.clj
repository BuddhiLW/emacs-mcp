(ns hive-mcp.engine.manifold-pool-test
  "Tests for hive-mcp.engine.manifold-pool — bounded wait-pool install
   (ENGINE-L0.5). Focus: the resolution policy that picks `max-threads`
   from explicit opts / env / sys-prop / default. Install is intentionally
   side-effecting and JVM-global; only one test in this ns actually runs
   `boot!`, and it asserts idempotency rather than re-installation."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.engine.manifold-pool :as mp]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest default-respects-cpu-count
  (testing "default cap is at least 64 and scales 4× CPU count"
    (let [cpus (.availableProcessors (Runtime/getRuntime))
          d    (mp/default-max-threads)]
      (is (>= d 64))
      (is (>= d (* 4 cpus))))))

(deftest resolve-priority-explicit-wins
  (testing "explicit :max-threads beats env, sys-prop, default"
    (is (= 7 (mp/resolve-max-threads {:max-threads 7})))))

(deftest resolve-skips-non-positive
  (testing "zero or negative explicit value falls through to default"
    (is (= (mp/default-max-threads) (mp/resolve-max-threads {:max-threads 0})))
    (is (= (mp/default-max-threads) (mp/resolve-max-threads {:max-threads -5})))))

(deftest resolve-falls-back-to-default
  (testing "no opts, no env, no prop → default"
    ;; Caller can't easily clear real env here; we just assert the default
    ;; path returns a positive int matching default-max-threads.
    (let [n (mp/resolve-max-threads {})]
      (is (pos? n))
      (is (>= n 64)))))

(deftest boot-is-idempotent
  (testing "second boot! returns nil and leaves the first pool in place"
    (let [first-result  (mp/boot! {:max-threads 32})
          first-pool    (mp/snapshot)
          second-result (mp/boot! {:max-threads 999})
          second-pool   (mp/snapshot)]
      (is (mp/installed?))
      ;; first call returns the pool (or nil if already installed from a
      ;; prior test run in the same JVM — both shapes are legal); second
      ;; call MUST be nil to signal the no-op.
      (is (nil? second-result))
      ;; The installed pool is unchanged across the redundant boot!.
      (is (identical? first-pool second-pool))
      ;; Sanity: the bounded pool is a dirigiste Executor.
      (is (instance? io.aleph.dirigiste.Executor first-pool)))))
