(ns hive-mcp.knowledge-graph.datahike-version-check-test
  "Classpath invariants that datahike's connect-time version-check depends on.

   datahike.connector/version-check compares the konserve version stored in
   the DB metadata against datahike.tools/konserve-version, which is read at
   compile time from META-INF/maven/org.replikativ/konserve/pom.properties.
   A :local/root source checkout has no such resource, so the value is nil
   and (compare nil <stored>) is negative — datahike then raises
   \"Database was written with newer konserve version.\" and connect fails."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest konserve-version-resolvable-test
  (testing "org.replikativ/konserve is on the classpath as a packaged artifact (pom present)"
    (is (some? (io/resource "META-INF/maven/org.replikativ/konserve/pom.properties"))))
  (testing "datahike's runtime konserve-version resolves to a non-nil value"
    (require 'datahike.tools)
    (is (some? @(resolve 'datahike.tools/konserve-version)))))

(deftest datahike-version-check-passes-with-real-konserve-test
  (testing "meta-data reports a non-nil :konserve/version so version-check can compare"
    (require 'datahike.tools)
    (let [ksv (:konserve/version (eval '(datahike.tools/meta-data)))]
      (is (some? ksv))
      (is (and (some? ksv) (>= (compare ksv "0.9.346") 0))))))

(deftest single-datahike-groupid-test
  (testing "the stale old-groupId datahike (io.replikativ) is absent from the classpath"
    (is (nil? (io/resource "META-INF/maven/io.replikativ/datahike/pom.properties"))))
  (testing "the active datahike is the new-groupId org.replikativ coordinate"
    (is (some? (io/resource "META-INF/maven/org.replikativ/datahike/pom.properties")))))
