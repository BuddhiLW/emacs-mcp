(ns hive-mcp.recall.canary
  "The golden recall canary: fault taxonomy + the fixture corpus it owns.

   Pure. Every fn takes OBSERVATIONS and returns a fault map or nil; nothing
   here reads a store, a config or a clock. `hive-mcp.recall.canary.live`
   supplies the observations and runs the probes.

   Fault contract. A fault fn NEVER throws and NEVER returns a bare boolean —
   nil means healthy, a map names what broke and prints as the diagnosis.

   Fixture contract. The canary owns its own anchor. Every fixture below is
   discovered by tag, not by id, and is created on first run if absent, so no
   probe can be silently disarmed by an entry someone deleted.

   Verdict contract. A probe that could not run is `:skipped` with a reason and
   is reported as such — never folded into the pass count."
  (:require [clojure.set :as set]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; The fixtures the canary owns
;; =============================================================================

(def canary-tag
  "Tag carried by every entry the canary owns. Discovery key, not an id."
  "recall-canary")

(def anchor-tag "recall-canary-anchor")
(def superseded-tag "recall-canary-superseded")
(def current-tag "recall-canary-current")

(def anchor-query
  "Rare literal tokens that co-occur in the anchor fixture and nowhere else."
  "quokka vestibule 7731 recall canary anchor")

(def supersession-query
  "A query both supersession fixtures answer; only the current one may return."
  "canary pelican index rebuild cadence")

(def fixtures
  "The corpus the canary writes once and re-reads forever.

   :role is the handle the probes use; :tags is how the row is found again;
   :supersedes names the role this row retracts (KG edge, written on create)."
  [{:role     :anchor
    :type     "note"
    :tags     [canary-tag anchor-tag]
    :content  (str "# Recall canary anchor (do not delete)\n\n"
                   "quokka vestibule 7731 — three rare literal tokens that occur "
                   "together in this entry and nowhere else in the corpus. The "
                   "recall canary searches for them on every scheduler tick; if "
                   "this row stops coming back, the lexical channel is dead and "
                   "every semantic answer above it is unverified.")}
   {:role     :superseded
    :type     "note"
    :tags     [canary-tag superseded-tag]
    :content  (str "# Recall canary — the RETRACTED claim (do not delete)\n\n"
                   "canary pelican index rebuild cadence: nightly. This row is "
                   "superseded on purpose. A retrieval path that returns it has "
                   "lost supersede-suppression, which means agents are being "
                   "served a claim the corpus has already retracted.")}
   {:role       :current
    :type       "note"
    :tags       [canary-tag current-tag]
    :supersedes :superseded
    :content    (str "# Recall canary — the CURRENT claim (do not delete)\n\n"
                     "canary pelican index rebuild cadence: hourly. This row "
                     "supersedes the retracted one; retrieval must return THIS "
                     "and suppress that.")}])

(defn fixture
  "The fixture spec for `role`, or nil."
  [role]
  (first (filter #(= role (:role %)) fixtures)))

(defn fixture-tags
  "Discovery tags for `role`: [canary-tag role-tag]."
  [role]
  (:tags (fixture role)))

;; =============================================================================
;; The fault taxonomy
;; =============================================================================

(defn recall-fault
  "Nil when recall is healthy; otherwise a fault map.

   Takes {:label :populated? :results :must-contain}. `:populated? false`
   yields nil — an empty store returning nothing is honest.

   :recall/empty-from-populated-store — zero rows from a store known to hold
   entries. :recall/anchor-missing — rows came back, but not the one that must
   be there."
  [{:keys [label populated? results must-contain]}]
  (let [results (vec results)
        got     (set (keep :id results))
        want    (set must-contain)
        missing (set/difference want got)]
    (cond
      (not populated?)
      nil

      (empty? results)
      {:fault        :recall/empty-from-populated-store
       :label        label
       :diagnosis    (str "a populated store returned zero rows. This is a SYSTEM "
                          "FAULT, not a query outcome — the query encoder, the "
                          "index, or the ranking unit disagree.")
       :expected-ids (vec want)}

      (seq missing)
      {:fault        :recall/anchor-missing
       :label        label
       :diagnosis    (str "the store returned " (count results) " confident rows "
                          "but not the anchor. Indistinguishable from success at "
                          "the call site — this is why the outage was silent.")
       :missing-ids  (vec missing)
       :returned-ids (mapv :id results)}

      :else nil)))

(defn rank-fault
  "Nil when `results` are ordered nearest-first; otherwise a fault map.

   Takes {:label :results}. The pipeline's contract is DISTANCE — lower is
   nearer — end to end. Rows with no :distance are ignored: tag/KG enrichment
   hits legitimately have none."
  [{:keys [label results]}]
  (let [ds (keep :distance results)]
    (when (and (seq ds) (not (apply <= ds)))
      {:fault     :recall/rank-inverted
       :label     label
       :diagnosis (str "results are not ordered nearest-first. Either the sort "
                       "reversed, or a similarity (higher = better) is being "
                       "carried in the :distance field (lower = better).")
       :distances (vec ds)})))

(defn dimension-fault
  "Nil when every reading's emitted width equals its collection's width.

   Takes {:label :readings}, readings a seq of {:collection :expected :actual}.
   Readings with no :expected (width not derivable from the name) are ignored;
   a nil :actual against a known :expected IS a mismatch — an unreadable width
   is not a passing width."
  [{:keys [label readings]}]
  (let [known      (filter :expected readings)
        mismatched (remove #(= (:expected %) (:actual %)) known)]
    (cond
      (empty? known)
      {:fault     :recall/no-collections-configured
       :label     label
       :diagnosis (str "no collection reports a derivable width, so the invariant "
                       "is vacuous. The embedding service did not come up.")}

      (seq mismatched)
      {:fault      :recall/dimension-mismatch
       :label      label
       :diagnosis  (str "a query embedded at one width and searched against an "
                        "index of another returns confident neighbours from a "
                        "space the query was never in.")
       :mismatched (vec mismatched)}

      :else nil)))

(defn supersession-fault
  "Nil when the retracted row stays out of `results`; otherwise a fault map.

   Takes {:label :results :superseded-id :current-id}. Returning the superseded
   row is the fault. A missing :current-id is reported separately so a dead
   retrieval lane is not mistaken for working suppression."
  [{:keys [label results superseded-id current-id]}]
  (let [ids (set (keep :id results))]
    (cond
      (contains? ids superseded-id)
      {:fault         :recall/superseded-returned
       :label         label
       :diagnosis     (str "a retrieval path returned an entry that a newer entry "
                           "supersedes. Wrongness is not a ranking preference — "
                           "suppression must happen at the exit of every path.")
       :superseded-id superseded-id
       :returned-ids  (vec ids)}

      (and current-id (not (contains? ids current-id)))
      {:fault       :recall/current-missing
       :label       label
       :diagnosis   (str "the superseding entry did not come back either, so the "
                         "clean result proves nothing about suppression — the "
                         "lane itself returned nothing relevant.")
       :current-id  current-id
       :returned-ids (vec ids)}

      :else nil)))

(defn presence-fault
  "Nil when `count` is positive; otherwise a fault map.

   Takes {:label :count :probe :diagnosis}. The shape for a probe whose only
   contract is 'this index answers at all'."
  [{:keys [label count probe diagnosis]}]
  (when-not (and (number? count) (pos? count))
    {:fault     :recall/probe-empty
     :label     label
     :probe     probe
     :count     count
     :diagnosis (or diagnosis
                    (str "the probe returned nothing. An index that answers "
                         "zero to its own smoke query is down, not empty."))}))

;; =============================================================================
;; The verdict
;; =============================================================================

(defn outcome
  "One probe's outcome. `fault` nil = pass; `skip` reason = not run."
  ([label fault] (outcome label fault nil))
  ([label fault skip]
   (cond
     skip  {:label label :status :skipped :reason skip}
     fault {:label label :status :fault :fault fault}
     :else {:label label :status :ok})))

(defn verdict
  "Fold probe outcomes into a report.

   {:ok? :ran :passed :faults :skipped}. `:ok?` is false the moment ANY probe
   faults; skipped probes never make it true and never make it false — they are
   counted so a canary that quietly stopped running is visible."
  [outcomes]
  (let [os      (vec outcomes)
        faults  (filterv #(= :fault (:status %)) os)
        skipped (filterv #(= :skipped (:status %)) os)
        passed  (filterv #(= :ok (:status %)) os)]
    {:ok?     (empty? faults)
     :ran     (count os)
     :passed  (count passed)
     :faults  (mapv :fault faults)
     :skipped (mapv #(select-keys % [:label :reason]) skipped)}))
