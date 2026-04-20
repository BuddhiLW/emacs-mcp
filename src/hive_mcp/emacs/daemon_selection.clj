(ns hive-mcp.emacs.daemon-selection
  "Daemon selection logic for multi-daemon ling distribution.

   Selects the healthiest daemon with capacity for new ling spawns.
   This is about PROCESS DISTRIBUTION, not data isolation — DataScript
   is unified across all daemons (ADR-010).

   Selection criteria (ranked):
   1. Health score (higher = better) — :emacs-daemon/health-score 0-100
   2. Capacity (fewer lings = better) — max 5 lings per daemon
   3. Status filter — only :active daemons considered
   4. Project affinity (optional) — prefer daemons already hosting same-project lings

   DDD: Domain Service — stateless selection algorithm."
  (:require [hive-mcp.emacs.daemon :as daemon]
            [hive-mcp.emacs.daemon-scoring :as scoring]
            [hive-mcp.swarm.datascript.connection :as conn]
            [datascript.core :as d]
            [taoensso.timbre :as log]
            [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


(def ^:const max-lings-per-daemon
  "Maximum number of lings a single daemon should host.
   Beyond this, Emacs process stability degrades significantly."
  5)

;;; Re-export scoring fns for backward compatibility with callers
(def default-health-score scoring/default-health-score)
(def health-level scoring/health-level)
(def healthy? scoring/healthy?)


(defn daemon-ling-count
  "Count the number of lings currently bound to a daemon.

   Arguments:
     daemon - Daemon map (from get-daemon or get-all-daemons)

   Returns:
     Integer count of bound lings"
  [daemon]
  (count (or (:emacs-daemon/lings daemon) #{})))

(defn daemon-has-capacity?
  "Check if a daemon can accept more lings.

   Arguments:
     daemon    - Daemon map
     max-lings - Optional max lings override (default: max-lings-per-daemon)

   Returns:
     true if daemon has room for at least one more ling"
  ([daemon]
   (daemon-has-capacity? daemon max-lings-per-daemon))
  ([daemon max-lings]
   (< (daemon-ling-count daemon) max-lings)))

(defn daemon-project-affinity
  "Calculate project affinity score for a daemon.

   If the daemon already hosts lings for the same project-id,
   it gets a bonus score (reduces cross-daemon project scatter).

   Arguments:
     daemon     - Daemon map
     project-id - Target project ID (may be nil)

   Returns:
     Affinity score (0-10). Higher = better affinity."
  [daemon project-id]
  (if (nil? project-id)
    0  ;; No project = no affinity preference
    (let [ling-ids (or (:emacs-daemon/lings daemon) #{})
          c (conn/ensure-conn)
          db @c
          ;; Count how many of this daemon's lings share the target project
          same-project-count
          (count
           (d/q '[:find [?e ...]
                  :in $ ?project-id [?ling-id ...]
                  :where
                  [?e :slave/id ?ling-id]
                  [?e :slave/project-id ?project-id]]
                db project-id (vec ling-ids)))]
      ;; Scale: 0 lings = 0, 1+ lings = 5, 3+ lings = 10
      (cond
        (>= same-project-count 3) 10
        (pos? same-project-count)  5
        :else                      0))))


(defn score-daemon
  "Compute a composite selection score for a daemon.

   Higher score = better candidate for new ling spawn.

   Scoring formula:
     health_score (0-100)
     + capacity_bonus (0-50): fewer lings = more bonus
     + affinity_bonus (0-10): same-project preference

   Arguments:
     daemon     - Daemon map
     project-id - Target project ID (may be nil)

   Returns:
     Map with :daemon-id, :score, :breakdown, :disqualified?"
  [daemon project-id]
  (let [daemon-id  (:emacs-daemon/id daemon)
        status     (:emacs-daemon/status daemon)
        health     (or (:emacs-daemon/health-score daemon) default-health-score)
        ling-count (daemon-ling-count daemon)
        has-cap    (< ling-count max-lings-per-daemon)

        ;; Disqualification checks
        not-active (not= :active status)
        at-capacity (not has-cap)
        too-sick   (= :unhealthy (health-level health))
        disqualified? (or not-active at-capacity too-sick)

        ;; Scoring components (only meaningful if not disqualified)
        health-score    health
        capacity-bonus  (* (- max-lings-per-daemon ling-count) 10) ;; 10 points per free slot
        affinity-bonus  (daemon-project-affinity daemon project-id)
        total-score     (if disqualified?
                          -1
                          (+ health-score capacity-bonus affinity-bonus))]
    {:daemon-id     daemon-id
     :score         total-score
     :ling-count    ling-count
     :health-score  health
     :health-level  (health-level health)
     :disqualified? disqualified?
     :disqualify-reasons (cond-> []
                           not-active  (conj :not-active)
                           at-capacity (conj :at-capacity)
                           too-sick    (conj :unhealthy))
     :breakdown     {:health   health-score
                     :capacity capacity-bonus
                     :affinity affinity-bonus}}))


(defn select-daemon
  "Select the best daemon for a new ling spawn.

   Queries all registered daemons, scores each one, and returns the
   best candidate. Falls back to default daemon if no better option exists.

   Arguments:
     store      - IEmacsDaemon store instance
     opts       - Optional map with:
                  :project-id   - Target project for affinity scoring
                  :default-id   - Fallback daemon ID (default: env-based)

   Returns:
     Map with :daemon-id (selected daemon) and :selection-details
     Returns {:daemon-id default-id :reason :no-daemons} if none available.
     Returns {:daemon-id default-id :reason :all-disqualified} if all disqualified."
  [store & [{:keys [project-id default-id]}]]
  (let [all-daemons (daemon/get-all-daemons store)
        default-id  (or default-id
                        (System/getenv "EMACS_SOCKET_NAME")
                        "server")]
    (if (empty? all-daemons)
      ;; No daemons registered — fall back to default
      (do
        (log/warn "No daemons registered, using default:" default-id)
        {:daemon-id default-id
         :reason    :no-daemons
         :scored    []})

      ;; Score and rank all daemons
      (let [scored    (->> all-daemons
                           (map #(score-daemon % project-id))
                           (sort-by :score #(compare %2 %1))) ;; highest first
            qualified (remove :disqualified? scored)
            best      (first qualified)]

        (if best
          (do
            (log/info "Selected daemon:" (:daemon-id best)
                      "score:" (:score best)
                      "lings:" (:ling-count best) "/" max-lings-per-daemon
                      "health:" (:health-level best))
            {:daemon-id (:daemon-id best)
             :reason    :selected
             :scored    scored})

          ;; All disqualified — fall back to default with warning
          (do
            (log/warn "All daemons disqualified! Falling back to default:" default-id
                      "Reasons:" (mapv #(select-keys % [:daemon-id :disqualify-reasons]) scored))
            {:daemon-id default-id
             :reason    :all-disqualified
             :scored    scored}))))))


(defn update-health-score!
  "Update a daemon's health score in DataScript.

   Called by health monitoring to reflect daemon health changes.

   Arguments:
     daemon-id - Daemon to update
     score     - New health score (0-100, clamped)

   Returns:
     Transaction report or nil if daemon not found"
  [daemon-id score]
  {:pre [(string? daemon-id)
         (number? score)]}
  (let [clamped (max 0 (min 100 (int score)))
        c       (conn/ensure-conn)
        db      @c]
    (when-let [eid (:db/id (d/entity db [:emacs-daemon/id daemon-id]))]
      (log/debug "Updating daemon health:" daemon-id "→" clamped (scoring/health-level clamped))
      (d/transact! c [{:db/id eid
                       :emacs-daemon/health-score clamped}]))))


;;; Scoring computation now lives in hive-mcp.emacs.daemon-scoring


;; =============================================================================
;; Heartbeat decomposition — stratified per ACD (Data < Calc < Action).
;; Pure calc helpers are #- private; actions end in !.
;; =============================================================================

(defn- daemon-snapshot
  "Action: read prior daemon state (prev-score, prev-errors, ling-count) from DB."
  [db eid]
  (let [entity (d/entity db eid)]
    {:prev-score  (or (:emacs-daemon/health-score entity) scoring/default-health-score)
     :prev-errors (or (:emacs-daemon/error-count entity) 0)
     :ling-count  (count (or (:emacs-daemon/lings entity) #{}))}))

(defn- ping-daemon!
  "Action: ping the Emacs daemon. Returns {:success bool :duration-ms int} or
   {:success false :error msg} on throwable. Supervised — never propagates."
  [daemon-id ping-fn]
  (try
    (if ping-fn
      (ping-fn daemon-id)
      (let [ec-fn (requiring-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
        (ec-fn "t" 3000)))
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn- log-heartbeat!
  "Action: structured log of a heartbeat outcome."
  [daemon-id {:keys [success? latency-ms new-errors health-score health-level ling-count]}]
  (if success?
    (log/debug "Heartbeat OK:" daemon-id
               "latency:" latency-ms "ms"
               "health:" health-score (name health-level)
               "lings:" ling-count)
    (log/warn "Heartbeat FAILED:" daemon-id
              "errors:" new-errors
              "health:" health-score (name health-level))))

(defn heartbeat!
  "Execute a heartbeat for a daemon: ping Emacs, measure latency, update health.

   Stratified orchestration: daemon-snapshot → ping-daemon! → (pure calc)
   → d/transact! → log-heartbeat!. Pure helpers live above; this fn is
   the boundary that wires DB + ping + log together.

   Arguments:
     daemon-id  - Daemon to heartbeat
     ping-fn    - Function that pings Emacs, returns {:success bool :duration-ms int}
                  (default: uses emacsclient eval-elisp-with-timeout)

   Returns:
     Map with :healthy? :health-score :latency-ms :health-level :consecutive-errors
     or nil if daemon not found"
  ([daemon-id]
   (heartbeat! daemon-id nil))
  ([daemon-id ping-fn]
   (let [c   (conn/ensure-conn)
         db  @c
         eid (:db/id (d/entity db [:emacs-daemon/id daemon-id]))]
     (when eid
       (let [{:keys [prev-score prev-errors ling-count]} (daemon-snapshot db eid)
             ping-result (ping-daemon! daemon-id ping-fn)
             metrics     (scoring/ping-metrics ping-result prev-errors)
             base-score  (scoring/compute-health-score prev-score
                                                       (:latency-ms metrics)
                                                       (:new-errors metrics)
                                                       ling-count)
             final-score (scoring/apply-recovery-bonus base-score
                                                       (:success? metrics)
                                                       prev-errors)
             report      (scoring/heartbeat-report metrics final-score ling-count)]
         (d/transact! c (scoring/heartbeat-tx-data eid (java.util.Date.) metrics final-score))
         (log-heartbeat! daemon-id report)
         report)))))

;;; Redistribution now lives in hive-mcp.emacs.daemon-redistribution.
;;; Re-export for backward compatibility with daemon_store.clj callers.
(defn redistribute-lings!
  "Delegate to daemon-redistribution. See that ns for full docs."
  [store]
  (require 'hive-mcp.emacs.daemon-redistribution)
  ((resolve 'hive-mcp.emacs.daemon-redistribution/redistribute-lings!) store))

(defn redistribution-status
  "Delegate to daemon-redistribution. See that ns for full docs."
  [store]
  (require 'hive-mcp.emacs.daemon-redistribution)
  ((resolve 'hive-mcp.emacs.daemon-redistribution/redistribution-status) store))

(comment
  ;; Score a daemon
  ;; (score-daemon {:emacs-daemon/id "server"
  ;;                :emacs-daemon/status :active
  ;;                :emacs-daemon/health-score 85
  ;;                :emacs-daemon/lings #{"ling-1" "ling-2"}}
  ;;               "hive-mcp")

  ;; Select best daemon
  ;; (select-daemon (daemon-store/get-store)
  ;;                {:project-id "hive-mcp"})

  ;; Heartbeat with health scoring
  ;; (heartbeat! "server")
  )
