(ns hive-mcp.server.routes-default-async-test
  "Tests for wrap-handler-default-async-for-commands and the memory
   tool's write-commands classification. Verifies:

   - Writes in the set get :async true injected.
   - Reads pass through unchanged.
   - Explicit :async false opts out (no injection).
   - Explicit :async true is left alone (no-op).
   - Commands outside the set are untouched regardless of direction."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.server.routes :as routes]
            [hive-mcp.tools.consolidated.memory :as cmem]))

(defn- capture-handler
  "Return [handler captured-ref] where captured-ref is an atom that
   receives the args the handler was called with. Handler returns :ok."
  []
  (let [captured (atom nil)
        handler  (fn [args]
                   (reset! captured args)
                   :ok)]
    [handler captured]))

(deftest injects-async-for-write-commands-by-default
  (testing ":add is in write-commands and has no explicit :async → :async true injected"
    (let [[h cap] (capture-handler)
          wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
      (is (= :ok (wrapped {:command "add" :content "hi"})))
      (is (true? (:async @cap)))
      (is (= "add" (:command @cap)))
      (is (= "hi" (:content @cap))))))

(deftest respects-explicit-async-false-opt-out
  (testing "caller passed :async false — shim must not overwrite"
    (let [[h cap] (capture-handler)
          wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
      (wrapped {:command "add" :async false})
      (is (false? (:async @cap))))))

(deftest respects-explicit-async-true-for-reads
  (testing "caller passed :async true on a read — shim is no-op (value left alone)"
    (let [[h cap] (capture-handler)
          wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
      (wrapped {:command "search" :async true})
      (is (true? (:async @cap))))))

(deftest leaves-read-commands-synchronous
  (testing "read commands (not in set) pass through without :async key"
    (let [[h cap] (capture-handler)
          wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
      (wrapped {:command "search" :query "q"})
      (is (not (contains? @cap :async)) "read must not get :async injected")
      (is (= "search" (:command @cap))))))

(deftest injects-for-every-classified-write
  (testing "every write command in the set gets :async true by default"
    (doseq [cmd cmem/write-commands]
      (let [[h cap] (capture-handler)
            wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
        (wrapped {:command (name cmd)})
        (is (true? (:async @cap))
            (str "expected " cmd " to default-async"))))))

(deftest leaves-reads-unclassified-as-sync
  (testing "read commands must not appear in write-commands set"
    (doseq [cmd [:query :metadata :get :search :expiring :batch-get]]
      (is (not (contains? cmem/write-commands cmd))
          (str cmd " must stay synchronous by default")))))

(deftest command-string-coerced-to-keyword
  (testing "handler receives :command as string — shim keywordizes for membership check"
    (let [[h cap] (capture-handler)
          wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
      (wrapped {:command "batch-add"})
      (is (true? (:async @cap))))))

(deftest missing-command-passes-through-unchanged
  (testing "args without a :command key are passed through untouched"
    (let [[h cap] (capture-handler)
          wrapped (routes/wrap-handler-default-async-for-commands h cmem/write-commands)]
      (wrapped {:foo :bar})
      (is (= {:foo :bar} @cap)))))
