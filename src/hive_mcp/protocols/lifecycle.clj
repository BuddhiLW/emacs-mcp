(ns hive-mcp.protocols.lifecycle
  "IShutdownHook / ISweepable / IResourceOwner / IShutdownBudget — `def`
   aliases of hive-spi.lifecycle.ports.

   The protocols themselves live in hive-spi so addons can implement them
   without compile-depending on hive-mcp. Every historical
   hive-mcp.protocols.lifecycle/* qualified name still resolves here.
   `satisfies?` must be called on the ports vars, never on these aliases:
   an alias holds a snapshot, so an impl added later via extend-protocol
   would be invisible to it."
  (:require [hive-spi.lifecycle.ports :as ports]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(do
  (def IShutdownHook ports/IShutdownHook)
  (def shutdown-priority ports/shutdown-priority)
  (def shutdown-name ports/shutdown-name)
  (def shutdown! ports/shutdown!))

(do
  (def ISweepable ports/ISweepable)
  (def sweep-interval-s ports/sweep-interval-s)
  (def sweep-name ports/sweep-name)
  (def sweep! ports/sweep!))

(do
  (def IResourceOwner ports/IResourceOwner)
  (def owner-id ports/owner-id)
  (def owned-resources ports/owned-resources)
  (def release-all! ports/release-all!))

(do
  (def IShutdownBudget ports/IShutdownBudget)
  (def shutdown-timeout-ms ports/shutdown-timeout-ms))
