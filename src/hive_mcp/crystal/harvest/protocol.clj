(ns hive-mcp.crystal.harvest.protocol
  "IHarvestSource protocol + HarvestOutcome ADT.

   Defines the contract for all harvest sources in the crystal pipeline.
   Each source implements IHarvestSource and returns a HarvestOutcome.
   Exhaustive matching via adt-case ensures all outcomes are handled.

   Part of CPPB Collect layer (Wave 1, T1)."
  (:require [hive-dsl.adt :refer [defadt]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; HarvestOutcome ADT — closed sum type for harvest results
;; =============================================================================

(defadt HarvestOutcome
  "Result of harvesting a single source. Three variants:
   - :harvest/ok      — successful harvest with data + timing
   - :harvest/empty   — source returned no data (not an error)
   - :harvest/error   — source failed (IO error, timeout, etc.)"
  [:harvest/ok    {:source keyword? :data map? :elapsed-ms number?}]
  [:harvest/empty {:source keyword? :reason string?}]
  [:harvest/error {:source keyword? :error map?}])

;; =============================================================================
;; IHarvestSource Protocol
;; =============================================================================

(defprotocol IHarvestSource
  "Contract for a crystal harvest source.
   Implementations must be total — harvest never throws.
   Returns HarvestOutcome ADT for exhaustive matching."
  (source-id [this]
    "Keyword identifier for this source (e.g. :memory, :git).")
  (harvest [this opts]
    "Harvest data from this source. Returns HarvestOutcome ADT.
     opts: {:directory string?, :agent-id string?, :project-id string?}")
  (available? [this]
    "True if this source can be harvested (deps loaded, services up)."))

;; =============================================================================
;; Constructors (convenience)
;; =============================================================================

(defn harvest-ok
  "Construct a :harvest/ok outcome."
  [source data elapsed-ms]
  (harvest-outcome :harvest/ok {:source source :data data :elapsed-ms elapsed-ms}))

(defn harvest-empty
  "Construct a :harvest/empty outcome."
  [source reason]
  (harvest-outcome :harvest/empty {:source source :reason reason}))

(defn harvest-error
  "Construct a :harvest/error outcome."
  [source error-map]
  (harvest-outcome :harvest/error {:source source :error error-map}))
