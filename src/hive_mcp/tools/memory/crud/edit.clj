(ns hive-mcp.tools.memory.crud.edit
  "Edit handler for memory entries.

   In-place mutation preserves entry ID, which preserves all KG edges
   (edges are keyed by entry ID, never content). Content changes trigger
   re-embedding via IMemoryStore/update-entry! — the Chroma backend's
   index-memory-entry! path re-embeds transparently on content change.

   Kanban move-to-status! already relies on the same primitive (see
   tools/memory_kanban.clj:292), so this handler just surfaces it to
   callers as a first-class MCP command.

   Batch edit runs sequentially for now. Addendum 20260423133956-0aa648bc
   tracks the single-Chroma-upsert + single-Datalevin-tx optimization as
   follow-up work; correctness ships first."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.memory.type-registry :as type-registry]
            [hive-mcp.plan.plans :as plans]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- normalize-type
  "Coerce a type parameter (keyword or string) into the canonical string form."
  [t]
  (when t (if (keyword? t) (name t) t)))

(defn- validate-type!
  "Throw ex-info with :invalid-type marker when the incoming type is not in
   the registry. Nil is allowed (means 'no type change')."
  [t]
  (when (and t (not (type-registry/valid-type? t)))
    (throw (ex-info (str "Invalid memory type: " (pr-str t)
                         ". Valid: " (vec (sort (type-registry/all-type-strings))))
                    {:type :invalid-type :value t}))))

(defn- build-updates
  "Compute the partial-update map from the incoming edit params against the
   existing entry. Returns [updates content-changed?]."
  [existing {:keys [type content tags duration abstraction_level]}]
  (let [new-type         (normalize-type type)
        content-changed? (and content (not= content (:content existing)))
        updates
        (cond-> {}
          new-type          (assoc :type new-type)
          content-changed?  (assoc :content content)
          (some? tags)      (assoc :tags tags)
          duration          (assoc :duration duration)
          abstraction_level (assoc :abstraction-level abstraction_level))]
    [updates content-changed?]))

(defn- apply-edit!
  "Apply a single edit. Returns a result map describing the outcome, or nil
   when the entry is not found. Does not build MCP-shaped responses — leaves
   that to the caller so batch ops can aggregate cleanly."
  [store {:keys [id reason] :as params}]
  (when-let [existing (or (mem-proto/get-entry store id)
                          (plans/get-plan id))]
    (let [[updates content-changed?] (build-updates existing params)]
      (if (empty? updates)
        {:id id :noop true :existing existing}
        (let [updated (mem-proto/update-entry! store id updates)]
          (log/info "Memory edit:" id
                    "fields:" (vec (keys updates))
                    (when content-changed? "[re-embed]")
                    (when reason (str "reason:" reason)))
          {:id               id
           :updated          updated
           :fields-changed   (vec (keys updates))
           :content-changed? content-changed?})))))

(defn handle-edit
  "Edit a memory entry in place. Entry ID is preserved, so all KG edges —
   keyed by entry ID — survive the edit untouched.

   Params:
     :id                 — required, entry to edit
     :type               — optional, new memory type (validated against registry)
     :content            — optional, new content (triggers re-embed)
     :tags               — optional, replaces existing tags
     :duration           — optional, new TTL category
     :abstraction_level  — optional, new abstraction level 1-4
     :reason             — optional, logged for audit; stored later as KG
                           edit-event node once audit-trail slice lands"
  [{:keys [id type] :as params}]
  (cond
    (or (nil? id) (str/blank? id))
    (mcp-error "id is required (non-blank string)")

    :else
    (try
      (validate-type! type)
      (with-store
        (let [store  (mem-proto/get-store)
              result (apply-edit! store params)]
          (cond
            (nil? result)
            (mcp-json {:error "Entry not found" :id id})

            (:noop result)
            (mcp-json (assoc (fmt/entry->json-alist (:existing result))
                             :noop true
                             :edit_applied false))

            :else
            (mcp-json (assoc (fmt/entry->json-alist (:updated result))
                             :edit_applied  true
                             :fields_changed (:fields-changed result)
                             :content_changed (boolean (:content-changed? result)))))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :invalid-type (:type (ex-data e)))
          (mcp-error (.getMessage e))
          (throw e))))))

(defn- batch-op-result
  [store op]
  (try
    (validate-type! (:type op))
    (if-let [r (apply-edit! store op)]
      (cond
        (:noop r)
        {:id (:id op) :status :noop}

        :else
        {:id               (:id op)
         :status           :edited
         :fields-changed   (:fields-changed r)
         :content-changed? (boolean (:content-changed? r))})
      {:id (:id op) :status :not-found})
    (catch Throwable t
      (log/warn "Batch-edit op failed for" (:id op) ":" (.getMessage t))
      {:id (:id op) :status :error :error (.getMessage t)})))

(defn- tally-statuses
  [results]
  (let [status->count (frequencies (map :status results))]
    {:total     (count results)
     :edited    (get status->count :edited 0)
     :noop      (get status->count :noop 0)
     :not-found (get status->count :not-found 0)
     :errors    (get status->count :error 0)}))

(defn handle-batch-edit
  "Apply a batch of edits sequentially. Each operation has the same shape
   as handle-edit params. Returns a summary + per-op result vector.

   Params:
     :operations  — required, seq of {:id ... :type? ... :content? ... :tags? ...}
     :dry-run     — optional, validate + preview without writing (default false)
     :atomic      — accepted but not enforced yet — follow-up ships the single-tx
                    path per addendum 20260423133956-0aa648bc"
  [{:keys [operations dry-run]
    :or   {dry-run false}}]
  (cond
    (empty? operations)
    (mcp-error "operations is required (non-empty array of edit ops)")

    dry-run
    (mcp-json {:dry_run  true
               :op_count (count operations)
               :preview  (mapv #(select-keys % [:id :type :content :tags
                                                :duration :abstraction_level :reason])
                               operations)})

    :else
    (with-store
      (let [store   (mem-proto/get-store)
            results (mapv #(batch-op-result store %) operations)
            summary (tally-statuses results)]
        (mcp-json (assoc summary :results results))))))
