(ns hive-mcp.config.resolve-test
  "Unit tests for hive-mcp.config.resolve/resolve-carto-store — WARN-not-fail
   fallback semantics when :services :carto-store is absent."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.config.resolve :as resolve]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- capture-warnings
  "Run thunk, capture all timbre :warn log messages as a vector of strings."
  [thunk]
  (let [warns (atom [])
        orig-config log/*config*]
    (try
      (log/merge-config!
       {:appenders
        {:capture
         {:enabled? true
          :min-level :warn
          :fn (fn [data]
                (let [{:keys [level vargs]} data]
                  (when (= :warn level)
                    (swap! warns conj (apply str (interpose " " vargs))))))}}})
      (thunk)
      (finally
        (log/set-config! orig-config)))
    @warns))

(deftest resolve-carto-store-returns-config-when-present
  (testing "returns :services :carto-store verbatim when explicitly configured"
    (let [cfg {:services {:carto-store {:backend :qdrant-carto}
                          :memory-store {:backend :chroma}}}
          warns (atom nil)]
      (reset! warns (capture-warnings
                     #(is (= {:backend :qdrant-carto}
                             (resolve/resolve-carto-store cfg)))))
      (is (empty? @warns) "no warnings when carto-store is declared"))))

(deftest resolve-carto-store-falls-back-with-warning
  (testing "falls back to :memory-store with WARN when :carto-store absent"
    (let [cfg {:services {:memory-store {:backend :chroma}}}
          warns (capture-warnings
                 #(is (= {:backend :chroma}
                         (resolve/resolve-carto-store cfg))))]
      (is (seq warns) "must emit a warning on fallback")
      (is (some #(re-find #"carto-store" %) warns)
          "warning must mention carto-store"))))

(deftest resolve-carto-store-nil-when-nothing-configured
  (testing "returns nil and warns when neither carto-store nor memory-store set"
    (let [cfg {:services {}}
          warns (capture-warnings
                 #(is (nil? (resolve/resolve-carto-store cfg))))]
      (is (seq warns)))))
