(ns hive-mcp.tools.memory.lifecycle
  "Lifecycle handlers for memory entry duration management.

   Focused modules:
   - This ns: duration, promote, demote, cleanup, expire, expiring-soon
   - decay.clj: staleness decay (handle-decay, run-decay-cycle!)
   - promotion.clj: xpoll auto-promotion (handle-xpoll-promote, run-xpoll-cycle!)

   Re-exports handle-decay and handle-xpoll-promote for backward compatibility
   with tools/memory.clj facade."
  (:require [hive-mcp.tools.memory.core :refer [with-chroma with-entry]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.tools.memory.decay :as decay]
            [hive-mcp.tools.memory.promotion :as promo]
            [hive-mcp.tools.core :refer [mcp-error coerce-int!]]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result]
            [hive-mcp.memory.types :as mt]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.agent.context :as ctx]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Duration Management
;; =============================================================================

(defn handle-set-duration
  "Set duration category for a memory entry."
  [{:keys [id duration]}]
  (log/info "mcp-memory-set-duration:" id duration)
  (with-chroma
    (let [store (mem-proto/get-store)
          expires (dur/calculate-expires duration)
          updated (mem-proto/update-entry! store id {:duration duration
                                                     :expires (or expires "")})]
      (if updated
        {:type "text" :text (json/write-str (fmt/entry->json-alist updated))}
        (mcp-error "Entry not found")))))

(defn- shift-entry-duration
  "Shift entry duration by delta steps."
  [id delta boundary-msg]
  (with-entry [entry id]
    (let [{:keys [new-duration changed?]} (dur/shift-duration (:duration entry) delta)]
      (if-not changed?
        {:type "text" :text (json/write-str {:message boundary-msg
                                             :duration new-duration})}
        (let [store (mem-proto/get-store)
              expires (dur/calculate-expires new-duration)
              updated (mem-proto/update-entry! store id {:duration new-duration
                                                         :expires (or expires "")})]
          {:type "text" :text (json/write-str (fmt/entry->json-alist updated))})))))

(defn handle-promote
  "Promote memory entry to longer duration."
  [{:keys [id]}]
  (log/info "mcp-memory-promote:" id)
  (shift-entry-duration id +1 "Already at maximum duration"))

(defn handle-demote
  "Demote memory entry to shorter duration."
  [{:keys [id]}]
  (log/info "mcp-memory-demote:" id)
  (shift-entry-duration id -1 "Already at minimum duration"))

;; =============================================================================
;; Cleanup & Expiry
;; =============================================================================

(defn handle-cleanup-expired
  "Remove all expired memory entries and clean up their KG edges."
  [_]
  (log/info "mcp-memory-cleanup-expired")
  (with-chroma
    (let [{:keys [count deleted-ids repaired]} (mem-proto/cleanup-expired! (mem-proto/get-store))
          edges-removed (when (seq deleted-ids)
                          (reduce (fn [total id]
                                    (+ total (kg-edges/remove-edges-for-node! id)))
                                  0 deleted-ids))]
      ;; Temporal dual-write: batch record all deletions from cleanup
      (when (seq deleted-ids)
        (temporal/record-mutations-batch!
         (mapv (fn [did]
                 {:entry-id   did
                  :op         :cleanup
                  :data       {:reason "expired"}})
               deleted-ids)))
      (when (pos? (or edges-removed 0))
        (log/info "Cleaned up" edges-removed "KG edges for" count "deleted entries"))
      {:type "text" :text (json/write-str {:deleted count
                                           :kg_edges_removed (or edges-removed 0)
                                           :repaired (or repaired 0)})})))

(defn handle-expire
  "Force-expire (delete) a memory entry by ID and clean up its KG edges."
  [{:keys [id]}]
  (log/info "mcp-memory-expire:" id)
  (with-entry [entry id]
    (let [edges-removed (kg-edges/remove-edges-for-node! id)]
      ;; Temporal dual-write: record deletion with full previous state
      (temporal/record-mutation-silent!
       {:entry-id       id
        :op             :expire
        :data           {:edges-removed edges-removed}
        :previous-value (select-keys entry [:type :content :tags :duration
                                            :helpful-count :unhelpful-count
                                            :access-count :project-id])
        :project-id     (:project-id entry)})
      (mem-proto/delete-entry! (mem-proto/get-store) id)
      (when (pos? edges-removed)
        (log/info "Cleaned up" edges-removed "KG edges for expired entry" id))
      {:type "text" :text (json/write-str {:expired id
                                           :kg_edges_removed edges-removed})})))

;; =============================================================================
;; Expiring-Soon Query
;; =============================================================================

(defn- worth-promoting?
  "Filter for entries worth alerting about expiration.
   Uses MemoryType promotion-worthy-types for type-safe dispatch."
  [entry]
  (or (contains? mt/promotion-worthy-types (keyword (or (:type entry) "note")))
      (contains? #{"medium" "long" "permanent"} (:duration entry))))

(defn- entry->expiring-meta
  "Convert entry to expiring-alert format with duration/expires info."
  [entry]
  (assoc (fmt/entry->metadata entry 150)
         :duration (:duration entry)
         :expires (:expires entry)))

(defn- expiring-soon*
  "Pure logic for expiring-soon query. Returns Result."
  [{:keys [days directory limit include-short]}]
  (let [days-val (coerce-int! days :days 3)
        limit-val (coerce-int! limit :limit 20)
        directory (or directory (ctx/current-directory))]
    (log/info "mcp-memory-expiring-soon:" days-val "limit:" limit-val "directory:" directory)
    (with-chroma
      (let [project-id (scope/get-current-project-id directory)
            all-entries (mem-proto/entries-expiring-soon (mem-proto/get-store) days-val {})
            scope-filter (scope/make-scope-tag project-id)
            filtered (->> all-entries
                          (filter #(scope/matches-scope? % scope-filter))
                          (filter #(or include-short (worth-promoting? %)))
                          (take limit-val))]
        (result/ok (mapv entry->expiring-meta filtered))))))

(defn handle-expiring-soon
  "List memory entries expiring within N days, filtered by project scope."
  [params]
  (rb/result->mcp (rb/try-result :memory/expiring-soon #(expiring-soon* params))))

;; =============================================================================
;; Re-exports (backward compatibility for tools/memory.clj facade)
;; =============================================================================

(def handle-decay
  "Run scheduled staleness decay on memory entries.
   Delegated to hive-mcp.tools.memory.decay."
  decay/handle-decay)

(def handle-xpoll-promote
  "Scan and auto-promote entries accessed across multiple projects.
   Delegated to hive-mcp.tools.memory.promotion."
  promo/handle-xpoll-promote)

(def run-decay-cycle!
  "Bounded, idempotent decay cycle for crystallize-session hooks.
   Delegated to hive-mcp.tools.memory.decay."
  decay/run-decay-cycle!)

(def run-xpoll-cycle!
  "Run bounded xpoll auto-promotion cycle for crystallize-session hooks.
   Delegated to hive-mcp.tools.memory.promotion."
  promo/run-xpoll-cycle!)
