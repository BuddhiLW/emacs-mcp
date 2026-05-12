(ns hive-mcp.vectordb.resilience
  "MCP-handler-side reactive resilience around memory-store calls.

   Cross-store seam: dispatches reconnect logic through
   `hive-mcp.protocols.memory/IMemoryStoreLiveness` so this layer never
   imports any vendor-specific namespace. Every store that owns a
   transport which can drop mid-flight (HTTP/gRPC pools, k8s pod
   restarts, NAT idle-timeout) extends the protocol; this layer
   orchestrates kick + await + retry without knowing which store it is
   talking to.

   Failure classification is the closed `ErrorClass` ADT in
   `hive-mcp.resilience.protocol`. Three branches:

   - `:err/schema-mismatch`  — terminate with `:embedder/dim-mismatch`
                                ex-info. No retry, no heal, no log
                                spam. Emits an advisory hive-event
                                (`:resilience/dim-mismatch`) so future
                                observers (metrics, KG) can react
                                without coupling to this layer.
   - `:err/transient`        — kick the heal loop, await recovery,
                                retry `f` once. Same single-retry
                                semantics as before the fix.
   - everything else         — propagate the original throwable.

   Internals use the `hive-dsl` Result railway (`ok` / `err` /
   `let-ok`). The legacy `call-with-resilience` keeps its
   exception-throwing surface for backward compatibility with the
   ~50 existing call sites; new code that wants Result semantics
   should call `call-with-resilience-result` directly."
  (:require [hive-dsl.result :as r]
            [hive.events.router :as events]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.protocols.memory-liveness :as liveness]
            [hive-mcp.resilience.classify :as rc]
            [hive-mcp.resilience.protocol :as rproto]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-budget-ms
  "Upper bound to block while a store's heal loop tries to install a
   fresh client. Mirrors the budget used by store-side reactive retry
   paths so the two timers compose without surprises."
  8000)

;; ---------------------------------------------------------------------------
;; Liveness kick (effect — pulled out so railway stages stay pure-ish)
;; ---------------------------------------------------------------------------

(defn- active-liveness-store
  "Return the active memory store iff it extends `IMemoryStoreLiveness`,
   else nil. Used by `kick-and-wait!` to avoid coupling this ns to any
   specific backend."
  []
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)]
      (when (liveness/liveness-store? store)
        store))))

(defn kick-and-wait!
  "Ask the active store to kick its heal loop and block up to
   `budget-ms` for probe-verified recovery. Returns true if the store
   reports alive at the end of the wait, false otherwise."
  ([]
   (kick-and-wait! default-budget-ms))
  ([budget-ms]
   (if-let [store (active-liveness-store)]
     (do (liveness/-kick-reconnect! store)
         (boolean (liveness/-await-reconnect! store budget-ms)))
     false)))

;; ---------------------------------------------------------------------------
;; Advisory event — fire-and-forget. Wrapped in `rescue` per the
;; \"Advisory events should not short-circuit the main pipeline\"
;; principle (memory 20260418200155-23c5b720) so a missing or
;; mis-registered handler can never break the resilience path.
;; ---------------------------------------------------------------------------

(defn- emit-dim-mismatch-event!
  [^Throwable t err-class]
  (r/rescue nil
            (events/dispatch
              [:resilience/dim-mismatch
               {:message  (:message err-class)
                :details  (:details err-class)
                :ex-class (some-> t class .getName)}])))

;; ---------------------------------------------------------------------------
;; Public predicates (kept for back-compat with existing imports)
;; ---------------------------------------------------------------------------

(defn transient-failure?
  "True iff `t` classifies as `:err/transient` per the new ADT
   classifier. Kept for back-compat — internal callers prefer
   working with the `ErrorClass` ADT directly."
  [^Throwable t]
  (rproto/transient? (rc/classify t)))

;; ---------------------------------------------------------------------------
;; Railway — Result-returning core
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Railway stages — one level of abstraction each, individually testable.
;;
;; Stage glossary:
;;   attempt       — boundary: thunk → Result. Catches throw.
;;   on-failure    — failure → terminal Result OR retry-sentinel.
;;     ├ on-schema-mismatch  — terminal err, advisory event.
;;     ├ on-fatal            — terminal err, no kick.
;;     └ on-transient        — kick + signal retry.
;;   retry-once    — retry-sentinel → Result of second attempt.
;; ---------------------------------------------------------------------------

(def ^:private retry-sentinel
  "Sentinel value `on-failure` returns inside an `(ok ...)` to instruct
   the caller to run a second attempt. Decoupled from booleans so
   pattern-matching is explicit."
  ::retry)

(defn- attempt
  "Run `f` and convert the throw boundary into a Result. Successful
   value rides on `(ok value)`; an exception rides as `:throwable`
   on the err so downstream stages classify without re-throwing."
  [f]
  (try
    (r/ok (f))
    (catch Throwable t
      (r/err :resilience/exec-failed {:throwable t}))))

(defn- on-schema-mismatch
  "Schema-mismatch terminator. Fires an advisory event and returns the
   standardized `:embedder/dim-mismatch` err."
  [^Throwable t err-class]
  (emit-dim-mismatch-event! t err-class)
  (r/err :embedder/dim-mismatch
         {:message   (:message err-class)
          :details   (:details err-class)
          :throwable t}))

(defn- on-fatal
  "Non-transient, non-schema terminator. No kick, no retry — preserve
   stack trace via `:throwable` for the legacy throwing shim."
  [^Throwable t err-class]
  (r/err :resilience/fatal {:throwable t :class err-class}))

(defn- on-transient
  "Transient: kick the heal loop, return retry sentinel."
  [^Throwable t budget-ms]
  (log/warn "Memory store transient failure in MCP handler path:"
            (.getMessage t) "— kicking heal loop and retrying once")
  (kick-and-wait! budget-ms)
  (r/ok retry-sentinel))

(defn- on-failure
  "Dispatch on `ErrorClass` variant. Returns a terminal Result or the
   retry sentinel. Pure dispatch — side effects live in the per-class
   handlers."
  [^Throwable t budget-ms]
  (let [class (rc/classify t)]
    (cond
      (rproto/schema-mismatch? class) (on-schema-mismatch t class)
      (not (rproto/transient? class)) (on-fatal t class)
      :else                            (on-transient t budget-ms))))

(defn- retry-sentinel?
  "True iff `result` is the retry-instruction `(ok ::retry)`."
  [result]
  (and (r/ok? result) (= retry-sentinel (:ok result))))

(defn- retry-once
  "Run `f` a second time after a kick. Wraps the retry's failure with
   `:resilience/retry-failed` so callers can distinguish a flaky
   transport from a permanent fault."
  [f]
  (let [r (attempt f)]
    (if (r/ok? r)
      r
      (let [t2 (:throwable r)]
        (log/warn "Memory store retry still failed after heal attempt:"
                  (some-> ^Throwable t2 .getMessage))
        (r/err :resilience/retry-failed {:throwable t2})))))

;; ---------------------------------------------------------------------------
;; Composed top-level — orchestration only, no per-stage logic.
;; ---------------------------------------------------------------------------

(defn call-with-resilience-result
  "Railway-oriented core. Returns a `hive-dsl.result/Result`:

   - `(ok value)`                          — `f` succeeded (first or retry).
   - `(err :embedder/dim-mismatch ...)`    — schema mismatch, no retry.
   - `(err :resilience/fatal ...)`         — non-transient, non-schema.
   - `(err :resilience/retry-failed ...)`  — transient retry exhausted.

   The body is a 4-line composition over `attempt → on-failure →
   retry-once`. Each stage is independently testable; this fn only
   wires them."
  ([f] (call-with-resilience-result f default-budget-ms))
  ([f budget-ms]
   (let [first-result (attempt f)]
     (cond
       (r/ok? first-result)
       first-result

       :else
       (let [decision (on-failure (:throwable first-result) budget-ms)]
         (if (retry-sentinel? decision)
           (retry-once f)
           decision))))))

;; ---------------------------------------------------------------------------
;; Legacy throwing surface — thin shim over the Result-returning core
;; ---------------------------------------------------------------------------

(defn- result->throw
  "Convert a `Result` back to the legacy throwing contract. Schema
   mismatches surface as `:embedder/dim-mismatch` ex-info; other
   errors re-throw the original throwable so stack traces are
   preserved."
  [result]
  (cond
    (r/ok? result)
    (:ok result)

    (= :embedder/dim-mismatch (:error result))
    (throw (ex-info (or (:message result)
                        "Milvus rejected upsert with schema mismatch")
                    {:err/tag    :embedder/dim-mismatch
                     :err/cause  (some-> ^Throwable (:throwable result) .getMessage)
                     :details    (:details result)
                     :fix        "Route via collection-locator; vector dim must equal collection dim"}
                    (:throwable result)))

    :else
    (throw (or (:throwable result)
               (ex-info (str (:error result))
                        (dissoc result :throwable))))))

(defn call-with-resilience
  "Run `f` (0-arg). On a transient transport failure, kick the active
   store's heal loop, await recovery up to `budget-ms`, and retry `f`
   once. Schema-mismatch failures (e.g. Milvus code 1804) re-surface
   as `:embedder/dim-mismatch` ex-info — no kick, no retry, no log
   spam. Other fatal exceptions propagate unchanged. Successful calls
   pay zero extra overhead beyond one try.

   Implementation note: backward-compatible throwing shim over
   `call-with-resilience-result`. New code wanting Result semantics
   should call the `*-result` variant directly."
  ([f]
   (call-with-resilience f default-budget-ms))
  ([f budget-ms]
   (-> (call-with-resilience-result f budget-ms)
       result->throw)))

(defmacro with-resilience
  "Evaluate `body` under `call-with-resilience`. See fn docstring for
   failure classification and retry semantics."
  [& body]
  `(call-with-resilience (fn [] ~@body)))

(defmacro with-resilience-result
  "Evaluate `body` under `call-with-resilience-result`. Returns a
   `Result`; never throws on classified failures."
  [& body]
  `(call-with-resilience-result (fn [] ~@body)))
