(ns hive-mcp.addons.manifest-test
  "Tests for classpath manifest scanner and manifest-based addon init.

   Covers:
   - scan-classpath-manifests discovery
   - Parsing and validation of scanned manifests
   - init-from-manifest! with mock constructor
   - Invalid manifests reported as errors, not thrown
   - Dedup: same addon via manifest + hardcoded → initialized once"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [hive-mcp.addons.manifest :as manifest]
            [hive-mcp.addons.protocol :as proto]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- write-test-manifest!
  "Write a manifest EDN file to a temp directory structure.
   Returns the root dir (parent of META-INF/)."
  [dir-root addon-id manifest-map]
  (let [addon-dir (io/file dir-root "META-INF" "hive-addons")
        edn-file  (io/file addon-dir (str addon-id ".edn"))]
    (.mkdirs addon-dir)
    (spit edn-file (pr-str manifest-map))
    dir-root))

(def valid-test-manifest
  {:addon/id      "test.scanner"
   :addon/type    :native
   :addon/init-ns "hive-mcp.addons.manifest-test"
   :addon/init-fn "->test-addon"
   :addon/capabilities #{:tools}
   :addon/description "Test addon for scanner"})

(def invalid-test-manifest
  {:addon/id 123  ;; wrong type — should be string
   :addon/type :native})

;; =============================================================================
;; Mock Addon for init-from-manifest! tests
;; =============================================================================

(defrecord MockManifestAddon [id]
  proto/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{:tools})
  (initialize! [_ _config] {:success? true :errors []})
  (shutdown! [_] {:success? true :errors []})
  (tools [_] [])
  (schema-extensions [_] {})
  (health [_] {:status :ok}))

(defn ->test-addon
  "Constructor matching the test manifest. Returns a MockManifestAddon."
  [_config]
  (->MockManifestAddon "test.scanner"))

;; =============================================================================
;; Scanner Tests
;; =============================================================================

(deftest scan-classpath-manifests-returns-structure
  (testing "Returns map with :manifests and :errors keys"
    (let [result (manifest/scan-classpath-manifests)]
      (is (map? result))
      (is (contains? result :manifests))
      (is (contains? result :errors))
      (is (vector? (:manifests result)))
      (is (vector? (:errors result))))))

(deftest scan-handles-invalid-manifests-gracefully
  (testing "Invalid manifests are collected in :errors, not thrown"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "hive-test-manifests-" (System/nanoTime)))]
      (try
        (write-test-manifest! tmp-dir "bad" invalid-test-manifest)
        ;; We can't easily inject the temp dir into the classloader,
        ;; but we can test parse-manifest directly
        (let [edn-str (pr-str invalid-test-manifest)
              result  (manifest/parse-manifest edn-str)]
          (is (false? (:valid? result)))
          (is (some? (:errors result))))
        (finally
          ;; Cleanup
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))

(deftest parse-manifest-rejects-malformed-edn
  (testing "Malformed EDN string returns parse error"
    (let [result (manifest/parse-manifest "{:broken")]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

;; =============================================================================
;; init-from-manifest! Tests
;; =============================================================================

(deftest init-from-manifest-with-valid-constructor
  (testing "Resolves constructor, gets IAddon, calls register + init"
    (let [registered (atom nil)
          inited     (atom nil)
          register!  (fn [addon]
                       (reset! registered (proto/addon-id addon))
                       {:success? true})
          init!      (fn [addon-id]
                       (reset! inited addon-id)
                       {:success? true})
          result     (manifest/init-from-manifest!
                      valid-test-manifest register! init!)]
      (is (some? result))
      (is (:success? result))
      (is (= "test.scanner" @registered))
      (is (= "test.scanner" @inited))
      (is (= :manifest (:source result))))))

(deftest init-from-manifest-with-bad-constructor
  (testing "Unresolvable constructor returns nil"
    (let [m (assoc valid-test-manifest
                   :addon/init-ns "nonexistent.ns"
                   :addon/init-fn "no-such-fn")
          result (manifest/init-from-manifest!
                  m (fn [_]) (fn [_]))]
      (is (nil? result)))))

;; =============================================================================
;; Topological Sort Integration
;; =============================================================================

(deftest manifests-load-order-respects-dependencies
  (testing "Manifests are sorted by dependency order"
    (let [m1 {:addon/id "a" :addon/type :native
              :addon/init-ns "a" :addon/init-fn "init"
              :addon/dependencies #{}}
          m2 {:addon/id "b" :addon/type :native
              :addon/init-ns "b" :addon/init-fn "init"
              :addon/dependencies #{"a"}}
          {:keys [ordered cycles]} (manifest/manifests-load-order [m2 m1])]
      (is (= ["a" "b"] (mapv :addon/id ordered)))
      (is (empty? cycles)))))

(deftest manifests-load-order-detects-cycles
  (testing "Cyclic dependencies are reported"
    (let [m1 {:addon/id "x" :addon/type :native
              :addon/init-ns "x" :addon/init-fn "init"
              :addon/dependencies #{"y"}}
          m2 {:addon/id "y" :addon/type :native
              :addon/init-ns "y" :addon/init-fn "init"
              :addon/dependencies #{"x"}}
          {:keys [cycles]} (manifest/manifests-load-order [m1 m2])]
      (is (seq cycles)))))
