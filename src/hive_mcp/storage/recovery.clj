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

   Future strategies (deferred to follow-ups):
   - `:truncate`   — rewind WAL to last good LSN via `dl/open-tx-log`
                     replay. Needs upstream datalevin support to
                     surface 'good-up-to' watermarks; tracked
                     separately.

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
   we treat ambiguous cases as `:unknown` and let the caller's policy
   decide whether to escalate."
  ["MDB_CORRUPTED"
   "MDB_PAGE_NOTFOUND"
   "MDB_INVALID"
   "MDB_CURSOR_FULL"
   "Page not found"
   "WAL corrupt"
   "txlog corrupt"
   "Invalid txlog"
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
  "Operator-tunable default. `:throw` preserves pre-L1.2 semantics."
  {:strategy :throw
   :max-attempts 2})

(defn- apply-strategy
  "Run the configured recovery strategy for a classified failure.
   Returns `:retry` when the caller should attempt `open-fn` again
   (because the on-disk state was healed), or `:abort` when the
   classification is non-recoverable or the policy says to escalate."
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
     :strategy       :throw | :audit | :quarantine
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
