(ns hive-mcp.resilience.runner
  "L2 — apply a `RetryDecision` policy around an effectful 0-arg
   thunk. Returns a `hive-dsl.result/Result`.

   This is the protocol-driven generalization of
   `hive-mcp.vectordb.resilience/call-with-resilience-result`. The
   vectordb ns hard-codes the memory-store kick-and-wait recovery;
   the runner here accepts a `kick!` fn so any backend (plan store,
   kanban store, downstream addons) can plug in its own heal primitive
   without re-implementing the railway.

   Design follows the SLAP-decomposed shape of vectordb/resilience:
   per-class branching is encoded as small handlers; the top-level
   composition is one if-else over `attempt → on-failure → retry-once`."
  (:require [hive-dsl.result :as r]
            [hive-mcp.resilience.classify :as classify]
            [hive-mcp.resilience.policy :as policy]
            [hive-mcp.resilience.protocol :as proto]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private retry-sentinel ::retry)

(defn- attempt
  "Boundary — convert a thrown exception into a Result."
  [f]
  (try
    (r/ok (f))
    (catch Throwable t
      (r/err :runner/exec-failed {:throwable t}))))

(defn- on-schema-mismatch [t err-class]
  (r/err :embedder/dim-mismatch
         {:message   (:message err-class)
          :details   (:details err-class)
          :throwable t}))

(defn- on-fatal [t err-class]
  (r/err :runner/fatal {:throwable t :class err-class}))

(defn- on-transient [^Throwable t err-class kick!]
  (log/warn "Runner transient failure:" (.getMessage t)
            "— invoking kick! and retrying once")
  (when kick! (kick!))
  (r/ok retry-sentinel))

(defn- on-failure
  "Dispatch on classification. `kick!` may be nil — runners with no
   recovery primitive degrade to no-kick + still retry once on
   transient (since the transient may have been a passing flake)."
  [^Throwable t kick!]
  (let [err-class (classify/classify t)]
    (cond
      (proto/schema-mismatch? err-class) (on-schema-mismatch t err-class)
      (not (proto/transient? err-class)) (on-fatal t err-class)
      :else                              (on-transient t err-class kick!))))

(defn- retry-sentinel? [result]
  (and (r/ok? result) (= retry-sentinel (:ok result))))

(defn- retry-once [f]
  (let [r (attempt f)]
    (if (r/ok? r)
      r
      (let [t2 (:throwable r)]
        (log/warn "Runner retry still failed:"
                  (some-> ^Throwable t2 .getMessage))
        (r/err :runner/retry-failed {:throwable t2})))))

(defn run
  "Run thunk `f`. Returns a `Result`. Options:

   - `:kick!` — 0-arg fn called once before the retry on a transient
     failure. Optional; if omitted, a transient still triggers retry
     but without any recovery work.
   - `:budget-ms` — currently unused by the runner; reserved for
     future per-call budget tracking. Accepted for API parity with
     `vectordb.resilience/call-with-resilience-result`."
  ([f] (run f {}))
  ([f {:keys [kick!]}]
   (let [first-result (attempt f)]
     (if (r/ok? first-result)
       first-result
       (let [decision (on-failure (:throwable first-result) kick!)]
         (if (retry-sentinel? decision)
           (retry-once f)
           decision))))))

(defn decide-only
  "Convenience — classify a Throwable and return its `RetryDecision`
   without running anything. Used by callers that already own the
   try/catch and just want the policy shape."
  [t]
  (policy/decide (classify/classify t)))
