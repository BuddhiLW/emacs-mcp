(ns hive-mcp.channel.audience
  "Audience routing and progress digest for the HIVEMIND piggyback channel.

   Pure calculations — no I/O, no state, no requires beyond clojure.string.
   Two questions:

   - `addressed-to?` / `filter-messages` — does a shout belong in THIS
     reader's context?
   - `digest` — collapse a burst of per-turn :progress rows into one row.

   Delivery contract. A shout reaches the agent that SPAWNED the shouter and
   nobody else:

     :broadcast? true  -> every reader
     author = reader   -> never (a ling does not read back its own shout;
                          coordinator lanes are exempt so the wave scheduler
                          still sees the events it authors)
     :parent-id set    -> that reader alone
     :parent-id absent -> root-level, coordinator readers only

   Digest contract. Rows whose :e is digestible collapse, per agent, into ONE
   row carrying the burst count under :n and the LAST message under :m, sitting
   at the position of that agent's last such row. Every other event — started,
   completed, error, aborted, ask — passes through verbatim and in place."
  (:require [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Reader identity
;; =============================================================================

(def ^:const coordinator-prefix
  "Prefix every coordinator-lane reader id carries."
  "coordinator")

(defn coordinator-reader?
  "True when `reader-id` names a coordinator lane. The MCP lane derives its
   reader id as \"coordinator\" or \"coordinator-<project-id>\" (see
   hive-dsl.context.identity/make-piggyback-agent-id), so this is a prefix
   test, not equality. nil counts as a coordinator."
  [reader-id]
  (or (nil? reader-id)
      (str/starts-with? (str reader-id) coordinator-prefix)))

(defn same-agent?
  "Do two ids name the same reader? Tolerates the coordinator lane's
   \"coordinator-<project>\" suffixing, so a shout whose :parent-id is the bare
   \"coordinator\" still reaches reader \"coordinator-hive\"."
  [reader-id other-id]
  (let [r (str reader-id)
        o (str other-id)]
    (or (= r o)
        (and (coordinator-reader? r) (coordinator-reader? o)))))

;; =============================================================================
;; Audience
;; =============================================================================

(defn addressed-to?
  "Is `msg` part of `reader-id`'s audience? First rule that matches wins; see
   the namespace docstring for the contract."
  [reader-id {:keys [agent-id parent-id broadcast?]}]
  (let [coord? (coordinator-reader? reader-id)]
    (cond
      broadcast? true
      (and (not coord?) (same-agent? reader-id agent-id)) false
      (some? parent-id) (same-agent? reader-id parent-id)
      :else coord?)))

(defn filter-messages
  "Keep only the messages addressed to `reader-id`, in order. Returns a vector."
  [reader-id msgs]
  (filterv #(addressed-to? reader-id %) msgs))

;; =============================================================================
;; Digest
;; =============================================================================

(def digestible-events
  "Event names whose bursts collapse into a rollup row."
  #{"progress"})

(defn- digestible?
  [row]
  (contains? digestible-events (some-> (:e row) name)))

(defn digest
  "Collapse per-agent bursts of digestible rows into one row each.

   Rows are the formatted piggyback shape {:a agent :e event :m message
   :t task}; a collapsed row gains :n, the number of rows it stands for. A
   burst of one is left untouched — :n only appears where something was
   actually dropped."
  [rows]
  (let [rows (vec rows)
        indexed (map-indexed vector rows)
        last-idx (reduce (fn [acc [i row]]
                           (if (digestible? row) (assoc acc (:a row) i) acc))
                         {} indexed)
        counts (frequencies (keep #(when (digestible? %) (:a %)) rows))]
    (into []
          (keep-indexed
           (fn [i row]
             (cond
               (not (digestible? row)) row
               (= i (get last-idx (:a row)))
               (let [n (get counts (:a row) 1)]
                 (cond-> row (> n 1) (assoc :n n)))
               :else nil)))
          rows)))
