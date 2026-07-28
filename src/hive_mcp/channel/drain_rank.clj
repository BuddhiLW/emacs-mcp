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
            [clojure.string :as str]))

(def raw-tag-weight
  "Weight of a raw-tag hit."
  2.0)

(def topic-tag-weight
  "Weight of a topic-token hit."
  1.0)

(def age-weight
  "Score added per prior offer of an entry."
  0.05)

(def default-char-budget
  "Batch char budget used when ctx supplies none."
  12000)

(def floor-types
  "Entry types that fill the floor lane."
  #{:axiom})

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

(def ^:private noise-tag-prefixes
  ["agent:" "scope:" "kg:" "qn:" "ns:" "carto" "kanban" "priority-"])

(def ^:private noise-tag-exact
  #{"axiom" "principle" "convention" "decision" "snippet" "note"
    "todo" "doing" "review" "done" "permanent" "long" "medium" "short"
    "ephemeral" "global"})

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
  "Lane of a buffered entry: :floor for `floor-types`, :pool otherwise."
  [entry]
  (if (contains? floor-types (kw (:T entry))) :floor :pool))

(defn- noise-tag?
  "True for tags carrying no topic signal — namespacing prefixes, shape
   markers, status and duration words."
  [^String s]
  (or (contains? noise-tag-exact s)
      (boolean (some (fn [^String p] (str/starts-with? s p)) noise-tag-prefixes))))

(defn- raw-tags
  "Lowercased raw tag set of an entry."
  [entry]
  (into #{}
        (comp (map str) (map str/lower-case) (remove str/blank?))
        (:tags entry)))

(defn- topic-tokens
  "Topic vocabulary of a raw tag set: noise tags dropped, compounds expanded."
  [raw]
  (into #{}
        (comp (remove noise-tag?)
              (mapcat (fn [^String s]
                        (conj (into #{} (remove str/blank?) (str/split s #"[-_.]+"))
                              s))))
        raw))

(defn score
  "Score a pool entry against task `tokens` after `offers` prior offers.

   `raw-tag-weight` for any raw-tag hit, plus `topic-tag-weight` for any
   topic-token hit, plus the entry's `type-bias`, plus `age-weight` per offer."
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
       (* age-weight (double (or offers 0))))))

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
   offer count), :char-budget (defaults to `default-char-budget`).

   The floor lane is filled before the ranker runs: floor entries keep FIFO
   order and are never scored. Pool entries are ordered by `score` descending
   with FIFO index as tiebreak, or kept FIFO when :tokens is empty.

   Returns {:ordered <every pending entry, floor lane first>
            :batch   <prefix of :ordered fitting the budget>
            :taken   <count of :batch>}."
  [pending {:keys [tokens offers char-budget]}]
  (let [indexed (vec (map-indexed vector pending))
        floor (into [] (comp (filter (fn [[_ e]] (= :floor (lane e)))) (map second)) indexed)
        pool-idx (into [] (remove (fn [[_ e]] (= :floor (lane e)))) indexed)
        ts (set tokens)
        pool (if (seq ts)
               (into []
                     (map second)
                     (sort-by (fn [[i e]] [(- (score e ts (get offers (:id e) 0))) i])
                              pool-idx))
               (into [] (map second) pool-idx))
        ordered (into floor pool)
        taken (budget-prefix ordered (or char-budget default-char-budget))]
    {:ordered ordered
     :batch (subvec ordered 0 taken)
     :taken taken}))
