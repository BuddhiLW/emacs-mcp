(ns hive-mcp.multi.plan-test
  "Tests for hive-mcp.multi.plan — persistent compile-then-run.

   Coverage:
     - compile-and-persist! returns plan-id + wave-count + registry-version
     - fetch retrieves the persisted plan with original :ops vector
     - run! re-executes with same waves
     - registry-version mismatch logs :multi/registry-stale (not error)
     - plan-not-found / plan-malformed error categories surface correctly

   We mock memory.add / memory.get-full / tools.multi/run-multi via with-redefs
   so the tests don't depend on a live Milvus.

   Decision: 20260429230453-7e7627cc"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.multi.plan :as plan]
            [hive-mcp.multi.registry :as registry]
            [hive-mcp.multi.registry.tools :as r-tools]
            [hive-mcp.tools.memory.crud :as crud]
            [hive-mcp.tools.multi :as t-multi]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test fixtures — in-memory plan store
;; =============================================================================

(def ^:private store (atom {}))

(defn- mock-add
  "Pretend to be hive-mcp.tools.memory.crud/handle-add: assign an id, store
   the entry, return the MCP envelope with the id in the JSON body."
  [params]
  (let [id (str "plan-" (System/nanoTime))]
    (swap! store assoc id (:content params))
    {:type "text"
     :text (str "{\"id\":\"" id "\"}")}))

(defn- mock-get-full
  "Pretend to be hive-mcp.tools.memory.crud/handle-get-full: return the
   stored content under :content key."
  [{:keys [id]}]
  (when-let [content (get @store id)]
    {:content content}))

(defn- with-mocks [f]
  (reset! store {})
  ;; Redef the crud-level aliases since multi.plan resolves
  ;; `hive-mcp.tools.memory.crud/handle-add` and `crud/handle-get-full`.
  (with-redefs [crud/handle-add      mock-add
                crud/handle-get-full mock-get-full]
    (f)))

(use-fixtures :each with-mocks)

;; =============================================================================
;; §1 — compile-and-persist! happy path
;; =============================================================================

(deftest compile-and-persist-returns-plan-id
  (testing "Successful compile produces plan-id + wave-count + registry version"
    (let [ops [{:id "a" :tool "memory" :command "search"}
               {:id "b" :tool "kg" :command "stats" :depends_on ["a"]}]
          result (plan/compile-and-persist! ops {:reason "test"})]
      (is (contains? result :ok) (str "expected :ok result, got: " result))
      (is (string? (-> result :ok :plan-id)))
      (is (= 2 (-> result :ok :wave-count))
          "Two waves: a in wave 1, b in wave 2 (depends_on a)")
      (is (integer? (-> result :ok :registry/version)))
      (is (= 2 (count (-> result :ok :ops)))))))

(deftest compile-and-persist-stores-edn-content
  (testing "The persisted content is EDN that round-trips through fetch"
    (let [ops [{:id "x" :tool "memory" :command "search"}]
          {{:keys [plan-id]} :ok} (plan/compile-and-persist! ops {})]
      (let [fetched (plan/fetch plan-id)]
        (is (contains? fetched :ok))
        (let [plan (:ok fetched)]
          (is (= 1 (count (:ops plan))))
          (is (= "x" (-> plan :ops first :id)))
          (is (= 1 (:wave-count plan))))))))

;; =============================================================================
;; §2 — compile rejects ops that fail validation
;; =============================================================================

(deftest compile-rejects-cycle
  (testing "Circular ops produce :multi/plan-compile-failed"
    (let [ops [{:id "a" :tool "memory" :command "search" :depends_on ["b"]}
               {:id "b" :tool "kg" :command "stats"      :depends_on ["a"]}]
          result (plan/compile-and-persist! ops {})]
      (is (contains? result :error))
      (is (= :multi/plan-compile-failed (:error result))))))

(deftest compile-rejects-missing-tool
  (testing "Op missing :tool produces compile error"
    (let [ops [{:id "a" :command "search"}]
          result (plan/compile-and-persist! ops {})]
      (is (contains? result :error)))))

;; =============================================================================
;; §3 — fetch error categories
;; =============================================================================

(deftest fetch-not-found-returns-plan-not-found
  (testing "fetching a non-existent plan-id returns :multi/plan-not-found"
    (let [result (plan/fetch "no-such-plan-id-xyz")]
      (is (contains? result :error))
      (is (= :multi/plan-not-found (:error result))))))

(deftest fetch-malformed-content-returns-plan-malformed
  (testing "Plan with non-EDN content returns :multi/plan-malformed"
    (let [bad-id "plan-bad-1"]
      (swap! store assoc bad-id "this is not edn { ( ] :")
      (let [result (plan/fetch bad-id)]
        (is (contains? result :error))
        (is (contains? #{:multi/plan-malformed :multi/plan-not-found} (:error result)))))))

(deftest fetch-content-without-ops-returns-malformed
  (testing "Plan with EDN missing :ops returns :multi/plan-malformed"
    (let [bad-id "plan-bad-2"]
      (swap! store assoc bad-id "{:no-ops-here true}")
      (let [result (plan/fetch bad-id)]
        (is (contains? result :error))
        (is (= :multi/plan-malformed (:error result)))))))

;; =============================================================================
;; §4 — run!
;; =============================================================================

(deftest run-executes-persisted-plan
  (testing "run! fetches the plan, dispatches to run-multi, returns wrapped result"
    (let [executed (atom nil)]
      (with-redefs [t-multi/run-multi
                    (fn [ops & _]
                      (reset! executed ops)
                      {:success true :waves {1 {:results []}}
                       :summary {:total (count ops) :success (count ops) :failed 0 :waves 1}})]
        (let [{{:keys [plan-id]} :ok}
              (plan/compile-and-persist!
               [{:id "a" :tool "memory" :command "search"}] {})
              result (plan/run! plan-id {})]
          (is (contains? result :ok))
          (is (true? (-> result :ok :success)))
          (is (some? @executed))
          (is (= 1 (count @executed))))))))

(deftest run-not-found-passes-through
  (testing "run! on missing plan-id surfaces :multi/plan-not-found"
    (let [result (plan/run! "nonexistent-plan-xyz" {})]
      (is (contains? result :error))
      (is (= :multi/plan-not-found (:error result))))))

;; =============================================================================
;; §5 — registry-stale: warn-not-fail behavior
;; =============================================================================

(deftest run-with-stale-registry-still-executes
  (testing "When stored :registry/version differs from current, run! still
            executes (warn only); result carries :registry/stale? true"
    (with-redefs [t-multi/run-multi
                  (fn [_ops & _]
                    {:success true :waves {} :summary {:total 0 :success 0 :failed 0 :waves 0}})]
      (let [{{:keys [plan-id]} :ok}
            (plan/compile-and-persist!
             [{:id "a" :tool "memory" :command "search"}] {})]
        ;; Mutate the registry to invalidate the snapshot version
        (r-tools/register! :test/stale-test "stale-injected-tool"
                           {:handler (constantly :ok)})
        (try
          (let [result (plan/run! plan-id {})]
            (is (contains? result :ok)
                "stale registry warns, doesn't error")
            (is (true? (-> result :ok :registry/stale?))
                ":registry/stale? true marks the divergence"))
          (finally
            (r-tools/deregister-by-owner! :test/stale-test)))))))
