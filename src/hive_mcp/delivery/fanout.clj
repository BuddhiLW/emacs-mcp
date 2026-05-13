(ns hive-mcp.delivery.fanout
  "Isolated fanout for IDeliveryChannel registry (ENGINE-L2.3).

   Each `fanout!` invocation gates every registered channel through:

     1. A per-channel circuit breaker — once a channel exceeds
        `:max-failures` consecutive failures (timeout or throw) it is
        skipped until the cooldown elapses. Reuses the L1.1 pure
        transitions in `slots.breaker`.

     2. A bounded-time deliver via hive-weave's `safe-future-call` —
        each `deliver!` is wrapped in a Result-returning future that
        cancels on `:per-channel-timeout-ms`. Channels can't share a
        thread, can't block siblings, and timeout/exception paths
        converge into a single Result branch.

     3. Telemetry — every drop, timeout, or exception emits a
        `:delivery/failure` event (non-fatal, ignored if no handler is
        registered) plus a structured log line.

   The event payload is the original persistent map passed by the
   caller; Clojure's structural sharing already guarantees no channel
   can mutate it. The 'immutable envelope' here is a labelling
   convention — `wrap-envelope` tags the event with `::id` + `::ts` so
   downstream telemetry can correlate per-channel outcomes back to a
   single fanout invocation."
  (:require [hive-dsl.result :as r :refer [rescue]]
            [hive-mcp.knowledge-graph.slots.breaker :as cb]
            [hive-mcp.protocols.delivery-channel :as dc]
            [hive-weave.safe :as ws]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; -----------------------------------------------------------------------------
;; Config
;; -----------------------------------------------------------------------------

(def default-policy
  "Per-channel breaker + timeout defaults. Override via `fanout!` opts."
  {:max-failures           3
   :initial-cooldown-ms    15000   ;; 15s — shorter than L1.1's 30s; channels
                                   ;; flap faster than KG slots and we want
                                   ;; recovery probes sooner.
   :max-cooldown-ms        300000  ;; 5min ceiling.
   :per-channel-timeout-ms 500     ;; generous for in-process channels, tight
                                   ;; enough to catch a hung TCP write.
   :availability-timeout-ms 50})

;; -----------------------------------------------------------------------------
;; State — per-channel breakers
;; -----------------------------------------------------------------------------

(defonce ^{:private true
           :doc "channel-id -> breaker map. Lives for the JVM. Reset via
                 `reset-breakers!` (tests / operator)."}
  *breakers (atom {}))

(defn reset-breakers!
  "Clear all per-channel breaker state. Tests / ops surface."
  []
  (cb/reset! *breakers))

(defn breaker-snapshot
  "Diagnostic snapshot of every channel's breaker state."
  []
  (cb/snapshot *breakers))

;; -----------------------------------------------------------------------------
;; Envelope tagging
;; -----------------------------------------------------------------------------

(defn wrap-envelope
  "Attach correlation metadata (`::id`, `::ts`) to an event. Idempotent:
   calling twice keeps the first id/ts. Caller can pass an explicit id
   to thread a parent correlation through."
  ([event] (wrap-envelope event nil))
  ([event id]
   (cond-> event
     (nil? (::id event))
     (assoc ::id (or id (str (random-uuid))))
     (nil? (::ts event))
     (assoc ::ts (System/currentTimeMillis)))))

;; -----------------------------------------------------------------------------
;; Telemetry
;; -----------------------------------------------------------------------------

(defn- emit-failure-event!
  "Best-effort emit of `:delivery/failure`. Wrapped in `rescue` so a
   missing event-handler never propagates back into the caller. We
   late-resolve `dispatch` to avoid a compile-time edge from delivery
   into the event bus."
  [payload]
  (rescue nil
    (when-let [dispatch (requiring-resolve 'hive-mcp.events.core/dispatch)]
      (dispatch [:delivery/failure payload]))))

(defn- record-outcome!
  "Log + emit `:delivery/failure` and update the per-channel breaker."
  [chan-id envelope-id outcome detail policy]
  (case outcome
    :ok
    (cb/record-success! *breakers chan-id)

    (:timeout :exception :unavailable-throw)
    (do
      (cb/record-failure! *breakers chan-id policy)
      (log/warn "[fanout]" outcome "on channel" chan-id
                "envelope:" envelope-id
                (when detail (str "detail: " detail)))
      (emit-failure-event! {:channel-id  chan-id
                            :envelope-id envelope-id
                            :outcome     outcome
                            :detail      (some-> detail str)}))

    :breaker-open
    (do
      (log/debug "[fanout] breaker open — skipping" chan-id)
      (emit-failure-event! {:channel-id  chan-id
                            :envelope-id envelope-id
                            :outcome     :breaker-open}))))

;; -----------------------------------------------------------------------------
;; Result classification — converts hive-weave Result → outcome keyword
;; -----------------------------------------------------------------------------

(defn- classify
  "Map a hive-weave `safe-future-call` Result to one of:
   `[:ok val]` | `[:timeout msg]` | `[:exception msg]`."
  [result]
  (cond
    (r/ok? result)
    [:ok (:ok result)]

    (= :weave/timeout (:error result))
    [:timeout (str "timed out after " (:timeout-ms result) "ms")]

    (= :weave/exception (:error result))
    [:exception (or (:message result) (:class result) "exception")]

    :else
    [:exception (str "unknown error: " (pr-str result))]))

;; -----------------------------------------------------------------------------
;; Public fanout
;; -----------------------------------------------------------------------------

(defn- channel-allowed?
  "Consult the breaker for `chan-id`. Returns true when the call is
   allowed to proceed (`:closed` or `:half-open`)."
  [chan-id policy]
  (let [b (cb/attempt *breakers chan-id policy)]
    (not= :block (cb/decision b))))

(defn fanout!
  "Deliver `event` to every registered IDeliveryChannel with full
   per-channel fault isolation.

   Options (merged into `default-policy`):
   - :max-failures
   - :initial-cooldown-ms / :max-cooldown-ms
   - :per-channel-timeout-ms
   - :availability-timeout-ms

   Returns a map keyed by channel-id of the per-channel outcome
   (`:ok` | `:timeout` | `:exception` | `:breaker-open` |
   `:unavailable` | `:unavailable-throw`). Callers that don't care about
   outcomes can ignore the return value — fanout never throws."
  ([event] (fanout! event nil))
  ([event opts]
   (let [policy   (merge default-policy opts)
         envelope (wrap-envelope event)
         env-id   (::id envelope)
         timeout  (:per-channel-timeout-ms policy)
         av-to    (:availability-timeout-ms policy)]
     (reduce
      (fn [acc ch]
        (let [chan-id (dc/channel-id ch)]
          (cond
            (not (channel-allowed? chan-id policy))
            (do (record-outcome! chan-id env-id :breaker-open nil policy)
                (assoc acc chan-id :breaker-open))

            :else
            (let [av-result (ws/safe-future-call
                             {:timeout-ms av-to
                              :name (str "ch/" (name chan-id) "/available?")}
                             #(dc/available? ch))
                  [av-tag av-val] (classify av-result)]
              (case av-tag
                ;; Treat availability check failures as soft skips that
                ;; still count against the breaker — a flapping
                ;; `connected?` shouldn't poll forever.
                :timeout
                (do (record-outcome! chan-id env-id :timeout av-val policy)
                    (assoc acc chan-id :timeout))

                :exception
                (do (record-outcome! chan-id env-id :unavailable-throw av-val policy)
                    (assoc acc chan-id :unavailable-throw))

                :ok
                (if (not av-val)
                  (assoc acc chan-id :unavailable)
                  (let [d-result (ws/safe-future-call
                                  {:timeout-ms timeout
                                   :name (str "ch/" (name chan-id) "/deliver!")}
                                  #(dc/deliver! ch envelope))
                        [d-tag d-val] (classify d-result)]
                    (case d-tag
                      :ok        (do (record-outcome! chan-id env-id :ok nil policy)
                                     (assoc acc chan-id :ok))
                      :timeout   (do (record-outcome! chan-id env-id :timeout d-val policy)
                                     (assoc acc chan-id :timeout))
                      :exception (do (record-outcome! chan-id env-id :exception d-val policy)
                                     (assoc acc chan-id :exception))))))))))
      {}
      (dc/get-channels)))))
