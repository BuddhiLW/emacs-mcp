(ns hive-mcp.recall.canary-live-test
  "The canary's RUNTIME arm, driven end to end against a writable store double.

   Proves the three things the pure tests cannot: the fixtures are created once
   and then re-found by tag, the probes read through the production search
   handler, and an absent provider degrades to a visible skip instead of a
   silent pass."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.chroma.search :as chroma-search]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.recall.canary.live :as cl]
            [hive-mcp.recall.golden :as g]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.search :as search]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; A store double that can actually be written to and tag-queried
;; =============================================================================

(defn- matches-tags?
  [entry tags]
  (let [have (set (:tags entry))]
    (every? have tags)))

(defrecord WritableStore [rows]
  mem-proto/IMemoryStore
  (connect!                  [_ _])
  (disconnect!               [_])
  (connected?                [_] true)
  (health-check              [_] {:healthy? true})
  (add-entry!                [_ entry]
    (let [id (or (:id entry) (str "canary-" (count @rows)))]
      (swap! rows conj (assoc entry :id id))
      id))
  (get-entry                 [_ id] (first (filter #(= id (:id %)) @rows)))
  (update-entry!             [_ _ _])
  (delete-entry!             [_ _])
  (query-entries             [_ opts]
    (let [{:keys [tags limit]} opts]
      (->> @rows
           (filter #(or (empty? tags) (matches-tags? % tags)))
           (take (or limit 100))
           vec)))
  (search-similar            [_ q opts]
    (let [qt (g/tokens q)]
      (->> @rows
           (map #(assoc % :similarity (g/cosine qt (g/tokens (:content %)))))
           (filter #(pos? (:similarity %)))
           (map #(assoc % :distance (max 0.0 (- 1.0 (:similarity %)))))
           (sort-by :distance <)
           (take (or (:limit opts) 10))
           vec)))
  (supports-semantic-search? [_] true)
  (cleanup-expired!          [_])
  (entries-expiring-soon     [_ _ _])
  (find-duplicate            [_ _ _ _])
  (store-status              [_] {:ok true :count (count @rows)})
  (reset-store!              [_] (reset! rows [])))

(defn- ->writable-store [] (->WritableStore (atom [])))

(defn- run-with-stubs [f]
  (with-redefs [kg-edges/record-co-access!           (constantly nil)
                kg-scope/visible-scopes              (constantly ["hive"])
                kg-scope/descendant-scopes           (constantly [])
                scope/get-current-project-id         (constantly "hive")
                ctx/current-directory                (constantly "/tmp/recall")
                chroma-search/resolve-ingest-search  (constantly nil)]
    (f)))

(defmacro ^:private with-stubs [& body] `(run-with-stubs (fn [] ~@body)))

;; =============================================================================
;; Fixture lifecycle — the canary owns its anchor
;; =============================================================================

(deftest fixtures-are-created-once-and-then-found-by-tag
  (testing "first pass writes the corpus it needs; the second finds it again and
            writes nothing — otherwise every tick would litter the store"
    (let [store (->writable-store)]
      (with-stubs
        (g/with-store store
          (let [first-pass  (cl/ensure-fixtures! store)
                second-pass (cl/ensure-fixtures! store)]
            (is (= #{:anchor :superseded :current} (set (:created first-pass))))
            (is (empty? (:created second-pass))
                "the second pass re-created fixtures — discovery by tag is broken")
            (is (= (select-keys first-pass [:anchor :superseded :current])
                   (select-keys second-pass [:anchor :superseded :current]))
                "the ids moved between passes")
            (is (every? some? (vals (select-keys first-pass [:anchor :superseded :current])))))))))) 

(deftest a-deleted-fixture-is-rewritten-not-mourned
  (testing "the failure that disarmed the old canary: someone deletes the
            anchor. The canary must re-create it, not fail forever."
    (let [store (->writable-store)]
      (with-stubs
        (g/with-store store
          (cl/ensure-fixtures! store)
          (reset! (:rows store) [])
          (let [again (cl/ensure-fixtures! store)]
            (is (= #{:anchor :superseded :current} (set (:created again))))))))))

;; =============================================================================
;; The pass, end to end
;; =============================================================================

(deftest the-anchor-round-trips-through-the-production-handler
  (testing "fixtures written, anchor retrieved through the real search handler,
            ordering intact"
    (let [store (->writable-store)]
      (with-stubs
        (g/with-store store
          (let [v (cl/run! {:carto? false})
                faults (set (map :fault (:faults v)))]
            (is (not (contains? faults :recall/anchor-missing))
                (str "the anchor did not come back: " (:faults v)))
            (is (not (contains? faults :recall/empty-from-populated-store))
                (str "a populated store returned nothing: " (:faults v)))
            (is (not (contains? faults :recall/rank-inverted)))))))))

(deftest suppression-is-NOT-a-property-of-the-store
  (testing "a bare IMemoryStore has no supersede-suppression — that lives ABOVE
            it, at the exit of the retrieval path. Driving the canary against a
            raw store double must therefore FIRE :recall/superseded-returned.

            This is the probe's own wiring proof: it reads the real retrieval
            path, so whatever that path does or fails to do shows up here."
    (let [store (->writable-store)]
      (with-stubs
        (g/with-store store
          (let [v (cl/run! {:carto? false})
                faults (set (map :fault (:faults v)))]
            (is (contains? faults :recall/superseded-returned)
                (str "the retracted fixture was suppressed by a store that has "
                     "no suppression — the probe is not reading the real path: "
                     (:faults v)))))))))

(deftest an-absent-store-skips-loudly-and-never-passes
  (testing "no store registered: the retrieval probes must report as SKIPPED
            with a reason, and must not be counted as passing"
    (with-redefs [mem-proto/store-set? (constantly false)]
      (let [v (cl/run! {:carto? false})
            labels (set (map :label (:skipped v)))]
        (is (contains? labels :lexical-anchor))
        (is (contains? labels :supersession))
        (is (every? #(seq (:reason %)) (:skipped v))
            "a skip without a reason is indistinguishable from a pass")))))

(deftest carto-probes-skip-when-carto-is-absent
  (testing "hive-carto is an addon; its probes must degrade to a named skip"
    (let [out (cl/probe-carto-tag "hive-mcp")]
      (is (contains? #{:ok :skipped :fault} (:status out)))
      (when (= :skipped (:status out))
        (is (= "hive-carto not loaded" (:reason out)))))))

;; =============================================================================
;; The deliberate break — a canary that cannot fail proves nothing
;; =============================================================================

(deftest the-canary-fires-when-retrieval-goes-blind
  (testing "a store that answers every query with nothing must produce a fault,
            not a green tick"
    (let [store (->writable-store)]
      (with-stubs
        (g/with-store store
          (cl/ensure-fixtures! store)
          (with-redefs [search/handle-search-semantic
                        (constantly {:type "text" :text "{\"results\":[]}"})]
            (let [v (cl/run! {:carto? false})
                  faults (set (map :fault (:faults v)))]
              (is (false? (:ok? v)) "blind retrieval reported OK")
              (is (contains? faults :recall/empty-from-populated-store)
                  (str "expected the outage's own fault shape, got " faults)))))))))

(deftest the-canary-fires-when-a-retracted-row-comes-back
  (testing "suppression regression: the superseded fixture is served again"
    (let [store (->writable-store)]
      (with-stubs
        (g/with-store store
          (let [ids (cl/ensure-fixtures! store)
                payload (str "{\"results\":[{\"id\":\"" (:current ids) "\"},"
                             "{\"id\":\"" (:superseded ids) "\"}]}")]
            (with-redefs [search/handle-search-semantic
                          (constantly {:type "text" :text payload})]
              (let [out (cl/probe-supersession (assoc ids :linked? true))]
                (is (= :fault (:status out)))
                (is (= :recall/superseded-returned (get-in out [:fault :fault])))))))))))
