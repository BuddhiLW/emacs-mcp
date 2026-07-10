(ns hive-mcp.vectordb.resilience-test
  "Protocol-level tests for the cross-store resilience seam.

   No real backend required — every test uses a `reify` of
   `IMemoryStoreLiveness` so we can assert the orchestration contract
   (catch transient → kick → await → retry once) without a milvus,
   qdrant or chroma instance.

   Reload-safety: this ns assumes the protocol surface is loaded; it
   never re-`defprotocol`s anything itself."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.protocols.memory-liveness :as liveness]
            [hive-mcp.vectordb.resilience :as resilience]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Reified IMemoryStore + IMemoryStoreLiveness stubs
;; =============================================================================

(defn- minimal-imemstore
  "The IMemoryStore protocol has 16 required methods; the resilience
   layer only ever touches the IMemoryStoreLiveness surface, so we
   reify the rest with constant returns just so `register-store!`'s
   `satisfies?` precondition is satisfied."
  []
  (reify mem-proto/IMemoryStore
    (connect! [_ _] {:success? true})
    (disconnect! [_] {:success? true})
    (connected? [_] true)
    (health-check [_] {:healthy? true :backend "stub"})
    (add-entry! [_ _])
    (get-entry [_ _])
    (update-entry! [_ _ _])
    (delete-entry! [_ _])
    (query-entries [_ _])
    (search-similar [_ _ _])
    (supports-semantic-search? [_] false)
    (cleanup-expired! [_])
    (entries-expiring-soon [_ _ _])
    (find-duplicate [_ _ _ _])
    (store-status [_] {:backend "stub"})
    (reset-store! [_] true)))

;; LiveStub is a plain defrecord; we attach IMemoryStoreLiveness via
;; `extend` (not `extend-type` or `reify`) so the protocol impl lands in
;; the protocol's :impls map by class identity rather than by host-
;; interface inheritance. In long-running REPLs where multiple protocol
;; generations coexist, `satisfies?` against a reify can return false
;; because the reify's interface class and the protocol's :on-interface
;; class are different objects with the same name. `extend` sidesteps
;; that entirely.

(defrecord LiveStub [calls-atom probe-result kick-effect await-result])

(extend LiveStub
  liveness/IMemoryStoreLiveness
  {:-probe!
   (fn [this]
     (swap! (:calls-atom this) conj :probe!)
     (:probe-result this))
   :-kick-reconnect!
   (fn [this]
     (swap! (:calls-atom this) conj :kick-reconnect!)
     (when-let [eff (:kick-effect this)] (eff))
     nil)
   :-await-reconnect!
   (fn [this budget-ms]
     (swap! (:calls-atom this) conj [:await-reconnect! budget-ms])
     (:await-result this))})

(defn- live-stub
  "Build a LiveStub with the given orchestration knobs. Records each
   liveness-method invocation into `calls-atom` for assertions."
  [calls-atom & {:keys [probe-result kick-effect await-result]
                 :or {probe-result true
                      await-result true}}]
  (->LiveStub calls-atom probe-result kick-effect await-result))

;; =============================================================================
;; Fixtures — isolate registry mutation
;; =============================================================================

;; Direct registry poke that bypasses `register-store!`'s
;; `(satisfies? IMemoryStore store)` precondition.
;;
;; Why bypass: in long-running REPL/CI sessions the JAR's AOT-compiled
;; IMemoryStore interface can drift from the source-loaded one after a
;; protocol reload (defonce guards mitigate but cannot eliminate this
;; fully — once two generations of the host interface coexist, satisfies?
;; can return false for a reify that DOES implement the post-reload
;; interface, because the precondition resolved the protocol var from a
;; different classloader than the reify did).
;;
;; The resilience seam doesn't actually need the IMemoryStore method
;; surface for any of these tests — only the IMemoryStoreLiveness
;; methods. Bypassing the precondition lets us test the real
;; orchestration contract without a JVM restart between iterations.

(def ^:private registry-atom
  ;; `store-registry` is `^:private` in hive-mcp.protocols.memory; use
  ;; ns-resolve + var-get to bypass the private lookup at compile time
  ;; (regular `@#'ns/sym` would trip the private-var compile error).
  (some-> (ns-resolve 'hive-mcp.protocols.memory 'store-registry)
          var-get))

(defn- put-store! [k store]
  (if registry-atom
    (swap! registry-atom assoc k store)
    (throw (ex-info "could not locate store-registry private var" {}))))

(defn- clear-registry! []
  (when registry-atom (reset! registry-atom {})))

(defn- registry-fixture
  "Ensure each test runs against a clean store registry, bypassing the
   IMemoryStore precondition (see comment above on why)."
  [t]
  (let [snapshot (when registry-atom @registry-atom)]
    (try
      (clear-registry!)
      (t)
      (finally
        (clear-registry!)
        (when snapshot
          (doseq [[k s] snapshot]
            (put-store! k s)))))))

(use-fixtures :each registry-fixture)

;; =============================================================================
;; transient-failure? — pure classification
;; =============================================================================

(deftest transient-failure?-detects-IOException
  (testing "java.io.IOException at any depth in the cause chain"
    (let [io   (java.io.IOException. "connect timed out")
          wrap (java.util.concurrent.ExecutionException. "wrap" io)]
      (is (true? (resilience/transient-failure? io)))
      (is (true? (resilience/transient-failure? wrap))))))

(deftest transient-failure?-detects-cause-io-ex-data
  (testing "ex-info with {:cause :io} ex-data — the HTTP transport tag"
    (let [t (ex-info "boom" {:cause :io})]
      (is (true? (resilience/transient-failure? t))))))

(deftest transient-failure?-detects-message-markers
  (testing "any of the cross-store transient markers in the message"
    (doseq [marker ["selector manager closed" "UNAVAILABLE"
                    "Connection reset" "Keepalive failed"]]
      (is (true? (resilience/transient-failure? (Exception. marker)))
          (str "marker not detected: " marker)))))

(deftest transient-failure?-rejects-fatal
  (testing "non-transient errors stay fatal"
    (is (false? (resilience/transient-failure?
                 (IllegalArgumentException. "schema mismatch"))))
    (is (false? (resilience/transient-failure?
                 (ex-info "auth failed" {:cause :auth}))))))

;; =============================================================================
;; kick-and-wait! — protocol dispatch
;; =============================================================================

(deftest kick-and-wait!-no-store-returns-false
  (testing "no registered store → no-op + false (graceful)"
    (is (false? (resilience/kick-and-wait! 100)))))

(deftest kick-and-wait!-non-liveness-store-returns-false
  (testing "store without IMemoryStoreLiveness → no-op + false"
    (put-store! :default (minimal-imemstore))
    (is (false? (resilience/kick-and-wait! 100)))))

(deftest kick-and-wait!-dispatches-via-protocol
  (testing "liveness-extending store → kick + await sequence"
    (let [calls (atom [])]
      (put-store! :default (live-stub calls :await-result true))
      (is (true? (resilience/kick-and-wait! 500)))
      (is (= [:kick-reconnect! [:await-reconnect! 500]] @calls)
          "kick-and-wait! must call -kick-reconnect! then -await-reconnect!"))))

(deftest kick-and-wait!-propagates-await-result
  (testing "await=false → kick-and-wait! reports false"
    (let [calls (atom [])]
      (put-store! :default (live-stub calls :await-result false))
      (is (false? (resilience/kick-and-wait! 100))))))

;; =============================================================================
;; call-with-resilience — orchestration contract
;; =============================================================================

(deftest call-with-resilience-passes-success-through
  (testing "happy path: f returns ok, no kick, single call"
    (let [calls (atom [])]
      (put-store! :default (live-stub calls))
      (is (= 42 (resilience/call-with-resilience (constantly 42))))
      (is (empty? @calls)
          "no liveness method should be touched on a successful call"))))

(deftest call-with-resilience-rethrows-fatal
  (testing "non-transient error: re-throws unchanged, no kick"
    (let [calls (atom [])]
      (put-store! :default (live-stub calls))
      (is (thrown? IllegalArgumentException
                   (resilience/call-with-resilience
                    (fn [] (throw (IllegalArgumentException. "fatal"))))))
      (is (empty? @calls)
          "fatal exceptions must NOT trigger the heal loop"))))

(deftest call-with-resilience-retries-once-on-transient
  (testing "transient → kick + await + retry once; second attempt's value wins"
    (let [calls    (atom [])
          attempts (atom 0)
          f        (fn []
                     (swap! attempts inc)
                     (if (= 1 @attempts)
                       (throw (java.io.IOException. "selector manager closed"))
                       :recovered))]
      (put-store! :default (live-stub calls :await-result true))
      (is (= :recovered (resilience/call-with-resilience f 200)))
      (is (= 2 @attempts) "f called exactly twice (initial + retry)")
      (is (= [:kick-reconnect! [:await-reconnect! 200]] @calls)
          "kick + await dispatched on the transient path"))))

(deftest call-with-resilience-rethrows-when-retry-also-fails
  (testing "transient that recurs after heal → second throw propagates"
    (let [calls (atom [])]
      (put-store! :default (live-stub calls :await-result false))
      (is (thrown-with-msg? java.io.IOException #"still dead"
            (resilience/call-with-resilience
             (fn [] (throw (java.io.IOException. "still dead")))
             100))))))
