(ns hive-mcp.channel.instruction-store-lifecycle-test
  "Characterization net for the instruction-store active-slot (single-slot with
   nil-on-empty + stop-on-teardown + stop-previous-on-replace). Locks behavior
   before the migration onto hive-mcp.protocols.registry/single-slot and after.
   Snapshot+restore fixture per the tests-must-not-touch-shared-state axiom."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.channel.instruction-store :as is]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- stub-store
  "A minimal IInstructionStore that records its id into `stopped-atom` when
   stop! is invoked, so teardown/replace semantics are observable."
  [stopped-atom id]
  (reify is/IInstructionStore
    (push! [_ _ _] nil)
    (drain! [_ _] [])
    (peek* [_ _] [])
    (clear! [_] nil)
    (start! [_] nil)
    (stop! [_] (swap! stopped-atom conj id))))

(use-fixtures :each
  (fn [f]
    (let [prior (is/get-store)]
      (try (f)
           (finally
             (if prior (is/set-store! prior) (is/clear-store!)))))))

(deftest lifecycle-and-stop-semantics-test
  (testing "nil-on-empty, set/get roundtrip, stop-previous-on-replace, stop-on-clear"
    (is/clear-store!)
    (is (nil? (is/get-store)) "unset -> nil (no fallback, no throw)")
    (let [stopped (atom [])
          a (stub-store stopped :a)
          b (stub-store stopped :b)]
      (is (identical? a (is/set-store! a)) "set-store! returns the store")
      (is (identical? a (is/get-store)))
      (is (identical? b (is/set-store! b)) "replacing returns the new store")
      (is (= [:a] @stopped) "set-store! stops the PREVIOUS store on replace")
      (is (identical? b (is/get-store)))
      (is/clear-store!)
      (is (= [:a :b] @stopped) "clear-store! stops the active store (teardown)")
      (is (nil? (is/get-store))))))

(deftest rejects-invalid-test
  (testing "set-store! rejects a non-IInstructionStore via :pre"
    (is (thrown? AssertionError (is/set-store! {:not "a store"})))))
