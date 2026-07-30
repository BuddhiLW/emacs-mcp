;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.channel.two-lane-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-dsl.bounded-atom :refer [bget]]
            [hive-dsl.context.identity :as ctx-id]
            [hive-mcp.channel.drain-rank :as rank]
            [hive-mcp.channel.memory-piggyback :as mp]
            [hive-mcp.channel.task-signal :as ts]))

;; =============================================================================
;; task-signal — allowlist is a security control, not an optimisation
;; =============================================================================

(deftest task-tokens-honours-the-allowlist
  (testing "an unknown tool contributes nothing"
    (is (= #{} (ts/task-tokens "totally-unknown" {:command "scan" :path "/etc"}))))

  (testing "a tool with an empty allowlist contributes nothing"
    (is (= #{} (ts/task-tokens "clojure_eval" {:code "(+ 1 2)"}))))

  (testing "only allowlisted keys contribute"
    (let [tokens (ts/task-tokens "code" {:command "carto read-form"
                                         :qn "hive-mcp.core/boot"
                                         :not-allowlisted "leakedvalue"})]
      (is (contains? tokens "carto"))
      (is (contains? tokens "read-form"))
      (is (not (contains? tokens "leakedvalue")))))

  (testing "nil and non-map args are tolerated"
    (is (= #{} (ts/task-tokens "code" nil)))
    (is (= #{} (ts/task-tokens nil {:command "x"})))
    (is (= #{} (ts/task-tokens "code" "not-a-map")))))

(deftest task-tokens-never-leaks-denied-keys
  (testing "payload and credential keys contribute nothing even when present"
    (doseq [k [:content :code :prompt :env :secret :token :api-key :password
               :credentials :authorization :headers :arguments :message]]
      (let [tokens (ts/task-tokens "code" {:command "carto" k "sentinelleak"})]
        (is (not (contains? tokens "sentinelleak"))
            (str "key " k " must not contribute")))))

  (testing "credential-shaped and credential-named tokens are dropped"
    (is (not (contains? (ts/task-tokens "code" {:command "deadbeef1234cafe"})
                        "deadbeef1234cafe")))
    (is (not (contains? (ts/task-tokens "code" {:qn "my-api-key"}) "my-api-key")))))

(deftest bash-command-yields-argv0-only
  (testing "only the program name survives, path- and assignment-stripped"
    (is (= #{"grep"} (ts/task-tokens "bash" {:command "/usr/bin/grep -rn secretpattern ."})))
    (is (= #{"psql"} (ts/task-tokens "bash" {:command "PGPASSWORD=hunter2 psql -c 'select 1'"}))))

  (testing "an over-long command is never tokenised"
    (is (= #{} (ts/task-tokens "bash" {:command (apply str (repeat 500 \a))})))))

(deftest cue-harvesting-is-on-by-default
  (testing "enabled? is true with no override — the drain is two-lane unless opted out"
    (binding [ts/*enabled?* nil]
      (is (true? (boolean (ts/enabled?))))))

  (testing "an explicit false binding still disables it"
    (binding [ts/*enabled?* false]
      (is (false? (boolean (ts/enabled?))))))

  (testing "cues yields nothing while disabled, and real tokens once enabled"
    (let [args {:command "carto read-form" :qn "a/b"}]
      (binding [ts/*enabled?* false]
        (is (= #{} (ts/cues "code" args))))
      (binding [ts/*enabled?* true]
        (is (= (ts/task-tokens "code" args) (ts/cues "code" args)))
        (is (seq (ts/cues "code" args)))))))

;; =============================================================================
;; drain-rank — floor lane before ranking
;; =============================================================================

(defn- entry [i t tags]
  {:id (str "e" i) :T t :C (str "body-" i) :tags tags})

(deftest lane-splits-axioms-from-the-pool
  (testing "axioms land in the floor lane, whatever the :T representation"
    (is (= :floor (rank/lane (entry 0 :axiom []))))
    (is (= :floor (rank/lane (entry 0 "axiom" []))))
    (is (= :floor (rank/lane (entry 0 ":axiom" [])))))

  (testing "every other type lands in the pool"
    (doseq [t [:note :convention :decision :knowledge :plan nil]]
      (is (= :pool (rank/lane (entry 0 t [])))))))

(deftest score-rewards-tag-overlap-and-type
  (testing "a raw-tag hit outscores no hit"
    (is (> (rank/score (entry 1 :note ["carto"]) #{"carto"} 0)
           (rank/score (entry 1 :note ["carto"]) #{"unrelated"} 0))))

  (testing "nil tokens score without throwing"
    (is (number? (rank/score (entry 1 :note ["x"]) nil 0)))))

(deftest offers-decay-an-entry-instead-of-ageing-it-upward
  (testing "an entry offered and never taken SINKS — the anti-shelf inversion"
    (is (< (rank/score (entry 1 :note ["x"]) #{} 10)
           (rank/score (entry 1 :note ["x"]) #{} 0))))

  (testing "the decay is capped, so a fresh cue hit still outranks a stale entry"
    (let [stale (rank/score (entry 1 :note ["x"]) #{} 1000)
          fresh-hit (rank/score (entry 1 :note ["x"]) #{"x"} 0)]
      (is (= (- rank/max-offer-decay) stale))
      (is (> fresh-hit stale))))

  (testing "decay is monotone up to the cap"
    (is (> (rank/score (entry 1 :note ["x"]) #{} 1)
           (rank/score (entry 1 :note ["x"]) #{} 2)))))

(deftest access-and-feedback-lift-an-entry
  (let [plain (entry 1 :note ["x"])]
    (testing "a read entry outranks an unread one"
      (is (> (rank/score (assoc plain :A 3) #{} 0) (rank/score plain #{} 0))))

    (testing "access credit saturates — 3 and 30 reads score the same"
      (is (= (rank/score (assoc plain :A 3) #{} 0)
             (rank/score (assoc plain :A 30) #{} 0))))

    (testing "helpful lifts, unhelpful sinks, and the clamp bounds both"
      (is (> (rank/score (assoc plain :F 1) #{} 0) (rank/score plain #{} 0)))
      (is (< (rank/score (assoc plain :F -1) #{} 0) (rank/score plain #{} 0)))
      (is (= (rank/score (assoc plain :F 1) #{} 0)
             (rank/score (assoc plain :F 99) #{} 0))))

    (testing "an entry carrying neither key scores as never read, never rated"
      (is (= (rank/score plain #{} 0)
             (rank/score (assoc plain :A 0 :F 0) #{} 0))))))

(deftest pins-promote-into-the-floor-lane
  (let [pending (into [] (for [i (range 6)] (entry i :note [(str "t" i)])))]
    (testing "a pinned id leads the order even with no cue hit of its own"
      (let [{:keys [ordered]} (rank/select-batch pending {:tokens #{"t5"} :pins #{"e3"}})]
        (is (= "e3" (:id (first ordered))))
        (is (= (count pending) (count ordered)))))

    (testing "lane reports :floor for a pinned entry, :pool without pins"
      (is (= :floor (rank/lane (entry 3 :note []) #{"e3"})))
      (is (= :pool (rank/lane (entry 3 :note []) #{"other"})))
      (is (= :pool (rank/lane (entry 3 :note [])))))))

(deftest floor-cap-demotes-overflow-and-never-drops
  (let [pending (into [] (for [i (range 12)] (entry i :axiom [(str "t" i)])))
        {:keys [ordered]} (rank/select-batch pending {:tokens #{"t11"} :floor-cap 4})]
    (testing "only :floor-cap entries hold the floor; the rest are demoted"
      (is (= ["e0" "e1" "e2" "e3"] (vec (take 4 (map :id ordered))))))

    (testing "demoted entries are RANKED, not dropped — the cued one leads the pool"
      (is (= "e11" (:id (nth ordered 4))))
      (is (= (count pending) (count ordered)))
      (is (= (set (map :id pending)) (set (map :id ordered)))))))

(deftest select-batch-fills-the-floor-lane-first
  (let [pending (into [] (for [i (range 10)]
                           (entry i (if (even? i) :axiom :note) [(str "t" i)])))
        {:keys [ordered batch taken]} (rank/select-batch pending {:tokens #{"t7"}})]
    (testing "every axiom precedes every pool entry"
      (is (= [:floor :pool] (distinct (map rank/lane ordered)))))

    (testing "the floor lane keeps FIFO order and is never scored"
      (is (= ["e0" "e2" "e4" "e6" "e8"] (mapv :id (take 5 ordered)))))

    (testing "the cued pool entry is promoted to the head of the pool"
      (is (= "e7" (:id (nth ordered 5)))))

    (testing "nothing is lost and the batch is a prefix of the order"
      (is (= (count pending) (count ordered)))
      (is (= (vec (take taken ordered)) (vec batch))))))

(deftest select-batch-keeps-fifo-without-tokens
  (let [pending (into [] (for [i (range 6)] (entry i :note [(str "t" i)])))]
    (doseq [ctx [{} {:tokens nil} {:tokens #{}}]]
      (is (= pending (:ordered (rank/select-batch pending ctx)))
          (str "ctx " (pr-str ctx) " must stay FIFO")))))

(deftest select-batch-respects-the-char-budget
  (let [pending (into [] (for [i (range 20)]
                           {:id (str "e" i) :T :note :C (apply str (repeat 400 \x)) :tags []}))
        {:keys [taken batch]} (rank/select-batch pending {:char-budget 1000})]
    (is (< taken (count pending)))
    (is (pos? taken) "at least one entry is always taken")
    (is (= taken (count batch)))))

;; =============================================================================
;; drain! — the 2-arity must be byte-identical to FIFO when uncued
;; =============================================================================

(defn- buffer-of [caller]
  (bget mp/buffers (ctx-id/caller-id-key (ctx-id/parse-caller-id caller))))

(defn- drain-trace
  "Enqueue a fixed corpus for `caller`, drain twice, and return every drain
   result paired with the buffer written back after it."
  [caller entries ctx one-arity?]
  (mp/clear-buffer! caller)
  (mp/enqueue! caller entries)
  (let [d1 (if one-arity? (mp/drain! caller) (mp/drain! caller ctx))
        b1 (buffer-of caller)
        d2 (if one-arity? (mp/drain! caller) (mp/drain! caller ctx))]
    [d1 b1 d2 (buffer-of caller)]))

(def ^:private corpus
  (into [] (for [i (range 24)]
             {:id (str "e" i)
              :type (if (even? i) :axiom :note)
              :content (apply str (repeat 900 \x))
              :tags [(str "t" i)]})))

(deftest uncued-drain-is-identical-to-fifo
  (let [base (drain-trace "two-lane-fifo-base" corpus nil true)]
    (testing "a nil ctx changes neither the batch nor the buffer"
      (is (= base (drain-trace "two-lane-fifo-nil" corpus nil false))))

    (testing "an empty token set changes neither the batch nor the buffer"
      (is (= base (drain-trace "two-lane-fifo-empty" corpus {:tokens #{}} false))))

    (testing "a ctx with no :tokens key changes neither"
      (is (= base (drain-trace "two-lane-fifo-nokey" corpus {:offers {}} false))))

    (testing "cues gathered while disabled leave the drain FIFO"
      (binding [ts/*enabled?* false]
        (is (= base (drain-trace "two-lane-fifo-off" corpus
                                 {:tokens (ts/cues "code" {:command "carto read-form"})}
                                 false)))))))

(deftest cued-drain-delivers-axioms-first
  (let [[d1 _ _ _] (drain-trace "two-lane-cued" corpus {:tokens #{"t7" "t9"}} false)
        ids (mapv :id (:batch d1))]
    (testing "the floor lane leads the batch even though t7/t9 are the cued entries"
      (is (= ["e0" "e2" "e4"] (vec (take 3 ids)))))

    (testing "no entry is duplicated"
      (is (= (count ids) (count (distinct ids)))))))
