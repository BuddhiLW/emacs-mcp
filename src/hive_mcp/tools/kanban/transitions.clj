(ns hive-mcp.tools.kanban.transitions
  "Pure functions computing kanban state transitions.

   Inputs: existing entry + target status + project context.
   Outputs: new content map, new tag vector, slim view, project-id extracted from tags.

   No side effects. Safe to property-test."
  (:require [hive-mcp.tools.kanban.predicates :as pred]
            [hive-mcp.tools.memory.scope :as scope]
            [clojure.string :as str])
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
  "Pure: derive the new tag vector for a status transition, MERGING onto the
   entry's `existing-tags`. Only the status tag is swapped and the priority
   tag reset to `priority`; every other tag — topical, agent attribution,
   extra scope tags — is preserved. The status tag is REPLACED, never
   unioned: no token of `pred/status-tag-set` other than `new-status`
   survives. Result is distinct, canonical tags first."
  [existing-tags new-status priority project-id]
  (let [kept (->> (or existing-tags [])
                  (filter string?)
                  (remove pred/status-tag-set)
                  (remove #(str/starts-with? % "priority-")))]
    (->> (concat ["kanban" new-status (str "priority-" priority)
                  (scope/make-scope-tag project-id)]
                 kept)
         distinct
         vec)))

(defn current-status [entry] (content-val (:content entry) :status "todo"))
(defn current-priority [entry] (content-val (:content entry) :priority "medium"))
(defn current-title [entry] (content-val (:content entry) :title nil))

(defn- scope-project-tag?
  "True when tag string starts with `scope:project:`."
  [tag]
  (and (some? tag)
       (.startsWith ^String (str tag) "scope:project:")))

(defn retag-transition
  "Pure: derive the tag-only mutation for a scope retag.

   Strips every existing `scope:project:*` tag, splices the new scope,
   then applies optional `±tags` deltas. Preserves status, priority,
   content — only tags change.

   Returns {:old-project-id .. :new-project-id .. :old-tags .. :new-tags ..
            :status .. :priority .. :title ..}."
  [entry new-project-id {:keys [add-tags remove-tags]}]
  (let [old-tags    (vec (or (:tags entry) []))
        old-pid     (extract-project-id-from-tags entry)
        status      (current-status entry)
        priority    (current-priority entry)
        title       (current-title entry)
        without-scope (filterv (complement scope-project-tag?) old-tags)
        with-new      (conj without-scope (scope/make-scope-tag new-project-id))
        with-add      (into with-new (filter string? (or add-tags [])))
        remove-set    (set (or remove-tags []))
        new-tags      (vec (distinct (remove #(contains? remove-set %) with-add)))]
    {:old-project-id old-pid
     :new-project-id new-project-id
     :old-tags       old-tags
     :new-tags       new-tags
     :status         status
     :priority       priority
     :title          title}))

(defn transition
  "Pure: derive {:old-status :new-status :new-content :new-tags :title :project-id}.
   Caller supplies project-id (typically from coeffect). Tags MERGE onto the
   entry's existing tags — see compute-new-tags."
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
     :new-tags    (compute-new-tags (:tags entry) new-status priority project-id)}))

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

;; ============================================================
;; List filter predicates (token-budget filters for list-slim*)
;; ============================================================

(defn substring-ci?
  "Case-insensitive substring match. Nil-safe."
  [needle haystack]
  (boolean (and needle haystack
                (str/includes? (str/lower-case (str haystack))
                               (str/lower-case (str needle))))))

(defn entry-matches-query?
  "True iff `q` (case-insensitive substring) appears in entry's title or
   description. Blank/nil query => match all."
  [entry q]
  (or (nil? q) (and (string? q) (str/blank? q))
      (let [content (:content entry)
            title   (content-val content :title "")
            desc    (content-val content :description "")]
        (or (substring-ci? q title)
            (substring-ci? q desc)))))

(defn entry-tags-match?
  "True iff entry's tags satisfy `extra-tags` under `mode`.
   mode = :all (every tag present, AND) | :any (at least one, OR).
   Empty/nil extra-tags => match all."
  [entry extra-tags mode]
  (if (empty? extra-tags)
    true
    (let [entry-tags (set (:tags entry))]
      (case mode
        :any (boolean (some #(contains? entry-tags %) extra-tags))
        ;; default :all
        (every? #(contains? entry-tags %) extra-tags)))))

(defn entry-priority?
  "True iff entry's priority equals `priority`. Nil priority => match all."
  [entry priority]
  (or (nil? priority)
      (= priority (content-val (:content entry) :priority nil))))

(defn entry-after-ts?
  "True iff the entry's timestamp for `kind` (:created or :updated) is
   strictly greater than `threshold` (ISO-8601 string compare).
   Nil threshold => match all.

   Source order:
   - :created  → content :created, then top-level :created
   - :updated  → top-level :updated, content :updated, content :started, content :completed"
  [entry kind threshold]
  (or (nil? threshold)
      (let [content (:content entry)
            ts (case kind
                 :created (or (content-val content :created nil)
                              (:created entry))
                 :updated (or (:updated entry)
                              (content-val content :updated nil)
                              (content-val content :started nil)
                              (content-val content :completed nil))
                 nil)]
        (boolean (and ts
                      (pos? (compare (str ts) (str threshold))))))))

(defn paginate
  "Skip `offset` then take `limit`. Both optional, both positive numbers
   when provided."
  [coll offset limit]
  (cond->> coll
    (and (number? offset) (pos? offset)) (drop offset)
    (and (number? limit)  (pos? limit))  (take limit)))

(defn project-fields
  "Project a task map down to a subset of fields. `fields` is a seq of
   strings or keywords; nil/empty returns the task untouched."
  [task fields]
  (if (or (nil? fields) (empty? fields))
    task
    (select-keys task (mapv #(if (keyword? %) % (keyword (name %))) fields))))

(defn post-filters?
  "True iff any clojure-side filter (post-store-fetch) is in play. Used
   to bump the store fetch window so narrow matches aren't truncated by
   the default active-task cap."
  [{:keys [query priority created_after updated_after
           tags tag_match offset limit fields]}]
  (boolean (or (and (string? query) (not (str/blank? query)))
               priority
               created_after
               updated_after
               (and (or (= tag_match "any") (= tag_match :any)) (seq tags))
               offset
               limit
               (seq fields))))