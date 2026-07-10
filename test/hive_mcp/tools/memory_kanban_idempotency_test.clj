(ns hive-mcp.tools.memory-kanban-idempotency-test
  "Idempotency-key coverage for `b+` (kanban create).

   Three properties are tested:
   1. Without `:idempotency_key`, behaviour is unchanged: every create
      mints a fresh id (no surprise to callers that aren't using the
      retry-safe path).
   2. With a key, the FIRST create writes a new entry tagged
      `idempotency:<key>` and returns its id.
   3. With the same key on a subsequent call (within the entry's TTL
      and project scope), no second write happens — the existing id is
      returned verbatim.

   No real backend: the test stubs `kanban-facade/query-entries`
   (lookup) + `mem-crud/handle-add` (write) via `with-redefs`. This
   keeps the test pure and fast; real-backend coverage will land as
   part of the wrap-ceremony smoke test once it is bundled."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.memory-kanban :as mem-kanban]
            [hive-mcp.tools.memory.crud :as mem-crud]
            [hive-mcp.vectordb.kanban-facade :as kanban-facade]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test fixtures
;; =============================================================================

(defn- run-create
  "Drive the public handler with stubbed lookup + write. Returns
   [result-map calls-vec], where calls-vec records every (handle-add)
   invocation so the tests can assert how many actual writes occurred."
  [params {:keys [lookup-results] :or {lookup-results []}}]
  (let [calls (atom [])
        ;; The real `handle-add` does I/O on the embedding pool. Stub
        ;; with the same shape downstream code expects: text id on
        ;; success, :isError envelope on failure.
        stub-add (fn [args]
                   (swap! calls conj args)
                   {:type "text" :text (str "stub-id-" (count @calls))})]
    (with-redefs [kanban-facade/query-entries (fn [& _] lookup-results)
                  mem-crud/handle-add stub-add]
      [(mem-kanban/handle-mem-kanban-create params) @calls])))

;; =============================================================================
;; Property 1 — no idempotency-key keeps legacy behaviour
;; =============================================================================

(deftest no-key-mints-fresh-id
  (testing "absent :idempotency_key → standard create path, one write"
    (let [[result calls] (run-create {:title "t1"} {})]
      (is (= "stub-id-1" (:text result))
          "without an idempotency key the stub returns its synthesized id")
      (is (= 1 (count calls))
          "exactly one handle-add call should fire")
      (is (not-any? #(some (fn [t] (re-find #"^idempotency:" t)) %)
                    (map :tags calls))
          "no idempotency tag should be attached when no key was provided"))))

;; =============================================================================
;; Property 2 — first create with a key writes once and tags
;; =============================================================================

(deftest first-create-with-key-writes-once-and-tags
  (testing ":idempotency_key on a cold lookup → one write tagged with idempotency:<key>"
    (let [[result calls] (run-create
                          {:title "t2" :idempotency_key "wave-2026-05-07-a"}
                          {:lookup-results []})]
      (is (= "stub-id-1" (:text result)))
      (is (= 1 (count calls)))
      (is (some #(= "idempotency:wave-2026-05-07-a" %)
                (:tags (first calls)))
          "the new entry's tags must include idempotency:<key> for future retries to find"))))

(deftest first-create-with-kebab-key-also-tags
  (testing ":idempotency-key (kebab) accepted on the canonical handler shape"
    (let [[result calls] (run-create
                          {:title "t2k" :idempotency-key "kebab-key-1"}
                          {:lookup-results []})]
      (is (= "stub-id-1" (:text result)))
      (is (some #(= "idempotency:kebab-key-1" %)
                (:tags (first calls)))))))

(deftest first-create-with-idk-alias-also-tags
  (testing ":idk short-form accepted (matches DSL param-aliases mapping)"
    (let [[result calls] (run-create
                          {:title "t2-short" :idk "idk-short-1"}
                          {:lookup-results []})]
      (is (= "stub-id-1" (:text result)))
      (is (some #(= "idempotency:idk-short-1" %)
                (:tags (first calls)))))))

;; =============================================================================
;; Property 3 — second create with same key short-circuits to existing id
;; =============================================================================

(deftest retry-with-same-key-returns-existing-id-no-write
  (testing "lookup returns an existing entry → handler returns its id; NO new write"
    (let [existing  {:id "20260507000000-aaaa1111"
                     :tags ["kanban" "todo" "idempotency:wave-2026-05-07-a"]}
          [result calls] (run-create
                          {:title "t3-retry" :idempotency_key "wave-2026-05-07-a"}
                          {:lookup-results [existing]})]
      (is (= "20260507000000-aaaa1111" (:text result))
          "result must echo the existing entry's id verbatim")
      (is (zero? (count calls))
          "no second handle-add should fire on a retry hit"))))

(deftest blank-key-treated-as-absent
  (testing "blank/whitespace key is ignored — treated as no key at all"
    (let [[result1 calls1] (run-create
                            {:title "t-blank" :idempotency_key ""}
                            {:lookup-results []})
          [result2 calls2] (run-create
                            {:title "t-blank" :idempotency_key "   "}
                            {:lookup-results []})]
      (is (= 1 (count calls1)))
      (is (= 1 (count calls2)))
      (is (not-any? #(some (fn [t] (re-find #"^idempotency:" t)) %)
                    (concat (map :tags calls1) (map :tags calls2)))
          "blank keys must not produce an idempotency:<empty> tag"))))

;; =============================================================================
;; Failure mode — lookup throws should not break creation
;; =============================================================================

(deftest lookup-failure-degrades-to-fresh-create
  (testing "if the lookup query throws, the create still proceeds (no retry-safety lost on a missed lookup)"
    (let [calls (atom [])
          stub-add (fn [args]
                     (swap! calls conj args)
                     {:type "text" :text "stub-id-after-failed-lookup"})]
      (with-redefs [kanban-facade/query-entries (fn [& _]
                                                   (throw (ex-info "backend down" {})))
                    mem-crud/handle-add stub-add]
        (let [result (mem-kanban/handle-mem-kanban-create
                      {:title "t-degraded" :idempotency_key "k-fail"})]
          (is (= "stub-id-after-failed-lookup" (:text result)))
          (is (= 1 (count @calls)))
          (is (some #(= "idempotency:k-fail" %)
                    (:tags (first @calls)))
              "even on lookup failure, the new entry should still carry the idempotency tag so a future retry can find it"))))))
