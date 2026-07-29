(ns hive-mcp.test.stub.swarm-rows
  "Row builders that match what production actually writes.

   `lings/add-slave!` does NOT stamp `:slave/alive?` or `:slave/last-active-at`,
   but `queries/get-all-slaves` filters through `alive-and-fresh?`, which treats
   a nil `:slave/last-active-at` as dead. A slave created with `add-slave!`
   alone is therefore invisible to every non-`:include-stale?` query the moment
   it is created — so a fixture built that way asserts against an empty result
   set and looks like a product bug.

   Use `add-live-slave!` wherever a test needs the row to be VISIBLE."
  (:require [hive-mcp.swarm.datascript.lings :as lings]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn add-live-slave!
  "Add a slave and stamp the liveness attributes production stamps.

   Same signature as `lings/add-slave!`. Returns the update tx report."
  ([slave-id] (add-live-slave! slave-id {}))
  ([slave-id opts]
   (lings/add-slave! slave-id opts)
   (lings/update-slave! slave-id
                        {:slave/alive? true
                         :slave/last-active-at (System/currentTimeMillis)})))
