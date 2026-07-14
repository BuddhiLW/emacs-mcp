(ns hive-mcp.tools.memory.scope
  "Project scope utilities for memory operations with hierarchical resolution."
  (:require [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.memory.domain :as domain]
            [hive-dsl.adt :refer [adt-case]]
            [clojure.set]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- last-path-segment
  "Extract last non-blank path segment from a directory path."
  [directory]
  (let [parts (str/split directory #"/")
        project-name (last parts)]
    (when (and project-name (not (str/blank? project-name)))
      project-name)))

(defn get-current-project-id
  "Get current project ID from directory path, returning 'global' when nil."
  ([]
   (get-current-project-id nil))
  ([directory]
   (if (and directory (not (str/blank? (str/trim directory))))
     ;; Priority 1: Check THIS directory's .hive-project.edn (no parent walk)
     (let [direct-config (kg-scope/read-direct-project-config directory)
           direct-pid (:project-id direct-config)]
       (if (and direct-pid (not= direct-pid "global"))
         ;; Found .hive-project.edn in THIS directory — use its project-id
         (let [resolved (kg-scope/resolve-project-id direct-pid)]
           (log/trace "get-current-project-id: resolved via direct .hive-project.edn ->"
                      direct-pid (when (not= resolved direct-pid)
                                   (str " (alias -> " resolved ")")))
           resolved)
         ;; No .hive-project.edn in this dir — fall back to last path segment
         (let [segment (last-path-segment directory)]
           (or (when segment (kg-scope/resolve-project-id segment))
               "global"))))
     ;; No directory = global scope (Go context pattern)
     (do
       (log/debug "get-current-project-id: no directory provided, using global scope")
       "global"))))

(defn inject-project-scope
  "Add project scope tag if not already present."
  [tags project-id]
  (let [has-scope? (some #(str/starts-with? % "scope:") tags)]
    (if has-scope?
      tags
      (if (= project-id "global")
        (conj (vec tags) "scope:global")
        (conj (vec tags) (str "scope:project:" project-id))))))

(defn make-scope-tag
  "Create a scope tag for a project-id."
  [project-id]
  (kg-scope/scope->tag project-id))

(defn matches-scope?
  "Check if entry matches the given scope filter with hierarchical support.
   Accepts either a ScopeFilter ADT value or a legacy string/set/nil value."
  [entry scope-filter]
  (let [tags (set (or (:tags entry) []))]
    (if (and (map? scope-filter) (:adt/type scope-filter))
      ;; ADT path — exhaustive dispatch
      (adt-case domain/ScopeFilter scope-filter
        :scope/all     true
        :scope/global  (contains? tags "scope:global")
        :scope/project (let [visible-tags (kg-scope/visible-scope-tags (:project-id scope-filter))]
                         (some tags visible-tags))
        :scope/auto    (let [visible-tags (kg-scope/visible-scope-tags (:project-id scope-filter))]
                         (some tags visible-tags)))
      ;; Legacy path — string/set/nil (for lifecycle.clj, decay.clj callers)
      (cond
        (or (nil? scope-filter) (= scope-filter "all"))
        true

        (= scope-filter "global")
        (contains? tags "scope:global")

        (set? scope-filter)
        (some tags scope-filter)

        :else
        (let [visible-tags (kg-scope/visible-scope-tags (str scope-filter))]
          (some tags visible-tags))))))

(defn derive-scope-filter
  "Derive scope filter from scope parameter and project-id.
   Returns a ScopeFilter ADT value."
  [scope project-id]
  (domain/parse-scope scope project-id))

(defn resolve-scope-chain
  "Walk the parent chain for a project-id, returning ordered vector from self to root."
  [project-id]
  (kg-scope/visible-scopes project-id))

(defn- collect-alias-scope-tags
  "Collect scope tags for aliases of all projects in a scope chain."
  [scope-chain]
  (reduce
   (fn [acc project-id]
     (if (= project-id "global")
       acc
       (let [config (kg-scope/get-project-config project-id)
             aliases (:aliases config)]
         (if (seq aliases)
           (into acc (map #(str "scope:project:" %) aliases))
           acc))))
   #{}
   scope-chain))

(defn expand-scope-tags
  "Expand a project-id into all scope tags it should be able to see, including aliases."
  ([project-id]
   (expand-scope-tags project-id false))
  ([project-id include-descendants?]
   (let [base-tags (if include-descendants?
                     (kg-scope/full-hierarchy-scope-tags project-id)
                     (kg-scope/visible-scope-tags project-id))
         chain (kg-scope/visible-scopes project-id)
         alias-tags (collect-alias-scope-tags chain)]
     (if (seq alias-tags)
       (clojure.set/union base-tags alias-tags)
       base-tags))))

(defn derive-hierarchy-scope-filter
  "Derive hierarchical scope filter including ancestors and aliases.
   Accepts either a ScopeFilter ADT value or a legacy string/nil."
  [scope]
  (if (and (map? scope) (:adt/type scope))
    ;; ADT path
    (adt-case domain/ScopeFilter scope
      :scope/all     nil
      :scope/global  #{"scope:global"}
      :scope/project (let [base-tags (kg-scope/visible-scope-tags (:project-id scope))
                           chain (kg-scope/visible-scopes (:project-id scope))
                           alias-tags (collect-alias-scope-tags chain)]
                       (if (seq alias-tags)
                         (clojure.set/union base-tags alias-tags)
                         base-tags))
      :scope/auto    nil)
    ;; Legacy path
    (cond
      (= scope "all") nil
      (nil? scope) nil
      (= scope "global") #{"scope:global"}
      :else
      (let [base-tags (kg-scope/visible-scope-tags scope)
            chain (kg-scope/visible-scopes scope)
            alias-tags (collect-alias-scope-tags chain)]
        (if (seq alias-tags)
          (clojure.set/union base-tags alias-tags)
          base-tags)))))

(defn matches-hierarchy-scopes?
  "Check if entry matches any of the hierarchical scope filters."
  [entry valid-scopes]
  (if (nil? valid-scopes)
    true
    (let [tags (set (or (:tags entry) []))]
      (some tags valid-scopes))))

(def ProjectId
  "Non-blank project identifier string; \"global\" is the root scope."
  [:and :string [:fn (fn [s] (not (str/blank? s)))]])

(m/=> get-current-project-id
      [:function
       [:=> [:cat] ProjectId]
       [:=> [:cat [:maybe :string]] ProjectId]])
