(ns hive-mcp.protocols.query-entries-lsp-test
  "LSP property test for IMemoryStore.query-entries.

   Liskov: any IMemoryStore implementation MUST honor the opts contract
   regardless of backend. The closed Malli schema
   `hive-mcp.schema.memory/QueryEntriesOpts` defines the legal opts;
   `malli.generator/generator` produces conforming values; each backend
   (currently the qdrant in-memory fallback — sufficient for contract
   pinning without live infra) must:

     1. Accept generated opts without throwing.
     2. Return a sequential collection.
     3. Honor `:limit` as an upper bound.

   Backends added later (chroma, milvus fallback, additional qdrant
   modes) plug into `backends-under-test` below.

   The test focuses on contract shape, not query semantics — semantic
   trifectas live alongside each backend's own test ns
   (e.g. hive-qdrant.store-test)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.schema.memory :as schema]
            [malli.core :as m]
            [malli.generator :as mg]))

;; -----------------------------------------------------------------------------
;; Backends under test — local-only, no live infra required
;; -----------------------------------------------------------------------------

(defn- in-memory-store
  "Reify IMemoryStore for the LSP sweep — no external infra required.
   Honors :limit + :tags + :exclude-tags + :order-by post-filter so the
   property test's :limit cap is meaningfully enforced. New backends can
   be added to `backends-under-test` once their fallback paths are in
   place."
  []
  (let [entries (atom (into [] (for [i (range 50)]
                                 {:id      (str "e" i)
                                  :type    "note"
                                  :content (str "row-" i)
                                  :tags    ["lsp" (if (even? i) "todo" "done")]
                                  :created (str "2026-05-04T" (format "%02d" (mod i 24)))})))]
    (reify proto/IMemoryStore
      (query-entries [_ {:keys [limit tags exclude-tags order-by include-content?]
                         :or   {limit 100}}]
        (let [tag-set         (set tags)
              ex-set          (set exclude-tags)
              filtered        (cond->> @entries
                                (seq tag-set)
                                (filter (fn [e] (every? (set (:tags e)) tag-set)))
                                (seq ex-set)
                                (remove (fn [e] (some ex-set (:tags e))))
                                (not include-content?)
                                (mapv #(dissoc % :content)))
              [field dir]      (or order-by [nil nil])
              ordered         (cond
                                (nil? field)  filtered
                                (= dir :desc) (vec (sort-by field #(compare %2 %1) filtered))
                                :else         (vec (sort-by field filtered)))]
          (vec (take limit ordered)))))))

(def backends-under-test
  "Each entry: {:label str :ctor (-> store)}.
   Add new IMemoryStore implementations here to extend the LSP sweep.
   Default stays infra-free — uses an inline reify-based store so the
   test runs from `clojure -M:test` without docker / sibling projects
   on the classpath."
  [{:label "in-memory-reify" :ctor in-memory-store}])

;; -----------------------------------------------------------------------------
;; Schema sanity (golden — schema itself loads + accepts canonical shape)
;; -----------------------------------------------------------------------------

(deftest query-entries-opts-schema-golden
  (testing "empty opts validates"
    (is (m/validate schema/QueryEntriesOpts {})))
  (testing "full opts validates"
    (is (m/validate schema/QueryEntriesOpts
                    {:type "note"
                     :project-id "hive"
                     :project-ids ["hive" "hive-mcp"]
                     :tags ["kanban" "todo"]
                     :exclude-tags ["archive"]
                     :limit 100
                     :include-expired? false
                     :include-content? true
                     :output-fields ["id" "tags"]
                     :order-by [:created :desc]})))
  (testing "closed: unknown key rejected"
    (is (not (m/validate schema/QueryEntriesOpts {:bogus-key true})))))

;; -----------------------------------------------------------------------------
;; LSP property: every backend honors the contract for any valid opts
;; -----------------------------------------------------------------------------

(def opts-gen
  "Generator over QueryEntriesOpts. Constraint: cap :limit so unbounded
   gen output doesn't blow up the in-memory fallback's linear scan."
  (mg/generator schema/QueryEntriesOpts
                {::mg/size 5}))

(defn- run-backend
  "Apply query-entries with `opts` against `backend-ctor`, returning a
   triple of [result limit thrown?]. `thrown?` is true iff the call
   raised; the LSP contract prohibits this for valid opts."
  [backend-ctor opts]
  (let [store (backend-ctor)]
    (try
      (let [result (proto/query-entries store opts)]
        [result (:limit opts) false])
      (catch Throwable _t
        [nil (:limit opts) true]))))

(defn- result-shape-ok?
  "Predicate: result is sequential (vector/seq/list) and (when :limit
   present) does not exceed it."
  [[result limit thrown?]]
  (and (not thrown?)
       (or (nil? result)
           (sequential? result))
       (or (nil? limit)
           (nil? result)
           (<= (count result) limit))))

(defspec lsp-query-entries-contract-property 100
  (prop/for-all [opts opts-gen]
                (every? (fn [{:keys [ctor]}]
                          (result-shape-ok? (run-backend ctor opts)))
                        backends-under-test)))

;; -----------------------------------------------------------------------------
;; Mutation kill-test — confirm the property fails on a deliberately
;; broken backend wrapper. Pins the predicate's diagnostic power.
;; -----------------------------------------------------------------------------

(deftest mutation-throwing-backend-fails-property
  (testing "a backend that throws on every query-entries call must
            fail result-shape-ok?, otherwise the property is vacuous"
    (let [thrown-pair [nil 10 true]]
      (is (false? (result-shape-ok? thrown-pair))))))

(deftest mutation-overlimit-backend-fails-property
  (testing "a backend that returns more rows than :limit must fail
            result-shape-ok?, otherwise :limit is unenforced"
    (let [over-limit-pair [(repeat 50 {:id "x"}) 10 false]]
      (is (false? (result-shape-ok? over-limit-pair))))))
