(ns hive-mcp.chroma.client-test
  "DIP: the Chroma transport seam is exercised against a concrete stub
   IChromaTransport, never the live vendor client."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.chroma.client :as cc]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord RecordingTransport [calls]
  cc/IChromaTransport
  (-configure [_ opts] (swap! calls conj [:configure opts]) :configured)
  (-get-collection [_ coll-name] (swap! calls conj [:get-collection coll-name]) {:name coll-name})
  (-create-collection [_ coll-name opts] (swap! calls conj [:create-collection coll-name opts]) {:name coll-name})
  (-delete-collection [_ coll] (swap! calls conj [:delete-collection coll]) :deleted)
  (-add [_ coll records opts] (swap! calls conj [:add coll records opts]) :added)
  (-get [_ coll opts] (swap! calls conj [:get coll opts]) [])
  (-query [_ coll embedding opts] (swap! calls conj [:query coll embedding opts]) [])
  (-delete [_ coll opts] (swap! calls conj [:delete coll opts]) :deleted)
  (-update [_ coll records] (swap! calls conj [:update coll records]) :updated))

(def ^:dynamic *calls* nil)

(use-fixtures :each
  (fn [t]
    (let [prev  (cc/transport)
          calls (atom [])]
      (cc/set-transport! (->RecordingTransport calls))
      (binding [*calls* calls]
        (try (t) (finally (cc/set-transport! prev)))))))

(defn- last-call [] (last @*calls*))

(deftest positional-ops-dispatch-through-active-transport
  (cc/get-collection "mem")
  (is (= [:get-collection "mem"] (last-call)))
  (cc/delete-collection {:id "x"})
  (is (= [:delete-collection {:id "x"}] (last-call)))
  (cc/update {:id "c"} [{:id 1}])
  (is (= [:update {:id "c"} [{:id 1}]] (last-call))))

(deftest kwargs-normalize-to-an-opts-map
  (testing "get: trailing kwargs collapse to a map"
    (cc/get :coll :ids [1] :include #{:documents :metadatas})
    (is (= [:get :coll {:ids [1] :include #{:documents :metadatas}}] (last-call))))
  (testing "get: no kwargs => nil opts (forwards as a bare call)"
    (cc/get :coll)
    (is (= [:get :coll nil] (last-call))))
  (testing "query: embedding stays positional, rest are opts"
    (cc/query :coll [0.1 0.2] :num-results 5 :where {:type "note"})
    (is (= [:query :coll [0.1 0.2] {:num-results 5 :where {:type "note"}}] (last-call))))
  (testing "add: records positional, :upsert? in opts"
    (cc/add :coll [{:id 1}] :upsert? true)
    (is (= [:add :coll [{:id 1}] {:upsert? true}] (last-call))))
  (testing "add: no opts => nil"
    (cc/add :coll [{:id 2}])
    (is (= [:add :coll [{:id 2}] nil] (last-call))))
  (testing "delete: ids in opts"
    (cc/delete :coll :ids [1 2])
    (is (= [:delete :coll {:ids [1 2]}] (last-call)))))

(deftest maps-pass-through-unchanged
  (testing "create-collection accepts a trailing metadata map (connection.clj shape)"
    (cc/create-collection "c" {:metadata {:dimension 3 :created-by "hive-mcp"}})
    (is (= [:create-collection "c" {:metadata {:dimension 3 :created-by "hive-mcp"}}] (last-call))))
  (testing "configure forwards its opts map unchanged"
    (cc/configure {:host "h" :port 8000})
    (is (= [:configure {:host "h" :port 8000}] (last-call)))))

(deftest default-transport-is-the-soft-vendor-adapter
  (cc/set-transport! (cc/->SoftVendorTransport))
  (is (= "SoftVendorTransport" (.getSimpleName (class (cc/transport)))))
  (is (satisfies? cc/IChromaTransport (cc/transport))))
