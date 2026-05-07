;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.resolver-test
  "ConfigBackendResolver — Strategy chain: config > defaults > fallback."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.knowledge-graph.slots.protocol :as p]
            [hive-mcp.knowledge-graph.slots.config :as cfg]))

(defn- path-lookup-stub
  "Build a path-lookup fn from a literal config map. `path` is a vec of keys."
  [m]
  (fn [path] (get-in m path)))

;; -----------------------------------------------------------------------------
;; Defaults — golden cases
;; -----------------------------------------------------------------------------

(deftest defaults-encode-storage-plan
  (testing "default mapping matches the storage migration plan decision"
    (let [r (cfg/->resolver (path-lookup-stub {}))]
      (is (= :datalevin (p/resolve-backend r :carto)))
      (is (= :datahike  (p/resolve-backend r :memory)))
      (is (= :datalevin (p/resolve-backend r :sessions)))
      (is (= :datahike  (p/resolve-backend r :default))))))

(deftest unknown-slot-falls-back
  (testing "unknown slot falls through to fallback (:datahike)"
    (let [r (cfg/->resolver (path-lookup-stub {}))]
      (is (= :datahike (p/resolve-backend r :nonexistent))))))

;; -----------------------------------------------------------------------------
;; config.edn override (Strategy chain)
;; -----------------------------------------------------------------------------

(deftest config-edn-overrides-defaults
  (testing ":services :kg :slots <slot> :backend overrides the canonical mapping"
    (let [m {:services {:kg {:slots {:carto    {:backend :datahike}
                                     :memory   {:backend :datalevin}}}}}
          r (cfg/->resolver (path-lookup-stub m))]
      (is (= :datahike  (p/resolve-backend r :carto))   "config flips :carto")
      (is (= :datalevin (p/resolve-backend r :memory))  "config flips :memory")
      (is (= :datalevin (p/resolve-backend r :sessions))
          "untouched slot keeps default"))))

(deftest config-string-coerced-to-keyword
  (testing "config.edn string values coerce to keyword"
    (let [m {:services {:kg {:slots {:carto {:backend "datascript"}}}}}
          r (cfg/->resolver (path-lookup-stub m))]
      (is (= :datascript (p/resolve-backend r :carto))))))

;; -----------------------------------------------------------------------------
;; Default-mapping introspection
;; -----------------------------------------------------------------------------

(deftest default-mapping-returns-canonical-map
  (let [r (cfg/->resolver (path-lookup-stub {}))]
    (is (= cfg/default-slot->backend (p/default-mapping r)))))

(deftest custom-defaults-injected
  (testing "callers can pass an override defaults map"
    (let [custom {:carto :datalevin :exotic :proximum}
          r (cfg/->resolver (path-lookup-stub {}) custom)]
      (is (= :datalevin (p/resolve-backend r :carto)))
      (is (= :proximum  (p/resolve-backend r :exotic)))
      (is (= :datahike  (p/resolve-backend r :memory))
          "slot absent from override defaults still falls back"))))
