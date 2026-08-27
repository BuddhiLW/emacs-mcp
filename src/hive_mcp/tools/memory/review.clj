(ns hive-mcp.tools.memory.review
  "Human review queue for write-gated memory types.

   A write requesting a gated type (see type-registry :gate) is parked as the
   gate's queue type and tagged pending. This namespace is the ONLY authority
   that resolves such an entry: approve lands the originally requested type,
   reject lands a different one. Both strip the pending residue, so a resolved
   entry never looks queued again.

   Resolution delegates to the ordinary edit path with the gate bypassed, so
   there is exactly one implementation of 'update an entry in place' and the
   entry id — hence every KG edge keyed by it — survives untouched."
  (:require [hive-mcp.tools.memory.crud.edit :as edit]
            [hive-mcp.tools.memory.crud.query :as query]
            [hive-mcp.tools.memory.core :refer [with-entry]]
            [hive-mcp.tools.core :refer [mcp-error]]
            [hive-mcp.memory.type-registry :as type-registry]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-reject-type
  "Type a rejected candidate lands as when the reviewer names none. `:principle`
   because a rejected axiom is nearly always a design heuristic that failed the
   inviolability half of the test, not something to discard."
  "principle")

(defn queue-types
  "Type strings that hold entries awaiting review — every gate's :queue-as."
  []
  (into #{}
        (keep #(some-> (get-in % [:gate :queue-as]) type-registry/sanitize-type))
        (vals (type-registry/registry))))

(defn- verdict-tags
  "Audit tags stamped on a resolved entry. `requested` is the gated type the
   entry originally asked for; nil when the entry carried no marker."
  [verdict requested resolved-type]
  (cond-> [(str "axiom-review:" (name verdict))
           (str "reviewed-as:" resolved-type)]
    requested (conj (str "review-requested:" requested))))

(defn- resolve-entry!
  "Land `entry` on `new-type`, stripping queue residue and stamping the verdict.
   Runs the edit with the write gate bypassed — this is the authority."
  [entry id new-type verdict reason]
  (let [requested (type-registry/requested-type-of (:tags entry))
        tags      (->> (verdict-tags verdict requested new-type)
                       (into (type-registry/strip-queue-tags (:tags entry)))
                       distinct
                       vec)]
    (log/info "memory review:" id (name verdict) "->" new-type
              (when reason (str "reason:" reason)))
    (binding [type-registry/*gate-bypass?* true]
      ;; Synchronous on this thread, so the binding is in scope for the seam.
      (edit/handle-edit {:id     id
                         :type   new-type
                         :tags   tags
                         :reason (or reason
                                     (str "axiom review: " (name verdict)
                                          " -> " new-type))}))))

(defn- pending-entry?
  "True when `entry` is actually parked awaiting review. Guards against
   resolving an entry that was never queued."
  [entry]
  (contains? (queue-types) (type-registry/sanitize-type (:type entry))))

(defn handle-review
  "Resolve — or list — the human review queue for write-gated types.

   Params:
     :id       — entry to resolve. Omit to LIST the pending queue.
     :verdict  — \"approve\" | \"reject\" (required with :id)
     :as       — type a rejected entry lands as (default: principle).
                 Ignored on approve, which lands the requested type.
     :reason   — optional, logged for audit.
     :scope / :directory / :limit — passed through when listing.

   Approve lands the type the entry originally requested (recorded in its
   requested-type tag); if that marker is missing it falls back to `:as`, then
   to axiom. Reject lands `:as`. Both strip the pending tags."
  [{:keys [id verdict as reason] :as params}]
  (cond
    ;; --- list mode ---
    (or (nil? id) (str/blank? (str id)))
    ;; scope "all" by default: a nomination is reviewed wherever it was made,
    ;; matching the catchup bundle's global branch. Sorted queue-types keeps
    ;; the choice deterministic if a second gate is ever declared.
    (query/handle-query (merge {:type      (first (sort (queue-types)))
                                :limit     25
                                :scope     "all"
                                :verbosity "metadata"}
                               (select-keys params [:scope :directory :limit
                                                    :include_descendants])))

    (not (#{"approve" "reject"} (some-> verdict str/lower-case str/trim)))
    (mcp-error (str "verdict must be \"approve\" or \"reject\" (got "
                    (pr-str verdict) "). Omit :id to list the pending queue."))

    :else
    (with-entry [entry id]
      (let [v (keyword (str/lower-case (str/trim verdict)))]
        (cond
          (not (pending-entry? entry))
          (mcp-error (str "Entry " id " is not awaiting review (type "
                          (pr-str (:type entry)) "). Only entries parked in a "
                          "review queue " (pr-str (vec (sort (queue-types))))
                          " can be reviewed."))

          (= :approve v)
          (let [requested (or (type-registry/requested-type-of (:tags entry))
                              (some-> as type-registry/sanitize-type)
                              "axiom")]
            (resolve-entry! entry id requested v reason))

          :else
          (let [target (or (some-> as type-registry/sanitize-type)
                           default-reject-type)]
            (if-not (type-registry/valid-type? target)
              (mcp-error (str "Invalid target type for reject: " (pr-str as)))
              (resolve-entry! entry id target v reason))))))))
