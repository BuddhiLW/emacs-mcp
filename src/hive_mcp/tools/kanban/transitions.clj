(ns hive-mcp.tools.kanban.transitions
  "Pure functions computing kanban state transitions.

   Inputs: existing entry + target status + project context.
   Outputs: new content map, new tag vector, slim view, project-id extracted from tags.

   No side effects. Safe to property-test."
  (:require [hive-mcp.tools.kanban.predicates :as pred]
            [hive-mcp.tools.memory.scope :as scope])
  (:import [java.time ZonedDateTime]
           [java.time.format DateTimeFormatter]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private timestamp-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn kanban-timestamp
  "ISO-8601 timestamp string for kanban created/started/completed fields."
  []
  (.format (ZonedDateTime/now) timestamp-format))

(defn content-val
  "Get value from a content map, trying keyword then string key, with default.
   Tolerates JSON-roundtripped maps where keys may be strings."
  [content k default]
  (if-let [v (get content k)]
    v
    (if-let [v2 (get content (name k))]
      v2
      default)))

(defn build-kanban-tags
  "Tag vector for a kanban entry: [\"kanban\" status \"priority-<p>\" scope]."
  [status priority project-id]
  (conj ["kanban" status (str "priority-" priority)]
        (scope/make-scope-tag project-id)))

(defn extract-project-id-from-tags
  "Pull the bare project id out of an entry's tag vector (drops the
   `scope:project:` prefix). Returns nil if no scope tag present."
  [entry]
  (some (fn [tag]
          (when-let [s (when (some? tag) (str tag))]
            (when (.startsWith ^String s "scope:project:")
              (subs s (count "scope:project:")))))
        (:tags entry)))

(defn task->slim
  "Project a kanban entry to its slim public shape."
  ([entry] (task->slim entry false))
  ([entry multi-project?]
   (let [content (:content entry)]
     (cond-> {:id (:id entry)
              :title    (content-val content :title nil)
              :status   (content-val content :status nil)
              :priority (content-val content :priority nil)}
       multi-project? (assoc :project (extract-project-id-from-tags entry))))))

(defn compute-new-content
  "Pure: derive the new content map for a status transition.
   - `doing` stamps `:started`
   - `done`  stamps `:completed`
   Other transitions only update `:status`."
  [content new-status]
  (let [stamped (cond-> (assoc content :status new-status)
                  (= new-status "doing") (assoc :started   (kanban-timestamp))
                  (= new-status "done")  (assoc :completed (kanban-timestamp)))]
    stamped))

(defn compute-new-tags
  "Pure: derive the new tag vector for a status transition."
  [new-status priority project-id]
  (build-kanban-tags new-status priority project-id))

(defn current-status [entry] (content-val (:content entry) :status "todo"))
(defn current-priority [entry] (content-val (:content entry) :priority "medium"))
(defn current-title [entry] (content-val (:content entry) :title nil))

(defn transition
  "Pure: derive {:old-status :new-status :new-content :new-tags :title :project-id}.
   Caller supplies project-id (typically from coeffect)."
  [entry new-status project-id]
  (let [new-status (pred/normalize-status new-status)
        old-status (current-status entry)
        priority   (current-priority entry)
        title      (current-title entry)]
    {:old-status  old-status
     :new-status  new-status
     :priority    priority
     :title       title
     :project-id  project-id
     :new-content (compute-new-content (:content entry) new-status)
     :new-tags    (compute-new-tags new-status priority project-id)}))

(defn sort-by-priority-then-created
  "Stable order: priority asc, then id asc (id encodes creation timestamp)."
  [tasks]
  (sort (fn [a b]
          (let [pa (get pred/priority-order
                        (content-val a :priority "medium") 1)
                pb (get pred/priority-order
                        (content-val b :priority "medium") 1)]
            (if (zero? (compare pa pb))
              (compare (str (:id a)) (str (:id b)))
              (compare pa pb))))
        tasks))

(defn effective-dir
  "Resolve directory: explicit > current request context."
  [directory current-directory-fn]
  (or directory (current-directory-fn)))
