(ns hive-mcp.knowledge-graph.grounding-test
  "Tests for the re-grounding writer.

   Regression focus: entries returned by the real backend readers are FLAT
   (Chroma's metadata->entry flattens :source-file/:source-hash/:grounded-at to
   top-level keys; Milvus has no such columns at all). The writer used to look
   them up under a :metadata key that no backend ever emits, so every entry
   short-circuited to :no-source-metadata while backfill-grounding!'s own filter
   (which checked both shapes) selected nothing. These tests pin the two call
   sites to a single shared lookup."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.grounding :as grounding]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.memory.temporal :as temporal])
  (:import [java.io File]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- temp-source-file ^File []
  (let [f (File/createTempFile "ground" ".clj")]
    (.deleteOnExit f)
    (spit f "(ns x)")
    f))

(defn- stub-store
  "Reify only the store methods the grounding writer touches.
   `entries` is a map of id -> entry. `writes` is an atom collecting updates."
  [entries writes]
  (reify mem-proto/IMemoryStore
    (get-entry [_ id] (get entries id))
    (update-entry! [_ id updates] (swap! writes conj [id updates]) nil)
    (query-entries [_ _opts] (vec (vals entries)))))

;; =============================================================================
;; needs-regrounding? — type tolerance
;; =============================================================================

(deftest needs-regrounding-accepts-string-grounded-at-test
  (testing "Chroma persists grounded-at as an ISO string — must not throw"
    (let [now (.toString (java.time.Instant/now))
          old (.toString (.minus (java.time.Instant/now)
                                 (java.time.Duration/ofDays 30)))]
      (is (false? (boolean (grounding/needs-regrounding? {:grounded-at now} 7))))
      (is (true? (boolean (grounding/needs-regrounding? {:grounded-at old} 7)))))))

(deftest needs-regrounding-blank-grounded-at-is-never-grounded-test
  (testing "Chroma's metadata-defaults stores :grounded-at \"\" — blank means never grounded"
    (is (true? (boolean (grounding/needs-regrounding? {:grounded-at ""} 7))))
    (is (true? (boolean (grounding/needs-regrounding? {:grounded-at nil} 7))))))

(deftest needs-regrounding-accepts-date-grounded-at-test
  (testing "java.util.Date still works (the writer stamps a Date)"
    (is (false? (boolean (grounding/needs-regrounding? {:grounded-at (java.util.Date.)} 7))))))

;; =============================================================================
;; reground-entry! — the key-path bug
;; =============================================================================

(deftest reground-reads-top-level-source-file-test
  (testing "source-file at TOP LEVEL (the real Chroma reader shape) is found"
    (let [f (temp-source-file)
          writes (atom [])
          entry {:id "e1" :type :decision :content "c" :source-file (.getPath f)}
          store (stub-store {"e1" entry} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (let [res (grounding/reground-entry! "e1")]
          (is (not= :no-source-metadata (:status res)))
          (is (= :regrounded (:status res)))
          (is (= 1 (count @writes))))))))

(deftest reground-reads-nested-source-file-test
  (testing "source-file nested under :metadata still works (legacy shape)"
    (let [f (temp-source-file)
          writes (atom [])
          entry {:id "e2" :metadata {:source-file (.getPath f)}}
          store (stub-store {"e2" entry} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (is (= :regrounded (:status (grounding/reground-entry! "e2"))))))))

(deftest reground-detects-drift-from-top-level-source-hash-test
  (testing "stored :source-hash at top level must be read — otherwise drift is invisible"
    (let [f (temp-source-file)
          writes (atom [])
          entry {:id "e3"
                 :source-file (.getPath f)
                 :source-hash "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}
          store (stub-store {"e3" entry} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (let [res (grounding/reground-entry! "e3")]
          (is (= :needs-review (:status res)))
          (is (true? (:drift? res))))))))

(deftest reground-no-source-anchor-still-short-circuits-test
  (testing "an entry with no anchor anywhere is still :no-source-metadata"
    (let [writes (atom [])
          store (stub-store {"e4" {:id "e4" :content "prose"}} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (is (= :no-source-metadata (:status (grounding/reground-entry! "e4"))))
        (is (empty? @writes))))))

;; =============================================================================
;; The regression guard: backfill and reground must agree
;; =============================================================================

(deftest backfill-and-reground-agree-on-source-lookup-test
  (testing "an entry backfill selects as with-source is not rejected by reground"
    (let [f (temp-source-file)
          writes (atom [])
          entry {:id "e5" :source-file (.getPath f)}
          store (stub-store {"e5" entry} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (let [res (grounding/backfill-grounding! {:force? true})]
          (is (nil? (:error res)))
          (is (= 1 (:total-scanned res)))
          (is (= 1 (:with-source res)))
          ;; the bug: with-source was 1 but reground said :no-source-metadata
          (is (= 1 (:processed res)))
          (is (zero? (get-in res [:by-status :no-source-metadata] 0)))
          (is (= 1 (get-in res [:by-status :regrounded] 0))))))))

;; =============================================================================
;; Persistence read-back: the store must not be able to lie about the write
;; =============================================================================

(defn- persisting-store
  "A store that actually keeps what update-entry! is given (Chroma-like)."
  [entries-atom]
  (reify mem-proto/IMemoryStore
    (get-entry [_ id] (get @entries-atom id))
    (update-entry! [_ id updates] (swap! entries-atom update id merge updates) nil)
    (query-entries [_ _opts] (vec (vals @entries-atom)))))

(deftest reground-flags-dropped-write-test
  (testing "a store that silently discards :grounded-at (Milvus's closed column set) is reported, not believed"
    (let [f (temp-source-file)
          writes (atom [])
          ;; stub-store's get-entry always returns the ORIGINAL entry — exactly
          ;; what Milvus does: update-entry! accepts the key and drops it.
          store (stub-store {"p1" {:id "p1" :source-file (.getPath f)}} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (let [res (grounding/reground-entry! "p1")]
          (is (true? (:updated? res)) "the write was attempted")
          (is (false? (:persisted? res))
              "read-back must show the grounding fact did NOT survive")
          (is (= 1 (:persistence-lost (grounding/backfill-grounding! {:force? true})))
              "backfill must surface the loss so a scheduled pass cannot report false success"))))))

(deftest reground-confirms-persisted-write-test
  (testing "a store that keeps the write reports :persisted? true and zero loss"
    (let [f (temp-source-file)
          entries (atom {"p2" {:id "p2" :source-file (.getPath f)}})
          store (persisting-store entries)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (let [res (grounding/reground-entry! "p2")]
          (is (= :regrounded (:status res)))
          (is (true? (:persisted? res)))
          (is (some? (:grounded-at (get @entries "p2"))))
          (is (zero? (:persistence-lost (grounding/backfill-grounding! {:force? true})))))))))

(deftest backfill-tolerates-string-grounded-at-test
  (testing "backfill's needs-regrounding? filter must not throw on a string grounded-at"
    (let [f (temp-source-file)
          writes (atom [])
          entry {:id "e6" :source-file (.getPath f) :grounded-at ""}
          store (stub-store {"e6" entry} writes)]
      (with-redefs [mem-proto/get-store (fn ([] store) ([_] store))
                    temporal/record-mutation-silent! (fn [_] nil)]
        (let [res (grounding/backfill-grounding! {})]
          (is (nil? (:error res)))
          (is (= 1 (:processed res))))))))
