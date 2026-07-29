(ns hive-mcp.memory.store.contract-test
  "Backend-agnostic contract tests for IMemoryStore, IMemoryStoreWithAnalytics,
   and IMemoryStoreWithStaleness protocols.

   Usage: bind `*store-factory*` to a zero-arg fn that returns a fresh store
   implementing the protocols, then run this namespace's tests.

   Example (from a backend-specific runner):
     (binding [contract/*store-factory* #(my-backend/create-store)]
       (clojure.test/run-tests 'hive-mcp.memory.store.contract-test))"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.protocols.memory :as proto]
            [hive-test.generators.memory :as gen-mem]
            [hive-test.properties :as props]))

;; =============================================================================
;; Dynamic Var — Backends bind this to a factory fn
;; =============================================================================

(def ^:dynamic *store-factory*
  "Zero-arg function returning a fresh IMemoryStore for each test.
   Must be bound before running these tests."
  nil)

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn store-factory-fixture
  "Skip tests gracefully when *store-factory* is not bound.
   Backend runners (e.g., chroma-contract-runner-test) bind it
   before invoking these tests."
  [f]
  (if *store-factory*
    (f)
    nil))

(use-fixtures :each store-factory-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- fresh-store
  "Create a fresh store from the factory and reset it."
  []
  (let [store (*store-factory*)]
    (proto/reset-store! store)
    store))

(defn- make-entry
  "Build a minimal valid memory entry with a unique id."
  ([]
   (make-entry {}))
  ([overrides]
   (merge {:id       (proto/generate-id)
           :type     :note
           :content  (str "test-content-" (random-uuid))
           :tags     ["test"]
           :duration :medium}
          overrides)))

(defn- make-entry-with-hash
  "Build an entry with pre-computed content-hash."
  [& {:as overrides}]
  (let [entry (make-entry overrides)]
    (assoc entry :content-hash (proto/content-hash (:content entry)))))

;; =============================================================================
;; 1. Totality — add-entry!, query-entries, search-similar never throw
;; =============================================================================

(defspec prop-add-entry-total 50
  (prop/for-all [entry gen-mem/gen-memory-entry]
    (let [store (fresh-store)
          full  (assoc entry :id (proto/generate-id)
                             :content-hash (proto/content-hash (:content entry)))]
      (do (proto/add-entry! store full) true))))

(defspec prop-query-entries-total 50
  (prop/for-all [mem-type gen-mem/gen-memory-type]
    (let [store (fresh-store)]
      (do (proto/query-entries store {:type mem-type :limit 10}) true))))

(defspec prop-search-similar-total 30
  (prop/for-all [query gen/string-alphanumeric]
    (let [store (fresh-store)]
      (if (proto/supports-semantic-search? store)
        (do (proto/search-similar store query {:limit 5}) true)
        true))))

;; =============================================================================
;; 2. Add → Get roundtrip: content/type/tags preserved
;; =============================================================================

(deftest test-add-get-roundtrip
  (let [store (fresh-store)
        entry (make-entry {:type    :convention
                           :content "Convention: always use kebab-case"
                           :tags    ["style" "naming"]})
        _     (proto/add-entry! store entry)
        got   (proto/get-entry store (:id entry))]
    (testing "get-entry returns non-nil after add"
      (is (some? got)))
    (testing "content roundtrips"
      (is (= (:content entry) (:content got))))
    (testing "type roundtrips"
      (is (= (:type entry) (:type got))))
    (testing "tags roundtrip"
      (is (= (:tags entry) (:tags got))))))

;; =============================================================================
;; 3. Add → Delete → Get: returns nil after delete
;; =============================================================================

(deftest test-add-delete-get
  (let [store (fresh-store)
        entry (make-entry)
        _     (proto/add-entry! store entry)
        _     (proto/delete-entry! store (:id entry))
        got   (proto/get-entry store (:id entry))]
    (testing "get-entry returns nil after delete"
      (is (nil? got)))))

;; =============================================================================
;; 4. Add → Update → Get: update merges correctly
;; =============================================================================

(deftest test-add-update-get
  (let [store   (fresh-store)
        entry   (make-entry {:type :note :content "original" :tags ["a"]})
        _       (proto/add-entry! store entry)
        _       (proto/update-entry! store (:id entry)
                                     {:content "updated" :tags ["a" "b"]})
        got     (proto/get-entry store (:id entry))]
    (testing "content updated"
      (is (= "updated" (:content got))))
    (testing "tags updated"
      (is (= ["a" "b"] (:tags got))))
    (testing "type preserved (not in update)"
      (is (= :note (:type got))))))

;; =============================================================================
;; 5. Idempotency — cleanup-expired!, reset-store!
;; =============================================================================

(deftest test-cleanup-expired-shape
  (let [store (fresh-store)
        r     (proto/cleanup-expired! store)]
    (testing "cleanup-expired! returns a map"
      (is (map? r)))
    (testing "reports how many entries it reaped under :count"
      (is (integer? (:count r))))
    (testing "reports which ids it reaped under :deleted-ids"
      (is (sequential? (:deleted-ids r))))))

(deftest test-cleanup-expired-idempotent
  (let [store (fresh-store)]
    (testing "calling cleanup-expired! twice yields same effect"
      (let [r1 (proto/cleanup-expired! store)
            r2 (proto/cleanup-expired! store)]
        ;; Both calls should succeed (no explosions on empty store)
        (is (= r1 r2) "cleanup-expired! should be idempotent")))))

(deftest test-reset-store-idempotent
  (let [store (fresh-store)]
    (testing "calling reset-store! twice is safe"
      (let [r1 (proto/reset-store! store)
            r2 (proto/reset-store! store)]
        (is (= r1 r2) "reset-store! should be idempotent")))))

;; =============================================================================
;; 6. Invariant: after random add/delete ops, count = adds − deletes
;; =============================================================================

(deftest test-add-delete-count-invariant
  (let [store  (fresh-store)
        ids    (mapv (fn [i]
                       (let [entry (make-entry {:content (str "entry-" i)})]
                         (proto/add-entry! store entry)
                         (:id entry)))
                     (range 5))
        ;; Delete entries at index 1 and 3
        _      (proto/delete-entry! store (nth ids 1))
        _      (proto/delete-entry! store (nth ids 3))
        remaining (keep #(proto/get-entry store %) ids)]
    (testing "count equals adds minus deletes"
      (is (= 3 (count remaining))))))

(defspec prop-add-delete-invariant 30
  (prop/for-all [n (gen/choose 1 10)]
    (let [store (fresh-store)
          ids   (mapv (fn [i]
                        (let [entry (make-entry {:content (str "inv-" i)})]
                          (proto/add-entry! store entry)
                          (:id entry)))
                      (range n))
          ;; Delete the first half
          n-del  (quot n 2)
          _      (doseq [id (take n-del ids)]
                   (proto/delete-entry! store id))
          alive  (count (keep #(proto/get-entry store %) ids))]
      (= alive (- n n-del)))))

;; =============================================================================
;; 7. Duplicate detection
;; =============================================================================

(deftest test-find-duplicate-same-content
  (let [store (fresh-store)
        entry (make-entry-with-hash :type :convention :content "dup-test-content")
        _     (proto/add-entry! store entry)
        dup   (proto/find-duplicate store :convention
                                    (:content-hash entry) {})]
    (testing "find-duplicate returns the entry id for same content-hash"
      (is (some? dup)))))

(deftest test-find-duplicate-different-content
  (let [store (fresh-store)
        entry (make-entry-with-hash :type :convention :content "unique-A")
        _     (proto/add-entry! store entry)
        dup   (proto/find-duplicate store :convention
                                    (proto/content-hash "completely-different")
                                    {})]
    (testing "find-duplicate returns nil for different content-hash"
      (is (nil? dup)))))

;; =============================================================================
;; 8. Expiration — ephemeral+past cleaned up, permanent never cleaned
;; =============================================================================

(deftest test-expiration-cleanup
  (let [store     (fresh-store)
        past-ts   "2020-01-01T00:00:00Z"
        future-ts "2099-12-31T23:59:59Z"

        ephemeral (make-entry {:duration   :ephemeral
                               :expires-at past-ts
                               :content    "should-expire"})
        permanent (make-entry {:duration   :permanent
                               :content    "should-survive"})
        future-e  (make-entry {:duration   :short
                               :expires-at future-ts
                               :content    "not-yet-expired"})
        _         (proto/add-entry! store ephemeral)
        _         (proto/add-entry! store permanent)
        _         (proto/add-entry! store future-e)
        _         (proto/cleanup-expired! store)]
    (testing "ephemeral entry with past expiry is cleaned up"
      (is (nil? (proto/get-entry store (:id ephemeral)))))
    (testing "permanent entry is never cleaned up"
      (is (some? (proto/get-entry store (:id permanent)))))
    (testing "future-expiry entry survives cleanup"
      (is (some? (proto/get-entry store (:id future-e)))))))

;; =============================================================================
;; 9. Analytics — log-access!, record-feedback!, helpfulness-ratio
;; =============================================================================

(deftest test-analytics-log-access
  (let [store (fresh-store)]
    (when (proto/analytics-store? store)
      (let [entry (make-entry {:content "analytics-test"})
            _     (proto/add-entry! store entry)
            id    (:id entry)
            _     (proto/log-access! store id)
            _     (proto/log-access! store id)
            got   (proto/get-entry store id)]
        (testing "log-access! increments access-count"
          (is (= 2 (:access-count got))))))))

(deftest test-analytics-record-feedback
  (let [store (fresh-store)]
    (when (proto/analytics-store? store)
      (let [entry (make-entry {:content "feedback-test"})
            _     (proto/add-entry! store entry)
            id    (:id entry)
            _     (proto/record-feedback! store id :helpful)
            _     (proto/record-feedback! store id :helpful)
            _     (proto/record-feedback! store id :unhelpful)
            got   (proto/get-entry store id)]
        (testing "record-feedback! increments helpful-count"
          (is (= 2 (:helpful-count got))))
        (testing "record-feedback! increments unhelpful-count"
          (is (= 1 (:unhelpful-count got))))))))

(deftest test-analytics-helpfulness-ratio
  (let [store (fresh-store)]
    (when (proto/analytics-store? store)
      (let [entry (make-entry {:content "ratio-test"})
            _     (proto/add-entry! store entry)
            id    (:id entry)
            _     (proto/record-feedback! store id :helpful)
            _     (proto/record-feedback! store id :helpful)
            _     (proto/record-feedback! store id :unhelpful)
            ratio (proto/get-helpfulness-ratio store id)]
        (testing "helpfulness-ratio shape"
          (is (contains? ratio :helpful-count))
          (is (contains? ratio :unhelpful-count))
          (is (contains? ratio :total))
          (is (contains? ratio :ratio)))
        (testing "helpfulness-ratio values correct"
          (is (= 2 (:helpful-count ratio)))
          (is (= 1 (:unhelpful-count ratio)))
          (is (= 3 (:total ratio)))
          (is (< (Math/abs (- (double (:ratio ratio)) (/ 2.0 3.0)))
                 0.001)))))))

;; =============================================================================
;; 10. Staleness — update-staleness!, get-stale-entries
;; =============================================================================

(deftest test-staleness-update
  (let [store (fresh-store)]
    (when (proto/staleness-store? store)
      (let [entry (make-entry {:content "staleness-test"})
            _     (proto/add-entry! store entry)
            id    (:id entry)
            _     (proto/update-staleness! store id {:staleness-alpha 2
                                                     :staleness-beta  8})
            got   (proto/get-entry store id)]
        (testing "update-staleness! sets alpha"
          (is (= 2 (:staleness-alpha got))))
        (testing "update-staleness! sets beta"
          (is (= 8 (:staleness-beta got))))))))

(deftest test-staleness-get-stale-entries
  (let [store (fresh-store)]
    (when (proto/staleness-store? store)
      (let [;; stale entry: beta/(alpha+beta) = 9/10 = 0.9
            stale   (make-entry {:content "stale-entry"})
            _       (proto/add-entry! store stale)
            _       (proto/update-staleness! store (:id stale)
                                             {:staleness-alpha 1
                                              :staleness-beta  9})
            ;; fresh entry: beta/(alpha+beta) = 1/10 = 0.1
            fresh-e (make-entry {:content "fresh-entry"})
            _       (proto/add-entry! store fresh-e)
            _       (proto/update-staleness! store (:id fresh-e)
                                             {:staleness-alpha 9
                                              :staleness-beta  1})
            results (proto/get-stale-entries store 0.5 {})]
        (testing "get-stale-entries returns entries above threshold"
          (is (some #(= (:id stale) (:id %)) results)))
        (testing "get-stale-entries excludes entries below threshold"
          (is (not (some #(= (:id fresh-e) (:id %)) results))))))))

;; =============================================================================
;; 11. Lifecycle — connect!, connected?, health-check, store-status
;; =============================================================================

(deftest test-lifecycle-connected
  (let [store (fresh-store)]
    (testing "connected? returns a boolean"
      (is (boolean? (proto/connected? store))))))

(deftest test-lifecycle-health-check-shape
  (let [store  (fresh-store)
        health (proto/health-check store)]
    (testing "health-check returns a map"
      (is (map? health)))
    (testing "health-check contains :healthy?"
      (is (contains? health :healthy?)))
    (testing "health-check :healthy? is boolean"
      (is (boolean? (:healthy? health))))))

(deftest test-lifecycle-store-status-shape
  (let [store  (fresh-store)
        status (proto/store-status store)]
    (testing "store-status returns a map"
      (is (map? status)))
    (testing "store-status contains :backend"
      (is (contains? status :backend)))
    (testing "store-status :backend is a string"
      (is (string? (:backend status))))
    (testing "store-status contains :configured?"
      (is (contains? status :configured?)))
    (testing "store-status contains :entry-count"
      (is (contains? status :entry-count)))))

(deftest test-lifecycle-connect-then-connected
  (let [store  (fresh-store)
        result (proto/connect! store {})]
    (testing "connect! returns a map with :success?"
      (is (map? result))
      (is (contains? result :success?)))
    (testing "connected? returns true after successful connect"
      (when (:success? result)
        (is (true? (proto/connected? store)))))))

;; =============================================================================
;; Property-based: defprop-invariant for add/delete count
;; =============================================================================

(def ^:private gen-add-delete-ops
  "Generator producing [initial-state ops] where ops are :add/:delete keywords.
   Ensures we never delete more than we add."
  (gen/let [n-adds   (gen/choose 1 10)
            n-deletes (gen/choose 0 n-adds)]
    [{:adds n-adds :deletes 0 :store nil :ids []}
     (vec (concat (repeat n-adds :add)
                  (repeat n-deletes :delete)))]))

(defn- apply-store-op
  "Apply a single :add or :delete op to the state, tracking counts."
  [state op]
  (let [store (:store state)]
    (case op
      :add
      (let [entry (make-entry {:content (str "prop-" (random-uuid))})]
        (proto/add-entry! store entry)
        (-> state
            (update :adds inc)
            (update :ids conj (:id entry))))

      :delete
      (if-let [id (peek (:ids state))]
        (do (proto/delete-entry! store id)
            (-> state
                (update :deletes inc)
                (update :ids pop)))
        state))))

(defspec prop-count-invariant-via-macro 30
  (prop/for-all [state+ops gen-add-delete-ops]
    (let [[init-state ops] state+ops
          store            (fresh-store)
          state            (assoc init-state :store store)
          final            (reduce apply-store-op state ops)
          expected-alive   (- (:adds final) (:deletes final))
          actual-alive     (count (keep #(proto/get-entry store %) (:ids final)))]
      (= expected-alive actual-alive))))

;; =============================================================================
;; 12. Disconnect — returns expected shape
;; =============================================================================

(deftest test-disconnect-shape
  (let [store  (fresh-store)
        result (proto/disconnect! store)]
    (testing "disconnect! returns a map"
      (is (map? result)))
    (testing "disconnect! contains :success?"
      (is (contains? result :success?)))
    (testing "disconnect! contains :errors"
      (is (contains? result :errors)))))

;; =============================================================================
;; 13. Entries expiring soon — returns entries within window
;; =============================================================================

(deftest test-entries-expiring-soon
  (let [store     (fresh-store)
        soon-ts   (str (.plus (java.time.ZonedDateTime/now
                                (java.time.ZoneId/systemDefault))
                              (java.time.Duration/ofDays 3)))
        soon-e    (make-entry {:duration   :short
                               :expires-at soon-ts
                               :content    "expiring-soon-test"})
        far-e     (make-entry {:duration   :long
                               :expires-at "2099-12-31T23:59:59Z"
                               :content    "expiring-far-test"})
        perm-e    (make-entry {:duration   :permanent
                               :content    "permanent-test-entry"})
        _         (proto/add-entry! store soon-e)
        _         (proto/add-entry! store far-e)
        _         (proto/add-entry! store perm-e)
        results   (proto/entries-expiring-soon store 7 {})]
    (testing "entries-expiring-soon returns a collection"
      (is (sequential? results)))))

;; =============================================================================
;; 14. Query entries — type filtering
;; =============================================================================

(deftest test-query-entries-by-type
  (let [store  (fresh-store)
        note   (make-entry {:type :note :content "query-test-note"})
        conv   (make-entry {:type :convention :content "query-test-convention"})
        _      (proto/add-entry! store note)
        _      (proto/add-entry! store conv)
        notes  (proto/query-entries store {:type :note :limit 100})
        convs  (proto/query-entries store {:type :convention :limit 100})]
    (testing "query-entries returns a collection"
      (is (sequential? notes))
      (is (sequential? convs)))
    (testing "query by type :note returns note entries"
      (is (some #(= (:id note) (:id %)) notes)))
    (testing "query by type :convention returns convention entries"
      (is (some #(= (:id conv) (:id %)) convs)))))

;; =============================================================================
;; 15. Search similar — behavioral test
;; =============================================================================

(deftest test-search-similar-behavioral
  (let [store (fresh-store)]
    (when (proto/supports-semantic-search? store)
      (let [_       (proto/add-entry! store
                      (make-entry {:content "Clojure is a functional programming language"}))
            _       (proto/add-entry! store
                      (make-entry {:content "Python is popular for data science"}))
            results (proto/search-similar store "functional programming" {:limit 5})]
        (testing "search-similar returns a collection"
          (is (sequential? results)))
        (testing "search-similar respects :limit"
          (is (<= (count results) 5)))))))

;; =============================================================================
;; 16. Staleness — propagate-staleness! does not throw
;; =============================================================================

(deftest test-staleness-propagate
  (let [store (fresh-store)]
    (when (proto/staleness-store? store)
      (let [entry  (make-entry {:content "propagation-source-test"})
            _      (proto/add-entry! store entry)
            result (proto/propagate-staleness! store (:id entry) 0)]
        (testing "propagate-staleness! completes without throwing"
          ;; Result is backend-dependent (count of propagated deps)
          ;; Contract: must not throw, may return nil or integer
          (is (or (nil? result) (integer? result))))))))
