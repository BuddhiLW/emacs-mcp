(ns hive-mcp.test.stub.batch-extensions
  "Batch extension seams (:bx/*) for driver-free tests.

   hive-mcp.batch delegates cycle detection and wave assignment to
   `delegate-or-noop` extension keys. hive-mcp core ships no implementation —
   on a cold run every op lands in wave 1 and cycles pass validation. The
   registry is the seam; this ns installs implementations in it rather than
   letting a test assert the absence of the collaborator.

   The implementations are hive.events.multi's, a real hive-mcp dependency
   already on the :test-unit classpath — not a hand-rolled fake, so a test
   using them is pinned to an independent implementation of the contract.

   API:
     (install!)            register :bx/h and :bx/i, returns the key vector
     with-batch-extensions clojure.test :each fixture (snapshot + restore)

   Contracts mirrored from hive-mcp.batch:
     :bx/h detect-cycles => vector of error strings, empty when acyclic
     :bx/i assign-waves  => ops, each carrying an integer :wave"
  (:require [clojure.string :as str]
            [hive-mcp.extensions.registry :as ext]
            [hive.events.multi :as hem]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private ext-keys [:bx/h :bx/i])

(defn detect-cycles
  "Cycle errors for OPS, empty when acyclic. Non-cycle findings of
   hem/validate-ops are dropped — hive-mcp.batch has already made them."
  [ops]
  (let [{:keys [valid errors]} (hem/validate-ops ops)]
    (if valid
      []
      (into [] (filter #(str/includes? (str %) "Circular dependency")) errors))))

(defn install!
  "Register the batch extensions. Returns the registered key vector."
  []
  (ext/register! :bx/h detect-cycles)
  (ext/register! :bx/i hem/assign-waves)
  ext-keys)

(defn with-batch-extensions
  "clojure.test fixture: install the batch extensions for the test, then
   restore whatever was registered under those keys before."
  [f]
  (let [prior (into {} (map (juxt identity ext/get-extension)) ext-keys)]
    (try
      (install!)
      (f)
      (finally
        (doseq [[k v] prior]
          (if v
            (ext/register! k v)
            (ext/deregister! k)))))))
