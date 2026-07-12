(ns hive-mcp.embeddings.deadline
  "Time budget for a bounded provider chain.

   Strata (each calls only downward):

     value object  Deadline          an expiry + a clock
     port          IClock            the one impurity — reading the time
     arithmetic    cap-budget        plain numbers; knows nothing of clocks

   The arithmetic layer takes plain data, so malli both validates AND generates
   it — hive-schemas synthesizes the property tests from these schemas.

   `cap-budget` carries the invariant: an attempt may spend only what is left,
   so N attempts of B ms can never sum past the total."
  (:require [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Value Objects
;; =============================================================================

(def Ms
  "A duration in milliseconds. Never negative; bounded so generators stay sane."
  [:int {:min 0 :max 600000}])

(def min-viable-ms
  "A budget below this cannot buy a useful attempt, so the chain stops instead
   of spending the remainder on a call that will time out anyway."
  250)

(def Budget
  "What is left, and what one attempt would like to spend."
  [:map
   [:remaining-ms   Ms]
   [:per-attempt-ms Ms]])

(def Remaining
  "Just what is left on the clock."
  [:map [:remaining-ms Ms]])

(def ViableRemaining
  "A remainder large enough to be worth another attempt."
  [:map [:remaining-ms [:int {:min min-viable-ms :max 600000}]]])

;; =============================================================================
;; Arithmetic — plain data, no clock (the deepest stratum)
;; =============================================================================

(defn cap-budget
  "What one attempt may spend: its own budget, capped by what remains."
  [{:keys [remaining-ms per-attempt-ms]}]
  (min per-attempt-ms remaining-ms))

(def ^:private remaining-validator
  "Compiled once — m/validate would rebuild the validator on every call."
  (m/validator Remaining))

(defn viable-remaining?
  "Is enough budget left to be worth another attempt?

   Validates the shape before doing arithmetic, so a malformed budget is simply
   not viable rather than an NPE thrown at the caller. A `nil` remainder means
   we do not know how much time is left — the safe reading of that is `no`."
  [m]
  (and (remaining-validator m)
       (>= (:remaining-ms m) min-viable-ms)))

;; =============================================================================
;; Clock Port (DIP)
;; =============================================================================

(defprotocol IClock
  "Monotonic time source."
  (-now-ms [this] "Monotonic milliseconds. Only meaningful as a difference."))

(defrecord SystemClock []
  IClock
  (-now-ms [_] (quot (System/nanoTime) 1000000)))

(defrecord FixedClock [now-ms-atom]
  IClock
  (-now-ms [_] @now-ms-atom))

(defn system-clock
  "The production clock."
  []
  (->SystemClock))

(defn fixed-clock
  "A clock the caller advances by hand — lets a test bound time without sleeping."
  [start-ms]
  (->FixedClock (atom start-ms)))

(defn advance!
  "Move a FixedClock forward by `ms`. Returns the new time."
  [fixed-clock ms]
  (swap! (:now-ms-atom fixed-clock) + ms))

;; =============================================================================
;; Deadline — the clock-aware layer, delegating every decision downward
;; =============================================================================

(defrecord Deadline [clock expires-at-ms])

(defn deadline
  "A Deadline expiring `total-ms` from now."
  ([total-ms] (deadline (system-clock) total-ms))
  ([clock total-ms]
   (->Deadline clock (+ (-now-ms clock) total-ms))))

(defn remaining-ms
  "Milliseconds left, never negative."
  [d]
  (max 0 (- (:expires-at-ms d) (-now-ms (:clock d)))))

(defn attempt-budget-ms
  "What the next attempt may spend against this deadline."
  [d per-attempt-ms]
  (cap-budget {:remaining-ms   (remaining-ms d)
               :per-attempt-ms per-attempt-ms}))

(defn viable?
  "Is there enough time left to start another attempt?"
  [d]
  (viable-remaining? {:remaining-ms (remaining-ms d)}))

(defn expired?
  "Complement of viable? — no attempt should be started."
  [d]
  (not (viable? d)))

;; =============================================================================
;; Contracts (malli, Rung 2)
;; =============================================================================

(def HasExpiry [:map [:expires-at-ms :int]])

(m/=> cap-budget [:=> [:cat Budget] Ms])

(m/=> viable-remaining? [:=> [:cat Remaining] :boolean])

(m/=> remaining-ms [:=> [:cat HasExpiry] [:int {:min 0}]])

(m/=> attempt-budget-ms [:=> [:cat HasExpiry Ms] Ms])

(m/=> viable? [:=> [:cat HasExpiry] :boolean])

(m/=> expired? [:=> [:cat HasExpiry] :boolean])
