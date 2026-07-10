(ns hive-mcp.multi.registry-contract-test
  "Cross-impl contract suite for every tool registered in `multi.registry`.

   Walks `r-tools/all-names` (the live registry surface) and asserts:
   1. Every entry has a callable handler resolvable via
      `multi-registry/resolve-tool-handler`.
   2. Every entry's `command=help` response is shape-compliant — either
      a `{:type \"text\" :text ...}` MCP success or a `:isError`-flagged
      MCP error (per `tools.core/mcp-error` shape). NEVER throws.
   3. Every entry resolves to a `Batchable` via
      `multi-registry/lookup-batchable-or-default` (LSP guarantee — the
      `DefaultBatchableAdapter` substitutes for tools without an
      explicit record).
   4. Running an empty op-set through `Batchable/batch-execute` returns
      the canonical `{:success :waves :summary}` shape (Batchable
      contract clause 1). Confirms ALL tools satisfy the same shape
      regardless of which path they take, catching the kind of read-path
      drift that produced the qdrant `get-entry` decoder bug one layer
      below.

   The suite exists explicitly to catch new-tool drift: any addon that
   registers a tool but breaks one of the above auto-fails here.

   Decision: 20260429230453-7e7627cc (capabilities-as-protocols, SOLID
   for consolidated MCP tools). Plan: sketch-bigger-root-problem-optimized-spring."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-mcp.batch.protocol :as bproto]
            [hive-mcp.multi.registry :as multi-registry]
            [hive-mcp.multi.registry.tools :as r-tools]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; Force the core seed to populate the registry before any test runs.
;; `multi.core-seed` defonce fires on namespace load.
(require 'hive-mcp.multi.core-seed)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- mcp-result-shape-ok?
  "Returns true iff `result` is either:
   - a successful MCP tool response: `{:type \"text\" :text <string>}`, OR
   - an MCP error: `{:isError true ...}` or `{:type \"text\" :isError true ...}`.
   Tolerates extra keys."
  [result]
  (and (map? result)
       (or (and (= "text" (:type result)) (string? (:text result)))
           (true? (:isError result)))))

(defn- batchable-empty-result-shape-ok?
  "Validate `batch-execute` over an EMPTY op-set returns the canonical shape
   without throwing. Empty input is the smallest probe that still exercises
   the contract — any tool whose Batchable can't handle the no-op case is
   broken at LSP."
  [batchable]
  (try
    (let [result (bproto/batch-execute batchable [] {})]
      (and (map? result)
           (boolean? (:success result))
           (map? (:waves result))
           (map? (:summary result))
           (every? #(contains? (:summary result) %)
                   [:total :success :failed :waves])
           (every? int? [(get-in result [:summary :total])
                         (get-in result [:summary :success])
                         (get-in result [:summary :failed])
                         (get-in result [:summary :waves])])))
    (catch Throwable _ false)))

;; =============================================================================
;; Cross-impl tests
;; =============================================================================

(deftest registry-populated
  (testing "core-seed has registered the expected consolidated tool set"
    (let [names (r-tools/all-names)]
      (is (>= (count names) 20)
          (str "Expected ≥20 registered tools, got " (count names) ": "
               (vec names))))))

(deftest every-tool-has-resolvable-handler
  (testing "Every name from r-tools/all-names resolves to a callable handler"
    (doseq [tool-name (r-tools/all-names)]
      (let [handler (multi-registry/resolve-tool-handler tool-name)]
        (is (ifn? handler)
            (str "Tool " (pr-str tool-name)
                 " in registry but no callable handler resolved"))))))

(deftest every-tool-help-shape-compliant
  (testing "Every tool's `command=help` response satisfies the MCP envelope shape"
    (doseq [tool-name (r-tools/all-names)]
      (let [handler (multi-registry/resolve-tool-handler tool-name)
            result  (try (handler {:command "help"})
                         (catch Throwable t
                           {:threw (.getMessage t)
                            :ex-class (.getName (class t))}))]
        (is (mcp-result-shape-ok? result)
            (str "Tool " (pr-str tool-name)
                 " help response not MCP-shape-compliant: "
                 (pr-str (select-keys result
                                       [:type :text :isError :threw :ex-class]))))))))

(deftest every-tool-resolves-to-batchable
  (testing "Every tool resolves to a Batchable record (explicit OR default adapter)"
    (doseq [tool-name (r-tools/all-names)]
      (let [bx (multi-registry/lookup-batchable-or-default tool-name)]
        (is (some? bx)
            (str "Tool " (pr-str tool-name) " yielded nil from lookup-batchable-or-default"))
        (is (satisfies? bproto/Batchable bx)
            (str "Tool " (pr-str tool-name)
                 " resolved Batchable does not satisfy protocol — "
                 "class: " (when bx (.getName (class bx)))))))))

(deftest empty-batch-execute-shape-uniform
  (testing "Every tool's Batchable handles the no-op `(batch-execute bx [] {})` with the canonical shape"
    (doseq [tool-name (r-tools/all-names)]
      (let [bx (multi-registry/lookup-batchable-or-default tool-name)]
        (is (batchable-empty-result-shape-ok? bx)
            (str "Tool " (pr-str tool-name)
                 " Batchable failed canonical shape on empty op-set "
                 "(class: " (when bx (.getName (class bx))) ")"))))))
