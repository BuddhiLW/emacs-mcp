(ns hive-mcp.tools.catchup.query-axioms-regression-test
  "RED regression tests for catchup/scope/query-axioms.

   Pins two regressions:

   1. GOLDEN — query-axioms must return all axioms for a scope.
      Previously returned 0 because safe-deref silently dropped slow-branch results.

   2. PROPERTY — query-axioms must honor a latency budget.
      Currently: @f-legacy (unbounded deref) blocks indefinitely when the
      legacy convention[axiom] query stalls on Milvus cold path — the outer
      safe-deref in catchup.clj catches it 45s later with an empty vector,
      losing all axioms including the ones f-formal already delivered.

   3. MUTATION — when the legacy branch hangs, query-axioms must still
      deliver f-formal results within budget. Today it blocks on @f-legacy.

   Dependency note: these tests use a reified IMemoryStore (no Milvus)
   with controllable per-branch latency, so they run in CI and are
   deterministic. The hive-ttracking EPIC (kanban 20260414104332-192b2da4)
   will replace the ad-hoc budget plumbing with `tt/track` + `deftest-tt`."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.catchup.scope :as cs]))

;; =============================================================================
;; Mock store with per-query-type controllable latency
;; =============================================================================

(defn- make-store
  "Reified IMemoryStore backed by a vector of entries.
   `delays` is a map keyed by :type string (e.g. {\"convention\" 30000}) —
   ms to sleep before returning for matching query opts. Default 0.

   Note: query-scoped-entries applies :tags filter in-memory via filter-by-tags,
   so we can only key delays on :type (mirroring real Milvus slow path where
   type=convention cold query is the bottleneck, not tag predicates)."
  [entries delays]
  (reify mem-proto/IMemoryStore
    (connect! [_ _] nil)
    (disconnect! [_] nil)
    (connected? [_] true)
    (health-check [_] {:healthy? true})
    (add-entry! [_ _] nil)
    (get-entry [_ _] nil)
    (update-entry! [_ _ _] nil)
    (delete-entry! [_ _] true)
    (query-entries [_ opts]
      (let [{:keys [type tags project-id project-ids limit]} opts
            delay-ms (get delays type 0)]
        (when (pos? delay-ms) (Thread/sleep delay-ms))
        (->> entries
             (filter (fn [e]
                       (and (or (nil? type) (= type (:type e)))
                            (or (nil? project-id)
                                (= project-id (:project-id e)))
                            (or (nil? project-ids)
                                (some #{(:project-id e)} project-ids))
                            (or (nil? tags)
                                (every? (set (:tags e)) tags)))))
             (take (or limit 100))
             vec)))
    (search-similar [_ _ _] [])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_] 0)
    (entries-expiring-soon [_ _ _] [])
    (find-duplicate [_ _ _ _] nil)
    (store-status [_] {:backend "mock"})
    (reset-store! [_] nil)))

(def ^:private fx-entries
  [{:id "ax-1" :type "axiom" :project-id "hive-mcp"
    :tags ["axiom" "scope:project:hive-mcp"] :content "formal ax 1"
    :created "2026-04-14T10:00:00Z"}
   {:id "ax-2" :type "axiom" :project-id "hive-mcp"
    :tags ["axiom" "scope:project:hive-mcp"] :content "formal ax 2"
    :created "2026-04-14T10:01:00Z"}
   {:id "ax-3" :type "axiom" :project-id "global"
    :tags ["axiom" "scope:global"] :content "global ax"
    :created "2026-04-14T10:02:00Z"}
   ;; legacy: type=convention with tag "axiom"
   {:id "legacy-1" :type "convention" :project-id "hive-mcp"
    :tags ["axiom" "scope:project:hive-mcp"] :content "legacy ax 1"
    :created "2026-04-14T10:03:00Z"}])

(defmacro ^:private with-store
  [store & body]
  `(with-redefs [mem-proto/store-set? (constantly true)
                 mem-proto/get-store  (constantly ~store)]
     ~@body))

;; =============================================================================
;; RED — Golden: all axiom sources must be returned
;; =============================================================================

(deftest ^:regression query-axioms-returns-both-formal-and-legacy-test
  (testing "query-axioms aggregates formal axioms AND legacy convention[axiom]"
    (with-store (make-store fx-entries {})
      (let [result (cs/query-axioms "hive-mcp")
            ids    (set (map :id result))]
        (is (contains? ids "ax-1")  "formal type=axiom hive-mcp scope missing")
        (is (contains? ids "ax-2")  "formal type=axiom hive-mcp scope missing")
        (is (contains? ids "ax-3")  "global axiom piercing missing")
        (is (contains? ids "legacy-1") "legacy convention[axiom] missing")
        (is (= 4 (count result)) "expected 4 distinct axioms")))))

;; =============================================================================
;; RED — Property: latency budget enforcement
;; =============================================================================

(def ^:private budget-ms 2000)

(deftest ^:regression query-axioms-honors-latency-budget-test
  (testing "query-axioms must return within budget even if legacy branch stalls"
    (with-store (make-store fx-entries
                            {"convention" 30000})
      (let [f  (future (cs/query-axioms "hive-mcp"))
            t0 (System/currentTimeMillis)
            r  (deref f (+ budget-ms 500) ::timeout)
            elapsed (- (System/currentTimeMillis) t0)]
        (when (= r ::timeout) (future-cancel f))
        ;; RED today: this fails because @f-legacy blocks the whole fn.
        (is (not= ::timeout r)
            (str "query-axioms blocked past budget " budget-ms
                 "ms — unbounded @f-legacy deref; elapsed " elapsed))
        (when (not= ::timeout r)
          (is (<= elapsed budget-ms)
              (str "query-axioms exceeded budget " budget-ms
                   "ms; elapsed " elapsed)))))))

;; =============================================================================
;; RED — Mutation: partial progress under slow legacy branch
;; =============================================================================

(deftest ^:regression query-axioms-partial-progress-on-slow-legacy-test
  (testing "when legacy branch is slow, formal results must still be delivered"
    (with-store (make-store fx-entries
                            {"convention" 30000})
      (let [f  (future (cs/query-axioms "hive-mcp"))
            r  (deref f (+ budget-ms 500) ::timeout)]
        (when (= r ::timeout) (future-cancel f))
        ;; RED: blocked → ::timeout → zero formal axioms delivered.
        ;; GREEN goal: returns at least {ax-1 ax-2 ax-3} from f-formal.
        (is (not= ::timeout r)
            "legacy branch stall must not prevent f-formal delivery")
        (when (not= ::timeout r)
          (let [ids (set (map :id r))]
            (is (contains? ids "ax-1"))
            (is (contains? ids "ax-2"))
            (is (contains? ids "ax-3"))))))))
