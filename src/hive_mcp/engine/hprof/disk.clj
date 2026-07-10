(ns hive-mcp.engine.hprof.disk
  "Disk-pressure probe for the hprof boot guard (ENGINE-L0.3).

   The hprof sweep can shrink existing dumps, but only the underlying
   filesystem can answer 'is there room for a fresh heap dump?'. This
   namespace wraps `java.io.File.getUsableSpace` so the boot
   orchestrator can refuse to start when the residual free space is
   below the policy floor."
  (:require [clojure.java.io :as io])
  (:import (java.io File)))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn free-bytes
  "Return usable bytes on the filesystem containing `path`. Walks up to
   the nearest existing parent so a path that has not been mkdir'd yet
   still yields an accurate answer. Returns 0 when no existing
   ancestor is found (effectively: 'disk is gone, refuse to boot')."
  [path]
  (loop [^File f (io/file path)]
    (cond
      (nil? f)    0
      (.exists f) (.getUsableSpace f)
      :else       (recur (.getParentFile f)))))

(defn free-gb
  "Free space in gibibytes (1024³ bytes per GiB)."
  [path]
  (/ (double (free-bytes path)) 1024.0 1024.0 1024.0))

(defn under-pressure?
  "True when the filesystem holding `path` reports less than
   `min-free-gb` of usable space."
  [path min-free-gb]
  (< (free-gb path) (double min-free-gb)))
