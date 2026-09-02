(ns hive-mcp.tools.kg.synthetics-test
  "Tests for `kg cleanup-synthetics` — prunes or demotes synthetic-pattern
   nodes whose outgoing edges mostly target missing/expired memory entries.

   Fabrication pattern:
     1. DataScript KG fixture (fresh per test).
     2. Stub memory store whose `get-entry` returns non-nil for a
        configurable set of 'live' IDs — missing IDs simulate expired
        raw entries.
     3. Register :projects-to relation (synthetic nodes use it) and
        create synth- prefixed edges.
     4. Assert cleanup behavior under varying thresholds/actions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.kg.synthetics :as synthetics]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Stub Memory Store
;; =============================================================================

(defrecord StubMemoryStore [live-ids-atom]
  mem-proto/IMemoryStore
  (connect! [_ _] nil)
  (disconnect! [_] nil)
  (connected? [_] true)
  (health-check [_] {:healthy? true})
  (add-entry! [_ e] e)
  (get-entry [_ id]
    (when (contains? @live-ids-atom id)
      {:id id :kind :mock :content "live"}))
  (update-entry! [_ _ _] nil)
  (delete-entry! [_ _] nil)
  (query-entries [_ _] [])
  (search-similar [_ _ _] [])
  (supports-semantic-search? [_] false)
  (cleanup-expired! [_] {:count 0 :deleted-ids []})
  (entries-expiring-soon [_ _ _] [])
  (find-duplicate [_ _ _ _] nil)
  (store-status [_] {:stub true})
  (reset-store! [_] true))

(def ^:dynamic *live-ids* nil)

(defn stub-store-fixture
  "Install a StubMemoryStore whose live-ids can be rebound per test."
  [f]
  (let [live (atom #{})
        store (->StubMemoryStore live)]
    (mem-proto/set-store! store)
    (binding [*live-ids* live]
      (try
        (f)
        (finally
          (mem-proto/reset-registry!))))))

(defn- set-live! [ids]
  (reset! *live-ids* (set ids)))

;; =============================================================================
;; Fixture Composition
;; =============================================================================

(defn register-relations-fixture [f]
  ;; synth-* nodes use :projects-to; also reuse :co-accessed for variety
  (schema/register-relation-types! #{:projects-to :co-accessed})
  (f))

(use-fixtures :each
  fixtures/datascript-fixture
  register-relations-fixture
  stub-store-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- synth-id
  "Generate a synth- prefixed ID in the observed format
   `synth-<timestamp>-<short>`."
  ([] (synth-id (subs (str (java.util.UUID/randomUUID)) 0 6)))
  ([suffix] (str "synth-20260101T000000-" suffix)))

(defn- raw-id
  "Generate a raw memory entry ID (non-synth prefix)."
  []
  (str "20260101000000-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn- wire-synth!
  "Create a synthetic node projecting onto raw entry IDs.
   Returns the synth-id. Drains the write-coalescing queue so the
   edges are visible to subsequent reads."
  [target-ids & [{:keys [relation confidence]
                  :or {relation :projects-to
                       confidence 0.8}}]]
  (let [sid (synth-id)]
    (doseq [tid target-ids]
      (edges/add-edge! {:from sid
                        :to tid
                        :relation relation
                        :confidence confidence}))
    (conn/drain-writer!)
    sid))

;; =============================================================================
;; list-synthetic-source-nodes
;; =============================================================================

(deftest list-synthetic-source-nodes-empty-test
  (testing "Empty graph returns empty vector"
    (is (= [] (synthetics/list-synthetic-source-nodes)))))

(deftest list-synthetic-source-nodes-filters-non-synth-test
  (testing "Only :synth- prefixed source nodes are returned"
    (let [s1 (wire-synth! [(raw-id)])
          s2 (wire-synth! [(raw-id)])
          raw-src (raw-id)]
      ;; Add an edge from a non-synth source — must be excluded
      (edges/add-edge! {:from raw-src
                        :to (raw-id)
                        :relation :depends-on
                        :confidence 0.5})
      (conn/drain-writer!)
      (let [result (synthetics/list-synthetic-source-nodes)]
        (is (= 2 (count result)))
        (is (every? #(.startsWith ^String % "synth-") result))
        (is (contains? (set result) s1))
        (is (contains? (set result) s2))
        (is (not (contains? (set result) raw-src)))))))

(deftest list-synthetic-source-nodes-distinct-test
  (testing "Same synth with multiple outgoing edges appears once"
    (let [targets [(raw-id) (raw-id) (raw-id)]
          sid (wire-synth! targets)]
      (is (= [sid] (synthetics/list-synthetic-source-nodes))))))

;; =============================================================================
;; Empty / No-op
;; =============================================================================

(deftest cleanup-empty-graph-test
  (testing "cleanup on empty graph returns zero counts"
    (let [result (synthetics/cleanup-synthetics!)]
      (is (= 0 (:scanned result)))
      (is (= 0 (:pruned result)))
      (is (= 0 (:demoted result)))
      (is (= 0 (:preserved result)))
      (is (= [] (:details result))))))

;; =============================================================================
;; Classification under default threshold (0.2)
;; =============================================================================

(deftest cleanup-prunes-dead-scaffolding-test
  (testing "Synthetic with 5/5 expired targets gets pruned (live-ratio 0.0)"
    (let [targets (repeatedly 5 raw-id)
          sid (wire-synth! targets)]
      (set-live! []) ;; all expired
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 1 (:scanned result)))
        (is (= 1 (:pruned result)))
        (is (= 0 (:demoted result)))
        (is (= 0 (:preserved result)))
        ;; Edges for the synth must be gone
        (is (empty? (edges/get-edges-from sid)))))))

(deftest cleanup-preserves-live-pattern-test
  (testing "Synthetic with all-live targets is preserved (live-ratio 1.0)"
    (let [targets (repeatedly 5 raw-id)
          sid (wire-synth! targets)]
      (set-live! targets)
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 1 (:scanned result)))
        (is (= 0 (:pruned result)))
        (is (= 1 (:preserved result)))
        ;; Edges untouched
        (is (= 5 (count (edges/get-edges-from sid))))))))

(deftest cleanup-preserves-mixed-above-threshold-test
  (testing "Synthetic with 2/5 live (ratio 0.4) preserved at threshold 0.2"
    (let [targets (repeatedly 5 raw-id)
          live    (take 2 targets)
          sid     (wire-synth! targets)]
      (set-live! live)
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 1 (:preserved result)))
        (is (= 0 (:pruned result)))
        (is (= 5 (count (edges/get-edges-from sid))))))))

(deftest cleanup-prunes-80pct-dead-test
  (testing "Synthetic with 1/5 live sits AT the 0.2 ratio boundary but names
            only one surviving member, so the member floor (2) prunes it"
    (let [targets (repeatedly 5 raw-id)
          live    (take 1 targets)
          sid     (wire-synth! targets)]
      (set-live! live)
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 1 (:pruned result)) "one live member is not a cluster")
        (is (= 0 (:preserved result)))
        (is (empty? (edges/get-edges-from sid)))))))

(deftest cleanup-member-floor-boundary-test
  (testing "Synthetic with 2/10 live sits at BOTH boundaries (ratio 0.2, two
            live members); neither rule fires, so it is preserved"
    (let [targets (repeatedly 10 raw-id)
          live    (take 2 targets)
          sid     (wire-synth! targets)]
      (set-live! live)
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 1 (:preserved result)) "two live members at ratio 0.2 stay")
        (is (= 0 (:pruned result)))
        (is (= 10 (count (edges/get-edges-from sid))))))))

(deftest cleanup-prunes-just-below-threshold-test
  (testing "Synthetic with 0/5 live or 1/6 (~0.167) gets pruned"
    (let [targets (repeatedly 6 raw-id)
          live    (take 1 targets)
          sid     (wire-synth! targets)]
      (set-live! live)
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 1 (:pruned result)) "~0.167 is below 0.2")
        (is (empty? (edges/get-edges-from sid)))))))

;; =============================================================================
;; :demote action
;; =============================================================================

(deftest cleanup-demote-action-test
  (testing "Action :demote sets outgoing-edge confidence to demote-confidence"
    (let [targets (repeatedly 5 raw-id)
          sid     (wire-synth! targets {:confidence 0.9})]
      (set-live! []) ;; all expired
      (let [result (synthetics/cleanup-synthetics! {:action :demote})]
        (is (= 1 (:demoted result)))
        (is (= 0 (:pruned result)))
        ;; Edges survive but at 0.1 confidence
        (let [out (edges/get-edges-from sid)]
          (is (= 5 (count out)))
          (doseq [e out]
            (is (== synthetics/demote-confidence (:kg-edge/confidence e)))))))))

(deftest cleanup-demote-as-string-test
  (testing "Action can be passed as string (MCP-style)"
    (let [targets (repeatedly 3 raw-id)
          sid     (wire-synth! targets)]
      (set-live! [])
      (let [result (synthetics/cleanup-synthetics! {:action "demote"})]
        (is (= 1 (:demoted result)))
        (is (seq (edges/get-edges-from sid)))))))

;; =============================================================================
;; :dry-run?
;; =============================================================================

(deftest cleanup-dry-run-test
  (testing "Dry-run classifies without mutating"
    (let [targets (repeatedly 4 raw-id)
          sid-dead  (wire-synth! targets)
          live-tgts (repeatedly 4 raw-id)
          sid-live  (wire-synth! live-tgts)]
      (set-live! live-tgts)
      (let [result (synthetics/cleanup-synthetics! {:dry-run? true})]
        (is (= 2 (:scanned result)))
        (is (= 1 (:pruned result)) "would prune dead synth")
        (is (= 1 (:preserved result)))
        (is (true? (:dry-run? result)))
        ;; NO mutation
        (is (= 4 (count (edges/get-edges-from sid-dead))))
        (is (= 4 (count (edges/get-edges-from sid-live))))))))

(deftest cleanup-dry-run-demote-test
  (testing "Dry-run with :demote action does not change confidence"
    (let [targets (repeatedly 3 raw-id)
          sid     (wire-synth! targets {:confidence 0.9})]
      (set-live! [])
      (synthetics/cleanup-synthetics! {:dry-run? true :action :demote})
      (doseq [e (edges/get-edges-from sid)]
        (is (== 0.9 (:kg-edge/confidence e))
            "confidence unchanged in dry-run")))))

;; =============================================================================
;; :limit bounds per cycle
;; =============================================================================

(deftest cleanup-respects-limit-test
  (testing "Only first `limit` synthetics are evaluated"
    (doseq [_ (range 10)]
      (wire-synth! (repeatedly 3 raw-id)))
    (set-live! []) ;; all dead
    (let [result (synthetics/cleanup-synthetics! {:limit 4})]
      (is (= 4 (:scanned result)))
      (is (= 4 (:pruned result)))
      ;; Remaining 6 synthetics still exist
      (is (= 6 (count (synthetics/list-synthetic-source-nodes)))))))

;; =============================================================================
;; :threshold is tunable
;; =============================================================================

(deftest cleanup-custom-threshold-test
  (testing "Higher threshold prunes more aggressively"
    (let [targets (repeatedly 5 raw-id)
          live    (take 3 targets) ;; ratio 0.6
          sid     (wire-synth! targets)]
      (set-live! live)
      ;; Default threshold 0.2 → preserved
      (let [r1 (synthetics/cleanup-synthetics! {:dry-run? true})]
        (is (= 1 (:preserved r1))))
      ;; Threshold 0.8 → pruned (0.6 < 0.8)
      (let [r2 (synthetics/cleanup-synthetics! {:threshold 0.8})]
        (is (= 1 (:pruned r2)))
        (is (empty? (edges/get-edges-from sid)))))))

;; =============================================================================
;; :details payload
;; =============================================================================

(deftest cleanup-details-report-test
  (testing "Details include per-synth stats"
    (let [targets (repeatedly 4 raw-id)
          live    (take 2 targets)
          sid     (wire-synth! targets)]
      (set-live! live)
      (let [result (synthetics/cleanup-synthetics! {:dry-run? true})
            d (first (:details result))]
        (is (= sid (:synth-id d)))
        (is (= 4 (:edge-count d)))
        (is (= 4 (:target-count d)))
        (is (= 2 (:live-count d)))
        (is (== 0.5 (:live-ratio d)))
        (is (= 2 (:min-live d)) "the member floor is reported with the stats")
        (is (= :preserved (:outcome d)) "two live members at ratio 0.5 is a cluster")
        (is (true? (:dry-run? d)))))))

;; =============================================================================
;; Mixed batch
;; =============================================================================

(deftest cleanup-mixed-batch-test
  (testing "Mix of dead / alive / partial synthetics"
    (let [;; Dead
          dead-tgts (repeatedly 3 raw-id)
          s-dead (wire-synth! dead-tgts)
          ;; Live
          live-tgts (repeatedly 3 raw-id)
          s-live (wire-synth! live-tgts)
          ;; Partial: 2/3 live clears both the member floor and the ratio
          mixed (repeatedly 3 raw-id)
          s-mixed (wire-synth! mixed)
          mixed-live (take 2 mixed)]
      (set-live! (concat live-tgts mixed-live))
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 3 (:scanned result)))
        (is (= 1 (:pruned result)))
        (is (= 2 (:preserved result)))
        (is (empty? (edges/get-edges-from s-dead)))
        (is (seq (edges/get-edges-from s-live)))
        (is (seq (edges/get-edges-from s-mixed)))))))

;; =============================================================================
;; Idempotency
;; =============================================================================

(deftest cleanup-idempotent-test
  (testing "Running cleanup twice is idempotent"
    (let [targets (repeatedly 3 raw-id)
          _sid (wire-synth! targets)]
      (set-live! [])
      (let [r1 (synthetics/cleanup-synthetics!)]
        (is (= 1 (:pruned r1))))
      (let [r2 (synthetics/cleanup-synthetics!)]
        (is (= 0 (:scanned r2)))
        (is (= 0 (:pruned r2)))))))

;; =============================================================================
;; MCP Handler Smoke
;; =============================================================================

(deftest handler-smoke-test
  (testing "MCP handler returns JSON-shaped response"
    (let [targets (repeatedly 3 raw-id)
          _ (wire-synth! targets)
          _ (set-live! [])
          result (synthetics/handle-kg-cleanup-synthetics
                  {:dry_run true :action "delete"})]
      (is (map? result))
      (is (= "text" (:type result)) "mcp response has :type 'text'")
      (is (string? (:text result)) "mcp response has :text string")
      (is (not (:isError result)) "success path is not flagged :isError"))))

(deftest handler-rejects-bad-action-test
  (testing "Handler rejects invalid action"
    (let [result (synthetics/handle-kg-cleanup-synthetics
                  {:action "bogus"})]
      (is (:isError result)))))

(deftest handler-rejects-bad-threshold-test
  (testing "Handler rejects threshold outside [0,1]"
    (let [result (synthetics/handle-kg-cleanup-synthetics
                  {:threshold 1.5})]
      (is (:isError result)))))

(deftest handler-rejects-negative-limit-test
  (testing "Handler rejects negative limit"
    (let [result (synthetics/handle-kg-cleanup-synthetics
                  {:limit -3})]
      (is (:isError result)))))

;; =============================================================================
;; No memory store available (defensive)
;; =============================================================================

(deftest cleanup-without-memory-store-test
  (testing "When no memory store is registered, cleanup refuses to classify
            rather than treating every target as dead"
    ;; Temporarily remove the stub
    (mem-proto/reset-registry!)
    (let [targets (repeatedly 3 raw-id)
          sid (wire-synth! targets)]
      (let [result (synthetics/cleanup-synthetics!)]
        (is (= 0 (:scanned result)))
        (is (= 0 (:pruned result)))
        (is (= 1 (:errors result)))
        (is (string? (:error result)))
        (is (= 3 (count (edges/get-edges-from sid))) "nothing is mutated on refusal")))))
