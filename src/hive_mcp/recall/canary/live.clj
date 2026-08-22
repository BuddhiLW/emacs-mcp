(ns hive-mcp.recall.canary.live
  "The runtime arm of the golden recall canary: fixtures, probes, one `run!`.

   Boundary. Every observation is read through the SAME seam an agent uses —
   `tools.memory.search/handle-search-semantic` for retrieval, the embedding
   service for widths, the carto handlers for the code index — so a canary pass
   means the caller-visible path works, not that some inner fn does.

   Contract:
   - `run!` never throws. It returns `hive-mcp.recall.canary/verdict`.
   - A probe whose provider is absent is SKIPPED with a reason and is reported;
     it never counts as a pass.
   - A fault is logged at ERROR and dispatched as :recall/canary-failed when a
     handler is registered (same opt-in shape as the grounding pass).
   - Fixtures are created once, discovered by tag thereafter, and marked
     permanent — the canary owns its anchor so no deletion can disarm it."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [hive-mcp.dns.result :as result]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.recall.canary :as canary]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private fixture-project "hive")

;; =============================================================================
;; Seams
;; =============================================================================

(defn- store
  "The registered :default IMemoryStore, or nil."
  []
  (result/rescue nil (when (mem-proto/store-set?) (mem-proto/get-store))))

(defn- resolve-fn
  "`requiring-resolve` that answers nil for an ABSENT namespace.

   Bare requiring-resolve throws FileNotFoundException when the namespace does
   not exist at all, which is the normal state for an addon nobody loaded. A
   throw there would be caught by `run!`'s outer rescue and reported as a
   crashed canary — turning 'hive-carto is not installed' into 'retrieval is
   broken'. A missing provider is a SKIP; only a present one can fault."
  [sym]
  (result/rescue nil (requiring-resolve sym)))

(defn- populated?
  "True when the store demonstrably holds entries. The premise every recall
   assertion rests on — asserted, never assumed."
  [st]
  (result/rescue false (boolean (seq (mem-proto/query-entries st {:type "note" :limit 1})))))

(defn- search
  "Run `query` through the agent-visible semantic search seam.
   Returns {:results [...]} or {:error msg}."
  [query limit]
  (if-let [f (resolve-fn 'hive-mcp.tools.memory.search/handle-search-semantic)]
    (result/rescue
     {:error "search threw"}
     (let [resp (f {:query query :limit limit :scope "global"})]
       (if (:isError resp)
         {:error (str (:text resp))}
         (json/read-str (:text resp) :key-fn keyword))))
    {:error "hive-mcp.tools.memory.search/handle-search-semantic not available"}))

(defn- carto-call
  "Invoke a carto handler by symbol with `args`; nil when carto is not loaded."
  [sym args]
  (when-let [f (resolve-fn sym)]
    (result/rescue nil
                   (let [resp (f args)]
                     (when-not (:isError resp)
                       (json/read-str (:text resp) :key-fn keyword))))))

(defn- emit-event!
  "Dispatch `event` when a handler is registered. A missing event bus must never
   break the pass, hence the rescue."
  [event payload]
  (result/rescue
   nil
   (let [registered? (resolve-fn 'hive-mcp.events.core/handler-registered?)
         dispatch!   (resolve-fn 'hive-mcp.events.core/dispatch)]
     (when (and registered? dispatch! (registered? event))
       (dispatch! [event payload])))))

;; =============================================================================
;; Fixtures — created once, found by tag forever after
;; =============================================================================

(defn- find-fixture
  [st role]
  (result/rescue nil
                 (first (mem-proto/query-entries st {:tags (canary/fixture-tags role)
                                                     :limit 1}))))

(defn- create-fixture!
  [st role]
  (let [{:keys [type content tags]} (canary/fixture role)]
    (result/rescue nil
                   (mem-proto/add-entry! st {:type type
                                             :content content
                                             :tags tags
                                             :duration "permanent"
                                             :abstraction-level 2
                                             :project-id fixture-project}))))

(defn- link-supersession!
  "Write the :supersedes edge current -> superseded. Returns true on success."
  [current-id superseded-id]
  (boolean
   (when-let [f (resolve-fn 'hive-mcp.knowledge-graph.edges.write/add-edge!)]
     (result/rescue false
                    (do (f {:from current-id :to superseded-id :relation :supersedes
                            :created-by "recall-canary" :source-type :manual
                            :scope fixture-project})
                        true)))))

(defn ensure-fixtures!
  "Ids of the canary's own entries, creating any that are missing.

   Returns {:anchor id :superseded id :current id :created [roles] :linked? bool}.
   Ids may be nil when the store refused the write — probes read that as a skip,
   never as a pass."
  [st]
  (let [existing (into {} (for [{:keys [role]} canary/fixtures]
                            [role (:id (find-fixture st role))]))
        created  (into {} (for [[role id] existing
                                :when (nil? id)]
                            [role (create-fixture! st role)]))
        ids      (merge existing created)
        linked?  (if (and (contains? created :current) (:current ids) (:superseded ids))
                   (link-supersession! (:current ids) (:superseded ids))
                   true)]
    (assoc ids :created (vec (keys created)) :linked? linked?)))

;; =============================================================================
;; Probes
;; =============================================================================

(defn probe-dimension
  "CASE 5 — the width the embedder emits must equal the width the index holds."
  []
  (let [dim-of (resolve-fn 'hive-mcp.embeddings.service/dimension-for-collection)
        listed (resolve-fn 'hive-mcp.embeddings.service/list-configured-collections)
        actual (resolve-fn 'hive-mcp.embeddings.service/get-dimension-for)]
    (if (and dim-of listed actual)
      (let [readings (result/rescue
                      []
                      (vec (for [c (keys (listed))]
                             {:collection c
                              :expected   (dim-of c)
                              :actual     (result/rescue nil (actual c))})))]
        (canary/outcome :dimension-invariant
                        (canary/dimension-fault {:label :dimension-invariant
                                                 :readings readings})))
      (canary/outcome :dimension-invariant nil "embedding service not available"))))

(defn probe-anchor
  "CASE 1 — the anchor's rare tokens must retrieve the anchor, nearest first."
  [st ids]
  (cond
    (nil? (:anchor ids))
    (canary/outcome :lexical-anchor nil "anchor fixture absent and could not be written")

    :else
    (let [{:keys [results error]} (search canary/anchor-query 10)]
      (if error
        (canary/outcome :lexical-anchor {:fault :recall/search-errored
                                         :label :lexical-anchor
                                         :diagnosis error})
        (canary/outcome :lexical-anchor
                        (or (canary/recall-fault {:label        :lexical-anchor
                                                  :populated?   (populated? st)
                                                  :results      results
                                                  :must-contain [(:anchor ids)]})
                            (canary/rank-fault {:label   :lexical-anchor-ordering
                                                :results results})))))))

(defn probe-supersession
  "CASE 2 — a retracted entry must not come back from any retrieval path."
  [ids]
  (cond
    (not (and (:superseded ids) (:current ids)))
    (canary/outcome :supersession nil "supersession fixtures absent")

    (not (:linked? ids))
    (canary/outcome :supersession nil "supersedes edge could not be written")

    :else
    (let [{:keys [results error]} (search canary/supersession-query 10)]
      (if error
        (canary/outcome :supersession {:fault :recall/search-errored
                                       :label :supersession
                                       :diagnosis error})
        (canary/outcome :supersession
                        (canary/supersession-fault {:label         :supersession
                                                    :results       results
                                                    :superseded-id (:superseded ids)
                                                    :current-id    (:current ids)}))))))

(defn probe-carto-tag
  "CASE 3 — the code index answers its own match-all tag query."
  [scope]
  (if-let [body (carto-call 'hive-carto.cartography.handlers.kg-queries/handle-carto-search
                            {:name-pattern "*" :limit 5 :scope scope})]
    (canary/outcome :carto-tag
                    (canary/presence-fault
                     {:label :carto-tag
                      :probe (str "carto_search name-pattern=* scope=" scope)
                      :count (or (:count body) (count (:results body)))
                      :diagnosis (str "the carto index returned zero forms for a "
                                      "match-all pattern on a scanned scope — the "
                                      "index is down or empty, and every carto "
                                      "answer above it is vacuous.")}))
    (canary/outcome :carto-tag nil "hive-carto not loaded")))

(defn probe-carto-semantic
  "CASE 4 — the carto vector lane answers a natural-language query."
  [scope]
  (if-let [body (carto-call 'hive-carto.cartography.handlers.semantic-grep/handle-semantic-grep
                            {:query "run a decay cycle over memory and edges"
                             :limit 5 :scope scope})]
    (canary/outcome :carto-semantic
                    (canary/presence-fault
                     {:label :carto-semantic
                      :probe (str "semantic-grep scope=" scope)
                      :count (or (:count body) (count (:results body)))
                      :diagnosis (str "the carto semantic lane returned nothing for "
                                      "a query drawn from the corpus's own "
                                      "docstrings — the vector side is dead.")}))
    (canary/outcome :carto-semantic nil "hive-carto not loaded")))

;; =============================================================================
;; The pass
;; =============================================================================

(defn run!
  "Run every canary probe once and return the verdict.

   Opts: :scope (carto scope, default \"hive-mcp\"), :carto? (default true).
   Never throws. Logs ERROR and dispatches :recall/canary-failed on any fault."
  ([] (run! {}))
  ([{:keys [scope carto?] :or {scope "hive-mcp" carto? true}}]
   (result/rescue
    {:ok? false :ran 0 :passed 0
     :faults [{:fault :recall/canary-crashed
               :diagnosis "the canary itself threw — treat as a fault, not a pass"}]
     :skipped []}
    (let [st  (store)
          ids (when st (ensure-fixtures! st))
          outs (cond-> [(probe-dimension)]
                 st        (conj (probe-anchor st ids) (probe-supersession ids))
                 (nil? st) (conj (canary/outcome :lexical-anchor nil "no :default IMemoryStore registered")
                                 (canary/outcome :supersession nil "no :default IMemoryStore registered"))
                 carto?    (conj (probe-carto-tag scope) (probe-carto-semantic scope)))
          v (canary/verdict outs)
          v (cond-> v (seq (:created ids)) (assoc :fixtures-created (:created ids)))]
      (if (:ok? v)
        (log/info "Recall canary OK:" (select-keys v [:ran :passed :skipped]))
        (do (log/error "RECALL CANARY FAULT — retrieval is not trustworthy:" (:faults v))
            (emit-event! :recall/canary-failed v)))
      (when (seq (:skipped v))
        (log/warn "Recall canary skipped probes:" (:skipped v)))
      v))))
