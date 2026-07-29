(ns hive-mcp.protocols.memory-liveness
  "Cross-store resilience seam — `IMemoryStoreLiveness`, re-exported from
   hive-spi.memory.ports.

   The protocol lives in the hive-spi SPI leaf; this namespace keeps the
   historical `hive-mcp.protocols.memory-liveness/*` names resolving via
   plain `def` aliases. `liveness-store?` calls `satisfies?` on the
   CANONICAL ports var (stores extend it via extend-protocol, which mutates
   the ports var's root — an alias snapshot would miss those impls).

   Stores that don't extend it fall through to a pass-through path in
   `hive-mcp.vectordb.resilience` (catch, log, re-raise; no kick/retry)."
  (:require [hive-spi.memory.ports :as ports]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; Protocol re-export — def aliases of hive-spi.memory.ports (NOT defprotocol).

(do
  (def IMemoryStoreLiveness ports/IMemoryStoreLiveness)

  ;; Method re-exports must DELEGATE, never `def`-alias. `extend` rebinds each
  ;; protocol method var's root to a fn carrying a fresh MethodImplCache, so a
  ;; value alias freezes the cache as of THIS namespace's load and never sees an
  ;; impl registered afterwards.
  (defn -probe! [store] (ports/-probe! store))
  (defn -kick-reconnect! [store] (ports/-kick-reconnect! store))
  (defn -await-reconnect! [store budget-ms] (ports/-await-reconnect! store budget-ms)))

(defn liveness-store?
  "Check if the store extends the resilience seam."
  [store]
  (satisfies? ports/IMemoryStoreLiveness store))
