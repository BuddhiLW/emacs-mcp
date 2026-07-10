(ns hive-mcp.events.handlers.crystal
  "Wrap/crystallize event handlers.

   Handles events related to session crystallization:
   - :crystal/wrap-request - Unified wrap path
   - :crystal/wrap-notify  - Wrap notification for HIVEMIND piggyback"

  (:require [hive-mcp.events.core :as ev]
            [hive-mcp.events.interceptors :as interceptors]
            [clojure.string :as str]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- format-session-summary
  "Format session summary content from wrap data."
  [{:keys [accomplishments decisions conventions in-progress next-actions date]}]
  (let [date-str (or date (str (java.time.LocalDate/now)))]
    (str "## Session Summary: " date-str "\n\n"
         "### Completed\n"
         (if (seq accomplishments)
           (str/join "\n" (map #(str "- [x] " %) accomplishments))
           "- (none)")
         "\n\n### Decisions Made\n"
         (if (seq decisions)
           (str/join "\n" (map #(str "- " %) decisions))
           "- (none)")
         "\n\n### Conventions Added\n"
         (if (seq conventions)
           (str/join "\n" (map #(str "- " %) conventions))
           "- (none)")
         "\n\n### In Progress\n"
         (if (seq in-progress)
           (str/join "\n" (map #(str "- [ ] " %) in-progress))
           "- (none)")
         "\n\n### Next Actions\n"
         (if (seq next-actions)
           (str/join "\n" (map #(str "- " %) next-actions))
           "- (none)"))))

;; =============================================================================
;; Handler: :crystal/wrap-request (Option A - Unified wrap path)
;; =============================================================================

(def ^:private memory-type->bucket
  "Maps a memory `:type` string to the wrap-notify bucket key the shout
   formatter reads. Types outside this set fold into :other."
  {"decision"   :decisions
   "convention" :conventions
   "note"       :notes
   "principle"  :principles
   "axiom"      :axioms
   "snippet"    :snippets
   "plan"       :plans
   "knowledge"  :knowledge})

(defn project-wrap-stats
  "Reconcile a harvest `:summary` map (producer) into the wrap-notify consumer
   vocabulary read by `format-bucket-message`.

   The crystallize/harvest path produces a HarvestSummary whose keys
   (`:created-count`, `:kanban-completed`, `:kg-edge-count`,
   `:created-by-type` …) do NOT match the `:decisions`/`:conventions`/…
   vocabulary the shout formatter reads — which is why the piggyback always
   read `0 decisions, 0 conventions`. This is the single producer→consumer
   projection site (see memory 20260418200155-6151635c).

   - Per-type memory-creation counts come from `:created-by-type`; unknown
     types fold into `:other` so custom types still register.
   - `:kanban-closures`, `:kg-edges`, `:memories-created` surface kanban +
     KG + total deltas.
   - Idempotent on already-consumer-shaped maps (the elisp wrap-request path
     pre-sets `:decisions`/`:conventions`/…): when `:created-by-type` is
     absent those pre-set counts pass through unchanged."
  [summary]
  (let [m       (or summary {})
        by-type (:created-by-type m)
        typed   (when by-type
                  (reduce-kv (fn [acc t c]
                               (update acc (get memory-type->bucket t :other)
                                       (fnil + 0) c))
                             {} by-type))]
    (merge
     {:decisions 0 :conventions 0 :notes 0 :principles 0 :axioms 0
      :snippets 0 :plans 0 :knowledge 0 :other 0}
     (select-keys m [:decisions :conventions :notes :principles :axioms
                     :snippets :plans :knowledge :other :accomplishments])
     typed
     {:kanban-closures  (or (:kanban-closures m) (:kanban-completed m) 0)
      :kg-edges         (or (:kg-edges m) (:kg-edge-count m) 0)
      :memories-created (or (:memories-created m) (:created-count m) 0)})))

(defn- format-bucket-message
  "Format a session-wrap shout message from a stats-like map.

   Decisions and conventions always appear (count or 0). Other bucket
   types — notes, principles, axioms, snippets, plans, accomplishments,
   kanban-closures — are appended only when non-zero so the line stays
   tight on lean sessions while surfacing the full picture on rich ones.

   Disposition counters (audit kanban 20260429203442) — when producers
   populate them, the message gains a trailing \"[wrote N · merged M
   · skipped K]\" segment that disambiguates wrap-form items from the
   actual corpus delta:
     :wrapped           — entries newly written to the corpus by this wrap
     :already-in-corpus — entries deduped onto an existing record (tag merge)
     :skipped           — entries rejected (validation fail, missing fields, …)

   When ALL wrap-form bucket counts are zero AND no disposition keys are
   present, append a hint pointing the operator at /permeate so the
   classic \"0 decisions, 0 conventions\" line stops reading like
   \"nothing was learned\" — direct memory writes accrue independently.

   Accepts both `:decisions N` and `:decision-count N` shapes (legacy
   producer compat — strips trailing s and tries `<singular>-count`).
   Reason for existence: the bare 'N decisions, M conventions' format
   under-reported productive sessions where most work landed as
   conventions/principles/axioms (kanban 20260423144824)."
  [stats]
  (let [m     (or stats {})
        get-n (fn [k]
                (let [n (name k)
                      singular (if (str/ends-with? n "s")
                                 (subs n 0 (dec (count n)))
                                 n)]
                  (or (get m k)
                      (get m (keyword (str singular "-count")))
                      0)))
        decisions    (get-n :decisions)
        conventions  (get-n :conventions)
        bucket-pairs [[:notes "notes"]
                      [:principles "principles"]
                      [:axioms "axioms"]
                      [:knowledge "knowledge"]
                      [:snippets "snippets"]
                      [:plans "plans"]
                      [:other "other"]
                      [:accomplishments "accomplishments"]
                      [:kanban-closures "kanban→done"]
                      [:kg-edges "kg-edges"]]
        extras       (->> bucket-pairs
                          (keep (fn [[k label]]
                                  (let [n (get-n k)]
                                    (when (pos? n)
                                      (str n " " label)))))
                          seq)
        ;; Disposition triple (optional — only present when producer wired it)
        wrapped      (get m :wrapped)
        in-corpus    (get m :already-in-corpus)
        skipped      (get m :skipped)
        disposition? (or wrapped in-corpus skipped)
        disposition  (when disposition?
                       (str " [wrote " (or wrapped 0)
                            " · merged " (or in-corpus 0)
                            " · skipped " (or skipped 0) "]"))
        all-zero?    (and (zero? decisions)
                          (zero? conventions)
                          (nil? extras)
                          (not disposition?))
        hint         (when all-zero?
                       " (no memory writes, KG edges, or kanban closures harvested this session)")]
    (str "Session wrapped: " decisions " decisions, " conventions " conventions"
         (when extras (str ", " (str/join ", " extras)))
         disposition
         hint)))

(defn handle-crystal-wrap-request
  "Handler for :crystal/wrap-request events.

   Option A implementation - Unified wrap path. Receives wrap data from elisp
   via channel, stores to memory, and emits wrap_notify for Crystal Convergence.

   Expects event data:
   {:accomplishments  [\"Task 1\" \"Task 2\"]     ; list of completed tasks
    :decisions        [\"Decision 1\"]           ; list of decisions made
    :conventions      [\"Convention 1\"]         ; list of conventions
    :principles       [\"Principle 1\"]          ; list of principles (optional)
    :axioms           [\"Axiom 1\"]              ; list of axioms (optional)
    :snippets         [\"Snippet 1\"]            ; list of snippets (optional)
    :plans            [\"Plan 1\"]               ; list of plans (optional)
    :in-progress      [\"WIP task\"]             ; list of in-progress items
    :next-actions     [\"Next 1\"]               ; list of next session priorities
    :completed-tasks  [\"kanban-id-1\"]          ; kanban task IDs to mark done
    :project          \"hive-mcp\"}              ; project name for scoping

   Produces effects:
   - :log          - Log wrap request
   - :memory-write - Store session summary as note
   - :wrap-notify  - Queue for coordinator permeation
   - :shout        - Broadcast completion to hivemind"
  [coeffects [_ {:keys [accomplishments decisions conventions
                        principles axioms snippets plans
                        _in-progress _next-actions completed-tasks project]
                 :as data}]]
  (let [agent-id (or (get-in coeffects [:agent-context :agent-id])
                     (System/getenv "CLAUDE_SWARM_SLAVE_ID")
                     "unknown-agent")
        session-id (str "session:" (java.time.LocalDate/now) ":" agent-id)
        date-str (str (java.time.LocalDate/now))
        summary-content (format-session-summary (assoc data :date date-str))
        stats {:accomplishments (count accomplishments)
               :decisions       (count decisions)
               :conventions     (count conventions)
               :principles      (count principles)
               :axioms          (count axioms)
               :snippets        (count snippets)
               :plans           (count plans)
               :kanban-closures (count completed-tasks)}
        ;; Build effects map
        base-effects {:log {:level :info
                            :message (str "Wrap request from " agent-id
                                          ": " (count accomplishments) " accomplishments, "
                                          (count decisions) " decisions, "
                                          (count conventions) " conventions")}}
        ;; Add session summary note effect
        summary-effect {:memory-write {:type "note"
                                       :content summary-content
                                       :tags ["session-summary" "wrap" "full-summary"]
                                       :duration "short"
                                       :directory project}}
        ;; Add wrap-notify effect for Crystal Convergence
        notify-effect {:wrap-notify {:agent-id agent-id
                                     :session-id session-id
                                     :stats stats}}
        ;; Add shout effect — full bucket breakdown so productive sessions
        ;; with no :decision-typed entries don't read "0 decisions, 0 conventions"
        shout-effect {:shout {:agent-id agent-id
                              :event-type :completed
                              :message (format-bucket-message stats)}}]
    ;; Merge all effects
    (merge base-effects summary-effect notify-effect shout-effect)))

;; =============================================================================
;; Handler: :crystal/wrap-notify
;; =============================================================================

(defn handle-crystal-wrap-notify
  "Handler for :crystal/wrap-notify events.

   Bridges wrap crystallization to HIVEMIND piggyback. When a ling wraps,
   this handler ensures the coordinator sees it via the shout mechanism.

   Expects event data:
   {:agent-id    \"ling-123\"
    :session-id  \"session:2026-01-15:ling-123\"
    :project-id  \"hive-mcp\"              ; project ID for scoped permeation
    :created-ids [\"note-id-1\" \"note-id-2\"]
    :stats       {:notes 2 :decisions 1 :conventions 0
                  :principles 0 :axioms 0 :snippets 0 :plans 0
                  :kanban-closures 0}}

   Produces effects:
   - :log         - Log wrap notification
   - :wrap-notify - Record to DataScript wrap-queue (with project-id for scoping)
   - :shout       - Broadcast to HIVEMIND (makes it visible in piggyback)"

  [_coeffects [_ {:keys [agent-id session-id project-id created-ids stats]}]]
  (let [note-count (count (or created-ids []))
        ;; Defensive: ensure stats is a map before accessing keys
        ;; This prevents "Key must be integer" error if stats is a vector/nil
        safe-stats (if (map? stats) stats {})
        ;; Reconcile the HarvestSummary producer shape into the consumer
        ;; vocabulary the formatter reads, so the piggyback reports the real
        ;; session delta instead of a structural 0 decisions, 0 conventions.
        projected (project-wrap-stats safe-stats)
        message (format-bucket-message projected)]
    {:log {:level :info
           :message (str "Wrap notify from " agent-id " (project: " project-id "): " note-count " entries")}
     :wrap-notify {:agent-id agent-id
                   :session-id session-id
                   :project-id project-id
                   :created-ids created-ids
                   :stats projected}
     :shout {:agent-id agent-id
             :event-type :wrap_notify
             :data {:session-id session-id
                    :project-id project-id
                    :stats projected
                    :message message}}}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-handlers!
  "Register crystal/wrap-related event handlers."
  []
  (ev/reg-event :crystal/wrap-request
                [interceptors/debug]
                handle-crystal-wrap-request)

  (ev/reg-event :crystal/wrap-notify
                [interceptors/debug]
                handle-crystal-wrap-notify))