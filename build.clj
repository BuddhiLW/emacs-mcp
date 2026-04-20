(ns build
  "tools.build script for hive-mcp uberjar packaging.

   Usage:
     clj -T:build uber              ; default uberjar
     clj -T:build uber :profile k8s-headless  ; with addon profile
     clj -T:build clean             ; remove target/"
  (:require [clojure.tools.build.api :as b]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def lib 'io.github.hive-agi/hive-mcp)
(def version (format "0.16.0-SNAPSHOT"))
(def class-dir "target/classes")
(def uber-file "target/hive-mcp.jar")

;; Main namespace with (:gen-class) and -main
(def main-ns 'hive-mcp.server.core)

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Build an uberjar.

   Options:
     :profile - deps.edn alias to merge (e.g. 'k8s-headless').
                Resolved as keyword alias for extra-deps/paths."
  [{:keys [profile] :as opts}]
  (clean opts)
  (let [;; Base basis from project deps.edn
        ;; When a profile alias exists (e.g. :k8s-headless), merge it
        ;; so that addon deps from the profile are included in the jar.
        aliases (cond-> [:mcp]
                  profile (conj (keyword profile)))
        basis   (b/create-basis {:project "deps.edn"
                                 :aliases aliases})]
    (println "Building uberjar with aliases:" aliases)
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis     basis
                    :src-dirs  ["src"]
                    :class-dir class-dir
                    :ns-compile [main-ns]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns})
    (println "Uberjar built:" uber-file)))
