(ns hive-mcp.tools.memory.migration
  "Migration handlers for memory project and storage transitions."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.knowledge-graph.edges :as kg-edges]
            [hive-mcp.knowledge-graph.connection :as kg-conn]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.protocols.memory :as mem-proto]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-mcp.dns.result :refer [rescue]]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn handle-migrate-project
  "Migrate memory from one project-id to another (Chroma + KG edges)."
  [{:keys [old-project-id new-project-id update-scopes]}]
  (log/info "mcp-memory-migrate-project:" old-project-id "->" new-project-id)
  (with-store
    (let [store (mem-proto/get-store)
          entries (mem-proto/query-entries store {:project-id old-project-id :limit 10000})
          migrated (atom 0)
          updated-scopes (atom 0)
          old-scope-tag (scope/make-scope-tag old-project-id)
          new-scope-tag (scope/make-scope-tag new-project-id)]
      (doseq [entry entries]
        (let [new-tags (if update-scopes
                         (mapv (fn [tag]
                                 (if (= tag old-scope-tag)
                                   (do (swap! updated-scopes inc)
                                       new-scope-tag)
                                   tag))
                               (:tags entry))
                         (:tags entry))]
          (mem-proto/update-entry! store (:id entry) {:project-id new-project-id
                                                      :tags new-tags})
          ;; Temporal dual-write: record each migration
          (temporal/record-mutation-silent!
           {:entry-id       (:id entry)
            :op             :migrate
            :data           {:old-project-id old-project-id
                             :new-project-id new-project-id
                             :scopes-updated update-scopes}
            :previous-value {:project-id old-project-id}
            :project-id     new-project-id})
          (swap! migrated inc)))
      (let [kg-result (try
                        (kg-edges/migrate-edge-scopes! old-project-id new-project-id)
                        (catch Exception e
                          (log/warn "KG edge scope migration failed (non-blocking):"
                                    (.getMessage e))
                          {:migrated 0 :error (.getMessage e)}))]
        (mcp-json {:migrated @migrated
                   :updated-scopes @updated-scopes
                   :kg-edges-migrated (:migrated kg-result)
                   :kg-error (:error kg-result)
                   :old-project-id old-project-id
                   :new-project-id new-project-id})))))

(defn handle-migrate-scoped
  "Migrate specific memory entries by ID (or tag filter) from one project to another.
   Updates project-id and scope tags per-entry. Preserves KG edges — only updates
   edge scope for edges where BOTH endpoints are in the migrated set.

   Arguments:
     :entry-ids      - Vector of entry IDs to migrate (takes priority)
     :tag-filter     - Tag string to match entries (e.g. 'payment-flow'); used when entry-ids is empty
     :old-project-id - Source project-id (required for scope tag swap)
     :new-project-id - Target project-id (required)
     :dry-run        - Preview without modifying (default: false)

   Returns:
     {:migrated N :ids [...] :from old-project :to new-project
      :kg-edges-migrated N :skipped-ids [...] :errors [...]}"
  [{:keys [entry-ids tag-filter old-project-id new-project-id dry-run]}]
  (cond
    (str/blank? new-project-id)
    (mcp-error "new-project-id is required")

    (str/blank? old-project-id)
    (mcp-error "old-project-id is required")

    (= old-project-id new-project-id)
    (mcp-error "old-project-id and new-project-id must be different")

    (and (empty? entry-ids) (str/blank? tag-filter))
    (mcp-error "Either entry-ids or tag-filter is required")

    :else
    (let [dry-run (boolean dry-run)]
      (log/info "migrate-scoped:" (if (seq entry-ids)
                                    (str (count entry-ids) " entry IDs")
                                    (str "tag-filter=" tag-filter))
                old-project-id "->" new-project-id
                (when dry-run "(dry-run)"))
      (with-store
        (let [store          (mem-proto/get-store)
              old-scope-tag  (scope/make-scope-tag old-project-id)
              new-scope-tag  (scope/make-scope-tag new-project-id)
              ;; Resolve target entries — by IDs or by tag filter
              target-ids     (if (seq entry-ids)
                               (vec entry-ids)
                               (let [all-entries (mem-proto/query-entries
                                                  store {:project-id old-project-id
                                                         :limit 10000})]
                                 (->> all-entries
                                      (filter (fn [e]
                                                (some #(= % tag-filter) (:tags e))))
                                      (mapv :id))))
              ;; Fetch each entry, partition into found/not-found
              resolved       (reduce
                              (fn [acc eid]
                                (if-let [entry (mem-proto/get-entry store eid)]
                                  (update acc :found conj entry)
                                  (update acc :not-found conj eid)))
                              {:found [] :not-found []}
                              target-ids)
              found-entries  (:found resolved)
              skipped-ids    (:not-found resolved)
              migrated-ids   (atom [])
              errors         (atom [])]

          (when-not dry-run
            (doseq [entry found-entries]
              (try
                (let [new-tags (mapv (fn [tag]
                                       (if (= tag old-scope-tag)
                                         new-scope-tag
                                         tag))
                                     (:tags entry))]
                  (mem-proto/update-entry! store (:id entry)
                                           {:project-id new-project-id
                                            :tags new-tags})
                  ;; Temporal audit trail
                  (temporal/record-mutation-silent!
                   {:entry-id       (:id entry)
                    :op             :migrate-scoped
                    :data           {:old-project-id old-project-id
                                     :new-project-id new-project-id}
                    :previous-value {:project-id old-project-id}
                    :project-id     new-project-id})
                  (swap! migrated-ids conj (:id entry)))
                (catch Exception e
                  (log/warn "Failed to migrate entry" (:id entry) ":" (.getMessage e))
                  (swap! errors conj {:id (:id entry) :error (.getMessage e)})))))

          ;; Selectively migrate KG edge scopes for edges between migrated entries
          (let [migrated-set    (set (if dry-run (mapv :id found-entries) @migrated-ids))
                kg-edge-result  (if (or dry-run (< (count migrated-set) 2))
                                  {:migrated 0}
                                  (try
                                    (let [edges    (kg-edges/find-edges-between migrated-set)
                                          ;; Only migrate edges scoped to old-project-id
                                          to-update (filter #(= old-project-id (:kg-edge/scope %))
                                                            edges)
                                          tx-data   (vec (for [edge to-update
                                                               :let [eid (kg-conn/entid
                                                                          [:kg-edge/id (:kg-edge/id edge)])]
                                                               :when eid]
                                                           [:db/add eid :kg-edge/scope new-project-id]))]
                                      (when (seq tx-data)
                                        (kg-conn/transact! tx-data))
                                      {:migrated (count tx-data)})
                                    (catch Exception e
                                      (log/warn "KG edge scope migration failed (non-blocking):"
                                                (.getMessage e))
                                      {:migrated 0 :error (.getMessage e)})))]

            (mcp-json {:migrated          (if dry-run (count found-entries) (count @migrated-ids))
                       :ids               (if dry-run (mapv :id found-entries) @migrated-ids)
                       :skipped-ids       skipped-ids
                       :errors            @errors
                       :kg-edges-migrated (:migrated kg-edge-result)
                       :kg-error          (:error kg-edge-result)
                       :dry-run           dry-run
                       :from              old-project-id
                       :to                new-project-id})))))))

(defn- import-entry!
  "Import a single entry to memory store with content-hash deduplication."
  [entry project-id]
  (let [store (mem-proto/get-store)
        entry-hash (or (:content-hash entry)
                       (mem-proto/content-hash (:content entry)))
        entry-type (or (:type entry) "note")]
    (cond
      (mem-proto/find-duplicate store entry-type entry-hash {:project-id project-id})
      :skipped-hash

      (mem-proto/get-entry store (:id entry))
      :skipped-id

      :else
      (do
        (mem-proto/add-entry! store
                              {:id (:id entry)
                               :type entry-type
                               :content (:content entry)
                               :tags (if (vector? (:tags entry))
                                       (vec (:tags entry))
                                       (:tags entry))
                               :content-hash entry-hash
                               :created (:created entry)
                               :updated (:updated entry)
                               :duration (or (:duration entry) "long")
                               :expires (or (:expires entry) "")
                               :access-count (or (:access-count entry) 0)
                               :helpful-count (or (:helpful-count entry) 0)
                               :unhelpful-count (or (:unhelpful-count entry) 0)
                               :project-id project-id})
        :imported))))

(defn handle-import-json
  "Import memory entries from legacy JSON storage to Chroma."
  [{:keys [project-id dry-run]}]
  (log/info "mcp-memory-import-json:" project-id "dry-run:" dry-run)
  (with-store
    (let [pid (or project-id (scope/get-current-project-id))
          elisp (format "(json-encode (list :notes (hive-mcp-memory-query 'note nil %s 1000 nil t)
                                            :snippets (hive-mcp-memory-query 'snippet nil %s 1000 nil t)
                                            :conventions (hive-mcp-memory-query 'convention nil %s 1000 nil t)
                                            :decisions (hive-mcp-memory-query 'decision nil %s 1000 nil t)))"
                        (pr-str pid) (pr-str pid) (pr-str pid) (pr-str pid))
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if-not success
        (mcp-json {:error (str "Failed to read JSON: " error)})
        (let [data (json/read-str result :key-fn keyword)
              all-entries (concat (:notes data) (:snippets data)
                                  (:conventions data) (:decisions data))]
          (if dry-run
            (mcp-json {:dry-run true
                       :would-import (count all-entries)
                       :by-type {:notes (count (:notes data))
                                 :snippets (count (:snippets data))
                                 :conventions (count (:conventions data))
                                 :decisions (count (:decisions data))}})
            (let [results (mapv #(import-entry! % pid) all-entries)
                  imported (count (filter #(= :imported %) results))
                  skipped-hash (count (filter #(= :skipped-hash %) results))
                  skipped-id (count (filter #(= :skipped-id %) results))]
              (mcp-json {:imported imported
                         :skipped {:by-hash skipped-hash
                                   :by-id skipped-id
                                   :total (+ skipped-hash skipped-id)}
                         :project-id pid}))))))))

(defn hash-scope?
  "Detect if a scope looks like a hash (orphaned old-style scope)."
  [scope-id]
  (and (string? scope-id)
       (> (count scope-id) 12)
       (boolean (re-matches #"^[a-f0-9]+$" scope-id))))

(defn- extract-scope-id
  "Extract the scope ID from a scope:project: tag."
  [tag]
  (when (and (string? tag) (str/starts-with? tag "scope:project:"))
    (subs tag (count "scope:project:"))))

(defn- orphaned-scope-tag?
  "Check if a tag is an orphaned hash-based scope tag."
  [tag]
  (when-let [scope-id (extract-scope-id tag)]
    (hash-scope? scope-id)))

(defn handle-detect-orphaned
  "Detect orphaned hash-based scope tags in memory."
  [_args]
  (with-store
    (let [entries (mem-proto/query-entries (mem-proto/get-store) {:limit 5000 :include-expired? true})
          scope-entries (->> entries
                             (mapcat (fn [entry]
                                       (->> (:tags entry)
                                            (filter orphaned-scope-tag?)
                                            (map (fn [tag]
                                                   {:scope-id (extract-scope-id tag)
                                                    :entry-id (:id entry)})))))
                             (group-by :scope-id))
          orphaned-scopes (keys scope-entries)
          entries-by-scope (into {} (map (fn [[k v]] [k (count v)]) scope-entries))]
      (log/info "Detected" (count orphaned-scopes) "orphaned hash-based scopes")
      (mcp-json {:orphaned-scopes (vec orphaned-scopes)
                 :count (count orphaned-scopes)
                 :entries-by-scope entries-by-scope}))))

(defn- update-scope-tag
  "Replace old scope tag with new scope tag in a tags vector."
  [tags old-scope new-scope]
  (let [old-tag (str "scope:project:" old-scope)
        new-tag (str "scope:project:" new-scope)]
    (mapv #(if (= % old-tag) new-tag %) tags)))

(defn handle-migrate-scope
  "Migrate entries from old hash-based scope to new name-based scope."
  [{:keys [old_scope new_scope dry_run]}]
  (cond
    (str/blank? old_scope)
    (mcp-error "old_scope is required")

    (str/blank? new_scope)
    (mcp-error "new_scope is required")

    (= old_scope new_scope)
    (mcp-error "old_scope and new_scope must be different")

    :else
    (let [dry-run (if (nil? dry_run) true dry_run)]
      (with-store
        (let [store (mem-proto/get-store)
              old-tag (str "scope:project:" old_scope)
              entries (mem-proto/query-entries store {:limit 5000 :include-expired? true})
              matching (->> entries
                            (filter #(some #{old-tag} (:tags %)))
                            vec)
              entry-ids (mapv :id matching)]
          (log/info "migrate-scope:" (count matching) "entries from" old_scope "to" new_scope
                    (if dry-run "(dry-run)" ""))

          (when-not dry-run
            (doseq [entry matching]
              (let [new-tags (update-scope-tag (:tags entry) old_scope new_scope)]
                (mem-proto/update-entry! store (:id entry) {:tags new-tags})
                (log/debug "Migrated entry" (:id entry) "tags:" (:tags entry) "->" new-tags))))

          (mcp-json {:migrated (count matching)
                     :entries entry-ids
                     :dry-run (boolean dry-run)
                     :old-scope old_scope
                     :new-scope new_scope}))))))

(defn- read-hive-project-edn
  "Read and parse .hive-project.edn from a directory."
  [directory]
  (rescue nil
          (let [edn-file (io/file directory ".hive-project.edn")]
            (when (.exists edn-file)
              (edn/read-string (slurp edn-file))))))

(defn- update-hive-project-edn!
  "Update .hive-project.edn with new project-id and append old to aliases."
  [directory old-project-id new-project-id]
  (try
    (let [edn-file (io/file directory ".hive-project.edn")
          existing (when (.exists edn-file)
                     (edn/read-string (slurp edn-file)))
          current-aliases (or (:aliases existing) [])
          updated-aliases (if (some #{old-project-id} current-aliases)
                            current-aliases
                            (conj current-aliases old-project-id))
          updated-config (assoc (or existing {})
                                :project-id new-project-id
                                :aliases updated-aliases)]
      (spit (.getAbsolutePath edn-file)
            (pr-str updated-config))
      (log/info "Updated .hive-project.edn:" (.getAbsolutePath edn-file)
                {:project-id new-project-id :aliases updated-aliases})
      {:success true :config updated-config})
    (catch Exception e
      (log/warn "Failed to update .hive-project.edn:" (.getMessage e))
      {:error (.getMessage e)})))

(defn handle-rename-project
  "Unified rename-project orchestrating Chroma, KG, .edn, and config migration."
  [{:keys [old-project-id new-project-id directory dry-run]}]
  (cond
    (str/blank? old-project-id)
    (mcp-error "old-project-id is required")

    (str/blank? new-project-id)
    (mcp-error "new-project-id is required")

    (= old-project-id new-project-id)
    (mcp-error "old-project-id and new-project-id must be different")

    :else
    (let [dry-run (boolean dry-run)]
      (log/info "rename-project:" old-project-id "->" new-project-id
                (when dry-run "(dry-run)") {:directory directory})

      (if dry-run
        (let [chroma-count (rescue 0
                                   (with-store
                                     (count (mem-proto/query-entries (mem-proto/get-store)
                                                                     {:project-id old-project-id
                                                                      :limit 10000}))))
              kg-count (rescue 0
                               (count (kg-edges/get-edges-by-scope old-project-id)))
              edn-config (when directory (read-hive-project-edn directory))
              current-aliases (or (:aliases edn-config) [])
              would-add-alias (not (some #{old-project-id} current-aliases))]
          (mcp-json {:status "dry-run"
                     :chroma {:would-migrate chroma-count}
                     :kg-edges {:would-migrate kg-count}
                     :edn {:exists (boolean edn-config)
                           :current-aliases current-aliases
                           :would-add-alias would-add-alias}
                     :old-project-id old-project-id
                     :new-project-id new-project-id
                     :directory directory}))

        (let [migrate-result (try
                               (let [raw (handle-migrate-project
                                          {:old-project-id old-project-id
                                           :new-project-id new-project-id
                                           :update-scopes true})
                                     text (or (get-in raw [:result 0 :text])
                                              (:text raw))
                                     parsed (json/read-str text :key-fn keyword)]
                                 parsed)
                               (catch Exception e
                                 (log/warn "Phase 1 (Chroma+KG migration) failed:"
                                           (.getMessage e))
                                 {:error (.getMessage e)
                                  :migrated 0
                                  :updated-scopes 0
                                  :kg-edges-migrated 0}))

              edn-result (if directory
                           (update-hive-project-edn! directory old-project-id new-project-id)
                           {:skipped "no directory provided"})

              config-registered
              (rescue false
                      (when-let [config (:config edn-result)]
                        (kg-scope/register-project-config! new-project-id config)
                        true))

              _cache-cleared (rescue nil
                                     (kg-scope/clear-config-cache!)
                                     (when-let [config (:config edn-result)]
                                       (kg-scope/register-project-config! new-project-id config)))]

          (mcp-json {:status "success"
                     :chroma {:migrated (:migrated migrate-result 0)
                              :updated-scopes (:updated-scopes migrate-result 0)}
                     :kg-edges {:migrated (:kg-edges-migrated migrate-result 0)
                                :error (:kg-error migrate-result)}
                     :edn (if (:success edn-result)
                            {:updated true
                             :aliases (get-in edn-result [:config :aliases])}
                            {:updated false
                             :reason (or (:error edn-result)
                                         (:skipped edn-result))})
                     :config-registered (boolean config-registered)
                     :old-project-id old-project-id
                     :new-project-id new-project-id
                     :directory directory}))))))

;; =============================================================================
;; Backend Migration (Chroma <-> Proximum)
;; =============================================================================

(defn migrate-backend!
  "Migrate all entries from one IMemoryStore backend to another.

   Reads entries from source-store in batches, re-indexes each into target-store
   via add-entry! (which re-embeds via the configured EmbeddingProvider).

   Arguments:
     source-store - IMemoryStore instance to read from
     target-store - IMemoryStore instance to write to
     opts         - Optional map:
       :batch-size   - Entries per query batch (default: 500)
       :max-entries  - Total cap (default: 50000)
       :dry-run?     - Count without writing (default: false)
       :project-id   - Filter to specific project (default: all)
       :on-progress  - (fn [stats]) callback per batch

   Returns:
     {:migrated int :skipped int :errors int :total-source int}"
  [source-store target-store & [{:keys [batch-size max-entries dry-run? project-id on-progress]
                                 :or {batch-size 500 max-entries 50000 dry-run? false}}]]
  (log/info "migrate-backend! starting" {:dry-run? dry-run? :project-id project-id
                                         :batch-size batch-size :max-entries max-entries})
  (let [entry-types ["axiom" "decision" "convention" "principle" "note" "snippet"]
        stats (atom {:migrated 0 :skipped 0 :errors 0 :total-source 0})]
    (doseq [entry-type entry-types]
      (let [query-opts (cond-> {:type entry-type :limit batch-size :include-expired? true}
                         project-id (assoc :project-id project-id))
            entries (mem-proto/query-entries source-store query-opts)]
        (swap! stats update :total-source + (count entries))
        (doseq [entry entries
                :while (< (:migrated @stats) max-entries)]
          (if dry-run?
            (swap! stats update :migrated inc)
            (let [result (rescue :error
                                 (let [existing (mem-proto/get-entry target-store (:id entry))]
                                   (if existing
                                     :skipped
                                     (do (mem-proto/add-entry! target-store entry)
                                         :migrated))))]
              (case result
                :migrated (swap! stats update :migrated inc)
                :skipped  (swap! stats update :skipped inc)
                :error    (swap! stats update :errors inc)))))
        (when on-progress
          (on-progress @stats))))
    (let [result @stats]
      (log/info "migrate-backend! complete" result)
      result)))

(def tools
  [{:name "mcp_memory_migrate_project"
    :description "Migrate memory entries from one project-id to another. Updates project-id metadata and optionally scope tags."
    :inputSchema {:type "object"
                  :properties {:old-project-id {:type "string"
                                                :description "Current project-id to migrate from"}
                               :new-project-id {:type "string"
                                                :description "New project-id to migrate to"}
                               :update-scopes {:type "boolean"
                                               :description "Also update scope tags (default: false)"
                                               :default false}}
                  :required ["old-project-id" "new-project-id"]}
    :handler handle-migrate-project}

   {:name "mcp_memory_import_json"
    :description "Import memory entries from legacy JSON storage to Chroma. Use dry-run to preview."
    :inputSchema {:type "object"
                  :properties {:project-id {:type "string"
                                            :description "Project ID for imported entries"}
                               :dry-run {:type "boolean"
                                         :description "Preview without importing (default: false)"
                                         :default false}}}
    :handler handle-import-json}

   {:name "mcp_memory_detect_orphaned"
    :description "Detect orphaned hash-based scope tags in memory. Returns list of hash-based scopes that may need migration to name-based scopes. Use before migrate_scope."
    :inputSchema {:type "object"
                  :properties {}}
    :handler handle-detect-orphaned}

   {:name "mcp_memory_migrate_scope"
    :description "Migrate memory entries from old hash-based scope to new name-based scope. Use detect_orphaned first to find orphaned scopes. IMPORTANT: Use dry_run=true first to preview changes."
    :inputSchema {:type "object"
                  :properties {:old_scope {:type "string"
                                           :description "Old hash-based scope ID to migrate FROM (e.g., 'd987697ae05f40b1')"}
                               :new_scope {:type "string"
                                           :description "New name-based scope ID to migrate TO (e.g., 'funeraria')"}
                               :dry_run {:type "boolean"
                                         :description "Preview changes without modifying (default: true)"
                                         :default true}}
                  :required ["old_scope" "new_scope"]}
    :handler handle-migrate-scope}

   {:name "mcp_memory_migrate_scoped"
    :description "Migrate specific memory entries by ID or tag filter from one project to another. Updates project-id and scope tags per-entry. Preserves KG edges (only updates edge scope for edges where both endpoints are in the migrated set). Use dry-run to preview."
    :inputSchema {:type "object"
                  :properties {:entry-ids {:type "array"
                                           :items {:type "string"}
                                           :description "Specific entry IDs to migrate (takes priority over tag-filter)"}
                               :tag-filter {:type "string"
                                            :description "Tag to match entries for migration (e.g., 'payment-flow'). Used when entry-ids is empty."}
                               :old-project-id {:type "string"
                                                :description "Source project-id to migrate from"}
                               :new-project-id {:type "string"
                                                :description "Target project-id to migrate to"}
                               :dry-run {:type "boolean"
                                         :description "Preview changes without modifying (default: false)"
                                         :default false}}
                  :required ["old-project-id" "new-project-id"]}
    :handler handle-migrate-scoped}])
