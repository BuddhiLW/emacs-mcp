(ns hive-mcp.swarm.ledger
  "Durable, append-only swarm ledger over Datalevin/LMDB.

   A flat monotonic chain: each event is {:ledger/seq :ledger/ts :ledger/type
   :ledger/stream :ledger/payload}. Off-heap (LMDB mmap); queried on demand by
   seq-cursor, type, time-range, or tail; never fully materialized and never
   loaded at boot. Writers append terminal swarm events (task/claim/wave/kanban/
   health) here and retract the hot DataScript entity.

   Store construction is explicit (DIP): callers hold an ILedgerStore and pass
   it in. Reads cap result size via :limit (default 500) — never load all rows."
  (:require [clojure.edn :as edn]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]
            [hive-mcp.swarm.datalevin.driver :as dl]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Schema
;;; =============================================================================

(def ledger-schema
  "Flat append-only chain. :ledger/seq is the monotonic identity."
  {:ledger/seq     {:db/valueType :db.type/long    :db/unique :db.unique/identity}
   :ledger/ts      {:db/valueType :db.type/long    :db/index true}
   :ledger/type    {:db/valueType :db.type/keyword :db/index true}
   :ledger/stream  {:db/valueType :db.type/string  :db/index true}
   :ledger/payload {:db/valueType :db.type/string}})

(def ^:const default-limit
  "Cap on entries pulled per read. Bounds heap blast radius so a large ledger is
   never fully resident. Override via :limit; pass :limit nil to disable."
  500)

(def default-root
  (str (System/getProperty "user.home") "/.local/share/hive-mcp/swarm-ledger"))

;;; =============================================================================
;;; Protocol
;;; =============================================================================

(defprotocol ILedgerStore
  "Append-only durable ledger. Reads are bounded and ordered by :ledger/seq."
  (append! [store event]
    "event = {:type keyword (required) :payload map (required) :stream string (optional)}.
     Allocates the next monotonic :ledger/seq, persists write-through.
     Returns {:seq long :type kw :ts long} or {:error ...}.")
  (read-since [store cursor-seq opts]
    "Entries with :ledger/seq > cursor-seq, ascending, capped by (:limit opts).
     (:type opts) optionally filters by event type. Returns a vector of decoded
     entries (payload parsed) or [].")
  (read-time-range [store start-ms end-ms opts]
    "Entries with start-ms <= :ledger/ts <= end-ms, capped by (:limit opts).")
  (read-tail [store n]
    "The most recent ~n entries, ascending by seq.")
  (latest-seq [store]
    "Highest seq allocated by this store instance (long).")
  (close! [store]
    "Release the LMDB connection. Returns {:closed? true}."))

;;; =============================================================================
;;; Query helpers
;;; =============================================================================

(def ^:private pull-pattern
  [:ledger/seq :ledger/ts :ledger/type :ledger/stream :ledger/payload])

(defn- decode-entry
  "Parse the stored EDN payload back into data. Falls back to the raw string."
  [entry]
  (update entry :ledger/payload
          (fn [s] (rescue s (when s (edn/read-string s))))))

(defn- cap [limit eids]
  (if limit (take (long limit) eids) eids))

(defn- pull-sorted [db eids]
  (->> eids
       (map #(dl/pull db pull-pattern %))
       (map decode-entry)
       (sort-by :ledger/seq)
       vec))

(defn- q-since [db cursor]
  (dl/q '[:find [?e ...]
          :in $ ?c
          :where [?e :ledger/seq ?s] [(> ?s ?c)]]
        db (long cursor)))

(defn- q-since-typed [db cursor typ]
  (dl/q '[:find [?e ...]
          :in $ ?c ?t
          :where [?e :ledger/seq ?s] [(> ?s ?c)] [?e :ledger/type ?t]]
        db (long cursor) typ))

(defn- q-time-range [db a b]
  (dl/q '[:find [?e ...]
          :in $ ?a ?b
          :where [?e :ledger/ts ?ts] [(<= ?a ?ts)] [(<= ?ts ?b)]]
        db (long a) (long b)))

(defn- max-seq
  "Highest :ledger/seq on disk, or 0. One indexed column scan — not a full load."
  [conn]
  (long (or (dl/q '[:find (max ?s) . :where [_ :ledger/seq ?s]] (dl/db conn)) 0)))

;;; =============================================================================
;;; Record
;;; =============================================================================

(defrecord DatalevinLedgerStore [conn stream dir seq-counter]
  ILedgerStore

  (append! [_this event]
    (rescue {:error :ledger/append-failed}
      (let [s  (swap! seq-counter inc)
            ts (System/currentTimeMillis)
            ev {:ledger/seq     s
                :ledger/ts      ts
                :ledger/type    (:type event)
                :ledger/stream  (or (:stream event) stream)
                :ledger/payload (pr-str (:payload event))}]
        (dl/transact! conn [ev])
        {:seq s :type (:type event) :ts ts})))

  (read-since [_this cursor-seq {:keys [limit type] :or {limit default-limit}}]
    (rescue []
      (let [db   (dl/db conn)
            eids (if type
                   (q-since-typed db cursor-seq type)
                   (q-since db cursor-seq))]
        (pull-sorted db (cap limit eids)))))

  (read-time-range [_this start-ms end-ms {:keys [limit] :or {limit default-limit}}]
    (rescue []
      (let [db (dl/db conn)]
        (pull-sorted db (cap limit (q-time-range db start-ms end-ms))))))

  (read-tail [this n]
    (read-since this (max 0 (- (long @seq-counter) (long n))) {:limit n}))

  (latest-seq [_this] (long @seq-counter))

  (close! [_this]
    (rescue {:error :ledger/close-failed}
      (dl/close conn)
      {:closed? true})))

;;; =============================================================================
;;; Factory
;;; =============================================================================

(defn- ensure-dir! [path]
  (let [dir (java.io.File. path)]
    (when-not (.exists dir) (.mkdirs dir))))

(defn make-store
  "Open (creating if needed) a DatalevinLedgerStore.

   opts:
     :stream string (optional, default \"global\") — logical partition; also the
             default :ledger/stream for appends that omit one.
     :root   string (optional) — filesystem root; path = <root>/<stream>.
     :dir    string (optional) — explicit LMDB dir; overrides :root/:stream.

   Returns the store, or {:error ...} on failure."
  [{:keys [stream root dir]}]
  (rescue {:error :ledger/open-failed}
    (let [strm (or stream "global")
          path (or dir (str (or root default-root) "/" strm))
          _    (ensure-dir! path)
          conn (dl/get-conn path ledger-schema)]
      (log/info "Swarm ledger opened at" path)
      (->DatalevinLedgerStore conn strm path (atom (max-seq conn))))))

(comment
  ;; Smoke test against a throwaway temp dir (never the prod path).
  (let [tmp   (str (System/getProperty "java.io.tmpdir") "/ledger-smoke-" (System/nanoTime))
        store (make-store {:stream "hive" :dir tmp})]
    (append! store {:type :task/completed :payload {:task-id "t1" :agent "ling-a"}})
    (append! store {:type :claim/released :payload {:file "core.clj"}})
    (append! store {:type :task/completed :payload {:task-id "t2"}})
    (let [out {:since   (read-since store 0 {:limit 10})
               :typed   (read-since store 0 {:type :task/completed :limit 10})
               :tail    (read-tail store 2)
               :latest  (latest-seq store)}]
      (close! store)
      out)))
