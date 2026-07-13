(ns hive-mcp.knowledge-graph.store.contract
  "Backend-agnostic IKGStore contract suite: ONE suite, N drivers.

   `contract-cases` is the shared IKGStore behaviour — the behaviour EVERY
   backend must satisfy — expressed as DATA. Each case is a map
   {:id :desc :check}; `:check` is a pure assertion fn that receives the
   installed store and runs clojure.test assertions against it.

   `run-contract!` applies the whole suite to one driver, identified by its
   `harness/StoreFactory`. Every case gets its OWN fresh, disposable store, so
   cases cannot contaminate each other and count/keyset invariants are exact.

   Driver-free by construction. This ns names no concrete backend: it requires
   only the IKGStore facade, the connection layer and the harness ports, so it
   loads on the default (driver-free) test path. Drivers arrive as a
   StoreFactory ARGUMENT — datalevin/datahike applications live under
   test-backends/ and pass their own factory.

   Behaviour that is NOT identical across backends (reset-conn! semantics,
   temporal history/as-of, on-disk delete guards, linearizability) is
   deliberately absent: it belongs in the per-backend namespaces."
  (:require [clojure.test :refer [is testing]]
            [hive-mcp.knowledge-graph.disc :as disc]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.harness :as harness]
            [hive-mcp.knowledge-graph.connection.store :as cstore]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures for the cases (pure data builders)
;; =============================================================================

(defn- gen-node-id []
  (str "test-node-" (subs (str (random-uuid)) 0 8)))

(defn- gen-path []
  (str "/test/path/" (subs (str (random-uuid)) 0 8) ".clj"))

(defn- edge-map
  "A valid KG edge. Overrides are merged over the canonical shape."
  [& {:as overrides}]
  (merge {:kg-edge/id         (str "test-edge-" (random-uuid))
          :kg-edge/from       "node-a"
          :kg-edge/to         "node-b"
          :kg-edge/relation   :implements
          :kg-edge/confidence 1.0}
         overrides))

;; =============================================================================
;; The contract, as data
;; =============================================================================

(def contract-cases
  "The IKGStore behaviour every backend must satisfy, as data.

   Each :check receives the store INSTALLED for that case (fresh per case) and
   asserts with clojure.test/is. Cases divide into the store protocol itself
   and the connection-layer facades (edges/disc) that ride on top of it."
  [;; --- core protocol operations ---------------------------------------------
   {:id    :ensure-conn
    :desc  "ensure-conn! returns a non-nil connection"
    :check (fn [store]
             (is (some? (proto/ensure-conn! store))))}

   {:id    :transact-query-roundtrip
    :desc  "transact! + query roundtrip"
    :check (fn [store]
             (let [edge-id (str "test-edge-" (random-uuid))]
               (proto/transact! store [(edge-map :kg-edge/id edge-id)])
               (is (= #{["node-a" "node-b"]}
                      (set (proto/query store
                                        '[:find ?from ?to
                                          :in $ ?eid
                                          :where
                                          [?e :kg-edge/id ?eid]
                                          [?e :kg-edge/from ?from]
                                          [?e :kg-edge/to ?to]]
                                        [edge-id]))))))}

   {:id    :entid-resolves-lookup-ref
    :desc  "entid resolves a lookup-ref to a numeric eid"
    :check (fn [store]
             (let [edge-id (str "test-edge-" (random-uuid))]
               (proto/transact! store [(edge-map :kg-edge/id edge-id)])
               (let [eid (proto/entid store [:kg-edge/id edge-id])]
                 (is (some? eid))
                 (is (number? eid)))))}

   {:id    :pull-entity
    :desc  "pull-entity returns the full entity"
    :check (fn [store]
             (let [edge-id (str "test-edge-" (random-uuid))]
               (proto/transact! store [(edge-map :kg-edge/id edge-id
                                                 :kg-edge/relation :refines
                                                 :kg-edge/confidence 0.7)])
               (let [eid    (proto/entid store [:kg-edge/id edge-id])
                     pulled (proto/pull-entity store '[*] eid)]
                 (is (= edge-id (:kg-edge/id pulled)))
                 (is (= "node-a" (:kg-edge/from pulled)))
                 (is (= :refines (:kg-edge/relation pulled)))
                 (is (= 0.7 (:kg-edge/confidence pulled))))))}

   {:id    :db-snapshot
    :desc  "db-snapshot returns an immutable value"
    :check (fn [store]
             (is (some? (proto/db-snapshot store))))}

   ;; --- edges via the connection layer ---------------------------------------
   {:id    :edges-add-and-get
    :desc  "edges: add then get"
    :check (fn [_store]
             (let [from    (gen-node-id)
                   to      (gen-node-id)
                   edge-id (edges/add-edge! {:from from :to to :relation :implements})]
               (is (string? edge-id))
               (let [edge (edges/get-edge edge-id)]
                 (is (some? edge))
                 (is (= from (:kg-edge/from edge)))
                 (is (= to (:kg-edge/to edge)))
                 (is (= :implements (:kg-edge/relation edge))))))}

   {:id    :edges-query-from-to
    :desc  "edges: query by from / to"
    :check (fn [_store]
             (let [src  (gen-node-id)
                   tgt1 (gen-node-id)
                   tgt2 (gen-node-id)]
               (edges/add-edge! {:from src :to tgt1 :relation :implements})
               (edges/add-edge! {:from src :to tgt2 :relation :supersedes})
               (is (= 2 (count (edges/get-edges-from src))))
               (is (= 1 (count (edges/get-edges-to tgt1))))))}

   {:id    :edges-confidence-update
    :desc  "edges: confidence update"
    :check (fn [_store]
             (let [edge-id (edges/add-edge! {:from (gen-node-id)
                                             :to (gen-node-id)
                                             :relation :implements
                                             :confidence 0.5})]
               (edges/update-edge-confidence! edge-id 0.9)
               (is (= 0.9 (:kg-edge/confidence (edges/get-edge edge-id))))))}

   {:id    :edges-remove
    :desc  "edges: remove retracts the edge"
    :check (fn [_store]
             (let [edge-id (edges/add-edge! {:from (gen-node-id)
                                             :to (gen-node-id)
                                             :relation :implements})]
               (edges/remove-edge! edge-id)
               (is (nil? (edges/get-edge edge-id)))))}

   {:id    :edges-all-relations
    :desc  "edges: every relation kind in the schema is accepted"
    :check (fn [_store]
             (doseq [rel (schema/relation-types)]
               (let [edge-id (edges/add-edge! {:from (gen-node-id)
                                               :to (gen-node-id)
                                               :relation rel})]
                 (is (string? edge-id) (str "Failed for relation: " rel)))))}

   {:id    :edges-by-relation
    :desc  "edges: filter by relation"
    :check (fn [_store]
             (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
             (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :implements})
             (edges/add-edge! {:from (gen-node-id) :to (gen-node-id) :relation :supersedes})
             (is (= 2 (count (edges/get-edges-by-relation :implements))))
             (is (= 1 (count (edges/get-edges-by-relation :supersedes)))))}

   {:id    :edge-stats
    :desc  "edges: stats count totals, relations and scopes"
    :check (fn [_store]
             (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                               :relation :implements :scope "proj-a"})
             (edges/add-edge! {:from (gen-node-id) :to (gen-node-id)
                               :relation :supersedes :scope "proj-b"})
             (let [stats (edges/edge-stats)]
               (is (= 2 (:total-edges stats)))
               (is (= 1 (get-in stats [:by-relation :implements])))
               (is (= 1 (get-in stats [:by-scope "proj-a"])))))}

   ;; --- disc via the connection layer ----------------------------------------
   {:id    :disc-add-and-get
    :desc  "disc: add then get"
    :check (fn [_store]
             (let [path (gen-path)]
               (disc/add-disc! {:path path :content-hash "abc123"})
               (is (disc/disc-exists? path))
               (let [d (disc/get-disc path)]
                 (is (= path (:disc/path d)))
                 (is (= "abc123" (:disc/content-hash d))))))}

   {:id    :disc-update
    :desc  "disc: update replaces an attribute"
    :check (fn [_store]
             (let [path (gen-path)]
               (disc/add-disc! {:path path :content-hash "old-hash"})
               (disc/update-disc! path {:disc/content-hash "new-hash"})
               (is (= "new-hash" (:disc/content-hash (disc/get-disc path))))))}

   {:id    :disc-remove
    :desc  "disc: remove retracts the disc"
    :check (fn [_store]
             (let [path (gen-path)]
               (disc/add-disc! {:path path :content-hash "test"})
               (is (true? (disc/remove-disc! path)))
               (is (not (disc/disc-exists? path)))))}

   {:id    :disc-all-and-stats
    :desc  "disc: get-all + project filter + stats"
    :check (fn [_store]
             (disc/add-disc! {:path (gen-path) :content-hash "h1" :project-id "proj-a"})
             (disc/add-disc! {:path (gen-path) :content-hash "h2" :project-id "proj-b"})
             (is (= 2 (count (disc/get-all-discs))))
             (is (= 1 (count (disc/get-all-discs :project-id "proj-a"))))
             (is (= 2 (:total (disc/disc-stats)))))}])

(defn default-isolation
  "The IsolationStrategy every backend runs the contract under.

   GLOBAL installation (save + restore), not a thread-local override: the disc
   facade resolves its store from the global slot and does NOT consult
   `*test-store*`, so a thread-local override would leave it with no store at
   all. SYNCHRONOUS writes: the edges/disc facades write through the coalescing
   writer, whose pool thread would otherwise land the write after the case has
   already read — sync writes give deterministic read-after-write on every
   backend."
  []
  (harness/global-isolation :sync-writes? true))

;; =============================================================================
;; The runner — applies the contract to one driver
;; =============================================================================

(defn run-contract!
  "Run EVERY contract case against `factory`, each case in its own fresh,
   disposable store installed by `strategy`.

   The store handed to a case is resolved through the connection layer's
   OVERRIDE-AWARE accessor, so it is the ephemeral store under BOTH isolation
   strategies (a thread-local *test-store* binding, or a globally installed
   store) — and it is the same store the edges/disc facades resolve.

   Assertions report into the CALLING deftest; a case's `testing` context names
   the behaviour that failed. Isolation is per-case, so the exact-count
   invariants (edge-stats, disc-all-and-stats) hold."
  [factory strategy]
  (doseq [{:keys [desc check]} contract-cases]
    (testing desc
      (harness/with-disposable-store factory strategy
        (fn [] (check (cstore/ensure-store!)))))))

(defn kg-store-contract-tests
  "Apply the IKGStore contract to ONE driver, identified by its StoreFactory.

   Availability-gated: when the backend's driver is not on the classpath the
   suite is SKIPPED — a notice is printed and no assertion runs, so the test
   passes vacuously and a driver-free classpath stays green.

   opts:
     :strategy — IsolationStrategy used to install each ephemeral store.
                 Defaults to `default-isolation`, which every backend uses.
     :label    — backend name used in the testing context and skip notice."
  [factory & {:keys [strategy label] :or {label "backend"}}]
  (if-not (harness/available? factory)
    (println (str "[kg-store-contract] " label
                  " driver unavailable on this classpath — skipping contract suite."))
    (testing (str "IKGStore contract [" label "]")
      (run-contract! factory (or strategy (default-isolation))))))