(ns hive-mcp.engine.hprof.boot
  "Boot-time hprof rotation + disk-pressure guard (ENGINE-L0.3).

   Defense-in-depth layer 0: stop the cascade where a chain of OOM
   crashes fills the disk with 13GB hprof dumps and the next JVM start
   either fails for lack of room or amplifies the outage.

   Orchestrates three small modules:
   - `.spec`   — policy defaults
   - `.policy` — pure 'which files to keep/gzip/delete' decisions
   - `.fs`     — filesystem effects (move, gzip, delete, listing)
   - `.disk`   — usable-space probe

   Entry point: `(boot!)`. Call once from `server/core/start!` before
   Integrant init; failures here are non-fatal except for the disk
   floor, which aborts boot via `abort-fn`."
  (:require [hive-mcp.engine.hprof.spec   :as spec]
            [hive-mcp.engine.hprof.policy :as policy]
            [hive-mcp.engine.hprof.fs     :as fs]
            [hive-mcp.engine.hprof.disk   :as disk]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- gather!
  "Migrate stray hprofs from CWD into the storage dir, then list the
   storage dir. Returns a vector of HprofFile records."
  [storage-dir]
  (fs/ensure-dir! storage-dir)
  (let [cwd        (System/getProperty "user.dir")
        cwd-hprofs (fs/list-hprofs cwd)
        moved      (vec (keep #(fs/move-to-dir! % storage-dir) cwd-hprofs))]
    (when (seq cwd-hprofs)
      (log/info "[hprof] Moved" (count moved) "of" (count cwd-hprofs)
                "stray hprof(s) from CWD into" storage-dir))
    (fs/list-hprofs storage-dir)))

(defn- apply-classification!
  [{:keys [gzip delete]}]
  (doseq [h gzip]   (fs/gzip!   h))
  (doseq [h delete] (fs/delete! h)))

(defn rotate!
  "Run the rotation policy. Returns a summary map of before/after byte
   totals and the number of files in each bucket. Safe to call on an
   empty directory (returns zeros)."
  ([] (rotate! {}))
  ([overrides]
   (let [policy      (spec/merge-policy overrides)
         hprofs      (gather! (:dir policy))
         classified  (policy/classify hprofs policy)
         before      (policy/total-bytes hprofs)]
     (apply-classification! classified)
     (let [after        (fs/list-hprofs (:dir policy))
           after-bytes  (policy/total-bytes after)]
       (log/info "[hprof] Rotation: kept" (count (:keep classified))
                 "| gzipped" (count (:gzip classified))
                 "| deleted" (count (:delete classified))
                 "| bytes" before "→" after-bytes)
       {:before    before
        :after     after-bytes
        :kept      (count (:keep classified))
        :gzipped   (count (:gzip classified))
        :deleted   (count (:delete classified))
        :dir       (:dir policy)}))))

(defn enforce-disk-floor!
  "Abort boot when free space on the storage filesystem is below
   `:min-free-gb`. `abort-fn` defaults to `System/exit` with exit-code
   78 (EX_CONFIG) — tests pass a stub. Returns nil on success."
  ([overrides] (enforce-disk-floor! overrides (fn [code] (System/exit code))))
  ([overrides abort-fn]
   (let [{:keys [dir min-free-gb]} (spec/merge-policy overrides)
         free-gb (disk/free-gb dir)]
     (when (disk/under-pressure? dir min-free-gb)
       (log/error "[hprof] Boot aborted — free space"
                  (format "%.2f" free-gb) "GB <"
                  min-free-gb "GB on" dir
                  "(ENGINE-L0.3 disk-pressure guard)")
       (abort-fn 78))
     nil)))

(defn boot!
  "Single boot-time entrypoint. Rotates stale hprofs, then enforces the
   disk floor. Rotation failures are swallowed (non-fatal) so a
   transient FS error never blocks server startup; the disk floor is
   the hard backstop."
  ([] (boot! {}))
  ([overrides]
   (try
     (rotate! overrides)
     (catch Throwable t
       (log/warn t "[hprof] rotation failed (non-fatal) — proceeding to disk-floor check")))
   (enforce-disk-floor! overrides)))
