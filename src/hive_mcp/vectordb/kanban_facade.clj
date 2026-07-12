(ns hive-mcp.vectordb.kanban-facade
  "Backend-agnostic facade for kanban operations.

   Mirrors hive-mcp.vectordb.facade in surface but routes through the
   `:kanban` registry slot when the config toggle requests it. Decouples
   kanban from the milvus :default slot so list/move/get/delete/stats
   queries hit a dedicated qdrant collection (sub-10ms tag-filtered
   reads).

   Routing modes (config :memory :kanban-store):
     :default    — reads + writes go through (proto/get-store) :default;
                   identical to legacy behaviour. Backward-compatible
                   when no kanban-store key is set in config.
     :dual-read  — reads try :kanban first, fall back to :default on miss
                   or when the :kanban slot isn't registered. Writes go
                   to :kanban (primary) and best-effort mirror to
                   :default for soak before final cutover.
     :kanban     — reads + writes go only through :kanban. Asserts the
                   slot is registered.

   Surface kept minimal — only what the kanban codepath needs:
   `mode`, `active-key`, `available?`, `get-entry-by-id`, `query-entries`,
   `update-entry!`, `delete-entry!`, `add-entry!`. Embedding pipeline
   stays in hive-mcp.tools.memory.crud.write/handle-add (creates) — this
   facade only owns the read/update/delete hot path + a thin add-entry!
   for migration tooling."
  (:require [hive-dsl.result :refer [rescue]]
            [hive-mcp.config.core :as cfg-core]
            [hive-mcp.protocols.memory :as proto]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; Mode resolution (pure)
;;; ============================================================================

(defn mode
  "Current kanban-store routing mode. Pure read of the global config
   accessor; defaults to :default when config is unloaded or the
   :kanban-store key is absent."
  []
  (cfg-core/get-kanban-store-mode))

(defn active-key
  "Registry slot the *write* side targets:
     :default → :default      (legacy / milvus)
     :kanban  → :kanban       (qdrant kanban collection)
     :dual-read → :kanban     (primary; mirror happens implicitly to :default)

   Used by the create path to thread `:store-key` into
   hive-mcp.tools.memory.crud.write/handle-add."
  []
  (case (mode)
    :default :default
    (:kanban :dual-read) :kanban))

(defn registered?
  "True iff the registry has an entry under `slot-kw`. Wraps
   protocols.memory/registered-stores so callers don't reach for the
   atom directly."
  [slot-kw]
  (some? (get (proto/registered-stores) slot-kw)))

(defn available?
  "True iff the :kanban slot is currently registered. The kanban codepath
   uses this to decide whether to gate dual-read fallback or fail loud
   in :kanban mode at boot time."
  []
  (registered? :kanban))

;;; ============================================================================
;;; Store resolution (mode-aware)
;;; ============================================================================

(defn- mirror-write!
  "Best-effort mirror of a write op to the :default slot during
   :dual-read soak. Wraps the call in `rescue` so a milvus blip never
   breaks the primary :kanban write."
  [op-fn]
  (rescue nil (op-fn (proto/get-store))))

;;; ============================================================================
;;; CRUD — read path
;;; ============================================================================

(defn get-entry-by-id
  "Read a kanban entry by id. In :kanban and :dual-read modes, falls back
   across the :kanban and :default stores so legacy entries in either slot
   stay retrievable."
  [id]
  (case (mode)
    :default   (proto/get-entry (proto/get-store) id)
    :kanban    (or (rescue nil (proto/get-entry (proto/get-store :kanban) id))
                   (rescue nil (proto/get-entry (proto/get-store) id)))
    :dual-read (or (when (registered? :kanban)
                     (rescue nil (proto/get-entry (proto/get-store :kanban) id)))
                   (proto/get-entry (proto/get-store) id))))

(defn query-entries
  "Query kanban entries with filtering. Mirrors hive-mcp.vectordb.facade
   surface (keyword-arg API for backward compat with the kanban codepath
   call sites). The :order-by opt is preserved end-to-end so
   per-backend post-fetch sort kicks in.

   :kanban and :dual-read both merge :kanban + :default result sets,
   de-duped by :id (kanban-first), so entries that exist in only one slot
   are not lost. Caller still applies its own kanban-tag predicate."
  [& {:keys [type project-id project-ids tags exclude-tags limit
             include-expired? include-content? output-fields order-by]
      :or   {limit 100 include-expired? false
             ;; Kanban downstream filters operate on content fields
             ;; (:status, :priority, :task-type predicate). Default
             ;; :include-content? true so qdrant doesn't strip the
             ;; payload — the carto-default of "metadata-only" was
             ;; the wrong cut for kanban hot-path queries.
             include-content? true}
      :as   _opts}]
  (let [opts-map (cond-> {:type             type
                          :project-id       project-id
                          :project-ids      project-ids
                          :tags             tags
                          :exclude-tags     exclude-tags
                          :limit            limit
                          :include-expired? include-expired?
                          :include-content? include-content?}
                   output-fields (assoc :output-fields output-fields)
                   order-by      (assoc :order-by order-by))
        merged-query
        (fn []
          (let [primary   (when (registered? :kanban)
                            (rescue nil
                              (vec (proto/query-entries
                                    (proto/get-store :kanban) opts-map))))
                fallback  (rescue nil
                            (vec (proto/query-entries
                                  (proto/get-store) opts-map)))
                seen      (volatile! #{})
                keep-once (fn [e]
                            (let [id (:id e)]
                              (when-not (contains? @seen id)
                                (vswap! seen conj id)
                                e)))
                merged    (vec (keep keep-once
                                     (concat (or primary [])
                                             (or fallback []))))]
            ;; Trim back to caller's :limit after dedupe — the merged
            ;; result can otherwise be 2x the cap.
            (if (and limit (> (count merged) limit))
              (vec (take limit merged))
              merged)))]
    (case (mode)
      :default   (proto/query-entries (proto/get-store) opts-map)
      :kanban    (merged-query)
      :dual-read (merged-query))))

;;; ============================================================================
;;; CRUD — write path
;;; ============================================================================

(defn- store-for-id
  "Store slot that actually holds `id` — prefer :kanban, else :default,
   else the active-key slot (new-entry path). Lets writes reach a legacy
   entry that lives only in the :default slot."
  [id]
  (cond
    (some? (rescue nil (proto/get-entry (proto/get-store :kanban) id)))
    (proto/get-store :kanban)
    (some? (rescue nil (proto/get-entry (proto/get-store) id)))
    (proto/get-store)
    :else (proto/get-store (active-key))))

(defn add-entry!
  "Index a kanban entry. Used by migration tooling — the create path
   stays on hive-mcp.tools.memory.crud.write/handle-add (which handles
   embedding + duplicate detection + KG edges) and threads `:store-key`
   through to land in the same slot."
  [entry]
  (case (mode)
    :default   (proto/add-entry! (proto/get-store) entry)
    :kanban    (proto/add-entry! (proto/get-store :kanban) entry)
    :dual-read (let [primary (proto/add-entry! (proto/get-store :kanban) entry)]
                 (mirror-write! (fn [s] (proto/add-entry! s entry)))
                 primary)))

(defn update-entry!
  "Soft-update a kanban entry (status retag, tag rewrite). In :dual-read,
   primary = :kanban, mirror best-effort to :default."
  [id updates]
  (case (mode)
    :default   (proto/update-entry! (proto/get-store) id updates)
    :kanban    (proto/update-entry! (store-for-id id) id updates)
    :dual-read (let [primary (proto/update-entry! (proto/get-store :kanban) id updates)]
                 (mirror-write! (fn [s] (proto/update-entry! s id updates)))
                 primary)))

(defn delete-entry!
  "Hard-delete a kanban entry. In :dual-read, attempts both slots so
   the entry can't resurrect from the legacy slot during soak."
  [id]
  (case (mode)
    :default   (proto/delete-entry! (proto/get-store) id)
    :kanban    (proto/delete-entry! (store-for-id id) id)
    :dual-read (let [primary (proto/delete-entry! (proto/get-store :kanban) id)]
                 (mirror-write! (fn [s] (proto/delete-entry! s id)))
                 primary)))

;;; ============================================================================
;;; Boot-time validation
;;; ============================================================================

(defn validate-mode!
  "Boot-time check: in :kanban mode the :kanban slot MUST be registered.
   In :dual-read it SHOULD be — log a loud warning if absent so the
   operator notices before flipping further. :default never warns.

   Returns {:mode <kw> :ok? bool :warning <str or nil>}. Never throws —
   the caller (system layer1) decides whether to abort startup or
   degrade to legacy routing."
  []
  (let [m  (mode)
        ok (case m
             :default   true
             :kanban    (available?)
             :dual-read true)
        warning (case m
                  :kanban    (when-not (available?)
                               (str "kanban-store mode is :kanban but the "
                                    ":kanban slot is unregistered — kanban "
                                    "ops will throw at the boundary. Check "
                                    "hive-qdrant-kanban manifest discovery."))
                  :dual-read (when-not (available?)
                               (str "kanban-store mode is :dual-read but the "
                                    ":kanban slot is unregistered — facade "
                                    "will silently fall back to :default."))
                  nil)]
    (when warning (log/warn warning {:mode m}))
    {:mode m :ok? ok :warning warning}))
