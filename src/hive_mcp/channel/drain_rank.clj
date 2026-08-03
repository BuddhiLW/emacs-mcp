;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.channel.drain-rank
  "Two-lane ordering for the memory piggyback drain.

   `lane` puts axioms in the :floor lane and every other entry in the :pool
   lane. `select-batch` emits the floor lane first, FIFO and unranked, then the
   pool lane ordered by `score` against the current task tokens, and returns the
   char-budget prefix of that order. With no tokens the pool keeps FIFO order.
   `kw` is the String-or-Keyword normaliser every lookup here goes through.

   Operates on the buffered piggyback entry shape {:id :T :C (:S) (:tags)}.
   Pure — no IO, deterministic."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hive-mcp.tools.catchup.relevance :as rel]))

(def raw-tag-weight
  "Weight of a raw-tag hit."
  2.0)

(def topic-tag-weight
  "Weight of a topic-token hit."
  1.0)

(def offer-decay-weight
  "Score SUBTRACTED per prior offer that the entry did not convert into a
   delivery. An entry pushed repeatedly and never read sinks."
  0.15)

(def max-offer-decay
  "Ceiling on the accumulated offer decay, so a long-ignored entry sinks but
   never falls below the reach of a fresh cue hit."
  0.6)

(def access-weight
  "Weight of the entry's stored access count, saturating at 3 accesses."
  0.4)

(def feedback-weight
  "Weight of net helpful-minus-unhelpful feedback, clamped to [-1, 1]."
  0.5)

(def default-char-budget
  "Batch char budget used when ctx supplies none."
  12000)

(def floor-types
  "Entry types that fill the floor lane."
  #{:axiom})

(def default-floor-cap
  "Ceiling on floor-lane entries per drain. Overflow is demoted to the head of
   the pool, never dropped."
  8)

(def type-bias
  "Per-type score bias for pool entries."
  {:axiom 1.0
   :principle 0.6
   :convention 0.4
   :decision 0.2
   :plan 0.2
   :knowledge 0.1
   :snippet 0.1
   :note 0.0
   :ingestion 0.0})

(def default-type-bias
  "Bias for entry types absent from `type-bias`."
  0.0)

(defn kw
  "Normalise a String, Keyword or Symbol to an unqualified keyword.
   nil and blank strings normalise to nil."
  [x]
  (cond
    (nil? x) nil
    (keyword? x) (keyword (name x))
    (symbol? x) (keyword (name x))
    :else (let [s (str/replace (str/trim (str x)) #"^:+" "")]
            (when (seq s) (keyword s)))))

(defn lane
  "Lane of a buffered entry: :floor for `floor-types` or an id in `pins`,
   :pool otherwise. The 1-arity pins nothing."
  ([entry] (lane entry nil))
  ([entry pins]
   (if (or (contains? floor-types (kw (:T entry)))
           (and (seq pins) (contains? pins (:id entry))))
     :floor
     :pool)))

(defn- raw-tags
  "Lowercased raw tag set of an entry."
  [entry]
  (into #{}
        (comp (map str) (map str/lower-case) (remove str/blank?))
        (:tags entry)))

(defn- topic-tokens
  "Topic vocabulary of a lowercased raw tag set.

   Delegates to `relevance/topic-tags` — the ONE noise filter and compound
   expander, shared with the catchup lens so the two activation moments cannot
   drift into disagreeing about what a topic tag is."
  [raw]
  (rel/topic-tags raw))

(defn- offer-decay
  "Accumulated decay for `offers` prior unconverted offers, capped at
   `max-offer-decay`. Non-negative — the caller subtracts it."
  [offers]
  (min max-offer-decay (* offer-decay-weight (double (or offers 0)))))

(defn- access-credit
  "Credit for the entry's stored access count (:A), saturating at 3."
  [entry]
  (* access-weight (min 1.0 (/ (double (or (:A entry) 0)) 3.0))))

(defn- feedback-credit
  "Credit for the entry's net feedback (:F), clamped to [-1, 1]."
  [entry]
  (* feedback-weight (double (max -1 (min 1 (or (:F entry) 0))))))

(defn score
  "Score a pool entry against task `tokens` after `offers` prior offers.

   `raw-tag-weight` for any raw-tag hit, plus `topic-tag-weight` for any
   topic-token hit, plus the entry's `type-bias`, plus its access and feedback
   credit, MINUS `offer-decay-weight` per prior offer (capped at
   `max-offer-decay`).

   Access and feedback are read off the buffered entry's :A and :F keys, which
   `format-entry` copies from the stored counters. An entry lacking them scores
   as if never accessed and never rated."
  [entry tokens offers]
  (let [ts (cond (set? tokens) tokens
                 (nil? tokens) #{}
                 :else (set tokens))
        raw (raw-tags entry)
        topic (topic-tokens raw)
        raw-hit (if (seq (set/intersection ts raw)) 1.0 0.0)
        topic-hit (if (seq (set/intersection ts topic)) 1.0 0.0)
        bias (get type-bias (kw (:T entry)) default-type-bias)]
    (+ (* raw-tag-weight raw-hit)
       (* topic-tag-weight topic-hit)
       bias
       (access-credit entry)
       (feedback-credit entry)
       (- (offer-decay offers)))))

(defn- budget-prefix
  "Count of leading entries whose pr-str total fits `budget`.
   At least 1 whenever `entries` is non-empty."
  [entries budget]
  (let [n (count entries)]
    (loop [taken 0
           chars 0]
      (if (>= taken n)
        taken
        (let [c (+ chars (count (pr-str (nth entries taken))))]
          (if (and (pos? taken) (> c budget))
            taken
            (recur (inc taken) c)))))))

(defn select-batch
  "Two-lane order of `pending` plus the char-budget prefix of that order.

   ctx keys: :tokens (task cue tokens), :offers (map of entry :id -> prior
   offer count), :char-budget (defaults to `default-char-budget`), :pins (ids
   an activation rule promoted into the floor lane), :floor-cap (defaults to
   `default-floor-cap`).

   The floor lane is filled before the ranker runs: floor entries keep FIFO
   order and are never scored. The lane is CAPPED at :floor-cap — overflow is
   demoted to the pool rather than dropped, so one greedy rule cannot eat the
   whole budget. Pool entries are ordered by `score` descending with FIFO index
   as tiebreak, or kept FIFO when :tokens is empty.

   Returns {:ordered <every pending entry, floor lane first>
            :batch   <prefix of :ordered fitting the budget>
            :taken   <count of :batch>}."
  [pending {:keys [tokens offers char-budget pins floor-cap]}]
  (let [pin-set (set pins)
        indexed (vec (map-indexed vector pending))
        floor-all (filterv (fn [[_ e]] (= :floor (lane e pin-set))) indexed)
        cap (or floor-cap default-floor-cap)
        floor (into [] (comp (take cap) (map second)) floor-all)
        demoted (into [] (drop cap) floor-all)
        pool-idx (into demoted (remove (fn [[_ e]] (= :floor (lane e pin-set)))) indexed)
        ts (set tokens)
        pool (if (seq ts)
               (into []
                     (map second)
                     (sort-by (fn [[i e]] [(- (score e ts (get offers (:id e) 0))) i])
                              pool-idx))
               (into [] (map second) (sort-by first pool-idx)))
        ordered (into floor pool)
        taken (budget-prefix ordered (or char-budget default-char-budget))]
    {:ordered ordered
     :batch (subvec ordered 0 taken)
     :taken taken}))
