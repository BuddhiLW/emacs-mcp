(ns hive-mcp.addons.runtime-ports-test
  (:require [clojure.test :refer [deftest is]]
            [hive-mcp.addons.runtime-ports :as runtime-ports]))

(def expected-port-keys
  #{:memory/store
    :embedding/embed-batch
    :embedding/provider
    :embedding/configured?
    :kg/register-schema!
    :kg/infer-scope
    :kg/resolve-project-id
    :kg/query
    :extension/get
    :extension/register!
    :extension/contribute-commands!
    :extension/retract-contributions!})

(deftest composition-root-exposes-callable-adapters
  (let [ports (runtime-ports/runtime-ports)]
    (is (= expected-port-keys (set (keys ports))))
    (is (every? fn? (vals ports)))))
