(ns hive-mcp.tools.memory.analytics
  "Analytics handlers for memory feedback and usage tracking."
  (:require [hive-mcp.tools.memory.core :refer [with-store]]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.format :as fmt]
            [hive-mcp.tools.core :refer [mcp-json]]
            [hive-mcp.memory.temporal :as temporal]
            [hive-mcp.protocols.memory :as mem-proto]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.time ZonedDateTime]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- xpoll-tag
  "Create a scope-transfer tag for a project-id."
  [project-id]
  (str "xpoll:project:" project-id))

(defn- has-xpoll-tag?
  "Check if entry already has scope-transfer tag for given project."
  [entry project-id]
  (let [tag (xpoll-tag project-id)]
    (some #(= % tag) (or (:tags entry) []))))

(defn- detect-cross-project-access
  "Return the accessing project-id if cross-project, nil otherwise."
  [entry accessing-project-id]
  (when (and accessing-project-id
             (not (str/blank? accessing-project-id))
             (not= accessing-project-id "global")
             (not= accessing-project-id (:project-id entry)))
    accessing-project-id))

(defn handle-log-access
  "Log access to a memory entry, incrementing access-count and updating last-accessed."
  [{:keys [id directory]}]
  (log/info "mcp-memory-log-access:" id "directory:" directory)
  (with-store
    (let [store (mem-proto/get-store)]
      (if-let [entry (mem-proto/get-entry store id)]
        (let [new-count (inc (or (:access-count entry) 0))
              accessing-project (when directory
                                  (scope/get-current-project-id directory))
              cross-project-id (detect-cross-project-access entry accessing-project)
              base-updates {:access-count new-count
                            :last-accessed (str (ZonedDateTime/now))}
              updates (if (and cross-project-id
                               (not (has-xpoll-tag? entry cross-project-id)))
                        (let [new-tags (conj (vec (or (:tags entry) []))
                                             (xpoll-tag cross-project-id))
                              tag-str (str/join "," new-tags)]
                          (log/info "Scope transfer detected: entry" id
                                    "accessed from project" cross-project-id
                                    "(origin:" (:project-id entry) ")")
                          (assoc base-updates :tags tag-str))
                        base-updates)
              updated (mem-proto/update-entry! store id updates)]
        ;; Temporal dual-write: record access pattern
          (temporal/record-mutation-silent!
           {:entry-id   id
            :op         :log-access
            :data       {:access-count new-count
                         :cross-project cross-project-id}
            :project-id (or (:project-id entry) accessing-project)})
          (mcp-json (merge (fmt/entry->json-alist updated)
                           (when cross-project-id
                             {:xpoll {:detected true
                                      :accessing_project cross-project-id
                                      :origin_project (:project-id entry)}}))))
        (mcp-json {:error "Entry not found"})))))

(defn handle-feedback
  "Submit helpfulness feedback for a memory entry."
  [{:keys [id feedback]}]
  (log/info "mcp-memory-feedback:" id feedback)
  (with-store
    (let [store (mem-proto/get-store)]
      (if-let [entry (mem-proto/get-entry store id)]
        (let [prev-helpful (or (:helpful-count entry) 0)
              prev-unhelpful (or (:unhelpful-count entry) 0)
              updates (case feedback
                        "helpful" {:helpful-count (inc prev-helpful)}
                        "unhelpful" {:unhelpful-count (inc prev-unhelpful)}
                        (throw (ex-info "Invalid feedback type" {:feedback feedback})))
              updated (mem-proto/update-entry! store id updates)]
        ;; Temporal dual-write: record feedback event
          (temporal/record-mutation-silent!
           {:entry-id       id
            :op             :feedback
            :data           {:feedback feedback
                             :new-helpful (:helpful-count updates)
                             :new-unhelpful (:unhelpful-count updates)}
            :previous-value {:helpful-count prev-helpful
                             :unhelpful-count prev-unhelpful}
            :project-id     (:project-id entry)})
          (mcp-json (fmt/entry->json-alist updated)))
        (mcp-json {:error "Entry not found"})))))

(defn handle-helpfulness-ratio
  "Get helpfulness ratio for a memory entry."
  [{:keys [id]}]
  (log/info "mcp-memory-helpfulness-ratio:" id)
  (with-store
    (let [store (mem-proto/get-store)]
      (if-let [entry (mem-proto/get-entry store id)]
        (let [helpful (or (:helpful-count entry) 0)
              unhelpful (or (:unhelpful-count entry) 0)
              total (+ helpful unhelpful)
              ratio (when (pos? total) (/ (double helpful) total))]
          (mcp-json {:ratio ratio
                     :helpful helpful
                     :unhelpful unhelpful}))
        (mcp-json {:error "Entry not found"})))))
