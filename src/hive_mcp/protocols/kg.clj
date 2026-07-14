(ns hive-mcp.protocols.kg
  "Re-exports the KG SPI (hive-spi.kg.protocol) and owns active-store slot
   management. Backends implement hive-spi.kg.protocol/IKGStore directly."
  (:require [hive-spi.kg.protocol :as spi]
            [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; --- Re-export the SPI protocols + methods (interned so ns-qualified
;;     hive-mcp.protocols.kg/* access resolves; :refer would not). ---
(def IKGStore           spi/IKGStore)
(def IPersistentKGStore spi/IPersistentKGStore)
(def ITemporalKGStore   spi/ITemporalKGStore)

(def ensure-conn!   spi/ensure-conn!)
(def transact!      spi/transact!)
(def query          spi/query)
(def entity         spi/entity)
(def entid          spi/entid)
(def pull-entity    spi/pull-entity)
(def eids-by-attr   spi/eids-by-attr)
(def db-snapshot    spi/db-snapshot)
(def reset-conn!    spi/reset-conn!)
(def close!         spi/close!)
(def delete-database! spi/delete-database!)
(def history-db     spi/history-db)
(def as-of-db       spi/as-of-db)
(def since-db       spi/since-db)

(def kg-store?         spi/kg-store?)
(def persistent-store? spi/persistent-store?)
(def temporal-store?   spi/temporal-store?)

(def ->NoopKGStore    spi/->NoopKGStore)
(def map->NoopKGStore spi/map->NoopKGStore)
(def noop-store       spi/noop-store)

;; --- Active-store slot management (host runtime state, not a contract). ---
(defonce ^:private slot
  (reg/single-slot {:validate #(satisfies? spi/IKGStore %)
                    :on-empty #(throw (ex-info "No graph store configured. Call set-store! first."
                                               {:hint "Initialize with datascript-store, datalevin-store, or datahike-store"}))
                    :teardown spi/close!}))

(defn set-store!
  "Set the active graph store implementation."
  [store]
  (reg/install! slot store))

(defn get-store
  "Get the active graph store, or throw if none set."
  []
  (reg/current slot))

(defn store-set?
  "Check if a store has been configured."
  []
  (reg/present? slot))

(defn clear-store!
  "Clear the active store."
  []
  (reg/clear! slot))

(defn active-temporal?
  "True when the active store supports temporal queries."
  []
  (and (store-set?)
       (spi/temporal-store? (get-store))))
