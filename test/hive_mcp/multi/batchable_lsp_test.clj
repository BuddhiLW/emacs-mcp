(ns hive-mcp.multi.batchable-lsp-test
  "Liskov-substitutability property tests for the three explicit Batchable
   records (MemoryBatchable / KgBatchable / KanbanBatchable).

   The LSP guarantee: an explicit Batchable substitutes for DefaultBatchableAdapter
   without changing the external observable contract. Specifically:

   - `:success` is a boolean
   - `:waves` has shape `{wave-num {:ops [...] :results [...]}}`
   - `:summary` has `{:total :success :failed :waves}` ints
   - per-op result has `:id` and `:success`
   - count of results matches count of ops
   - never throws (Batchable contract clause 2)

   We do NOT compare exact `:result` payloads — those legitimately differ
   between implementations (a single-tx record may include richer per-op data
   than the per-op iterator). LSP requires SHAPE substitution, not value
   equality.

   Decision: 20260429230453-7e7627cc"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.batch.protocol :as bproto]
            [hive-mcp.multi.batchables :as bx]
            [hive-mcp.multi.batchable-adapter :as adapter]
            [hive-mcp.multi.registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Generators
;; =============================================================================

(defn- mk-mem-add-op [id]
  {:id id :tool "memory" :command "add"
   :type "note" :content (str "content-" id)
   :tags ["lsp-test" "ephemeral"]
   :duration "ephemeral"})

(defn- mk-mem-get-op [id]
  {:id id :tool "memory" :command "get"
   :ids [(str "ent-" id)]})

(defn- mk-kg-edge-op [id]
  {:id id :tool "kg" :command "edge"
   :from (str "node-" id "-a") :to (str "node-" id "-b")
   :relation "depends-on"})

(defn- mk-kanban-update-op [id]
  {:id id :tool "kanban" :command "update"
   :task_id (str "task-" id) :new_status "todo"})

(defn- with-unique-ids
  "Replace each op's :id with `\"op-<index>\"` to keep IDs unique across the
   generated vector. Underlying batch handlers (correctly) merge ops with the
   same :id, which would shrink the per-op result vector and break the
   shape predicate's `(= (count ops) (count results))` invariant."
  [ops]
  (mapv (fn [i op] (assoc op :id (str "op-" i))) (range) ops))

(def gen-memory-ops
  (gen/fmap with-unique-ids
    (gen/vector (gen/one-of [(gen/fmap mk-mem-add-op (gen/return ""))
                              (gen/fmap mk-mem-get-op (gen/return ""))])
                1 5)))

(def gen-kg-ops
  (gen/fmap with-unique-ids
    (gen/vector (gen/fmap mk-kg-edge-op (gen/return "")) 1 4)))

(def gen-kanban-ops
  (gen/fmap with-unique-ids
    (gen/vector (gen/fmap mk-kanban-update-op (gen/return "")) 1 4)))

;; =============================================================================
;; Shape predicates
;; =============================================================================

(defn- batchable-shape-ok?
  "Validate the LSP-required external shape of a batch-execute result."
  [ops result]
  (and (map? result)
       (boolean? (:success result))
       (map? (:waves result))
       (map? (:summary result))
       (every? #(contains? (:summary result) %) [:total :success :failed :waves])
       (every? int? [(get-in result [:summary :total])
                     (get-in result [:summary :success])
                     (get-in result [:summary :failed])
                     (get-in result [:summary :waves])])
       (let [all-results (mapcat :results (vals (:waves result)))]
         (and (= (count ops) (count all-results))
              (every? #(contains? % :id) all-results)
              (every? #(contains? % :success) all-results)))))

(defn- never-throws?
  "Run batch-execute under try/catch; return true iff it returns a map without
   throwing. Per Batchable contract clause 2."
  [batchable ops]
  (try
    (let [r (bproto/batch-execute batchable ops {})]
      (map? r))
    (catch Throwable _ false)))

;; =============================================================================
;; LSP property — explicit Batchable result-shape ≡ DefaultBatchableAdapter shape
;; =============================================================================

(defspec memory-batchable-lsp-shape 20
  (prop/for-all [ops gen-memory-ops]
    (let [explicit (bx/memory-batchable)
          default  (adapter/make-default-adapter
                    "memory" registry/resolve-tool-handler)]
      (and (never-throws? explicit ops)
           (never-throws? default ops)
           (batchable-shape-ok? ops (bproto/batch-execute explicit ops {}))
           ;; Default adapter may legitimately return :success false when the
           ;; underlying handler isn't reachable; we still require shape compliance.
           (batchable-shape-ok? ops (bproto/batch-execute default ops {}))))))

(defspec kg-batchable-lsp-shape 20
  (prop/for-all [ops gen-kg-ops]
    (let [explicit (bx/kg-batchable)
          default  (adapter/make-default-adapter
                    "kg" registry/resolve-tool-handler)]
      (and (never-throws? explicit ops)
           (never-throws? default ops)
           (batchable-shape-ok? ops (bproto/batch-execute explicit ops {}))
           (batchable-shape-ok? ops (bproto/batch-execute default ops {}))))))

(defspec kanban-batchable-lsp-shape 20
  (prop/for-all [ops gen-kanban-ops]
    (let [explicit (bx/kanban-batchable)
          default  (adapter/make-default-adapter
                    "kanban" registry/resolve-tool-handler)]
      (and (never-throws? explicit ops)
           (never-throws? default ops)
           (batchable-shape-ok? ops (bproto/batch-execute explicit ops {}))
           (batchable-shape-ok? ops (bproto/batch-execute default ops {}))))))

;; =============================================================================
;; Unit tests — concrete edge cases
;; =============================================================================

(deftest empty-ops-returns-shape-compliant
  (testing "Each Batchable handles an empty op vector without throwing"
    (doseq [[name bxr] [["MemoryBatchable" (bx/memory-batchable)]
                        ["KgBatchable"     (bx/kg-batchable)]
                        ["KanbanBatchable" (bx/kanban-batchable)]]]
      (let [r (bproto/batch-execute bxr [] {})]
        (is (map? r) name)
        (is (boolean? (:success r)) name)
        (is (= 0 (get-in r [:summary :total])) name)))))

(deftest unknown-command-error-isolated-per-op
  (testing "Ops with unknown command surface as :success false on that op,
            without crashing the whole batch"
    (let [bxr (bx/memory-batchable)
          ops [{:id "ok-1" :tool "memory" :command "add"
                :type "note" :content "test"}
               {:id "bad-1" :tool "memory" :command "totally-fake-cmd"}]
          r (bproto/batch-execute bxr ops {})]
      (is (map? r) "still returns a map")
      (is (= 2 (get-in r [:summary :total])) "all ops accounted for")
      (let [results (mapcat :results (vals (:waves r)))]
        (is (= 2 (count results)))
        ;; The bad-cmd op must be marked failed
        (is (some #(and (= "bad-1" (:id %)) (false? (:success %))) results))))))

(deftest batch-schema-returns-jsonschema-shape
  (testing "Each Batchable's batch-schema returns a valid JSONSchema fragment"
    (doseq [bxr [(bx/memory-batchable) (bx/kg-batchable) (bx/kanban-batchable)]]
      (let [s (bproto/batch-schema bxr)]
        (is (map? s))
        (is (= "object" (:type s)))
        (is (map? (:properties s)))))))
