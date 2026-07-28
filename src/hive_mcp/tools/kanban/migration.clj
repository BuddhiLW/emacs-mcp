(ns hive-mcp.tools.kanban.migration
  "One-shot migration of kanban entries from the legacy `:default` slot
   (typically milvus) to the dedicated `:kanban` slot (qdrant-local).

   Driven from the REPL once both slots are registered. The qdrant
   addon under META-INF/hive-addons/hive-qdrant-kanban.edn provisions
   the `:kanban` slot at boot — verify with `(proto/registered-stores)`
   before running.

   Pipeline:
     1. Extract every kanban-shaped entry from the :default slot
        (filter on content predicate, not just the 'kanban' tag — so
        non-kanban tag collisions don't leak into the new collection).
     2. Hand off to `hive-qdrant.migrate/sync!` for batched upsert into
        the :kanban target store.
     3. `verify` spot-checks N random ids round-trip to confirm
        post-migration integrity.

   Usage:
     (require '[hive-mcp.tools.kanban.migration :as mig])
     (mig/migrate-to-kanban!)
     ;; => {:extracted N :transformed N :loaded-ok N :loaded-fail N
     ;;     :batches B :dry-run? false}

     (mig/verify {:sample-size 20})
     ;; => {:checked 20 :ok 20 :missing []}

   Stays out of the synchronous boot path — running this against a
   live milvus is a heavy read; cutover sequence is feature-flag
   driven (see `hive-mcp.config.core/get-kanban-store-mode`)."
  (:require [hive-mcp.protocols.memory :as proto]
            [hive-mcp.tools.kanban.predicates :as kp]
            [taoensso.timbre :as log]
            [hive-mcp.tools.migrate.batch :as batch]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const default-extract-limit
  "Hard cap on the extract query — the active milvus deployment held 352
   active + a few hundred done at the time the migration was authored.
   5000 buys headroom for backlogs without paging."
  5000)

;; =============================================================================
;; Extract — pull from :default slot
;; =============================================================================

(defn extract-kanban-from-default-store
  "Pull every kanban entry from the :default slot. Includes expired
   entries so soft-deleted (done) tasks survive the cutover.

   Filters via `kp/kanban-entry?` (content-shape predicate, not tag
   match) so non-kanban entries that happen to carry 'kanban' as a
   substring tag don't leak into the kanban collection.

   Returns a vec of entry maps in the IMemoryStore protocol shape —
   `hive-qdrant.migrate/sync!` accepts them directly via its `transform`
   step (id default + type default + tags default)."
  []
  (let [src (proto/get-store)]
    (->> (proto/query-entries
           src
           {:type             "note"
            :tags             ["kanban"]
            :limit            default-extract-limit
            :include-expired? true})
         (filter kp/kanban-entry?)
         vec)))

;; =============================================================================
;; Migrate — backfill :kanban via hive-qdrant.migrate/sync!
;; =============================================================================

(defn migrate-to-kanban!
  "Run the full :default → :kanban migration. Both slots must be
   registered before the call. Forwards optional :batch-size and
   :dry-run? to the registered `IBatchMigrator`.

   Returns the sync! report — pass result to `verify` for round-trip
   spot-check."
  ([] (migrate-to-kanban! {}))
  ([{:keys [batch-size dry-run?]
     :or   {batch-size 500}
     :as   _opts}]
   (let [target (proto/get-store :kanban)
         _      (log/info "kanban migration starting"
                          {:batch-size batch-size :dry-run? (boolean dry-run?)})
         result (batch/sync! (batch/current-migrator)
                             {:source-fn  extract-kanban-from-default-store
                              :target     target
                              :batch-size batch-size
                              :dry-run?   (boolean dry-run?)})]
     (log/info "kanban migration done" result)
     result)))

;; =============================================================================
;; Verify — spot-check round-trip
;; =============================================================================

(defn verify
  "Spot-check that migrated kanban entries land cleanly in the :kanban
   slot. Pulls all source ids, samples `:sample-size`, and asks the
   registered `IBatchMigrator` to round-trip-check each.

   Returns {:checked N :ok N :missing [id...]}. `:missing` empty == clean
   migration."
  ([] (verify {}))
  ([{:keys [sample-size]
     :or   {sample-size 20}}]
   (let [src-ids (mapv :id (extract-kanban-from-default-store))
         sample  (->> src-ids shuffle (take sample-size) vec)
         target  (proto/get-store :kanban)]
     (batch/verify (batch/current-migrator) {:target target :ids sample}))))

;; =============================================================================
;; Status — quick sanity check before running
;; =============================================================================

(defn status
  "Inspect both slots without performing any writes. Useful as a
   pre-flight before `migrate-to-kanban!` to confirm:
     - :default slot is registered (the source)
     - :kanban  slot is registered (the target)
     - source count is plausible (not zero, not surprising)

   Returns {:default-registered? bool :kanban-registered? bool
            :source-count N :sample-ids [...]}"
  []
  (let [registry (proto/registered-stores)
        default? (some? (:default registry))
        kanban?  (some? (:kanban  registry))
        src      (when default? (extract-kanban-from-default-store))]
    {:default-registered? default?
     :kanban-registered?  kanban?
     :source-count        (count src)
     :sample-ids          (vec (take 5 (map :id src)))}))
