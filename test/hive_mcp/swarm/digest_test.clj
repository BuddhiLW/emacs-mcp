(ns hive-mcp.swarm.digest-test
  "Swarm status roster — pure, no fixtures, no live registry."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as tc-prop]
            [hive-mcp.swarm.digest :as dig]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private now 1000000)

(defn- shout
  [ts turn msg & {:keys [event parent task]
                  :or {event :progress parent "coordinator"}}]
  (cond-> {:event-type event :timestamp ts :message msg :parent-id parent}
    turn (assoc :data {:turn turn})
    task (assoc :task task)))

(defn- entry [& msgs] {:data {:messages (vec msgs)}})

;; =============================================================================
;; agent-row
;; =============================================================================

(deftest agent-row-reports-latest-state-test
  (let [row (dig/agent-row now "vt-billing"
                           (entry (shout (- now 60000) 1 "turn 1")
                                  (shout (- now 5000) 7 "turn 7"
                                         :task "[billing] ledger checkpoint")))]
    (is (= "vt-billing" (:a row)))
    (is (= "progress" (:e row)))
    (is (= 7 (:turn row)) "highest turn seen")
    (is (= "turn 7" (:m row)) "latest message, not the first")
    (is (= 5 (:idle-s row)) "seconds since the last shout")
    (is (= 2 (:shouts row)))
    (is (= "coordinator" (:parent row)))
    (is (= "[billing] ledger checkpoint" (:t row)))))

(deftest agent-row-nil-for-a-silent-agent-test
  (is (nil? (dig/agent-row now "quiet" (entry))))
  (is (nil? (dig/agent-row now "quiet" {}))))

(deftest agent-row-tolerates-a-producer-that-stamps-no-turn-test
  (let [row (dig/agent-row now "wave" (entry (shout (- now 1000) nil "started"
                                                    :event :started)))]
    (is (= "started" (:e row)))
    (is (nil? (:turn row)) "no turn key rather than a fabricated zero")))

(deftest agent-row-clips-a-runaway-message-test
  (let [row (dig/agent-row now "loud" (entry (shout now 1 (apply str (repeat 5000 "x"))))
                           50)]
    (is (= 51 (count (:m row))) "50 chars plus the ellipsis")))

;; =============================================================================
;; roster
;; =============================================================================

(deftest roster-sorts-most-recently-active-first-test
  (let [rows (dig/roster now {"stale" (entry (shout (- now 600000) 3 "old"))
                              "fresh" (entry (shout (- now 2000) 9 "new"))
                              "mid"   (entry (shout (- now 60000) 5 "mid"))})]
    (is (= ["fresh" "mid" "stale"] (mapv :a rows)))))

(deftest roster-filters-to-my-own-children-test
  (let [reg {"mine"   (entry (shout (- now 1000) 2 "a" :parent "coordinator"))
             "theirs" (entry (shout (- now 1000) 2 "b" :parent "ling-a"))}]
    (is (= ["mine"] (mapv :a (dig/roster now reg {:only-children-of "coordinator"}))))
    (is (= ["theirs"] (mapv :a (dig/roster now reg {:only-children-of "ling-a"}))))
    (is (= 2 (count (dig/roster now reg))) "unfiltered keeps both")))

(deftest roster-skips-silent-agents-test
  (is (= ["talker"] (mapv :a (dig/roster now {"talker" (entry (shout now 1 "hi"))
                                              "silent" (entry)})))))

(deftest render-is-one-line-per-agent-test
  (let [out (dig/render (dig/roster now {"a" (entry (shout (- now 3000) 4 "working"))
                                         "b" (entry (shout (- now 1000) 2 "also"))}))]
    (is (= 2 (count (str/split-lines out))))
    (is (str/includes? out "turn=4"))
    (is (str/includes? out "idle=3s"))))

;; =============================================================================
;; Properties
;; =============================================================================

(def ^:private gen-entry
  (gen/let [msgs (gen/vector
                  (gen/let [ts (gen/choose 0 now)
                            turn (gen/one-of [(gen/return nil) (gen/choose 1 99)])
                            m gen/string-alphanumeric]
                    (shout ts turn m))
                  0 8)]
    {:data {:messages msgs}}))

(def ^:private gen-registry
  (gen/map (gen/fmap #(str "ling-" %) (gen/choose 0 5)) gen-entry))

(defspec roster-never-invents-an-agent 200
  (tc-prop/for-all [reg gen-registry]
    (every? (set (keys reg)) (map :a (dig/roster now reg)))))

(defspec roster-is-sorted-by-idleness 200
  (tc-prop/for-all [reg gen-registry]
    (let [idles (map :idle-s (dig/roster now reg))]
      (= idles (sort idles)))))

(defspec roster-idle-is-never-negative 200
  (tc-prop/for-all [reg gen-registry]
    (every? #(<= 0 (:idle-s %)) (dig/roster now reg))))

(defspec roster-row-count-never-exceeds-agent-count 200
  (tc-prop/for-all [reg gen-registry]
    (<= (count (dig/roster now reg)) (count reg))))
