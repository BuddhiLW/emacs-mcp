(ns hive-mcp.extensions.loader-preload-test
  "Step 0 of load-extensions!: the host protocols an addon reifies must be in
   the image before any addon constructor namespace is loaded."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.extensions.loader :as loader]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private declared @#'loader/host-protocol-namespaces)
(def ^:private preload! #'loader/preload-host-protocols!)

(deftest every-declared-host-protocol-namespace-loads
  (testing "the declared set is not empty — an empty preload passes vacuously"
    (is (seq declared)))
  (testing "it covers the protocols the addons in this tree reify"
    (is (every? (set declared)
                '[hive-mcp.addons.protocol
                  hive-mcp.addons.terminal
                  hive-mcp.protocols.vessel])))
  (testing "none of them fails to load"
    (is (= [] (preload!))))
  (testing "each is in the image afterwards"
    (doseq [n declared]
      (is (some? (find-ns n)) (str n " absent from the image")))))

(deftest preload-puts-the-reified-protocols-in-reach
  (testing "an addon names these by qualified symbol and cannot :require them —
            the host is not a dependency of its own addons. reify resolves the
            symbol while the constructor namespace COMPILES, so absent this
            preload the addon dies with `Syntax error compiling reify*` and the
            composer reports it as an ordinary mount failure."
    (preload!)
    (is (some? (resolve 'hive-mcp.addons.protocol/IAddon)))
    (is (some? (resolve 'hive-mcp.addons.terminal/ITerminalAddon)))
    (is (some? (resolve 'hive-mcp.protocols.vessel/IVessel)))))
