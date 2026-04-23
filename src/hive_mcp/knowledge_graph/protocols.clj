(ns hive-mcp.knowledge-graph.protocols
  "Small interfaces (ISP) for the KG edge layer.

   Motivation: edges.clj currently mixes read and write ops in one namespace
   with no contract. A scope-filter bug (carto_callers returning 0 despite
   11k call-edges existing) went undetected because no contract enforced
   scope-filter consistency. ISP-sized protocols make the read-side
   contractual and trivially stubbable for tests.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IEdgeReader
  "Read-side of the edge store. Scope is optional — when supplied, edges
   must have :kg-edge/scope equal to it. Implementations MUST treat scope
   as a hard filter, not a hint."
  (get-edges-from [this id] [this id scope])
  (get-edges-to [this id] [this id scope])
  (batch-get-edges-from [this ids] [this ids scope])
  (batch-get-edges-to [this ids] [this ids scope]))
