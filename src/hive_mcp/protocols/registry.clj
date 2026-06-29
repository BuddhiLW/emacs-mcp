(ns hive-mcp.protocols.registry
  "Reusable backend-slot abstraction behind the per-protocol active-impl
   singletons (set-X!/get-X/X-set?/clear-X!).

   A Slot owns one validated, swappable backend impl: validation on install,
   a configurable empty-policy applied by `current` when nothing is installed
   (throw, or construct a fallback, or seed a default), and an optional
   teardown run on `clear!`. Consumers wrap a Slot in trivial name-preserving
   delegations, so existing call sites are untouched.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; ISlot — a validated single active-impl holder
;;; ============================================================================

(defprotocol ISlot
  "A validated holder for a single active backend implementation."

  (install! [this impl]
    "Validate `impl`, store it, return `impl`. Rejects invalid impls with an
     AssertionError when the slot was built with a :validate predicate.")

  (current [this]
    "Return the installed impl; when none is installed apply the empty-policy
     (throw, or return a constructed fallback / seeded default).")

  (present? [this]
    "True iff an impl is explicitly installed. The empty-policy value does NOT
     count as present.")

  (clear! [this]
    "Remove the installed impl, running the teardown fn on it first when
     configured. Returns nil."))

(defrecord SingleSlot [state validate on-empty teardown]
  ISlot
  (install! [_ impl]
    (when validate (assert (validate impl)))
    (reset! state impl)
    impl)
  (current [_]
    (if-let [v @state]
      v
      (when on-empty (on-empty))))
  (present? [_]
    (some? @state))
  (clear! [_]
    (when-let [impl @state]
      (when teardown
        (try (teardown impl) (catch Exception _))))
    (reset! state nil)
    nil))

(defn single-slot
  "Build a SingleSlot from a config map:
     :validate  pred — impls failing it are rejected (AssertionError) on install!.
     :on-empty  (fn []) — value `current` returns when nothing is installed.
                Pass a throwing fn for throw-on-empty slots; omit for nil-on-empty.
     :teardown  (fn [impl]) — run on the installed impl during clear!.
     :initial   seed value for the slot (default nil)."
  [{:keys [validate on-empty teardown initial]}]
  (->SingleSlot (atom initial) validate on-empty teardown))

;;; ============================================================================
;;; IRegistry — a keyed multi-impl registry
;;; ============================================================================

(defprotocol IRegistry
  "A keyed registry of validated backend implementations (several active at once)."

  (reg-put! [this k impl]
    "Validate `impl`, store it under key `k`, return `impl`.")

  (reg-get [this k]
    "Return the impl under `k`; when absent apply the missing-policy
     (the on-missing fn, called with [k snapshot], or nil).")

  (reg-remove! [this k]
    "Remove key `k`. No-op when absent. Returns nil.")

  (reg-snapshot [this]
    "Return a read-only {k -> impl} snapshot of the registry.")

  (reg-clear! [this]
    "Remove every entry. Returns nil."))

(defrecord MultiSlot [state validate on-missing]
  IRegistry
  (reg-put! [_ k impl]
    (when validate (assert (validate impl)))
    (swap! state assoc k impl)
    impl)
  (reg-get [_ k]
    (if-let [v (get @state k)]
      v
      (when on-missing (on-missing k @state))))
  (reg-remove! [_ k]
    (swap! state dissoc k)
    nil)
  (reg-snapshot [_] @state)
  (reg-clear! [_]
    (reset! state {})
    nil))

(defn multi-slot
  "Build a MultiSlot from a config map:
     :validate    pred — impls failing it are rejected (AssertionError) on reg-put!.
     :on-missing  (fn [k snapshot]) — value `reg-get` returns when `k` is absent.
                  Omit for nil-on-missing.
     :initial     seed map (default {})."
  [{:keys [validate on-missing initial]}]
  (->MultiSlot (atom (or initial {})) validate on-missing))
