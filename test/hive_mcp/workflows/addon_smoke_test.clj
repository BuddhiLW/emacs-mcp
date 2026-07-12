(ns hive-mcp.workflows.addon-smoke-test
  "Integration smoke-test: the hive-workflows addons load at boot.

   Tagged ^:integration because it requires the LIVE/local classpath (the
   server launched with local.deps.edn) — hive-workflows is on neither deps.edn
   nor any :test* alias classpath. Under a cold JVM this test self-skips loudly
   rather than false-failing. See hive-mcp.workflows.addon-smoke for the why."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.workflows.addon-smoke :as smoke]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest ^:integration workflow-addons-load-at-boot
  (testing "hive.workflows.strategy + hive.workflows.progress discovered via META-INF and initialized"
    (if-not (smoke/addons-on-classpath?)
      (is true
          (str "SKIPPED: hive-workflows addons not on this JVM's classpath — "
               "run under local.deps.edn / the live server, not a cold `-M:test`."))
      (let [{:keys [ok? missing-addons unresolved missing-methods scan-errors report]}
            (smoke/check)]
        (is ok? report)
        (is (empty? missing-addons) (str "addons missing from classpath: " missing-addons))
        (is (empty? unresolved) (str "addon init constructors unresolved: " unresolved))
        (is (empty? missing-methods) (str "strategy methods missing: " missing-methods))
        (is (empty? scan-errors) (str "manifest scan errors: " scan-errors))))))
