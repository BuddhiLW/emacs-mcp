;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.knowledge-graph.slots.breaker
  "Per-slot circuit breaker for KG slot initialization (ENGINE-L1.1).

   Anchored in the 2026-05-11/12 OOM cascades: when datalevin's
   `ensure-conn!` threw (`Resource temporarily unavailable`), every
   caller — decay scheduler, housekeeping sweep, KG disc traversal —
   retried `build-slot` inline, spawning fresh `ensure-conn!` attempts
   that each blocked on the LMDB file lock. Within minutes the manifold
   pool, the retry queues, and the working-set heap collectively
   exhausted memory.

   This namespace owns the pure decision logic. State lives in an atom
   external to the breaker — usually the registry's slot atom — so the
   breaker can be reused for vec-store slots, dataset slots, anything
   with the same 'expensive-open / cheap-fail-fast' shape.

   ## State machine

       :closed    ── failure × max-failures ──▶ :open
       :open      ── now ≥ opened-at+cooldown ─▶ :half-open
       :half-open ── success ─────────────────▶ :closed
       :half-open ── failure ─────────────────▶ :open (cooldown × 2, capped)

   `:closed` is the default — first-time slot reads pass through. `:open`
   short-circuits with no further `ensure-conn!` attempts until the
   cooldown clock elapses. `:half-open` admits exactly one probe; if
   that succeeds the slot is healthy again, if it fails the cooldown
   doubles (capped at `max-cooldown-ms`)."
  (:refer-clojure :exclude [reset!])
  (:require [taoensso.timbre :as log]))

;; -----------------------------------------------------------------------------
;; Policy
;; -----------------------------------------------------------------------------

(def default-policy
  "Tuning knobs. Tests inject overrides; production sticks with these."
  {:max-failures    3       ; consecutive failures before tripping
   :initial-cooldown-ms 30000 ; 30s — long enough to outlast a flap, short enough not to mask
   :max-cooldown-ms 600000})  ; 10min ceiling on exponential backoff

(defn- now-ms [] (System/currentTimeMillis))

(defn fresh
  "Initial breaker record for one slot."
  []
  {:state :closed
   :failures 0
   :opened-at nil
   :cooldown-ms 0})

;; -----------------------------------------------------------------------------
;; Pure transitions — no IO, no clock unless caller injects one.
;; -----------------------------------------------------------------------------

(defn- step-cooldown
  [{:keys [cooldown-ms]} {:keys [initial-cooldown-ms max-cooldown-ms]}]
  (min (long max-cooldown-ms)
       (long (max initial-cooldown-ms (* 2 (or cooldown-ms 0))))))

(defn on-success
  "Reset the breaker. Called when an `ensure-conn!` returns ok."
  [_breaker]
  (fresh))

(defn on-failure
  "Account a failure. Trip to `:open` when threshold crossed."
  [{:keys [state failures cooldown-ms] :as b} policy ts]
  (let [next-failures (inc (or failures 0))
        threshold     (:max-failures policy)
        next-state    (cond
                        (= :half-open state) :open
                        (>= next-failures threshold) :open
                        :else :closed)
        next-cd       (if (= :open next-state)
                        (step-cooldown b policy)
                        (or cooldown-ms 0))]
    (cond-> (assoc b :failures next-failures
                     :state next-state
                     :cooldown-ms next-cd)
      (= :open next-state) (assoc :opened-at ts))))

(defn maybe-recover
  "Promote `:open` → `:half-open` once cooldown elapses. Pure."
  [{:keys [state opened-at cooldown-ms] :as b} ts]
  (if (and (= :open state)
           (some? opened-at)
           (>= ts (+ ^long opened-at ^long (or cooldown-ms 0))))
    (assoc b :state :half-open)
    b))

(defn decision
  "Read-only view: `:pass` | `:block`.

   - `:closed`    → `:pass`.
   - `:open`      → `:block` until cooldown elapses, then `:pass` (caller
                   promotes to `:half-open` via `attempt`).
   - `:half-open` → `:pass` (single probe in flight)."
  [{:keys [state]}]
  (if (= :open state) :block :pass))

;; -----------------------------------------------------------------------------
;; Atom-backed orchestration — caller passes in an atom keyed by slot.
;; -----------------------------------------------------------------------------

(defn attempt
  "Decide whether `slot` is allowed to probe. Mutates the atom only
   when the breaker transitions `:open` → `:half-open`. Returns the
   refreshed breaker map."
  [breaker-atom slot policy]
  (let [ts (now-ms)
        b' (-> (get @breaker-atom slot (fresh))
               (maybe-recover ts))]
    (swap! breaker-atom assoc slot b')
    b'))

(defn record-success!
  "Call when `ensure-conn!` succeeded — collapses state to :closed."
  [breaker-atom slot]
  (swap! breaker-atom assoc slot (fresh)))

(defn record-failure!
  "Call when `ensure-conn!` threw or returned a failure variant.
   Trips the breaker to `:open` once consecutive failures cross
   `:max-failures`. Returns the new breaker."
  [breaker-atom slot policy]
  (let [ts (now-ms)
        new-b (-> (get @breaker-atom slot (fresh))
                  (on-failure policy ts))]
    (swap! breaker-atom assoc slot new-b)
    (when (= :open (:state new-b))
      (log/error "[kg-slot/breaker] slot tripped open"
                 {:slot slot
                  :failures (:failures new-b)
                  :cooldown-ms (:cooldown-ms new-b)}))
    new-b))

(defn snapshot
  "Diagnostic snapshot of the breaker atom."
  [breaker-atom]
  @breaker-atom)

(defn reset!
  "Tests / operator surface — clear every breaker."
  [breaker-atom]
  (clojure.core/reset! breaker-atom {}))
