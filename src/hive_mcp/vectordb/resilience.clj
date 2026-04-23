(ns hive-mcp.vectordb.resilience
  "MCP-handler-side reactive resilience around Milvus-backed memory store
   calls.

   Why this exists — even though `hive-milvus.store/*` protocol methods
   already wrap every RPC in `hive-milvus.store.health/resilient`:

   1. `milvus-clj.api/query` et al return a `future`. hive-milvus derefs
      them inside the resilient body with `@(milvus/...)`. When the
      underlying HTTP transport throws an `ex-info` tagged
      `{::client/transport :http :cause :io}` (e.g. 'selector manager
      closed'), the deref re-wraps it as
      `java.util.concurrent.ExecutionException`.

   2. `hive-milvus.failure/classify` calls
      `milvus-clj.client/classify-error` on that `ExecutionException`
      wrapper. The wrapper carries no `::client/transport` ex-data, so
      classify-error falls through to `:fatal`.

   3. `hive-milvus.store.health/classify-err` then re-throws instead of
      kicking the reconnect loop. The background heal loop never starts,
      `ensure-live!` keeps reading a stale `:alive? true` health-cache,
      and every subsequent call fails the same way until something calls
      `milvus-clj.api/connect!` by hand.

   Fix without touching the store implementation: catch the wrapped
   IO failure at the handler call site, invalidate the store's liveness
   cache, kick the heal loop, wait briefly for recovery, and retry the
   call once. On the retry, `hive-milvus.store.health/ensure-live!` sees
   the freshly-invalidated cache and — if the loop re-installed a client —
   lets the call through; if still dead, the reactive retry path inside
   the store takes another shot.

   Keep the heuristics narrow: we only treat exceptions whose root cause
   is `java.io.IOException` (or an `ex-info` marked `:cause :io`) as
   transient. Anything else re-throws unchanged so genuine programming
   errors still surface as the original stack trace."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-milvus.store.health :as health]
            [taoensso.timbre :as log])
  (:import [java.io IOException]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-budget-ms
  "Upper bound to block while the background heal loop tries to install a
   fresh client. Mirrors the budget `hive-milvus.store.health/with-auto-reconnect`
   uses on its reactive retry path."
  8000)

(def ^:private transient-message-markers
  "Substrings observed on transport-drop exceptions that slip past
   `milvus-clj.client/classify-error` because of future-wrapping
   (ExecutionException loses the ::client/transport ex-data)."
  ["selector manager closed"
   "IO failure"
   "Connection reset"
   "Broken pipe"
   "UNAVAILABLE"
   "DEADLINE_EXCEEDED"
   "Keepalive failed"
   "connection is likely gone"
   "not connected"])

(defn- causal-chain
  "Seq of the throwable and every `getCause` link, stopping on nil or a
   self-cycle."
  [^Throwable t]
  (loop [t t acc []]
    (if (or (nil? t) (some #(identical? t %) acc))
      acc
      (recur (.getCause t) (conj acc t)))))

(defn- message-looks-transient?
  [msg]
  (when msg
    (let [s (str msg)]
      (boolean (some #(.contains s ^String %) transient-message-markers)))))

(defn transient-failure?
  "True if `t` (or any cause in its chain) is a transport-level
   connection drop that the Milvus heal loop can recover from by
   rebuilding the HTTP/gRPC client.

   Detects:
   - Any `java.io.IOException` in the chain.
   - `ex-info` maps with `:cause :io` ex-data (set by the HTTP
     transport on IOException).
   - Messages carrying any `transient-message-markers` substring,
     which catches the ExecutionException wrapping a tagged ex-info
     whose ex-data is lost on the wrapper."
  [^Throwable t]
  (boolean
    (some (fn [^Throwable link]
            (or (instance? IOException link)
                (when-let [data (ex-data link)]
                  (or (= :io (:cause data))
                      ;; milvus-clj.client/transport is the tagged ns-qualified key
                      (contains? data :milvus-clj.client/transport)))
                (message-looks-transient? (.getMessage link))))
          (causal-chain t))))

(defn- store-config-atom
  "Fish out the Milvus store's `config-atom` for use with
   `hive-milvus.store.health` reconnect primitives. Returns nil when
   the active store is not a MilvusMemoryStore — in which case the
   resilience wrapper degenerates to a pass-through try/catch."
  []
  (when (mem-proto/store-set?)
    (let [store (mem-proto/get-store)]
      (when (instance? clojure.lang.ILookup store)
        (:config-atom store)))))

(defn kick-and-wait!
  "Invalidate the stale liveness cache, kick the background reconnect
   loop, and block up to `budget-ms` for the loop to install a fresh
   client. Returns true if `milvus/connected?` is true at the end of
   the wait. No-op + false when no Milvus config-atom is available."
  ([]
   (kick-and-wait! default-budget-ms))
  ([budget-ms]
   (if-let [cfg-atom (store-config-atom)]
     (do (health/invalidate-health-cache!)
         (health/kick-reconnect! cfg-atom)
         (health/await-reconnect! budget-ms))
     false)))

(defn call-with-resilience
  "Run `f` (0-arg). On a transient transport failure, kick reconnect,
   await recovery up to `budget-ms`, and retry `f` once. Fatal
   exceptions propagate unchanged. Successful calls pay zero extra
   overhead beyond one try."
  ([f]
   (call-with-resilience f default-budget-ms))
  ([f budget-ms]
   (try
     (f)
     (catch Throwable t
       (if-not (transient-failure? t)
         (throw t)
         (do
           (log/warn "Milvus transient failure observed in MCP handler path:"
                     (.getMessage t) "— kicking heal loop and retrying once")
           (kick-and-wait! budget-ms)
           (try
             (f)
             (catch Throwable t2
               (log/warn "Milvus retry still failed after heal attempt:"
                         (.getMessage t2))
               (throw t2)))))))))

(defmacro with-resilience
  "Evaluate `body` under `call-with-resilience`. See fn docstring for
   failure classification and retry semantics."
  [& body]
  `(call-with-resilience (fn [] ~@body)))
