(ns hive-mcp.extensions.reactive-test
  "A contribution made AFTER boot reaches the advertised surface: its composite
   is rebuilt, and every active addon's schema-extensions are re-read."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-addon.protocol :as proto]
            [hive-mcp.addons.core :as addon-core]
            [hive-mcp.extensions.reactive :as reactive]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- clean [f]
  (ext/clear-all!)
  (addon-core/reset-registry!)
  (reactive/uninstall!)
  (try (f)
       (finally
         (reactive/uninstall!)
         (ext/clear-all!)
         (addon-core/reset-registry!))))

(use-fixtures :each clean)

(defn- registered-tool [tool-name]
  (first (filter #(= tool-name (:name %)) (ext/get-registered-tools))))

(defn- command-enum [tool]
  (set (get-in tool [:inputSchema :properties "command" :enum])))

(deftest a-late-contribution-rebuilds-its-composite
  (reactive/install!)
  (is (nil? (registered-tool "analysis")))
  (ext/contribute-commands! "analysis" :probe
                            {"probe-lint" {:handler (fn [_] {:type "text" :text "ok"})
                                           :description "probe"}})
  (let [t (registered-tool "analysis")]
    (is (some? t) "the composite is built and registered by the contribution itself")
    (is (contains? (command-enum t) "probe-lint")))
  (testing "a retraction that empties the tool removes the composite, as boot would never have built it"
    (ext/retract-commands! "analysis" :probe)
    (is (nil? (registered-tool "analysis"))))
  (testing "a retraction that leaves commands rebuilds without the retracted one"
    (ext/contribute-commands! "analysis" :keep {"kept" {:handler identity}})
    (ext/contribute-commands! "analysis" :probe {"probe-lint" {:handler identity}})
    (ext/retract-commands! "analysis" :probe)
    (let [t (registered-tool "analysis")]
      (is (some? t))
      (is (contains? (command-enum t) "kept"))
      (is (not (contains? (command-enum t) "probe-lint"))))))

(deftest a-contribution-to-a-non-whitelisted-tool-builds-no-composite
  (reactive/install!)
  (ext/contribute-commands! "code" :probe {"probe" {:handler identity}})
  (is (nil? (registered-tool "code"))
      "a non-whitelisted name folds into its core tool, never overwrites it"))

(defn- schema-addon [id exts-atom]
  (reify proto/IAddon
    (addon-id [_] id)
    (addon-type [_] :native)
    (capabilities [_] #{:tools})
    (initialize! [_ _] {:success? true})
    (shutdown! [_] nil)
    (tools [_] [])
    (schema-extensions [_] @exts-atom)
    (health [_] {:status :ok})
    (excluded-tools [_] #{})
    (hooks [_] {})))

(deftest schema-extensions-are-re-read-on-a-late-contribution
  (let [exts (atom {"code" {"probe_param" {:type "string"}}})]
    (addon-core/register-addon! (schema-addon "probe.schema" exts))
    (addon-core/init-addon! "probe.schema")
    (is (= {"probe_param" {:type "string"}} (ext/get-schema-extensions "code")))
    (reset! exts {"code" {"probe_param" {:type "string"}
                          "late_param"  {:type "boolean"}}})
    (reactive/install!)
    (ext/contribute-commands! "code" :probe.schema {"late" {:handler identity}})
    (is (= {:type "boolean"} (get (ext/get-schema-extensions "code") "late_param"))
        "the advertised schema follows the addon's CURRENT schema-extensions")))

(deftest refresh-surface-without-a-server-reports-what-it-could-do
  (let [out (reactive/refresh-surface! "analysis")]
    (is (false? (:composite out)) "nothing contributed, nothing to build")
    (is (vector? (:schema-tools out)))
    (is (nil? (:server-tools out)) "no server context in a unit test")))
