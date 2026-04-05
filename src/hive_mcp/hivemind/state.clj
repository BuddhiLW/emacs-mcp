(ns hive-mcp.hivemind.state
  "Hivemind state atoms and direct accessors."

  (:require [hive-mcp.server.guards :as guards]
            [taoensso.timbre :as log]
            [hive-dsl.bounded-atom :refer [bounded-atom bput! bget bounded-swap!
                                           bclear! register-sweepable!]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^{:doc "Map of ask-id -> {:question ... :response-chan ...}"}
  pending-asks
  (atom {}))

;; gc-fix-3: agent-registry — LRU eviction, 100 entries, 2hr TTL
;; Stores per-agent message history. LRU evicts least-active agents.
(defonce ^{:doc "Agent message history ring buffer. Map of agent-id -> {:messages [...] :last-seen timestamp}."}
  agent-registry
  (bounded-atom {:max-entries 100
                 :ttl-ms 7200000    ;; 2 hours
                 :eviction-policy :lru}))
(register-sweepable! agent-registry :agent-registry)

;; gc-fix-3: pending-swarm-prompts — LRU eviction, 50 entries, 10min TTL
;; Prompts are ephemeral; unanswered ones stale quickly.
(defonce ^{:doc "Map of slave-id -> {:prompt :timestamp :session-id}. Permission prompts from Emacs slaves."}
  pending-swarm-prompts
  (bounded-atom {:max-entries 50
                 :ttl-ms 600000     ;; 10 minutes
                 :eviction-policy :lru}))
(register-sweepable! pending-swarm-prompts :pending-swarm-prompts)

;; gc-fix-3: ling-results — LRU eviction, 50 entries, 30min TTL
;; Results are short-lived; reviewed results waste memory.
(defonce ling-results
  (bounded-atom {:max-entries 50
                 :ttl-ms 1800000    ;; 30 minutes
                 :eviction-policy :lru}))
(register-sweepable! ling-results :ling-results)

(defn record-ling-result!
  "Record ling completion for coordinator review."
  [agent-id result]
  (bput! ling-results agent-id
         {:result result
          :timestamp (System/currentTimeMillis)
          :reviewed? false}))

(defn get-pending-ling-results
  "Get ling results awaiting coordinator review."
  []
  (->> @(:atom ling-results)
       (reduce-kv (fn [acc k entry]
                    (let [data (:data entry)]
                      (if (not (:reviewed? data))
                        (assoc acc k data)
                        acc)))
                  {})))

(defn mark-ling-reviewed!
  "Mark a ling result as reviewed by coordinator."
  [agent-id]
  (when-let [current (bget ling-results agent-id)]
    (bput! ling-results agent-id (assoc current :reviewed? true))))

(defn clear-ling-results!
  "Clear all ling results (e.g., at session end)."
  []
  (bclear! ling-results))

(defn add-swarm-prompt!
  "Add a permission prompt from a swarm slave."
  [slave-id prompt-text session-id timestamp]
  (let [prompt-data {:prompt prompt-text
                     :timestamp timestamp
                     :session-id session-id
                     :received-at (System/currentTimeMillis)}]
    (bput! pending-swarm-prompts slave-id prompt-data)
    (log/info "Swarm prompt received from" slave-id ":"
              (subs prompt-text 0 (min 50 (count prompt-text))))
    prompt-data))

(defn remove-swarm-prompt!
  "Remove a swarm prompt (after it's been answered)."
  [slave-id]
  (bounded-swap! pending-swarm-prompts dissoc slave-id)
  (log/debug "Swarm prompt cleared:" slave-id))

(defn get-swarm-prompts
  "Get all pending swarm prompts.
   Returns map of slave-id -> prompt-data (unwrapped)."
  []
  (reduce-kv (fn [acc k entry]
               (assoc acc k (:data entry)))
             {}
             @(:atom pending-swarm-prompts)))

(defn clear-agent-registry!
  "Clear agent registry. Guarded — no-op if coordinator running."
  []
  (guards/when-not-coordinator
   "clear-agent-registry! called"
   (bclear! agent-registry)))

;; =============================================================================
;; Per-agent Cleanup (for ling death)
;; =============================================================================

(defn remove-ling-result!
  "Remove a specific ling's result entry (on ling death)."
  [agent-id]
  (bounded-swap! ling-results dissoc agent-id))

(defn clear-agent-messages!
  "Remove a specific agent's message history (on ling death)."
  [agent-id]
  (bounded-swap! agent-registry dissoc agent-id))
