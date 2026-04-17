(ns hive-mcp.tools.catchup.axiom-cache
  "Stale-while-revalidate cache for `type=axiom` memory queries.

   Axioms are GLOBAL: every entry is visible from every project, including
   siblings outside the hierarchy. So the fetch skips scope filtering and
   hits the store with a pure `type=axiom` scan — but that scan is the
   single slowest Milvus branch (cold-path scalar filter). The cache hides
   that cost behind a 5-min TTL and a thundering-herd-gated background
   refresh so repeat catchups don't re-pay the 20s budget."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.dns.result :refer [rescue-interrupt rescue-log]]
            [hive-mcp.tools.catchup.hierarchy :as hier]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent Future TimeUnit TimeoutException]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private axioms-formal-budget-ms
  "Wall-clock budget for the formal `type=axiom` branch. Cold-path Chroma
   type-filter scans land at ~5-6s each, so 20s keeps us well under the 60s
   outer catchup acceptance gate while letting the branch land."
  20000)

(def ^:private axioms-cache-ttl-ms
  "Per-project TTL for query-axioms results. Axioms churn rarely; a 5-min
   cache eliminates repeated type-filter cold scans that blow through the
   20s budget on projects with few/no axioms."
  (* 5 60 1000))

(def ^:private axioms-cache
  "{project-id {:result [...] :expires-at epoch-ms :stored-at epoch-ms}}"
  (atom {}))

(def ^:private axioms-refreshing
  "Set of project-ids currently being refreshed in the background. Gates
   thundering-herd when several catchup calls race on a stale entry."
  (atom #{}))

(defn invalidate-axioms-cache!
  "Drop cached axiom results. Call after add/update/delete of axiom entries."
  ([] (reset! axioms-cache {}))
  ([project-id] (swap! axioms-cache dissoc project-id)))

(defn- deref-with-deadline
  "Block on `fut` up to `deadline-ms` wall-clock. On timeout, cancel(true)
   and log under `label`; on exception, log and return []. Never throws."
  [^Future fut deadline-ms label budget-ms]
  (let [remaining (max 0 (- deadline-ms (System/currentTimeMillis)))]
    (try
      (.get fut remaining TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (.cancel fut true)
        (log/warnf "catchup/query-axioms %s branch exceeded budget (%sms) — cancelled, partial results"
                   label budget-ms)
        [])
      (catch Throwable t
        (log/warnf t "catchup/query-axioms %s branch failed — partial results" label)
        []))))

(defn- fetch-axioms-sync!
  "Synchronous fetch with budget. Stores result in cache, returns it."
  [project-id now]
  (let [store (mem-proto/get-store)
        formal-deadline (+ now axioms-formal-budget-ms)
        f-formal (pool/with-catchup
                   (rescue-interrupt "catchup/query-axioms" []
                     (->> (mem-proto/query-entries
                            store
                            {:type "axiom"
                             :limit 200
                             :output-fields hier/metadata-projection})
                          (sort-by :created #(compare %2 %1))
                          (take 100)
                          vec)))
        formal (deref-with-deadline f-formal formal-deadline "formal"
                                    axioms-formal-budget-ms)]
    (swap! axioms-cache assoc project-id
           {:result formal
            :expires-at (+ now axioms-cache-ttl-ms)
            :stored-at now})
    formal))

(defn- trigger-refresh!
  "Fire-and-forget background refresh — stale-while-revalidate.
   Gated by `axioms-refreshing`: only the caller that actually adds
   project-id to the in-flight set submits the refresh task."
  [project-id]
  (let [[old new] (swap-vals! axioms-refreshing
                              (fn [s] (if (contains? s project-id) s (conj s project-id))))]
    (when (not= old new)
      (pool/with-catchup
        (rescue-log "catchup/query-axioms:refresh" nil
          (fetch-axioms-sync! project-id (System/currentTimeMillis)))
        (swap! axioms-refreshing disj project-id)))))

(defn query-axioms
  "Query axiom entries via the formal `type=axiom` branch.

   Stale-while-revalidate cache: fresh hit returns immediately; stale hit
   returns immediately and triggers a background refresh so the next call
   sees fresh data without blocking. Cold first call pays synchronous
   `axioms-formal-budget-ms` cost. Use `invalidate-axioms-cache!` after
   mutating axioms."
  [project-id]
  (let [now   (System/currentTimeMillis)
        hit   (get @axioms-cache project-id)
        fresh (and hit (< now (:expires-at hit)))]
    (cond
      fresh         (:result hit)
      hit           (do (trigger-refresh! project-id) (:result hit))
      :else         (fetch-axioms-sync! project-id now))))
