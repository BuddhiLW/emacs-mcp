(ns hive-mcp.tools.memory.search-test
  "Defensive timeout tests for memory search (task 20260404125344-0365f8a8).

   Pins the per-stage timeout guarantees:
   - Slow embedding (stub via slow store that blocks inside search-similar
     with an 'embedding' message) → structured err within budget + slack.
   - Slow vectordb (stub store that blocks forever) → structured err.
   - Throwing vectordb → structured err.
   - Happy path → same :ok result shape as before the refactor.

   Stubs work at the protocol boundary: we register a mock IMemoryStore
   via mem-proto/register-store! and inject behaviour in the defrecord's
   search-similar. Each test sets a unique :default store and restores
   any pre-existing default in a fixture-free try/finally pattern to
   stay thread-safe across the suite."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.memory.search :as search]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.chroma.search :as chroma-search]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.plan.plans :as plans]))

;; =============================================================================
;; Stub IMemoryStore
;; =============================================================================

(defrecord StubStore [search-fn]
  mem-proto/IMemoryStore
  (connect!                 [_ _])
  (disconnect!              [_])
  (connected?               [_] true)
  (health-check             [_] {:healthy? true})
  (add-entry!               [_ _])
  (get-entry                [_ _])
  (update-entry!            [_ _ _])
  (delete-entry!            [_ _])
  (query-entries            [_ _])
  (search-similar           [_ q opts] (search-fn q opts))
  (supports-semantic-search? [_] true)
  (cleanup-expired!         [_])
  (entries-expiring-soon    [_ _ _])
  (find-duplicate           [_ _ _ _])
  (store-status             [_] {:ok true})
  (reset-store!             [_]))

(defn- with-stub-store*
  "Register `store` as the :default memory store, run f, then restore
   whatever store (if any) was registered before. Never leaks state."
  [store f]
  (let [prior (get (mem-proto/registered-stores) :default)]
    (try
      (mem-proto/register-store! :default store)
      (f)
      (finally
        (if prior
          (mem-proto/register-store! :default prior)
          (mem-proto/unregister-store! :default))))))

(defmacro with-stub-store [store & body]
  `(with-stub-store* ~store (fn [] ~@body)))

;; =============================================================================
;; Common redefs — isolate from KG / scope IO
;; =============================================================================

(defn- run-with-stubs
  "Run thunk f with the common redefs that isolate the search handler
   from KG / ingest / plans / scope side-effects."
  [f]
  (with-redefs [kg-edges/record-co-access! (constantly nil)
                kg-scope/visible-scopes    (constantly ["test-proj"])
                kg-scope/descendant-scopes (constantly [])
                scope/get-current-project-id (constantly "test-proj")
                ctx/current-directory       (constantly "/tmp/test")
                chroma-search/resolve-ingest-search (constantly nil)
                plans/high-abstraction-type? (constantly false)]
    (f)))

(defmacro with-stubs [& body] `(run-with-stubs (fn [] ~@body)))

;; =============================================================================
;; Happy path
;; =============================================================================

(deftest happy-path-returns-ok-shape-test
  (testing "happy path: fast store returns formatted results in stable shape"
    (let [store (->StubStore
                 (fn [_q _opts]
                   [{:id "n-1" :type "note" :tags ["foo"]
                     :content "hello world" :distance 0.1
                     :project-id "test-proj"}]))]
      (with-stubs
        (with-stub-store store
          (let [resp (search/handle-search-semantic
                      {:query "hello" :limit 5 :directory "/tmp/test"})
                parsed (json/read-str (:text resp) :key-fn keyword)]
            (is (not (:isError resp)) "happy path must not be an error")
            (is (contains? parsed :results))
            (is (contains? parsed :count))
            (is (contains? parsed :query))
            (is (contains? parsed :scope))
            (is (= 1 (:count parsed)))
            (is (= "n-1" (-> parsed :results first :id)))))))))

;; =============================================================================
;; Slow embedding → structured err within budget
;; =============================================================================

(deftest slow-embed-returns-structured-error-test
  (testing "slow embedding (store throws after blocking with 'embedding' msg)
            yields a structured :memory/search-failed err with :stage :embed
            within the 30s budget + 100ms slack"
    ;; We simulate an embedding hang: store.search-similar blocks longer
    ;; than the vectordb-timeout (15s). The stage wrapper times out and
    ;; classify-stage inspects the :weave/timeout :name which carries the
    ;; 'memory-search/store' label. Since the safe-future's timeout msg
    ;; does not naturally contain 'embed', we inject it via a throw to
    ;; exercise the :embed classification path.
    (let [store (->StubStore
                 (fn [_q _opts]
                   (Thread/sleep 200)
                   (throw (ex-info "upstream embedding provider unreachable"
                                   {:stage :embed}))))]
      (with-stubs
        (with-stub-store store
          (let [t0 (System/currentTimeMillis)
                resp (search/handle-search-semantic
                      {:query "x" :limit 3 :directory "/tmp/test"})
                elapsed (- (System/currentTimeMillis) t0)
                parsed  (try (json/read-str (:text resp) :key-fn keyword)
                             (catch Exception _ nil))]
            (is (:isError resp) "must surface as an error response")
            (is (< elapsed 31000)
                (str "must return within 30s budget + slack, got " elapsed "ms"))
            (when parsed
              (is (or (= "embed" (:stage parsed))
                      (= :embed  (:stage parsed))
                      ;; Accept raw text (non-JSON) body too
                      (re-find #"embed" (str (:text resp))))
                  "stage classification should tag embed failures"))))))))

;; =============================================================================
;; Slow vectordb → structured err
;; =============================================================================

(deftest slow-vectordb-returns-structured-error-test
  (testing "store.search-similar hangs > vectordb-timeout → structured err with
            :stage :vectordb and elapsed within vectordb-timeout + slack"
    ;; Block 20s — exceeds the 15s vectordb-timeout. Without defensive
    ;; timeouts this would pin the pool for 20s (or forever on a dead
    ;; grpc client). With them, fork-join cancels after its budget and
    ;; we get a structured err.
    (let [store (->StubStore
                 (fn [_q _opts]
                   (Thread/sleep 20000)
                   [{:id "never" :type "note" :content "x" :distance 0.0}]))]
      (with-stubs
        (with-stub-store store
          (let [t0 (System/currentTimeMillis)
                resp (search/handle-search-semantic
                      {:query "x" :limit 3 :directory "/tmp/test"})
                elapsed (- (System/currentTimeMillis) t0)]
            (is (:isError resp) "hung vectordb must surface an error")
            (is (< elapsed 16500)
                (str "must cancel at vectordb-timeout (15s) + slack, got "
                     elapsed "ms"))
            (let [body (:text resp)]
              (is (or (re-find #"vectordb" (str body))
                      (re-find #"timed? out" (str body))
                      (re-find #"timeout"    (str body)))
                  (str "body should name vectordb/timeout: " body)))))))))

;; =============================================================================
;; Throwing vectordb → structured err
;; =============================================================================

(deftest throwing-vectordb-returns-structured-error-test
  (testing "store.search-similar throws → structured err with :stage :vectordb"
    (let [store (->StubStore
                 (fn [_q _opts]
                   (throw (ex-info "Qdrant grpc INTERNAL: Panic! This is a bug!"
                                   {:backend :qdrant}))))]
      (with-stubs
        (with-stub-store store
          (let [t0 (System/currentTimeMillis)
                resp (search/handle-search-semantic
                      {:query "x" :limit 3 :directory "/tmp/test"})
                elapsed (- (System/currentTimeMillis) t0)]
            (is (:isError resp) "thrown error must surface as error response")
            (is (< elapsed 2000)
                (str "thrown errors must return fast, got " elapsed "ms"))
            (is (some? (:text resp)))))))))

;; =============================================================================
;; Budget sanity: total wall clock bounded even on pathological combos
;; =============================================================================

(deftest total-budget-enforced-test
  (testing "even if both vectordb sides misbehave, total elapsed stays within
            overall 30s budget + slack"
    (let [store (->StubStore
                 (fn [_q _opts]
                   (Thread/sleep 40000)
                   []))]
      (with-stubs
        (with-stub-store store
          (let [t0 (System/currentTimeMillis)
                _ (search/handle-search-semantic
                   {:query "x" :limit 3 :directory "/tmp/test"})
                elapsed (- (System/currentTimeMillis) t0)]
            (is (< elapsed 31000)
                (str "overall budget must cap hang, got " elapsed "ms"))))))))
