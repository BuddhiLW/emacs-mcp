(ns hive-mcp.test.stub.forge-belt
  "Install the FOSS forge-belt extension defaults for tests.

   `hive-mcp.workflows.forge-belt` resolves every belt operation from the
   extension registry and degrades to a noop result when a key is missing.
   The defaults ship in `hive-mcp.workflows.forge-belt-defaults` but are
   registered during server boot (`hive-mcp.system.layer3`), which a test JVM
   never runs — so a cold test sees the noop path for the whole belt.

   API:
     with-forge-belt-defaults   run f with the :fb/* defaults registered
     forge-belt-fixture         clojure.test :each fixture"
  (:require [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def belt-keys
  "Every :fb/* extension key `register-forge-belt-defaults!` installs."
  [:fb/q1 :fb/q2 :fb/q3 :fb/q4 :fb/q5 :fb/q6 :fb/q7 :fb/q8
   :fb/h1 :fb/h2 :fb/h3 :fb/h3.5 :fb/h4 :fb/h5 :fb/h6 :fb/h7
   :fb/s1 :fb/s2
   :fb/compile :fb/run :fb/strike :fb/cont])

(defn with-forge-belt-defaults
  "Run F with the FOSS :fb/* defaults registered, restoring the prior registry."
  [f]
  (let [register! (requiring-resolve
                   'hive-mcp.workflows.forge-belt-defaults/register-forge-belt-defaults!)
        prior     (into {} (map (fn [k] [k (ext/get-extension k)])) belt-keys)]
    (try
      (register!)
      (f)
      (finally
        (doseq [[k v] prior]
          (if v (ext/register! k v) (ext/deregister! k)))))))

(defn forge-belt-fixture
  "clojure.test :each fixture form of `with-forge-belt-defaults`."
  [f]
  (with-forge-belt-defaults f))
