(ns hive-mcp.knowledge-graph.protocol
  "DEPRECATED: Backward-compatible facade for Knowledge Graph storage protocol.

   The canonical protocol definition has moved to hive-mcp.protocols.kg.
   This namespace re-exports all public vars for backward compatibility.

   New code should require hive-mcp.protocols.kg directly:
     (:require [hive-mcp.protocols.kg :as kg])

   Existing code using this namespace will continue to work:
     (:require [hive-mcp.knowledge-graph.protocol :as proto])
     (proto/set-store! store)  ;; delegates to protocols.kg/set-store!
     (proto/ensure-conn! store) ;; delegates to protocols.kg/ensure-conn!

   KG domain logic and storage implementation."
  (:require [hive-mcp.protocols.kg :as kg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Protocol Re-exports (backward compatibility)
;; =============================================================================

;; The canonical protocol names are IKGStore and ITemporalKGStore
;; in hive-mcp.protocols.kg. These aliases preserve the old names.

;; Protocol method vars - these dispatch via IKGStore/ITemporalKGStore
(def ensure-conn! kg/ensure-conn!)
(def transact! kg/transact!)
(def query kg/query)
(def entity kg/entity)
(def entid kg/entid)
(def pull-entity kg/pull-entity)
(def eids-by-attr kg/eids-by-attr)
(def db-snapshot kg/db-snapshot)
(def reset-conn! kg/reset-conn!)
(def close! kg/close!)

;; IPersistentKGStore (optional — backends with on-disk state)
(def delete-database! kg/delete-database!)
(def persistent-store? kg/persistent-store?)

;; Temporal protocol methods
(def history-db kg/history-db)
(def as-of-db kg/as-of-db)
(def since-db kg/since-db)

;; =============================================================================
;; Active Store Management (delegates to protocols.kg)
;; =============================================================================

(def set-store! kg/set-store!)
(def get-store kg/get-store)
(def store-set? kg/store-set?)
(def clear-store! kg/clear-store!)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(def temporal-store? kg/temporal-store?)
(def active-temporal? kg/active-temporal?)
(def kg-store? kg/kg-store?)
