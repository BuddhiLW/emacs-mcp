(ns hive-mcp.batch-test
  "Smoke test proving hive-mcp.batch is independently callable without
   any hive-mcp tool routing. Deeper coverage remains in
   hive-mcp.tools.multi-test which exercises the wrapper."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.batch :as batch]
            [hive-mcp.batch.protocol :as bp]
            [hive-mcp.extensions.registry :as ext]))

(defn- stub-handler
  "Simple in-process handler registry keyed by :tool keyword."
  [handlers tool-name]
  (get handlers tool-name))

(defn- with-text-envelope-parser
  "Supply a faithful :bx/b that parses the standard {:type \"text\" :text json}
   tool envelope into a keywordized map. The prod batch addon registers this at
   server boot; clojure -M:test never loads addons, so enrich-op-result's
   text-envelope downgrade path has no parser cold and silently can't see inner
   failures. Bare-map results pass through unchanged. Restores any prior :bx/b."
  [f]
  (let [prior (ext/get-extension :bx/b)]
    (ext/register! :bx/b
                   (fn [result]
                     (if (and (map? result)
                              (= "text" (:type result))
                              (string? (:text result)))
                       (try (json/read-str (:text result) :key-fn keyword)
                            (catch Exception _ result))
                       result)))
    (try (f)
         (finally
           (if prior (ext/register! :bx/b prior) (ext/deregister! :bx/b))))))

(use-fixtures :once with-text-envelope-parser)

(deftest run-operations-happy-path
  (testing "single op with an injected handler executes and returns success"
    (let [calls (atom [])
          handlers {"fake-tool" (fn [args]
                                  (swap! calls conj args)
                                  {:echo args})}
          result (batch/run-operations
                  [{:id "op-1" :tool "fake-tool" :command "noop" :arg 42}]
                  {:resolve-handler (partial stub-handler handlers)})]
      (is (:success result))
      (is (= 1 (count @calls)))
      (is (= 42 (:arg (first @calls))))
      (is (= 1 (get-in result [:summary :total])))
      (is (= 1 (get-in result [:summary :success])))
      (is (= 0 (get-in result [:summary :failed]))))))

(deftest run-operations-validation-error
  (testing "missing :tool is caught by validation before execution"
    (let [result (batch/run-operations
                  [{:id "op-bad" :command "noop"}]
                  {:resolve-handler (fn [_] nil)})]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest run-operations-dry-run
  (testing "dry-run reports plan without invoking handler"
    (let [called? (atom false)
          result (batch/run-operations
                  [{:id "op-1" :tool "noop-tool" :command "go"}]
                  {:resolve-handler (fn [_] (reset! called? true) (constantly nil))
                   :dry-run? true})]
      (is (:success result))
      (is (:dry-run result))
      (is (not @called?) "dry-run must not invoke handler"))))

(deftest ref-not-found-sentinel-back-compat
  (testing "sentinel keyword is preserved under legacy hive-mcp.tools.multi namespace"
    (is (= :hive-mcp.tools.multi/ref-not-found batch/ref-not-found))))

(deftest default-runner-is-batchable
  (testing "make-default-runner yields a Batchable implementation (T13 Phase 2)"
    (let [runner (batch/make-default-runner {:resolve-handler (constantly nil)})]
      (is (satisfies? bp/Batchable runner))
      (is (satisfies? bp/DAGBatchable runner))
      (is (satisfies? bp/StreamingBatchable runner)))))

(deftest default-runner-executes-via-protocol
  (testing "batch-execute via protocol matches legacy run-operations output shape"
    (let [calls  (atom 0)
          runner (batch/make-default-runner
                  {:resolve-handler (fn [_tool]
                                      (fn [_args] (swap! calls inc) {:ok true}))})
          result (bp/batch-execute runner
                                   [{:id "a" :tool "echo" :command "go"}]
                                   {})]
      (is (:success result))
      (is (= 1 @calls))
      (is (= 1 (get-in result [:summary :success]))))))

;; =============================================================================
;; Defect-fix coverage — multi DSL partial-failure (kanban 38c0fff0)
;; =============================================================================
;;
;; Three regressions found during the wrap-ceremony smoke test on 2026-05-06:
;;
;; 1. Dangling KG edges when a source `m+` produced `:id nil`. Dependent k>
;;    ran with `$ref:src.id` resolving to nil and silently created edges
;;    against null endpoints.
;; 2. Off-by-one in batch summary count when a worker timed out and
;;    returned nil — the wave aggregator collapsed all nil-id results
;;    under one entry, dropping ops from `:total`.
;; 3. (Out of scope here) `b+` not idempotent on retry — needs server-side
;;    idempotency-key plumbing in the kanban facade. Deferred.

(deftest enrich-op-result-downgrades-on-inner-success-false
  (testing "wrapper :success flipped to false when inner data reports failure"
    (let [enriched (batch/enrich-op-result
                    {:id "op-1" :tool "memory" :command "add"
                     :success true
                     :result {:type "text"
                              :text "{\"success\": false, \"errors\": [\"backend timeout\"]}"}})]
      (is (false? (:success enriched)))
      (is (string? (:error enriched)))
      (is (re-find #"backend timeout" (:error enriched))))))

(deftest enrich-op-result-downgrades-on-null-id-creation
  (testing "creation tools that return nil id are reclassified as failed"
    (let [enriched (batch/enrich-op-result
                    {:id "op-2" :tool "memory" :command "add"
                     :success true
                     :result {:type "text"
                              :text "{\"id\": null, \"success\": null}"}})]
      (is (false? (:success enriched)))
      (is (re-find #"nil id" (:error enriched))))))

(deftest enrich-op-result-leaves-genuine-success-alone
  (testing "true success with non-nil id stays :success true"
    (let [enriched (batch/enrich-op-result
                    {:id "op-3" :tool "memory" :command "add"
                     :success true
                     :result {:type "text"
                              :text "{\"id\": \"20260506-abc\", \"success\": true}"}})]
      (is (true? (:success enriched))))))

(deftest broken-ref-skips-dependent-op
  (testing "k> dependent op skipped when its $ref source resolved to nil"
    (let [;; Source m+ adds an entry but the (stubbed) backend reports
          ;; null id — enrich downgrades it. The dependent k> declares
          ;; depends_on so wave assignment puts it in wave 2; its
          ;; $ref:m1.data.id therefore resolves to nil → broken-ref skip.
          handlers {"memory" (fn [_args]
                               {:type "text"
                                :text "{\"id\": null, \"success\": null}"})
                    "kg"     (fn [_args]
                               {:type "text"
                                :text "{\"edge_id\": \"should-not-create\"}"})}
          result (batch/run-operations
                  [{:id "m1" :tool "memory" :command "add" :content "x"}
                   {:id "k1" :tool "kg" :command "edge"
                    :from "$ref:m1.data.id" :to "node-2" :rel "implements"
                    :depends_on ["m1"]}]
                  {:resolve-handler (partial stub-handler handlers)})
          k1-result (->> (vals (:waves result))
                         (mapcat :results)
                         (filter #(= "k1" (:id %)))
                         first)]
      (is (false? (:success k1-result))
          "k1 must not be reported as success — its $ref endpoint was nil")
      (is (re-find #"broken-ref|dependencies failed" (or (:error k1-result) ""))
          "k1's error must explain why it was skipped")
      (is (= 2 (get-in result [:summary :total])))
      (is (= 0 (get-in result [:summary :success])))
      (is (= 2 (get-in result [:summary :failed]))))))

(deftest broken-ref-detection-via-ref-not-found
  (testing "$ref to a missing source op-id is also classified as broken"
    ;; This case is normally caught by validate-ref-deps, but exercise
    ;; the runtime classifier directly.
    (let [handlers {"memory" (fn [_args] {:type "text" :text "{\"id\": \"a\"}"})}
          ;; Force the missing-dep validation to pass by declaring a self-loop-free
          ;; structure and using $ref outside depends_on intentionally.
          ;; (validate-ref-deps would normally reject this; this test exercises
          ;; the runtime path that catches what gets past validation.)
          result (batch/run-operations
                  [{:id "m1" :tool "memory" :command "add" :content "x"}
                   {:id "k1" :tool "memory" :command "add"
                    :content "x" :pinned-id "$ref:nonexistent.data.id"
                    :depends_on ["m1"]}]
                  {:resolve-handler (partial stub-handler handlers)})
          k1-result (->> (vals (:waves result))
                         (mapcat :results)
                         (filter #(= "k1" (:id %)))
                         first)]
      ;; Validation should reject this batch (k1's $ref to nonexistent isn't
      ;; in depends_on). But if validation is bypassed (e.g. extension :bx/g
      ;; not loaded), the runtime classifier provides the safety net.
      (is (or (seq (:errors result))
              (false? (:success k1-result)))
          "either validation rejects or runtime classifier skips"))))

(deftest a-ref-the-host-cannot-parse-is-broken-not-passed-through
  (testing "with no :bx/a parser registered, a $ref to an EXISTING op is still
            classified broken: the host cannot resolve it, so the literal
            string would otherwise reach the handler as a value"
    (let [seen     (atom [])
          handlers {"memory" (fn [args]
                               (swap! seen conj (:pinned-id args))
                               {:type "text" :text "{\"id\": \"a\"}"})}
          result   (batch/run-operations
                    [{:id "m1" :tool "memory" :command "add" :content "x"}
                     {:id "k1" :tool "memory" :command "add"
                      :content "x" :pinned-id "$ref:m1.data.id"
                      :depends_on ["m1"]}]
                    {:resolve-handler (partial stub-handler handlers)})
          k1       (->> (vals (:waves result))
                        (mapcat :results)
                        (filter #(= "k1" (:id %)))
                        first)]
      (is (false? (:success k1)))
      (is (re-find #"unparsed" (or (:error k1) "")))
      (is (not-any? #(and (string? %) (re-find #"^\$ref:" %)) @seen)
          "no handler ever received the literal $ref string"))))

(deftest nil-exec-result-normalized-to-failed
  (testing "nil entries returned by the wave executor become failed op-results
            keyed by the input op's id (no :total collision)"
    ;; Simulate a `:bx/j` worker pool returning nil for some positions
    ;; — exactly what a bounded-pmap worker emits when it times out
    ;; before producing a result. Without normalize-exec-result,
    ;; the wave aggregator assocs `{:data nil ...}` under key nil and
    ;; collapses every timed-out op into a single entry, dropping
    ;; them from the summary count.
    (let [orig-bxj (ext/get-extension :bx/j)
          ;; Override :bx/j to return nil for every op (worker timeout).
          _ (ext/register! :bx/j (fn [wave-ops _exec-one]
                                   (mapv (constantly nil) wave-ops)))
          handlers {"slow" (fn [_] {:ok true})}
          result (try
                   (batch/run-operations
                     [{:id "a" :tool "slow" :command "go"}
                      {:id "b" :tool "slow" :command "go"}]
                     {:resolve-handler (partial stub-handler handlers)})
                   (finally
                     ;; Restore prior :bx/j or unregister.
                     (if orig-bxj
                       (ext/register! :bx/j orig-bxj)
                       (ext/deregister! :bx/j))))]
      (is (= 2 (get-in result [:summary :total])))
      (let [results (->> (vals (:waves result)) (mapcat :results))]
        (is (= 2 (count results)))
        (is (= #{"a" "b"} (set (map :id results)))
            "every op-id must appear in the results vector exactly once"))
      (is (= 2 (get-in result [:summary :failed]))
          "nil-from-executor is a failure signal, not a phantom success"))))

;; =============================================================================
;; In-band tool error → summary.failed (kanban 20260629161156-76f4e486)
;; =============================================================================
;;
;; The earlier enrich-op-result tests exercise the JSON-text envelope
;; ({:text "{...}"}) form. KG handlers also fail *in band* without that
;; envelope: handle-kg-add-edge's validation returns a bare {:error "..."}
;; map directly, and mcp-error returns an {:isError true} envelope. Both
;; must surface as summary.failed>0 through the full run-operations flow —
;; otherwise a k> op whose edge was rejected reads back as success and any
;; dependent $ref dispatches against a phantom endpoint.

(deftest in-band-bare-error-counts-as-failed
  (testing "a handler returning a bare {:error ...} (no throw, no :success key) —
            exactly handle-kg-add-edge's validation-failure shape — is counted failed"
    (let [handlers {"kg" (fn [_args] {:error "relation is required"})}
          result (batch/run-operations
                  [{:id "k1" :tool "kg" :command "edge" :from "a" :to "b"}]
                  {:resolve-handler (partial stub-handler handlers)})
          k1 (->> (vals (:waves result)) (mapcat :results)
                  (filter #(= "k1" (:id %))) first)]
      (is (false? (:success result)))
      (is (= 1 (get-in result [:summary :total])))
      (is (= 0 (get-in result [:summary :success])))
      (is (= 1 (get-in result [:summary :failed]))
          "bare in-band :error must surface as failed, not masked as success")
      (is (false? (:success k1)))
      (is (re-find #"relation is required" (or (:error k1) ""))
          "the op error must carry the in-band message"))))

(deftest in-band-mcp-error-envelope-counts-as-failed
  (testing "a handler returning an mcp-error {:isError true} envelope is counted failed"
    (let [handlers {"kg" (fn [_args] {:isError true
                                      :error "Edge not found: x"
                                      :content [{:type "text" :text "Edge not found: x"}]})}
          result (batch/run-operations
                  [{:id "p1" :tool "kg" :command "promote" :edge_id "x" :to_scope "s"}]
                  {:resolve-handler (partial stub-handler handlers)})
          p1 (->> (vals (:waves result)) (mapcat :results)
                  (filter #(= "p1" (:id %))) first)]
      (is (false? (:success result)))
      (is (= 1 (get-in result [:summary :failed]))
          "mcp-error :isError envelope must surface as failed")
      (is (false? (:success p1))))))
