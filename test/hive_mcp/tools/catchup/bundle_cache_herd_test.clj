(ns hive-mcp.tools.catchup.bundle-cache-herd-test
  "The catchup bundle over a real store port: N pulls for one project cost
   one computation, a second pull costs nothing, and a memory write event
   makes the next pull recompute. Driver-free: StubMemoryStore behind the
   observing decorator."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-spi.memory.registry :as registry]
            [hive-mcp.tools.catchup.bundle :as bundle]
            [hive-mcp.tools.catchup.bundle-cache :as bc]
            [hive-mcp.tools.catchup.hierarchy :as hier]
            [hive-mcp.tools.catchup.scope-filter :as sf]
            [hive-mcp.memory.write-events :as we]))

(def ^:private pid "herd-p")
(def ^:private scope-tag (str "scope:project:" pid))

(defn- seed []
  [{:id "ax1" :type "axiom" :tags ["scope:global"] :content "axiom one"
    :project-id "global" :created "2026-08-01T00:00:00Z"}
   {:id "d1" :type "decision" :tags [scope-tag] :content "decision one"
    :project-id pid :created "2026-08-02T00:00:00Z"}
   {:id "c1" :type "convention" :tags [scope-tag "catchup-priority"] :content "conv one"
    :project-id pid :created "2026-08-03T00:00:00Z"}])

(defn- wait-until
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn- with-herd-store
  "Install an observing stub as :default, pin the hierarchy to `pid`, run F
   with the store, restore the prior registry."
  [f]
  (let [prior (registry/registered-stores)
        store (stub/->observing (stub/->stub (seed)))]
    (try
      (registry/set-store! store)
      (bc/reset-cache!)
      (with-redefs [hier/compute-hierarchy-project-ids (fn [_] [pid])
                    sf/compute-full-scope-tags         (fn [_] #{scope-tag})]
        (f store))
      (finally
        (bc/reset-cache!)
        (registry/reset-registry!)
        (doseq [[k s] prior] (registry/register-store! k s))))))

(defn- scans [store] (count (stub/calls-of store :query-entries)))
(defn- reset-calls! [store] (reset! (:calls store) []))

(deftest concurrent-pulls-cost-one-computation-test
  (with-herd-store
    (fn [store]
      (let [single (bundle/query-catchup-bundle pid)
            per-computation (scans store)]
        (is (pos? per-computation))
        (is (= ["ax1"] (mapv :id (:axioms single))))
        (is (= "decision one" (:content (first (:decisions single)))) "content hydrated")
        (is (= ["c1"] (mapv :id (:priority-conventions single))))

        (testing "N concurrent pulls after a cache reset cost exactly one computation"
          (bc/reset-cache!)
          (reset-calls! store)
          (let [bundles (mapv #(deref % 30000 ::timeout)
                              (mapv (fn [_] (future (bundle/query-catchup-bundle pid))) (range 6)))]
            (is (every? #(= single %) bundles))
            (is (= per-computation (scans store)))))

        (testing "a further pull is served from the cache"
          (reset-calls! store)
          (is (= single (bundle/query-catchup-bundle pid)))
          (is (= 0 (scans store))))))))

(deftest write-event-makes-the-next-pull-recompute-test
  (with-herd-store
    (fn [store]
      (bundle/query-catchup-bundle pid)
      (is (bc/subscribed?) "first pull subscribed to the write events")
      (reset-calls! store)
      (we/notify! :added {:id "d2" :memory-type "decision" :project-id pid})
      (is (wait-until #(zero? (:bundles (bc/stats))) 3000)
          "the bundle was dropped by the write event")
      (bundle/query-catchup-bundle pid)
      (is (pos? (scans store)) "recomputed")

      (testing "a kanban write does not disturb the bundle"
        (reset-calls! store)
        (we/notify! :added {:id "k1" :memory-type "kanban" :project-id pid})
        (Thread/sleep 200)
        (bundle/query-catchup-bundle pid)
        (is (= 0 (scans store)))))))

(deftest a-swapped-store-starts-cold-test
  (with-herd-store
    (fn [_store]
      (let [first-bundle (bundle/query-catchup-bundle pid)
            other (stub/->observing (stub/->stub (assoc-in (vec (seed)) [1 :content] "decision two")))]
        (registry/set-store! other)
        (let [second-bundle (bundle/query-catchup-bundle pid)]
          (is (pos? (scans other)) "the new store was scanned")
          (is (= "decision one" (:content (first (:decisions first-bundle)))))
          (is (= "decision two" (:content (first (:decisions second-bundle))))
              "content came from the new store, not the old store's content tier"))))))
