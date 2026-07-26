(ns hive-mcp.tools.memory.lifecycle-test
  "TDD tests for memory lifecycle - specifically run-decay-cycle! (P0.3).

   Tests the bounded, idempotent decay cycle designed for wrap/session-complete hooks.
   Uses with-redefs to mock Chroma operations."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.tools.memory.lifecycle :as lifecycle]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-spi.memory.registry :as registry]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.agent.context :as ctx]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-test-entry
  "Create a test memory entry with sensible defaults."
  [& {:keys [id type duration access-count staleness-beta last-accessed tags]
      :or {id "test-entry-1"
           type "note"
           duration "short"
           access-count 0
           staleness-beta 1
           last-accessed nil
           tags ["scope:project:hive-mcp"]}}]
  {:id id
   :type type
   :duration duration
   :access-count access-count
   :staleness-beta staleness-beta
   :last-accessed last-accessed
   :tags tags})

(defn month-ago-str
  "Return a ZonedDateTime string for N days ago."
  [days]
  (str (.minusDays (java.time.ZonedDateTime/now) days)))

(defn expired-entry
  "An entry whose :expires is already in the past, so cleanup-expired! reaps it."
  [id]
  {:id id :type "note" :duration "ephemeral" :expires "2020-01-01T00:00:00Z"
   :tags ["scope:project:hive-mcp"]})

(defmacro with-decay-store
  "Install a stub memory store for the body, then restore the registry.

   Options:
     :entries — live entries the store returns from query-entries
     :expired — already-expired entries, reaped by cleanup-expired!
     :faults  — {port-op message} injected into an observing decorator
     :bind    — symbol bound to the installed store inside the body"
  [{:keys [entries expired faults bind] :or {bind (gensym "store")}} & body]
  `(let [prior# (registry/registered-stores)
         ~bind  (cond-> (stub/->stub (concat ~entries ~expired))
                  true (stub/->observing (or ~faults {})))]
     (try
       (stub/install! ~bind)
       (with-redefs [kg-edges/remove-edges-for-node! (fn [_#] 0)
                     ctx/current-directory (constantly "/home/test/hive-mcp")]
         ~@body)
       (finally
         (registry/reset-registry!)
         (doseq [[k# s#] prior#] (registry/register-store! k# s#))))))

;; =============================================================================
;; run-decay-cycle! Tests
;; =============================================================================

(deftest run-decay-cycle-empty-test
  (testing "returns zeros when no entries exist"
    (with-decay-store {:entries []}
      (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
        (is (map? result))
        (is (= 0 (:decayed result)))
        (is (= 0 (:expired result)))
        (is (= 0 (:total-scanned result)))
        (is (nil? (:error result)))))))

(deftest run-decay-cycle-with-expired-test
  (testing "reports expired count from cleanup phase"
    (with-decay-store {:entries []
                       :expired (mapv expired-entry ["old-1" "old-2" "old-3"])}
      (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
        (is (= 3 (:expired result)))
        (is (= 0 (:decayed result)))
        (is (zero? (:total-scanned result)))))))

(deftest run-decay-cycle-decays-stale-entries-test
  (testing "decays entries that are old and low-access"
    (let [old-entries [(make-test-entry :id "stale-1"
                                        :duration "short"
                                        :access-count 0
                                        :staleness-beta 1
                                        :last-accessed (month-ago-str 30))
                       (make-test-entry :id "stale-2"
                                        :duration "medium"
                                        :access-count 1
                                        :staleness-beta 1
                                        :last-accessed (month-ago-str 14))]]
      (with-decay-store {:entries old-entries}
        (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
          (is (= 2 (:total-scanned result)))
          (is (pos? (:decayed result))))))))

(deftest run-decay-cycle-skips-permanent-and-axioms-test
  (testing "does not decay permanent or axiom entries"
    (let [entries [(make-test-entry :id "permanent-1"
                                    :duration "permanent"
                                    :access-count 0
                                    :last-accessed (month-ago-str 60))
                   (make-test-entry :id "axiom-1"
                                    :type "axiom"
                                    :duration "long"
                                    :access-count 0
                                    :last-accessed (month-ago-str 60))
                   (make-test-entry :id "should-decay"
                                    :duration "short"
                                    :access-count 0
                                    :last-accessed (month-ago-str 30))]]
      (with-decay-store {:entries entries}
        (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
          (is (= 3 (:total-scanned result)))
          ;; Only the "short" entry should decay
          (is (= 1 (:decayed result))))))))

(deftest run-decay-cycle-skips-recently-accessed-test
  (testing "does not decay entries accessed within recency window"
    (let [entries [(make-test-entry :id "recent-1"
                                    :duration "short"
                                    :access-count 0
                                    :last-accessed (month-ago-str 2))]] ;; 2 days ago, within 7-day window
      (with-decay-store {:entries entries}
        (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
          (is (= 1 (:total-scanned result)))
          (is (= 0 (:decayed result))))))))

(deftest run-decay-cycle-skips-high-access-test
  (testing "does not decay entries with high access count"
    (let [entries [(make-test-entry :id "popular-1"
                                    :duration "short"
                                    :access-count 5
                                    :last-accessed (month-ago-str 30))]]
      (with-decay-store {:entries entries}
        (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
          (is (= 1 (:total-scanned result)))
          (is (= 0 (:decayed result))))))))

(deftest run-decay-cycle-error-isolation-test
  (testing "returns error map on Chroma failure, does not throw"
    (with-decay-store {:faults {:cleanup-expired! "store is down"
                                :query-entries    "store is down"}}
      (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
        (is (map? result))
        (is (string? (:error result)))
        (is (= 0 (:decayed result)))
        (is (= 0 (:expired result)))))))

(deftest run-decay-cycle-cleanup-error-continues-decay-test
  (testing "continues decay even if cleanup phase fails"
    (let [entries [(make-test-entry :id "stale-1"
                                    :duration "short"
                                    :access-count 0
                                    :last-accessed (month-ago-str 30))]]
      (with-decay-store {:entries entries
                         :faults  {:cleanup-expired! "cleanup failed"}}
        (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
          ;; Cleanup failed but decay should still run
          (is (= 0 (:expired result)))
          (is (string? (:cleanup-error result)))
          (is (pos? (:decayed result))))))))

(deftest run-decay-cycle-idempotent-test
  (testing "calling twice with same data produces consistent results"
    (let [entries [(make-test-entry :id "stale-1"
                                    :duration "short"
                                    :access-count 0
                                    :staleness-beta 1
                                    :last-accessed (month-ago-str 30))]]
      (with-decay-store {:entries entries}
        (let [result1 (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})
              result2 (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
          ;; Both calls should produce same shape and similar results
          (is (= (:total-scanned result1) (:total-scanned result2)))
          (is (= (:decayed result1) (:decayed result2))))))))

(deftest run-decay-cycle-respects-limit-test
  (testing "limit parameter controls max entries processed"
    (let [entries (mapv #(make-test-entry :id (str "entry-" %)
                                          :duration "short"
                                          :access-count 0
                                          :last-accessed (month-ago-str 30))
                        (range 10))]
      (with-decay-store {:entries entries :bind store}
        (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp" :limit 5})
        (is (= 5 (:limit (ffirst (stub/calls-of store :query-entries))))
            "limit is passed through to the store query")))))

(deftest run-decay-cycle-default-limit-test
  (testing "default limit is 50"
    (with-decay-store {:entries [] :bind store}
      (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})
      (is (= 50 (:limit (ffirst (stub/calls-of store :query-entries))))
          "default limit is 50"))))

;; =============================================================================
;; Integration with crystallize-session (hooks.clj wiring)
;; =============================================================================

(deftest run-decay-cycle-returns-plain-map-test
  (testing "returns plain Clojure map, not MCP response format"
    (with-decay-store {:entries []}
      (let [result (lifecycle/run-decay-cycle! {:directory "/home/test/hive-mcp"})]
        ;; Should NOT have MCP response shape
        (is (nil? (:type result)))
        (is (nil? (:text result)))
        ;; Should have plain data keys
        (is (contains? result :decayed))
        (is (contains? result :expired))
        (is (contains? result :total-scanned))))))
