(ns hive-mcp.tools.kg.synthetics
  "Cleanup scaffolding for synthetic-pattern nodes.

   Synthetic pattern nodes (IDs prefixed `synth-`) exist only as nodes in
   the KG — they project onto raw memory entry IDs via outgoing
   `:projects-to` edges.

   When most of a synthetic's `:projects-to` targets point at
   expired/missing memory entries, the synthetic is dead scaffolding that
   pollutes KG insights (emergent-pattern review, 2026-04-23).

   **Spec:** live-ratio is computed over `:projects-to` edges *only*.
   Other outgoing relation types (e.g. `:depends-on`, `:co-accessed`) do
   not contribute to the freshness decision — a synthetic is fresh iff
   the raw memories it projects onto are still live.

   Actions:
   - `:demote` — set `:projects-to` edge confidence to 0.1 (other
     relations untouched)
   - `:delete` — remove the synthetic node via
     `edges/remove-edges-for-node!`, which cleans up ALL connected edges
     (not just `:projects-to`), leaving no orphan edges behind.

   This ns provides `cleanup-synthetics!` — a bounded per-cycle pass that:
     1. Enumerates distinct source nodes whose ID starts with `synth-`
     2. For each, counts live-vs-expired `:projects-to` targets via
        `mem-proto/get-entry`
     3. Classifies by live-ratio against a configurable :threshold
     4. Applies the selected action

   Uses the same fetch/sort/limit/tally pattern as
   `edges/decay-unverified-edges!`."
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.edges :as edges]
            [hive-mcp.knowledge-graph.edge-cycle :as edge-cycle]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Defaults
;; =============================================================================

(def ^:const default-threshold
  "Live-ratio below which a synthetic is considered dead scaffolding.
   0.2 = act when 80%+ of targets are missing/expired (matches the
   2026-04-23 emergent-pattern review observation)."
  0.2)

(def ^:const default-limit
  "Maximum synthetics to evaluate per cycle. Bounded to prevent
   unbounded scans on large graphs."
  50)

(def ^:const demote-confidence
  "Confidence score applied to `:projects-to` edges when :action is :demote.
   Non-`:projects-to` edges on a synthetic (if any) are left alone."
  0.1)

(def ^:const synth-id-prefix "synth-")

(def ^:const synth-target-relation
  "Relation type considered when computing a synthetic's live-ratio and
   when the `:demote` action adjusts confidence. Synthetic emergent
   patterns project onto raw memories via this relation; other relation
   types (if any) are deliberately excluded from freshness decisions."
  :projects-to)

;; =============================================================================
;; Enumeration
;; =============================================================================

(defn list-synthetic-source-nodes
  "Return distinct source node IDs from `:kg-edge/from` whose ID starts
   with `synth-`. Single Datalog query + client-side prefix filter.

   Returns a sorted vector of strings for deterministic per-cycle
   ordering."
  []
  (let [q '[:find [?from ...]
            :where
            [?e :kg-edge/from ?from]]
        all-from (conn/query q)]
    (->> (or all-from [])
         (filter string?)
         (filter #(.startsWith ^String % synth-id-prefix))
         distinct
         sort
         vec)))

;; =============================================================================
;; Live-ratio Classification
;; =============================================================================

(defn- memory-entry-live?
  "Check whether a memory entry id resolves to a non-nil entry via
   `mem-proto/get-entry`. Returns false if no memory store is registered
   (cleanup is conservative — treat unknown as not live)."
  [store entry-id]
  (try
    (some? (mem-proto/get-entry store entry-id))
    (catch Exception _ false)))

(defn- classify-synthetic
  "Inspect outgoing `:projects-to` edges for a synthetic node and compute
   live stats.

   Live-ratio is computed over `:projects-to` targets only — other edge
   types (e.g. `:depends-on`, `:co-accessed`) on a synthetic, if they
   exist, don't count toward 'is this pattern still supported by live
   evidence?'. The 2026-04-23 emergent-pattern review framed synthetic
   freshness specifically in terms of the projection onto raw memories,
   and this function enforces that contract.

   `:edges` in the returned map is the filtered vector (what demote acts
   on); `:edge-count` is the count of those filtered edges. Callers that
   need the full outgoing set can call `edges/get-edges-from` directly."
  [store synth-id]
  (let [all-out       (edges/get-edges-from synth-id)
        projects-edges (filterv #(= synth-target-relation (:kg-edge/relation %))
                                all-out)
        total         (count projects-edges)
        ;; Distinct targets — a synth may repeat a target via separate
        ;; edges; we only care about unique raw-memory ids for the ratio.
        targets       (distinct (map :kg-edge/to projects-edges))
        live          (count (filter #(memory-entry-live? store %) targets))
        target-ct     (count targets)
        ratio         (if (zero? target-ct) 0.0 (double (/ live target-ct)))]
    {:synth-id     synth-id
     :edges        projects-edges
     :edge-count   total
     :target-count target-ct
     :live-count   live
     :live-ratio   ratio}))

;; =============================================================================
;; Actions
;; =============================================================================

(defn- delete-synthetic!
  "Remove all edges connected to a synth- node. Returns the removed-edge
   count. Datascript/Datahike don't require a separate node-delete — the
   'node' exists only as a :kg-edge/from value, so once all edges are
   gone the node is gone."
  [synth-id]
  (edges/remove-edges-for-node! synth-id))

(defn- demote-synthetic!
  "Set outgoing-edge confidence to `demote-confidence` for the given edges.
   Returns the count of edges demoted."
  [out-edges]
  (reduce
   (fn [n edge]
     (if-let [eid (:kg-edge/id edge)]
       (do (edges/update-edge-confidence! eid demote-confidence) (inc n))
       n))
   0
   out-edges))

;; =============================================================================
;; Cycle Step
;; =============================================================================

(defn- step!
  "Per-synthetic step invoked by `edge-cycle/run-cycle!`.
   Returns :pruned, :demoted, or :preserved. When dry-run? is true
   never mutates — just classifies."
  [{:keys [threshold action dry-run? store details-atom]} synth-id]
  (let [{:keys [edge-count target-count live-count live-ratio edges]
         :as classification} (classify-synthetic store synth-id)
        below? (< live-ratio threshold)
        outcome (cond
                  (not below?)               :preserved
                  (= action :demote)         :demoted
                  :else                      :pruned)
        effect-count
        (cond
          dry-run?           0
          (= outcome :preserved) 0
          (= outcome :demoted)   (demote-synthetic! edges)
          :else                  (delete-synthetic! synth-id))]
    (swap! details-atom conj
           {:synth-id     synth-id
            :edge-count   edge-count
            :target-count target-count
            :live-count   live-count
            :live-ratio   live-ratio
            :outcome      outcome
            :effect-count effect-count
            :dry-run?     dry-run?})
    outcome))

;; =============================================================================
;; Public Entry Point
;; =============================================================================

(defn cleanup-synthetics!
  "Scan synthetic-pattern nodes and act on those whose outgoing edges
   mostly target missing/expired raw memory entries.

   Options:
     :threshold  - Live-ratio below which to act (default 0.2)
     :action     - :delete (remove node + all edges) or :demote (set
                   edge confidence to 0.1). Default :delete.
     :limit      - Max synthetics per cycle (default 50).
     :dry-run?   - When true, classify only; no mutations. Default false.

   Returns:
     {:scanned N :pruned N :demoted N :preserved N :errors N
      :dry-run? bool :action kw :threshold f :limit n
      :details [{...per-synth stats...}]}

   Bounded per cycle. Idempotent. Errors are tallied — never thrown."
  [& [{:keys [threshold action limit dry-run?]
       :or {threshold default-threshold
            action    :delete
            limit     default-limit
            dry-run?  false}}]]
  ;; Drain any pending write-coalesced edges so the scan sees the
  ;; authoritative state. No-op if writer isn't running.
  (conn/drain-writer!)
  (let [store (try (mem-proto/get-store) (catch Exception _ nil))
        details (atom [])
        action-kw (keyword action)
        tally (edge-cycle/run-cycle!
               {:fetch        list-synthetic-source-nodes
                :sort-key     identity ;; stable order on ID string
                :limit        limit
                :outcome-keys [:pruned :demoted :preserved]
                :step!        #(step! {:threshold    threshold
                                       :action       action-kw
                                       :dry-run?     (boolean dry-run?)
                                       :store        store
                                       :details-atom details}
                                      %)
                :error-log-fn (fn [synth-id err]
                                (log/debug "cleanup-synthetics step failed for"
                                           synth-id ":" (:message err)))
                :log-fn       (fn [t]
                                (when (or (pos? (:pruned t)) (pos? (:demoted t)))
                                  (log/info "cleanup-synthetics:"
                                            (:pruned t) "pruned,"
                                            (:demoted t) "demoted,"
                                            (:preserved t) "preserved"
                                            (when dry-run? " (dry-run)"))))})]
    ;; Flush post-cycle mutations so callers see committed state.
    (conn/drain-writer!)
    {:scanned    (:evaluated tally)
     :pruned     (:pruned tally)
     :demoted    (:demoted tally)
     :preserved  (:preserved tally)
     :errors     (:errors tally)
     :dry-run?   (boolean dry-run?)
     :action     action-kw
     :threshold  threshold
     :limit      limit
     :details    @details}))

;; =============================================================================
;; MCP Handler
;; =============================================================================

(defn- parse-action
  "Accept :delete/:demote as keyword or string. Defaults to :delete."
  [action]
  (cond
    (nil? action) :delete
    (keyword? action) action
    (string? action) (keyword action)
    :else :delete))

(defn- valid-action? [a] (contains? #{:delete :demote} a))

(defn handle-kg-cleanup-synthetics
  "MCP boundary handler. Accepts MCP-style params (strings/ints/booleans)
   and returns an MCP JSON response."
  [{:keys [threshold action limit dry_run]}]
  (log/info "kg_cleanup_synthetics"
            {:threshold threshold :action action :limit limit :dry-run dry_run})
  (try
    (let [action-kw (parse-action action)]
      (cond
        (not (valid-action? action-kw))
        (mcp-error (str "Invalid action '" action
                        "'. Valid: 'delete' or 'demote'."))

        (and threshold
             (or (not (number? threshold))
                 (< threshold 0.0) (> threshold 1.0)))
        (mcp-error "threshold must be a number in [0.0, 1.0]")

        (and limit (or (not (integer? limit)) (neg? limit)))
        (mcp-error "limit must be a non-negative integer")

        :else
        (let [result (cleanup-synthetics!
                      (cond-> {:action action-kw
                               :dry-run? (boolean dry_run)}
                        threshold (assoc :threshold threshold)
                        limit     (assoc :limit limit)))]
          (mcp-json (assoc result :success true)))))
    (catch Exception e
      (log/error e "kg_cleanup_synthetics failed")
      (mcp-error (str "cleanup-synthetics failed: " (.getMessage e))))))

(def tool-def
  {:name "kg_cleanup_synthetics"
   :description (str "Scan synthetic-pattern nodes (IDs prefixed 'synth-') "
                     "and delete or demote those whose outgoing edges "
                     "mostly target missing/expired memory entries. "
                     "Dead-scaffolding cleanup for KG insights.")
   :inputSchema {:type "object"
                 :properties {"threshold" {:type "number"
                                           :description "Live-ratio below which to act (default 0.2)"}
                              "action" {:type "string"
                                        :enum ["delete" "demote"]
                                        :description "Action on synthetics below threshold (default 'delete')"}
                              "limit" {:type "integer"
                                       :description "Max synthetics per cycle (default 50)"}
                              "dry_run" {:type "boolean"
                                         :description "Preview without mutating (default false)"}}
                 :required []}
   :handler handle-kg-cleanup-synthetics})
