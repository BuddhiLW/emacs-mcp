(ns hive-mcp.engine.hprof.spec
  "Domain types for hprof rotation (ENGINE-L0.3, defense-in-depth layer 0).

   Pure layer — no IO, no side effects, no external deps. Used by
   `.policy` (pure decisions) and `.fs` / `.disk` (IO boundaries).

   A HprofFile is the addressable unit of work: a path on disk, a byte
   size, and a last-modified timestamp. Policies describe retention
   intent — how many dumps to keep, whether to compress survivors, and
   the disk-pressure floor that must hold for the JVM to safely boot.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord HprofFile [path bytes mtime-ms])

(def default-policy
  "Defaults are deliberately conservative:
   - storage dir under XDG-friendly user-share path
   - keep 3 most-recent dumps (debug recency vs disk cost)
   - gzip survivors (a 13GB dump compresses to ~1.5GB)
   - abort boot below 5GB free (gives JVM headroom for a fresh dump)."
  {:dir         (or (System/getenv "HIVE_HPROF_DIR")
                    (str (System/getProperty "user.home")
                         "/.local/share/hive-mcp/hprof"))
   :keep-n      3
   :gzip?       true
   :min-free-gb 5})

(defn merge-policy
  "Layer caller overrides onto `default-policy`. Caller maps win.
   Always returns a complete policy map (never nil)."
  [overrides]
  (merge default-policy (or overrides {})))
