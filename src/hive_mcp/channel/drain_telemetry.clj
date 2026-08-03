;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.channel.drain-telemetry
  "Offer/delivery ledger for the memory piggyback drain.

   `record!` accumulates, per memory entry id, how many drains OFFERED the entry
   (ranked it into the order but left it outside the char budget) and how many
   DELIVERED it (it rode a batch). `shelf-report` joins that against a caller
   supplied `lookup` of the entry's stored access + feedback counters and
   returns one row per id.

   The ledger is pure in-memory accumulation on the drain path — no IO, no store
   read. `store-lookup` is the only IO here and it runs at report time.

   Bounded: 4000 ids, 12h TTL, LRU."
  (:require [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bclear!
                                           register-sweepable!]]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as mp]
            [taoensso.timbre :as log]))

(defonce ^{:doc "Map of memory entry id -> {:offers :delivered :last-seq}."}
  ledger
  (bounded-atom {:max-entries 4000
                 :ttl-ms 43200000
                 :eviction-policy :lru}))
(register-sweepable! ledger :drain-telemetry)

(defn record!
  "Fold one drain outcome into the ledger.

   `offered-ids` are ids ranked into the order but not taken this drain;
   `delivered-ids` are ids that rode the batch. Both may be nil. `seq-num` is
   the drain sequence number, stored as :last-seq. Returns nil."
  [{:keys [offered-ids delivered-ids seq-num]}]
  (letfn [(bump! [k id]
            (when id
              (let [prior (or (bget ledger id) {:offers 0 :delivered 0})]
                (bput! ledger id (-> prior
                                     (update k (fnil inc 0))
                                     (assoc :last-seq seq-num))))))]
    (run! (partial bump! :offers) offered-ids)
    (run! (partial bump! :delivered) delivered-ids)
    nil))

(defn snapshot
  "Ledger as a plain map of id -> {:offers :delivered :last-seq}."
  []
  (reduce-kv (fn [m id entry] (assoc m id (:data entry)))
             {}
             @(:atom ledger)))

(defn reset!
  "Clear the ledger. For testing and for starting a fresh measurement window."
  []
  (bclear! ledger))

(defn store-lookup
  "Store-backed `lookup` for `shelf-report`: id -> {:access-count :helpful-count
   :unhelpful-count}. Returns a fn yielding nil for every id when no store is
   configured. Never throws."
  []
  (if-not (mp/store-set?)
    (constantly nil)
    (let [store (mp/get-store)]
      (fn [id]
        (try
          (when-let [e (ports/get-entry store id)]
            (select-keys e [:access-count :helpful-count :unhelpful-count]))
          (catch Throwable t
            (log/debug t "drain-telemetry: lookup failed for" id)
            nil))))))

(defn shelf-report
  "One row per ledgered id, joined against `lookup`, worst shelf first.

   Row keys: :id :offers :delivered :last-seq :access-count :helpful-count
   :unhelpful-count :shelf-score. `shelf-score` is offers + delivered weighted
   against access — high means the entry keeps being pushed and never read.

   Sorted by :shelf-score descending. Pure given `lookup`."
  [lookup]
  (->> (snapshot)
       (map (fn [[id {:keys [offers delivered last-seq]}]]
              (let [{:keys [access-count helpful-count unhelpful-count]} (lookup id)
                    o (or offers 0)
                    d (or delivered 0)
                    a (or access-count 0)]
                {:id id
                 :offers o
                 :delivered d
                 :last-seq last-seq
                 :access-count a
                 :helpful-count (or helpful-count 0)
                 :unhelpful-count (or unhelpful-count 0)
                 :shelf-score (double (- (+ o d) (* 2 a)))})))
       (sort-by (juxt (comp - :shelf-score) :id))
       vec))

(defn summary
  "Aggregate counters over the ledger: entry count, total offers, total
   deliveries, and the offer:delivery ratio (nil when nothing was delivered)."
  []
  (let [rows (vals (snapshot))
        offers (reduce + 0 (keep :offers rows))
        delivered (reduce + 0 (keep :delivered rows))]
    {:entries (count rows)
     :offers offers
     :delivered delivered
     :offer-per-delivery (when (pos? delivered) (double (/ offers delivered)))}))
