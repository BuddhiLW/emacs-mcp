(ns hive-mcp.tools.catchup.carto-trifecta-test
  "Trifecta tests for catchup/carto indexed-forms-count.

   Pins the behavior of commit b271acc which routes indexed-forms-count
   through hive-mcp.vectordb.carto-facade/query-entries (the :carto slot,
   Milvus-backed) instead of hive-mcp.chroma.crud/query-entries
   (:default slot, Chroma). Backend mismatch made the count return 0
   regardless of scan progress.

   Facets:
     - Regression (clojure.test): backend-routing contract, kwargs shape,
       error paths, project-id defaulting.
     - Trifecta (deftrifecta): golden + property + mutation around the
       count contract via a thin adapter that isolates the query-fn.
     - Property: get-status output shape is total over boolean store states."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as tc-prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-mcp.tools.catchup.carto :as carto]))

;; =============================================================================
;; Private-var harness
;; =============================================================================

(def ^:private indexed-forms-count* @#'carto/indexed-forms-count)
(def ^:private get-status* carto/get-status)

(defn- stub-resolver
  "Returns a try-resolve replacement that yields `query-fn` only when asked
   for `target-sym`; nil otherwise. Records every symbol seen into `seen`."
  [target-sym query-fn seen]
  (fn [sym]
    (swap! seen conj sym)
    (when (= sym target-sym) query-fn)))

(defn run-count
  "Adapter: drive indexed-forms-count with a controllable result vector.
   Used as the trifecta subject — decouples the test from the resolver."
  ([results] (run-count results "hive-mcp"))
  ([results project-id]
   (with-redefs [carto/try-resolve (fn [_] (fn [& _] results))]
     (indexed-forms-count* project-id))))

;; =============================================================================
;; 1. Regression: the fix itself — routes via carto-facade, not chroma.crud
;; =============================================================================

(deftest routes-via-carto-facade-not-chroma-crud
  (testing "indexed-forms-count resolves carto-facade/query-entries"
    (let [seen     (atom [])
          carto-fn (fn [& _] [{:id "a"} {:id "b"} {:id "c"}])]
      (with-redefs [carto/try-resolve
                    (stub-resolver 'hive-mcp.vectordb.carto-facade/query-entries
                                   carto-fn seen)]
        (is (= 3 (indexed-forms-count* "hive-mcp"))
            "Count equals size of carto-facade result vector")
        (is (contains? (set @seen) 'hive-mcp.vectordb.carto-facade/query-entries)
            "Must resolve the carto-facade symbol")
        (is (not (contains? (set @seen) 'hive-mcp.chroma.crud/query-entries))
            "Must NOT resolve chroma.crud — that was the broken path")))))

(deftest zero-when-carto-facade-unavailable
  (testing "returns 0 when carto-facade query-fn cannot resolve"
    (with-redefs [carto/try-resolve (fn [_] nil)]
      (is (= 0 (indexed-forms-count* "hive-mcp"))))))

(deftest zero-when-query-throws
  (testing "catches exceptions from query-fn; degrades to 0 silently"
    (with-redefs [carto/try-resolve
                  (fn [_] (fn [& _] (throw (ex-info "boom" {}))))]
      (is (= 0 (indexed-forms-count* "hive-mcp"))))))

;; =============================================================================
;; 2. Regression: API contract (kwargs, not map; tag filter; project-id default)
;; =============================================================================

(deftest calls-query-fn-with-kwargs
  (testing "query-fn invoked via keyword args — ['carto'] tag, limit 10000"
    (let [captured (atom nil)
          query-fn (fn [& args]
                     (reset! captured args)
                     [])]
      (with-redefs [carto/try-resolve (fn [_] query-fn)]
        (indexed-forms-count* "my-proj")
        (let [args @captured]
          (is (even? (count args))
              "Kwargs must be even-length (key/value pairs)")
          (let [m (apply hash-map args)]
            (is (= ["carto"] (:tags m)))
            (is (= 10000 (:limit m)))
            (is (= "my-proj" (:project-id m)))))))))

(deftest project-id-defaults-to-hive-mcp
  (testing "nil project-id falls back to 'hive-mcp'"
    (let [captured (atom nil)]
      (with-redefs [carto/try-resolve
                    (fn [_] (fn [& args]
                              (reset! captured (apply hash-map args))
                              []))]
        (indexed-forms-count* nil)
        (is (= "hive-mcp" (:project-id @captured)))))))

;; =============================================================================
;; 3. Trifecta: count contract via adapter
;;
;;    Golden: count equals result size for representative shapes.
;;    Property: output is a non-negative int for any result vector length.
;;    Mutation: always-zero and off-by-one should diverge from golden.
;; =============================================================================

(deftrifecta indexed-forms-count-contract
  hive-mcp.tools.catchup.carto-trifecta-test/run-count
  {:golden-path "test/golden/catchup/carto-indexed-count.edn"
   :cases       {:empty  []
                 :single [{:id "a"}]
                 :three  [{:id "a"} {:id "b"} {:id "c"}]
                 :large  (vec (repeat 500 {:id "x"}))}
   :gen         (gen/vector (gen/return {:id "x"}) 0 200)
   :pred        #(and (integer? %) (>= % 0))
   :property-type :pred
   :num-tests   100
   :mutations   [["always-zero"  (fn [_] 0)]
                 ["off-by-one"   (fn [xs] (inc (count xs)))]
                 ["identity-err" (fn [_] -1)]]})

;; =============================================================================
;; 4. Property: project-id shape doesn't crash count
;; =============================================================================

(defspec prop-project-id-shape-total 100
  (tc-prop/for-all
    [pid (gen/one-of [(gen/return nil)
                      (gen/return "")
                      gen/string-alphanumeric
                      (gen/return "hive-mcp")])]
    (let [n (run-count [{:id "x"} {:id "y"}] pid)]
      (= 2 n))))

;; =============================================================================
;; 5. get-status output shape
;;
;;    Property: regardless of lsp / store availability, output always has the
;;    three core keys with correct types — this is what callers consume.
;; =============================================================================

(defspec prop-get-status-shape 50
  (tc-prop/for-all
    [lsp?   gen/boolean
     store? gen/boolean
     n      (gen/choose 0 50)]
    (let [results (vec (repeat n {:id "x"}))]
      (with-redefs [carto/try-resolve
                    (fn [sym]
                      (condp = sym
                        'lsp-mcp.sidecar/sidecar-running?
                        (fn [] lsp?)

                        'hive-mcp.vectordb.carto-facade/available?
                        (fn [] store?)

                        'hive-mcp.vectordb.carto-facade/query-entries
                        (fn [& _] results)

                        'hive-knowledge.cartography.tools/scan-state-snapshot
                        (fn [_] nil)

                        nil))]
        (let [s (get-status* "hive-mcp")]
          (and (contains? s :lsp-up?)
               (contains? s :carto-store?)
               (contains? s :indexed-forms)
               (boolean? (:lsp-up? s))
               (boolean? (:carto-store? s))
               (integer? (:indexed-forms s))
               (= lsp?   (:lsp-up? s))
               (= store? (:carto-store? s))
               (= n      (:indexed-forms s))))))))

(deftest get-status-merges-scan-info-when-present
  (testing "scan snapshot fields merge into status map"
    (with-redefs [carto/try-resolve
                  (fn [sym]
                    (condp = sym
                      'lsp-mcp.sidecar/sidecar-running?              (fn [] false)
                      'hive-mcp.vectordb.carto-facade/available?     (fn [] true)
                      'hive-mcp.vectordb.carto-facade/query-entries  (fn [& _] [])
                      'hive-knowledge.cartography.tools/scan-state-snapshot
                      (fn [_] {:status :finished
                               :finished-at 1234567890
                               :result {:snippets 10 :files 3 :edges 20}})
                      nil))]
      (let [s (get-status* "hive-mcp")]
        (is (= "finished" (:scan-status s)))
        (is (= 1234567890 (:last-scan-ts s)))
        (is (= {:snippets 10 :files 3 :edges 20} (:scan-result s)))))))
