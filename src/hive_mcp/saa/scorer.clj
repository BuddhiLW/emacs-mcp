(ns hive-mcp.saa.scorer
  "Default observation scorer. Korzybski heuristic for ranking Silence-phase
   observations, plus a grounding-sufficiency score."
  (:require [hive-mcp.protocols.saa :as psaa]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- score-entry
  "Korzybski heuristic score for one observation."
  [obs]
  (let [content (str (or (:data obs) obs))
        has-pattern? (re-find #"pattern|convention|decision" content)
        has-issue? (re-find #"bug|error|issue|fix" content)
        has-test? (re-find #"test|spec|assert" content)
        base-score 1.0]
    (cond-> base-score
      has-pattern? (+ 2.0)
      has-issue? (+ 3.0)
      has-test? (+ 1.5))))

(defrecord DefaultObservationScorer []
  psaa/IObservationScorer

  (score [_ observations]
    (->> observations
         (map (fn [obs] {:observation obs :score (score-entry obs)}))
         (sort-by :score >)
         vec))

  (grounding-score [_ observations files-read]
    (double
     (min 1.0
          (+ (if (seq observations) 0.3 0.0)
             (min 0.4 (* 0.1 (count observations)))
             (if (pos? (or files-read 0)) 0.3 0.0))))))

(defn ->default-scorer
  "Create a DefaultObservationScorer."
  []
  (->DefaultObservationScorer))
