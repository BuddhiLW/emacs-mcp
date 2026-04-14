(ns hive-mcp.vectordb.carto-facade-test
  "Hermetic tests for hive-mcp.vectordb.carto-facade.

   Stubs two IMemoryStore instances under the :default and :carto registry
   slots via with-redefs of protocols.memory/get-store, verifying that every
   carto-facade call is routed to the :carto slot ONLY and never touches the
   :default store.

   IMPORTANT: Tests MUST be hermetic — no mutation of the live registry atom.
   Convention 20260413194805-46ed1a18 — tests must never touch the running
   environment. We with-redefs `get-store`, never call reset-registry!."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.protocols.memory :as proto]
            [hive-mcp.vectordb.carto-facade :as cf]))

;; ============================================================================
;; Recording stub store
;; ============================================================================

(defrecord StubStore [label calls]
  proto/IMemoryStore
  (connect!       [_ _]        nil)
  (disconnect!    [_]          nil)
  (connected?     [_]          true)
  (health-check   [_]          {:healthy? true})
  (add-entry!     [_ entry]    (swap! calls conj [label :add-entry! entry]) (:id entry "stub-id"))
  (get-entry      [_ id]       (swap! calls conj [label :get-entry id]) {:id id :label label})
  (update-entry!  [_ id upd]   (swap! calls conj [label :update-entry! id upd]) true)
  (delete-entry!  [_ id]       (swap! calls conj [label :delete-entry! id]) true)
  (query-entries  [_ opts]     (swap! calls conj [label :query-entries opts]) [])
  (search-similar [_ q opts]   (swap! calls conj [label :search-similar q opts]) [])
  (supports-semantic-search? [_] true)
  (cleanup-expired! [_]        (swap! calls conj [label :cleanup-expired!]) 0)
  (entries-expiring-soon [_ _ _] [])
  (find-duplicate [_ t h opts] (swap! calls conj [label :find-duplicate t h opts]) nil)
  (store-status   [_]          {:label label})
  (reset-store!   [_]          nil))

(defn- make-pair []
  (let [calls (atom [])]
    {:calls   calls
     :default (->StubStore :default calls)
     :carto   (->StubStore :carto calls)}))

(defn- with-stubs
  "Run f with get-store redefed to serve the stubbed registry.
   Hermetic: never touches the live store-registry atom."
  [f]
  (let [{:keys [calls default carto]} (make-pair)
        stub-get (fn
                   ([] default)
                   ([key]
                    (case key
                      :default default
                      :carto   carto
                      (throw (ex-info "unknown key" {:key key})))))]
    (with-redefs [proto/get-store stub-get]
      (f calls))))

(defn- carto-labels [calls]
  (set (map first @calls)))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest carto-facade-routes-index-memory-entry
  (with-stubs
    (fn [calls]
      (cf/index-memory-entry! {:id "e1" :content "x"})
      (is (= #{:carto} (carto-labels calls)))
      (is (= [[:carto :add-entry! {:id "e1" :content "x"}]] @calls)))))

(deftest carto-facade-routes-index-memory-entries-batch
  (with-stubs
    (fn [calls]
      (cf/index-memory-entries! [{:id "a"} {:id "b"} {:id "c"}])
      (is (= #{:carto} (carto-labels calls)))
      (is (= 3 (count @calls))))))

(deftest carto-facade-routes-get-entry-by-id
  (with-stubs
    (fn [calls]
      (is (= {:id "x" :label :carto} (cf/get-entry-by-id "x")))
      (is (= #{:carto} (carto-labels calls))))))

(deftest carto-facade-routes-query-entries
  (with-stubs
    (fn [calls]
      (cf/query-entries :type "snippet" :tags ["carto"] :limit 50)
      (is (= #{:carto} (carto-labels calls)))
      (let [[[lbl op opts]] @calls]
        (is (= :carto lbl))
        (is (= :query-entries op))
        (is (= "snippet" (:type opts)))
        (is (= ["carto"] (:tags opts)))
        (is (= 50 (:limit opts)))))))

(deftest carto-facade-routes-search-similar
  (with-stubs
    (fn [calls]
      (cf/search-similar "foo" :limit 5)
      (is (= #{:carto} (carto-labels calls)))
      (let [[[lbl op q opts]] @calls]
        (is (= :carto lbl))
        (is (= :search-similar op))
        (is (= "foo" q))
        (is (= 5 (:limit opts)))))))

(deftest carto-facade-routes-update-and-delete
  (with-stubs
    (fn [calls]
      (cf/update-entry! "id1" {:content "new"})
      (cf/delete-entry! "id1")
      (is (= #{:carto} (carto-labels calls)))
      (is (= 2 (count @calls))))))

(deftest carto-facade-routes-find-duplicate
  (with-stubs
    (fn [calls]
      (cf/find-duplicate "snippet" "abc123" :project-id "hive-mcp")
      (is (= #{:carto} (carto-labels calls))))))

(deftest carto-facade-routes-cleanup-expired
  (with-stubs
    (fn [calls]
      (cf/cleanup-expired!)
      (is (= #{:carto} (carto-labels calls))))))

(deftest carto-facade-never-hits-default
  (testing "every carto-facade fn routes to :carto, never :default"
    (with-stubs
      (fn [calls]
        (cf/index-memory-entry! {:id "a"})
        (cf/index-memory-entries! [{:id "b"}])
        (cf/get-entry-by-id "c")
        (cf/query-entries :limit 1)
        (cf/search-similar "q")
        (cf/update-entry! "d" {})
        (cf/delete-entry! "e")
        (cf/find-duplicate "snippet" "hash")
        (cf/cleanup-expired!)
        (is (= #{:carto} (carto-labels calls))
            "no call should have hit the :default store")
        (is (every? #(= :carto (first %)) @calls))))))

(deftest carto-facade-content-hash-is-pure
  (testing "content-hash delegates to proto, no store involved"
    (is (string? (cf/content-hash "hello")))
    (is (= (cf/content-hash "hello") (cf/content-hash "hello")))))

(deftest carto-facade-generate-id-works
  (is (string? (cf/generate-id))))
