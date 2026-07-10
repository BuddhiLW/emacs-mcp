(ns hive-mcp.knowledge-graph.edges
  "CRUD operations for Knowledge Graph edges — thin re-export façade.

   The implementation is decomposed into cohesive slices under
   `hive-mcp.knowledge-graph.edges.*`:
     ids          — edge id generation
     queries      — read surface (find / get / count edges)
     write        — mutation surface (add / update / verify / remove)
     promotion    — co-access recording + co-access -> :depends-on promotion
     decay        — confidence decay + edge removal primitives
     batch        — N+1-eliminating batch reads
     migration    — edge scope migration
     nodes        — graph-algos node facade (GAV2)
     stats        — kg.edges/* metric cache
     stats-events — hive-events handlers that drive the cache

   This namespace re-exports every historical public var under its original
   name so external callers (and `requiring-resolve` shims) need zero changes."
  (:require [hive-mcp.knowledge-graph.protocols :as p]
            [hive-mcp.knowledge-graph.edges.ids :as ids]
            [hive-mcp.knowledge-graph.edges.queries :as queries]
            [hive-mcp.knowledge-graph.edges.write :as write]
            [hive-mcp.knowledge-graph.edges.promotion :as promotion]
            [hive-mcp.knowledge-graph.edges.decay :as decay]
            [hive-mcp.knowledge-graph.edges.batch :as batch]
            [hive-mcp.knowledge-graph.edges.migration :as migration]
            [hive-mcp.knowledge-graph.edges.nodes :as nodes]
            [hive-mcp.knowledge-graph.edges.stats :as stats]
            [hive-mcp.knowledge-graph.edges.stats-events]))

;; SPDX-License-Identifier: AGPL-3.0-or-later
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; ids
;; =============================================================================

(def generate-edge-id ids/generate-edge-id)

;; =============================================================================
;; Read surface (queries)
;; =============================================================================

(def get-edge queries/get-edge)
(def get-edges-from queries/get-edges-from)
(def get-edges-to queries/get-edges-to)
(def get-edges-by-relation queries/get-edges-by-relation)
(def get-edges-by-scope queries/get-edges-by-scope)
(def find-edge queries/find-edge)
(def find-edges-between queries/find-edges-between)
(def pull-edge-batch queries/pull-edge-batch)
(def get-all-edges queries/get-all-edges)
(def count-edges queries/count-edges)
(def get-edges-since queries/get-edges-since)

;; =============================================================================
;; Batch reads (N+1 elimination)
;; =============================================================================

(def batch-get-edges-from batch/batch-get-edges-from)
(def batch-get-edges-to batch/batch-get-edges-to)
(def batch-get-edges-from-with-db batch/batch-get-edges-from-with-db)
(def batch-get-edges-to-with-db batch/batch-get-edges-to-with-db)
(def batch-get-co-accessed batch/batch-get-co-accessed)

;; =============================================================================
;; Mutation surface (write)
;; =============================================================================

(def add-edge! write/add-edge!)
(def update-edge-confidence! write/update-edge-confidence!)
(def verify-edge! write/verify-edge!)
(def increment-confidence! write/increment-confidence!)
(def remove-edges-for-node! write/remove-edges-for-node!)

;; =============================================================================
;; Co-Access recording + promotion
;; =============================================================================

(def record-co-access! promotion/record-co-access!)
(def get-co-accessed promotion/get-co-accessed)
(def default-promotion-threshold promotion/default-promotion-threshold)
(def default-promoted-confidence promotion/default-promoted-confidence)
(def default-promotion-limit promotion/default-promotion-limit)
(def promote-co-access-edges! promotion/promote-co-access-edges!)

;; =============================================================================
;; Edge confidence decay + removal
;; =============================================================================

(def co-access-decay-rate decay/co-access-decay-rate)
(def semantic-decay-rate decay/semantic-decay-rate)
(def prune-threshold decay/prune-threshold)
(def edge-stale?
  ;; Re-export of the decay slice's public predicate; also reached via @#' from
  ;; edges_decay_test / edges_property_test.
  decay/edge-stale?)
(def decay-rate-for-edge decay/decay-rate-for-edge)
(def decay-step! decay/decay-step!)
(def remove-edge! decay/remove-edge!)
(def default-decay-staleness-days decay/default-decay-staleness-days)
(def default-decay-limit decay/default-decay-limit)
(def last-verified-millis decay/last-verified-millis)
(def decay-unverified-edges! decay/decay-unverified-edges!)

;; =============================================================================
;; Edge scope migration
;; =============================================================================

(def migrate-edge-scopes! migration/migrate-edge-scopes!)

;; =============================================================================
;; Edge stats — kg.edges/* metric cache facade
;; =============================================================================

(def refresh-stats! stats/refresh!)
(def reset-stats-cache! stats/reset-cache!)
(def edge-stats stats/snapshot)

;; =============================================================================
;; Graph-Algos Node Facade (GAV2)
;; =============================================================================
;;
;; Resolved by hive-knowledge.graph-algos.adapters.default/DatahikeKgReader
;; via requiring-resolve — the fn names below are load-bearing across repos:
;;   get-all-node-ids | node-ids-by-tag | neighbors

(def get-all-node-ids nodes/get-all-node-ids)
(def node-ids-by-tag-limit nodes/node-ids-by-tag-limit)
(def node-ids-by-tag nodes/node-ids-by-tag)
(def neighbors nodes/neighbors)

;; =============================================================================
;; IEdgeReader default implementation (ISP read-side)
;; =============================================================================
;;
;; Purely additive: delegates every protocol arity to the plain fns re-exported
;; above. Callers that want the contractual read-side interface can depend on
;; `p/IEdgeReader` and inject this `default-reader` (or a stub in tests).

(def default-reader
  "Process-wide IEdgeReader delegating to the plain fns in this ns."
  (reify p/IEdgeReader
    (get-edges-from [_ id] (get-edges-from id))
    (get-edges-from [_ id scope] (get-edges-from id scope))
    (get-edges-to [_ id] (get-edges-to id))
    (get-edges-to [_ id scope] (get-edges-to id scope))
    (batch-get-edges-from [_ ids] (batch-get-edges-from ids))
    (batch-get-edges-from [_ ids scope] (batch-get-edges-from ids scope))
    (batch-get-edges-to [_ ids] (batch-get-edges-to ids))
    (batch-get-edges-to [_ ids scope] (batch-get-edges-to ids scope))))
