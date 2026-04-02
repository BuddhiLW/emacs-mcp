(ns hive-mcp.config.io
  "Config file IO — reads and writes EDN config files.
   Effect boundary: all file-system operations isolated here.
   Returns Result values instead of swallowing errors."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-dsl.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Paths
;; =============================================================================

(def config-path
  "Canonical path for global hive config."
  (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn"))

(def legacy-config-path
  "Legacy path for backward compatibility migration."
  (str (System/getProperty "user.home") "/.config/hive.edn"))

;; =============================================================================
;; Read / Write
;; =============================================================================

(defn read-config-file
  "Read and parse an EDN config file.
   Returns Result: (ok map) on success, (ok nil) if file missing or not a map,
   (err :io/config-read ...) on parse/IO exception."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      (result/ok nil)
      (result/try-effect* :io/config-read
        (let [content (slurp f)
              parsed  (edn/read-string content)]
          (when (map? parsed)
            parsed))))))

(defn write-config!
  "Write config map to disk as EDN. Creates parent dirs if needed.
   Returns Result: (ok config) on success, (err :io/config-write ...) on failure."
  ([config] (write-config! config config-path))
  ([config path]
   (result/try-effect* :io/config-write
     (let [f      (io/file path)
           parent (.getParentFile f)]
       (when (and parent (not (.exists parent)))
         (.mkdirs parent))
       (spit f (pr-str config))
       (log/info "Config written to" path)
       config))))
