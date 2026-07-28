(ns hive-mcp.storage.recovery
  "Datalevin txlog self-heal at boot (ENGINE-L1.2).

   When the JVM dies ungracefully, the LMDB-backed Datalevin store can
   leave a partially-written WAL segment that makes the next
   `dtlv/get-conn` throw. The 2026-05-11 incident chain — WAL corrupt
   → ensure-conn! throws → every caller retries inline → file-lock
   contention → 13.5GB heap death — is exactly that failure shape.

   L1.1 (per-slot breaker) catches the retry storm AFTER the first
   throw; L1.2a (IConnInit) prevents concurrent re-opens of the same
   conn. L1.2 (this ns) closes the loop: classify the open failure
   and apply a recovery policy so the store can return to a healthy
   state without operator intervention.

   ## Policy (operator-configurable)

   - `:throw`      — preserve pre-L1.2 behaviour. Open failure
                     surfaces verbatim. Safe default for stores whose
                     data loss tolerance is zero.
   - `:quarantine` — on `:wal-corrupt`, move the entire db directory
                     aside to `<db>.corrupt.<ts>` and open a fresh
                     store. Data loss is the entire WAL since the
                     last successful close; acceptable when the
                     alternative is the OOM cascade and the operator
                     accepts the trade-off.
   - `:audit`      — never modify the on-disk store. Classify the
                     failure, emit telemetry, and rethrow. Useful in
                     CI / staging where you want to know what would
                     have triggered a quarantine without acting on it.
   - `:truncate`   — rewind WAL to the last good record via
                     `datalevin.txlog/truncate-partial-tail!`. Only
                     fires on `:wal-corrupt` with a recoverable
                     tail-zeroed signature (the post-crash unflushed
                     page case). Mid-segment corruption falls through
                     to the next strategy. Forensic copy of each
                     truncated segment lands at
                     `<db>.txlog-heal.<ts>/segment-N.wal.before-truncate`
                     before any mutation, per the No-NUKE axiom.

   ## Contract

   - Classification is pure. `classify-open-failure` examines an
     exception (any Throwable) and returns one of
     `#{:wal-corrupt :lock-contention :version-mismatch :unknown}`.
   - Quarantine is IO. `quarantine!` atomically renames the on-disk
     store directory to `(quarantine-path db-path)`, returning the
     new path.
   - `heal-and-open!` composes classification + policy + the caller's
     `open-fn`. It NEVER opens the db itself — that responsibility
     stays with the caller so we don't import datalevin transitively
     into a pure recovery ns."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Classification — pure
;; -----------------------------------------------------------------------------

(def ^:private corrupt-signatures
  "Substring fragments that indicate on-disk corruption. Conservative —
   ambiguous cases stay `:unknown` and the caller's policy decides.

   Two families:
   - LMDB-level page corruption (`MDB_*`).
   - Datalevin txn-log corruption — upstream uses `txn-log` (hyphenated)
     in error messages; pre-rename code used `txlog` / `WAL`. Match
     both so a wording flip upstream does not silently degrade us to
     `:unknown` (which is what produced the 2026-05-19 :carto incident)."
  ["MDB_CORRUPTED"
   "MDB_PAGE_NOTFOUND"
   "MDB_INVALID"
   "MDB_CURSOR_FULL"
   "Page not found"
   "WAL corrupt"
   "txlog corrupt"
   "Invalid txlog"
   "Invalid txn-log"
   "Txn-log segment"
   "txn-log corrupt"
   "txn-log"
   "checksum mismatch"])

(def ^:private lock-signatures
  ["Resource temporarily unavailable"
   "MDB_BUSY"
   "EAGAIN"
   "lock"])

(def ^:private version-signatures
  ["MDB_VERSION_MISMATCH"
   "version mismatch"
   "schema version"])

(defn- match-any?
  [^String msg signatures]
  (when msg
    (boolean (some #(str/includes? msg %) signatures))))

(defn classify-open-failure
  "Classify a Throwable raised by an LMDB-level open. Returns one of
   `:wal-corrupt`, `:lock-contention`, `:version-mismatch`, `:unknown`.

   Walks the cause chain so an `ExecutionException` wrapping the real
   LMDB error still classifies correctly."
  [^Throwable ex]
  (loop [t ex]
    (let [msg (some-> t .getMessage str)]
      (cond
        (match-any? msg corrupt-signatures)    :wal-corrupt
        (match-any? msg version-signatures)    :version-mismatch
        (match-any? msg lock-signatures)       :lock-contention
        (and t (.getCause t))                  (recur (.getCause t))
        :else                                  :unknown))))

;; -----------------------------------------------------------------------------
;; Quarantine — IO
;; -----------------------------------------------------------------------------

(defn quarantine-path
  "Derive the quarantine path for `db-path`. Deterministic given a
   timestamp; defaults to `System/currentTimeMillis` for live calls."
  ([db-path] (quarantine-path db-path (System/currentTimeMillis)))
  ([db-path ts]
   (str db-path ".corrupt." ts)))

(defn quarantine!
  "Move the db directory aside to `quarantine-path`. Returns the new
   path on success, nil if the source doesn't exist. Throws on IO
   failure — quarantine is a last-resort act and a failed move is
   itself an incident worth surfacing."
  ([db-path] (quarantine! db-path (quarantine-path db-path)))
  ([db-path target-path]
   (let [src (io/file db-path)]
     (when (.exists src)
       (let [dst (io/file target-path)]
         (when-not (.renameTo src dst)
           (throw (ex-info "Failed to quarantine corrupt datalevin store"
                           {:src db-path :dst target-path
                            :err :storage/quarantine-failed})))
         (log/warn "[storage/recovery] Quarantined corrupt datalevin store"
                   {:src db-path :dst target-path})
         target-path)))))

;; -----------------------------------------------------------------------------
;; Txn-log operations — DIP port
;; -----------------------------------------------------------------------------

(defprotocol ITxlogOps
  "Txn-log segment operations a recovery strategy needs from the store engine."
  (scan-segment [this path opts]
    "Scan the segment at PATH. Returns a map carrying :partial-tail?; throws
     when the segment cannot be read under OPTS.")
  (segment-files [this dir]
    "Seq of {:file java.io.File} for the segments under DIR.")
  (truncate-partial-tail! [this path opts]
    "Trim PATH's unflushed tail. Returns {:old-size :new-size :dropped-bytes}."))

(defrecord DatalevinTxlogOps []
  ITxlogOps
  (scan-segment [_ path opts]
    ((requiring-resolve (quote datalevin.txlog/scan-segment)) path opts))
  (segment-files [_ dir]
    ((requiring-resolve (quote datalevin.txlog/segment-files)) dir))
  (truncate-partial-tail! [_ path opts]
    ((requiring-resolve (quote datalevin.txlog/truncate-partial-tail!)) path opts)))

(defonce ^:private txlog-ops
  (atom (->DatalevinTxlogOps)))

(defn current-txlog-ops
  "The registered ITxlogOps implementation."
  []
  @txlog-ops)

(defn set-txlog-ops!
  "Register OPS as the ITxlogOps implementation. Returns OPS."
  [ops]
  (reset! txlog-ops ops)
  ops)

;; -----------------------------------------------------------------------------
;; Truncate — IO (datalevin txn-log tail-zero recovery)
;;
;; Datalevin pre-allocates each WAL segment to 256MB and writes records
;; into the prefix. A crash mid-write can leave the file with valid
;; records up to some offset, then a partial/zeroed tail. The strict
;; scan path treats those zeros as an "Invalid txn-log record magic"
;; and refuses to open the store. The lenient scan path (with
;; `:allow-preallocated-tail? true`) reports the same file as
;; `{:partial-tail? true :valid-end <offset>}`. `truncate-partial-tail!`
;; then trims the file to `valid-end`, after which a strict reopen
;; succeeds.
;;
;; This recovery preserves data integrity: any record before
;; `valid-end` is fully present in the on-disk format the strict
;; scanner already accepts. The only thing lost is the trailing
;; pre-allocated zeros (which carried no records anyway).
;; -----------------------------------------------------------------------------

(defn- txlog-segment-dir
  "Locate the txn-log segment directory under a datalevin db-path.
   Datalevin lays it out as `<db-path>/txlog/`."
  [db-path]
  (io/file db-path "txlog"))

(defn- segment-needs-heal?
  "Return :tail-zeroed if the segment can be loaded only with
   `:allow-preallocated-tail? true`; :ok if strict scan succeeds;
   :unhealable if even lenient scan refuses the file."
  [ops ^java.io.File seg-file]
  (let [path   (.getAbsolutePath seg-file)
        strict (try (scan-segment ops path {:collect-records? false
                                            :allow-preallocated-tail? false})
                    :ok
                    (catch Throwable _ :strict-failed))]
    (if (= :ok strict)
      :ok
      (try
        (let [lenient (scan-segment ops path {:collect-records? false
                                              :allow-preallocated-tail? true})]
          (if (:partial-tail? lenient)
            :tail-zeroed
            :unhealable))
        (catch Throwable _ :unhealable)))))

(defn- forensic-copy!
  "Copy a segment file to a heal-backup sibling directory before
   truncate. Returns the backup path string."
  [^java.io.File seg-file db-path ts]
  (let [backup-dir (io/file (str db-path ".txlog-heal." ts))
        backup-fn  (io/file backup-dir
                            (str (.getName seg-file) ".before-truncate"))]
    (.mkdirs backup-dir)
    (io/copy seg-file backup-fn)
    (.getAbsolutePath backup-fn)))

(defn truncate-tail!
  "Walk the txn-log segment directory under `db-path`. For each
   segment whose strict scan fails but lenient scan reports
   `:partial-tail? true`, copy it aside and trim it via the registered
   `ITxlogOps`.

   Returns a vec of per-segment heal records. An empty result means
   no segment needed truncation (in which case the caller should
   treat as a no-op heal — likely the corruption was elsewhere).

   `ops` defaults to `(current-txlog-ops)`; pass an explicit
   implementation to drive the walk without a store engine present."
  ([db-path] (truncate-tail! db-path (System/currentTimeMillis) (current-txlog-ops)))
  ([db-path ts] (truncate-tail! db-path ts (current-txlog-ops)))
  ([db-path ts ops]
   (let [seg-dir (txlog-segment-dir db-path)
         segs    (when (.isDirectory seg-dir)
                   (segment-files ops (.getAbsolutePath seg-dir)))]
     (reduce
      (fn [acc {:keys [^java.io.File file]}]
        (try
          (case (segment-needs-heal? ops file)
            :ok          acc
            :tail-zeroed (let [backup (forensic-copy! file db-path ts)
                               result (truncate-partial-tail!
                                       ops
                                       (.getAbsolutePath file)
                                       {:allow-preallocated-tail? true
                                        :collect-records? false})]
                           (log/warn "[storage/recovery] Truncated txn-log segment"
                                     {:path (.getAbsolutePath file)
                                      :backup backup
                                      :old-size (:old-size result)
                                      :new-size (:new-size result)
                                      :dropped-bytes (:dropped-bytes result)})
                           (conj acc (-> result
                                         (assoc :path (.getAbsolutePath file)
                                                :backup backup
                                                :sub-classification :tail-zeroed))))
            :unhealable  (do (log/warn "[storage/recovery] Segment unhealable — mid-segment corruption"
                                       {:path (.getAbsolutePath file)})
                             (conj acc {:path (.getAbsolutePath file)
                                        :truncated? false
                                        :sub-classification :mid-segment})))
          (catch Throwable t
            (log/error t "[storage/recovery] Segment heal failed"
                       {:path (.getAbsolutePath file)})
            (conj acc {:path (.getAbsolutePath file)
                       :truncated? false
                       :error (.getMessage t)}))))
      []
      segs))))

;; -----------------------------------------------------------------------------
;; Telemetry
;; -----------------------------------------------------------------------------

(defn- emit!
  "Best-effort dispatch into the event bus. Late-resolves so this ns
   stays pure of compile-time event deps. Wrapped in `rescue` so a
   missing handler is non-fatal."
  [event-type payload]
  (rescue nil
    (when-let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
      (dispatch [event-type payload]))))

;; -----------------------------------------------------------------------------
;; Policy dispatch
;; -----------------------------------------------------------------------------

(def default-policy
  "Operator-tunable default. `:throw` preserves pre-L1.2 semantics.

   `:strategy` may be a single keyword or a vector of keywords. When a
   vector is supplied, strategies are tried left-to-right per failure
   until one returns `:retry`; only after every entry has returned
   `:abort` does `heal-and-open!` give up."
  {:strategy :throw
   :max-attempts 2})

(defn- apply-single-strategy
  "Run one recovery strategy keyword for a classified failure. Returns
   `:retry` when the caller should attempt `open-fn` again, or
   `:abort` when this strategy can't make progress (the chained
   walker in `apply-strategy` then tries the next entry, if any)."
  [strategy classification db-path ex]
  (case strategy
    :throw
    (do (emit! :storage/open-failed
               {:db-path db-path
                :classification classification
                :strategy strategy
                :message (.getMessage ex)})
        :abort)

    :audit
    (do (log/warn "[storage/recovery] Audit mode — would have applied recovery"
                  {:db-path db-path :classification classification})
        (emit! :storage/open-failed
               {:db-path db-path
                :classification classification
                :strategy strategy
                :audit-only true
                :message (.getMessage ex)})
        :abort)

    :truncate
    (if (= :wal-corrupt classification)
      (let [report (truncate-tail! db-path)
            healed (filter :truncated? report)]
        (if (seq healed)
          (do (emit! :storage/wal-truncated
                     {:db-path db-path
                      :classification classification
                      :segments report})
              :retry)
          (do (emit! :storage/open-failed
                     {:db-path db-path
                      :classification classification
                      :strategy strategy
                      :note (if (seq report)
                              "policy=:truncate but no segment had a recoverable tail (mid-segment corruption)"
                              "policy=:truncate but no segments found under txlog/")
                      :segments report
                      :message (.getMessage ex)})
              :abort)))
      (do (emit! :storage/open-failed
                 {:db-path db-path
                  :classification classification
                  :strategy strategy
                  :note "policy=:truncate but classification not :wal-corrupt"
                  :message (.getMessage ex)})
          :abort))

    :quarantine
    (if (= :wal-corrupt classification)
      (let [target (quarantine! db-path)]
        (emit! :storage/wal-quarantined
               {:db-path db-path
                :quarantine-path target
                :classification classification})
        :retry)
      (do (emit! :storage/open-failed
                 {:db-path db-path
                  :classification classification
                  :strategy strategy
                  :note "policy=quarantine but classification not :wal-corrupt"
                  :message (.getMessage ex)})
          :abort))))

(defn- apply-strategy
  "Dispatch `strategy` (keyword or vec of keywords) to the per-failure
   handler. Vector form walks entries left-to-right and returns
   `:retry` on the first one that succeeds; `:abort` if all entries
   abort. Single keyword form delegates directly."
  [strategy classification db-path ex]
  (if (sequential? strategy)
    (reduce
     (fn [_ s]
       (let [outcome (apply-single-strategy s classification db-path ex)]
         (if (= :retry outcome)
           (reduced :retry)
           :abort)))
     :abort
     strategy)
    (apply-single-strategy strategy classification db-path ex)))

;; -----------------------------------------------------------------------------
;; Public composition
;; -----------------------------------------------------------------------------

(defn heal-and-open!
  "Attempt `(open-fn)`. On failure, classify, apply policy, retry up
   to `:max-attempts` total attempts. Returns the conn value or
   rethrows the last exception with `:classification` in the
   `ex-data` for upstream classifiers (per
   `[[20260423164323-7bcfef91]] classify-then-retry`).

   `policy` shape — see `default-policy`:
     :strategy       keyword | [keyword ...]
                     Keywords: :throw | :audit | :truncate | :quarantine
                     A vector chains strategies — each entry is tried
                     left-to-right per failure until one succeeds.
                     Example: `[:truncate :quarantine :throw]` heals
                     tail-zeroed corruption in place, falls back to
                     quarantine on unhealable corruption, finally
                     throws so the caller can decide.
     :max-attempts   positive int (default 2)

   `db-path` is informational — it's the directory the caller asks
   `open-fn` to open. The policy may mutate that on-disk state
   (e.g. `:quarantine`); `open-fn` is then re-invoked.

   `open-fn` is a 0-arg thunk that produces the conn. We accept it
   as a thunk rather than a fixed Datalevin call so this ns stays
   storage-agnostic — DataScript or any other LMDB-shaped store can
   reuse it."
  [{:keys [policy db-path]} open-fn]
  (let [{:keys [strategy max-attempts]} (merge default-policy policy)]
    (loop [attempt 1
           last-ex nil]
      (if (> attempt max-attempts)
        (throw (ex-info "heal-and-open! exhausted attempts"
                        {:db-path db-path
                         :attempts (dec attempt)
                         :classification (some-> last-ex classify-open-failure)
                         :err :storage/heal-exhausted}
                        last-ex))
        (let [result (try {:ok (open-fn)}
                          (catch Throwable t {:ex t}))]
          (if-let [conn (:ok result)]
            conn
            (let [ex (:ex result)
                  classification (classify-open-failure ex)]
              (log/warn ex "[storage/recovery] Open attempt"
                        attempt "of" max-attempts "failed"
                        {:db-path db-path :classification classification})
              (case (apply-strategy strategy classification db-path ex)
                :retry  (recur (inc attempt) ex)
                :abort  (throw (ex-info "Datalevin open failed and policy aborted retry"
                                        {:db-path db-path
                                         :classification classification
                                         :strategy strategy
                                         :err :storage/open-aborted}
                                        ex))))))))))
