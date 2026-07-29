(ns hive-mcp.test.stub.extensions
  "Stub registrations for the extension seam (`hive-mcp.extensions.registry`).

   Core delegates optional behaviour to functions registered under opaque
   keywords; when nothing is registered `delegate-or-noop` returns the default.
   A test that wants the delegated branch registers a stub here instead of
   depending on whichever addon happens to be on the classpath.

   API:
     (with-extensions {:cr/i (fn [refs ids scope] ...)} f)   register, run, restore
     (extensions-fixture {...})                              clojure.test :each fixture"
  (:require [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn with-extensions
  "Register EXTS ({key fn}) for the duration of F, then restore the prior
   registration for each key. Returns F's value."
  [exts f]
  (let [prior (into {} (map (fn [[k _]] [k (ext/get-extension k)])) exts)]
    (try
      (ext/register-many! exts)
      (f)
      (finally
        (doseq [[k v] prior]
          (if v (ext/register! k v) (ext/deregister! k)))))))

(defn without-extensions
  "Deregister KS for the duration of F, then restore them.

   Absence must be ARRANGED, never assumed: a cold JVM has an empty registry
   while a live image has the addons loaded, so a test that merely omits a
   registration passes cold and fails hot."
  [ks f]
  (let [prior (into {} (map (fn [k] [k (ext/get-extension k)])) ks)]
    (try
      (doseq [k ks] (ext/deregister! k))
      (f)
      (finally
        (doseq [[k v] prior]
          (when v (ext/register! k v)))))))

(defn extensions-fixture
  "clojure.test :each fixture form of `with-extensions`."
  [exts]
  (fn [f] (with-extensions exts f)))
