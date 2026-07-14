(ns hive-mcp.swarm.ledger.default
  "Process-wide default swarm ledger, opened lazily on first append.

   Write-through call sites resolve the store here. Opening is lazy (never at
   boot); a failure to open or append is swallowed and logged — the ledger is
   observability-grade, not transactionally coupled to the hot registry, so a
   ledger fault never breaks coordination."
  (:require [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private store-atom (atom nil))
(defonce ^:private opened? (atom false))

(defn set-store!
  "Inject an explicit ILedgerStore (tests / custom wiring)."
  [s]
  (reset! store-atom s)
  (reset! opened? true))

(defn- ensure-store
  "Return the default store, opening it once on first call. Caches nil on
   open failure. Late-resolves the datalevin ledger impl; nil when absent."
  []
  (or @store-atom
      (when-not @opened?
        (reset! opened? true)
        (if-let [make (try (requiring-resolve 'hive-mcp.swarm.ledger/make-store)
                           (catch Throwable _ nil))]
          (let [s (make {:stream "swarm"})]
            (if (:error s)
              (do (log/warn "Swarm ledger unavailable, appends are no-ops:" s) nil)
              (reset! store-atom s)))
          (do (log/warn "Swarm ledger backend (datalevin) not on classpath — appends are no-ops")
              nil)))))

(defn append!
  "Guarded write-through append. Returns the append result, or nil if the
   ledger is unavailable. Never throws."
  [event]
  (try
    (when-let [s (ensure-store)]
      (when-let [ap (requiring-resolve 'hive-mcp.swarm.ledger/append!)]
        (ap s event)))
    (catch Throwable t
      (log/warn "Swarm ledger append failed:" (.getMessage t))
      nil)))

(defn store
  "The default ILedgerStore (opening lazily), or nil."
  []
  (ensure-store))

(defn reset-store!
  "Close and clear the default store, re-arming lazy open. For tests."
  []
  (when-let [s @store-atom]
    (try (when-let [cl (requiring-resolve 'hive-mcp.swarm.ledger/close!)]
           (cl s))
         (catch Throwable _ nil)))
  (reset! store-atom nil)
  (reset! opened? false))
