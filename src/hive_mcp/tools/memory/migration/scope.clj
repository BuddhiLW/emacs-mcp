(ns hive-mcp.tools.memory.migration.scope
  "Scope-tag migration: detect orphaned hash-based scopes and rewrite them
   to name-based scopes."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.core :refer [mcp-json mcp-error]]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.memory.migration.helpers :as helpers]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [hive-mcp.vectordb.resilience :refer [with-resilience]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn handle-detect-orphaned
  "Detect orphaned hash-based scope tags in memory."
  [_args]
  (with-store
    (let [entries (with-resilience
                    (mem-proto/query-entries (mem-proto/get-store) {:limit 5000 :include-expired? true}))
          scope-entries (->> entries
                             (mapcat (fn [entry]
                                       (->> (:tags entry)
                                            (filter helpers/orphaned-scope-tag?)
                                            (map (fn [tag]
                                                   {:scope-id (helpers/extract-scope-id tag)
                                                    :entry-id (:id entry)})))))
                             (group-by :scope-id))
          orphaned-scopes (keys scope-entries)
          entries-by-scope (into {} (map (fn [[k v]] [k (count v)]) scope-entries))]
      (log/info "Detected" (count orphaned-scopes) "orphaned hash-based scopes")
      (mcp-json {:orphaned-scopes (vec orphaned-scopes)
                 :count (count orphaned-scopes)
                 :entries-by-scope entries-by-scope}))))

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
              entries (with-resilience
                        (mem-proto/query-entries store {:limit 5000 :include-expired? true}))
              matching (->> entries
                            (filter #(some #{old-tag} (:tags %)))
                            vec)
              entry-ids (mapv :id matching)]
          (log/info "migrate-scope:" (count matching) "entries from" old_scope "to" new_scope
                    (if dry-run "(dry-run)" ""))

          (when-not dry-run
            (doseq [entry matching]
              (let [new-tags (helpers/update-scope-tag (:tags entry) old_scope new_scope)]
                (with-resilience
                  (mem-proto/update-entry! store (:id entry) {:tags new-tags}))
                (log/debug "Migrated entry" (:id entry) "tags:" (:tags entry) "->" new-tags))))

          (mcp-json {:migrated (count matching)
                     :entries entry-ids
                     :dry-run (boolean dry-run)
                     :old-scope old_scope
                     :new-scope new_scope}))))))
