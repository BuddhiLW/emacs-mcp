(ns hive-mcp.engine.hprof.fs
  "Filesystem boundary for hprof files (ENGINE-L0.3).

   Side-effecting operations: list, move, gzip, delete. Pure decisions
   live in `.policy` — this namespace only translates intent into IO.

   Public functions return updated `HprofFile` records or simple boolean
   success values; failures log and yield nil/false so a partial
   rotation never aborts the rest of the sweep."
  (:require [hive-mcp.engine.hprof.spec :as spec]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import (java.io File FileInputStream FileOutputStream)
           (java.util.zip GZIPOutputStream)))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private hprof-name-re
  ;; Matches `java_pid<n>.hprof[.gz]` and any other `*.hprof[.gz]`.
  #".*\.hprof(?:\.gz)?")

(defn- hprof-file?
  [^File f]
  (and (.isFile f)
       (boolean (re-matches hprof-name-re (.getName f)))))

(defn file->hprof
  "Lift a java.io.File into the domain HprofFile record."
  [^File f]
  (spec/->HprofFile (.getCanonicalPath f) (.length f) (.lastModified f)))

(defn list-hprofs
  "Return a vector of HprofFile records found under `dir`. Empty when
   `dir` does not exist or is not a directory."
  [dir]
  (let [d (io/file dir)]
    (if (.isDirectory d)
      (->> (.listFiles d)
           (filter hprof-file?)
           (mapv file->hprof))
      [])))

(defn ensure-dir!
  "Create `dir` (with parents) when missing. Returns the java.io.File."
  [dir]
  (let [d (io/file dir)]
    (when-not (.exists d) (.mkdirs d))
    d))

(defn move-to-dir!
  "Rename `hprof` into `target-dir`. Returns a refreshed HprofFile or
   nil when the rename failed (typically: cross-device move)."
  [{:keys [path]} target-dir]
  (let [src (io/file path)
        dst (io/file target-dir (.getName src))]
    (if (.renameTo src dst)
      (file->hprof dst)
      (do (log/warn "[hprof] move failed:" path "→" (.getCanonicalPath dst))
          nil))))

(defn gzip!
  "Gzip a hprof in place — produces `<path>.gz`, deletes the original
   on success. Returns the path to the `.gz` file or nil on failure.

   Atomic + idempotent so an interrupted boot never causes a costly
   re-gzip of a multi-GB dump:
   - If a non-empty `<path>.gz` already exists, treat it as a completed
     prior compression: drop the raw and skip re-deflating.
   - Otherwise deflate to a `.gz.tmp` and atomically rename on success.
     A killed run leaves only an orphan tmp (cleaned on retry), never a
     partial `.gz` alongside a surviving raw — the state that made boot
     re-compress the same 13GB dump on every restart."
  [{:keys [path]}]
  (let [src (io/file path)
        dst (io/file (str path ".gz"))
        tmp (io/file (str path ".gz.tmp"))]
    (cond
      (and (.isFile dst) (pos? (.length dst)))
      (do (log/info "[hprof] gzip skipped — .gz already present, dropping raw:" path)
          (.delete src)
          (.getCanonicalPath dst))

      :else
      (try
        (with-open [in  (FileInputStream. src)
                    out (GZIPOutputStream. (FileOutputStream. tmp))]
          (io/copy in out))
        (if (.renameTo tmp dst)
          (do (.delete src)
              (.getCanonicalPath dst))
          (do (log/warn "[hprof] gzip rename failed:" (.getCanonicalPath tmp))
              (try (.delete tmp) (catch Throwable _ nil))
              nil))
        (catch Throwable t
          (log/warn t "[hprof] gzip failed:" path)
          (try (.delete tmp) (catch Throwable _ nil))
          nil)))))

(defn delete!
  "Best-effort delete. Returns true on success, false otherwise."
  [{:keys [path]}]
  (try
    (boolean (.delete (io/file path)))
    (catch Throwable t
      (log/warn t "[hprof] delete failed:" path)
      false)))
