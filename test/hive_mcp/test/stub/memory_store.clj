(ns hive-mcp.test.stub.memory-store
  "Atom-backed IMemoryStore stub for driver-free tests.

   Implements the hive-spi memory ports over an atom of {id -> entry}, so a
   test exercises the real code path through the port instead of erroring on a
   missing backend.

   API:
     (->stub)                  fresh store
     (install! store)          register as :default, returns store
     with-stub-store           clojure.test :each fixture (snapshot + restore)
     (seed! store entries)     bulk-add, returns the ids
     (entries store)           current {id -> entry} snapshot

   Contract mirrored from hive-mcp.memory.store.chroma:
     add-entry!     => entry id (string)
     delete-entry!  => true
     query-entries  => vector of entries, filtered then ordered
     search-similar => entries ranked by token overlap, each carrying :score
     health-check   => {:healthy? :latency-ms :backend :entry-count :errors :checked-at}
     store-status   => {:backend :configured? :entry-count :supports-search?}"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hive-mcp.memory.ids :as ids]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pure helpers
;; =============================================================================

(defn- ->name
  "Keyword or string to its string name; nil stays nil."
  [x]
  (cond (nil? x) nil (keyword? x) (name x) :else (str x)))

(defn- parse-ts
  "Epoch millis for an ISO-8601 instant or zoned datetime; nil when unparseable."
  [s]
  (when (string? s)
    (try
      (.toEpochMilli (java.time.Instant/parse s))
      (catch Exception _
        (try
          (.toEpochMilli (.toInstant (java.time.ZonedDateTime/parse s)))
          (catch Exception _ nil))))))

(defn- expiry-ms
  "Epoch millis of the entry's expiry. Accepts :expires (backend field) or
   :expires-at (contract-test field); nil when neither parses."
  [entry]
  (or (parse-ts (:expires entry)) (parse-ts (:expires-at entry))))

(defn- expired?
  "True when the entry's expiry is strictly before NOW-MS."
  [entry now-ms]
  (when-let [t (expiry-ms entry)]
    (< t now-ms)))

(defn- tag-set [entry] (into #{} (map str) (:tags entry)))

(defn- matches?
  "Does ENTRY satisfy the query-entries OPTS filters at NOW-MS?"
  [entry {:keys [type project-id project-ids tags exclude-tags include-expired?]} now-ms]
  (let [ts (tag-set entry)]
    (and (or (nil? type) (= (->name type) (->name (:type entry))))
         (or (nil? project-id) (= project-id (:project-id entry)))
         (or (empty? project-ids) (contains? (set project-ids) (:project-id entry)))
         (every? #(contains? ts (str %)) (or tags []))
         (not-any? #(contains? ts (str %)) (or exclude-tags []))
         (or include-expired? (not (expired? entry now-ms))))))

(defn- apply-order-by
  "Sort ENTRIES by [field direction]; identity when ORDER-BY is nil."
  [order-by entries]
  (if-let [[field direction] order-by]
    (vec (sort-by field (if (= direction :desc) #(compare %2 %1) compare) entries))
    (vec entries)))

(defn- tokens [s]
  (into #{} (remove str/blank?) (str/split (str/lower-case (str s)) #"[^\p{Alnum}]+")))

(defn- overlap-score
  "Count of QUERY tokens present in the entry's content + tags."
  [query entry]
  (let [qt (tokens query)]
    (if (empty? qt)
      0
      (count (set/intersection qt (into (tokens (:content entry))
                                        (mapcat tokens (:tags entry))))))))

;; =============================================================================
;; The stub
;; =============================================================================

(defrecord StubMemoryStore [state]
  ports/IMemoryStore

  (connect! [_this config]
    (swap! state assoc :connected? true :config config)
    {:success? true :errors [] :backend "stub" :metadata (or config {})})

  (disconnect! [_this]
    (swap! state assoc :connected? false)
    {:success? true :errors []})

  (connected? [_this] (boolean (:connected? @state)))

  (health-check [_this]
    {:healthy?    (boolean (:connected? @state))
     :latency-ms  0
     :backend     "stub"
     :entry-count (count (:entries @state))
     :errors      []
     :checked-at  (ids/iso-timestamp)})

  (add-entry! [_this entry]
    (let [id (or (:id entry) (ids/generate-id))
          e  (assoc entry :id id :created (or (:created entry) (ids/iso-timestamp)))]
      (swap! state assoc-in [:entries id] e)
      id))

  (get-entry [_this id] (get-in @state [:entries id]))

  (update-entry! [_this id updates]
    (when (get-in @state [:entries id])
      (get-in (swap! state update-in [:entries id] merge updates) [:entries id])))

  (delete-entry! [_this id]
    (swap! state update :entries dissoc id)
    true)

  (query-entries [_this opts]
    (let [now (System/currentTimeMillis)
          hit (->> (vals (:entries @state))
                   (filter #(matches? % opts now))
                   (apply-order-by (:order-by opts)))]
      (vec (if-let [n (:limit opts)] (take n hit) hit))))

  (search-similar [_this query-text opts]
    (let [now (System/currentTimeMillis)]
      (->> (vals (:entries @state))
           (filter #(matches? % opts now))
           (keep (fn [e]
                   (let [s (overlap-score query-text e)]
                     (when (pos? s) (assoc e :score s)))))
           (sort-by :score #(compare %2 %1))
           (take (or (:limit opts) 10))
           vec)))

  (supports-semantic-search? [_this] true)

  (cleanup-expired! [_this]
    (let [now  (System/currentTimeMillis)
          gone (->> (vals (:entries @state)) (filter #(expired? % now)) (mapv :id))]
      (swap! state update :entries #(apply dissoc % gone))
      {:deleted (count gone) :ids gone}))

  (entries-expiring-soon [_this days opts]
    (let [now     (System/currentTimeMillis)
          horizon (+ now (* (long days) 24 60 60 1000))
          project (:project-id opts)]
      (->> (vals (:entries @state))
           (filter (fn [e]
                     (when-let [t (expiry-ms e)]
                       (and (<= now t horizon)
                            (or (nil? project) (= project (:project-id e)))))))
           vec)))

  (find-duplicate [_this type content-hash opts]
    (let [project (:project-id opts)]
      (->> (vals (:entries @state))
           (filter (fn [e]
                     (and (= (->name type) (->name (:type e)))
                          (= content-hash (:content-hash e))
                          (or (nil? project) (= project (:project-id e))))))
           first)))

  (store-status [_this]
    {:backend          "stub"
     :configured?      true
     :entry-count      (count (:entries @state))
     :supports-search? true})

  (reset-store! [_this]
    (swap! state assoc :entries {})
    true)

  ports/IMemoryStoreBatch

  (get-entries [_this ids]
    (into [] (keep #(get-in @state [:entries %])) ids))

  ports/IMemoryStoreWithAnalytics

  (log-access! [this id]
    (when-let [e (get-in @state [:entries id])]
      (ports/update-entry! this id {:access-count (inc (or (:access-count e) 0))})))

  (record-feedback! [this id feedback]
    (let [k (if (= "helpful" (->name feedback)) :helpful-count :unhelpful-count)]
      (when-let [e (get-in @state [:entries id])]
        (ports/update-entry! this id {k (inc (or (k e) 0))}))))

  (get-helpfulness-ratio [_this id]
    (let [{:keys [helpful-count unhelpful-count]} (get-in @state [:entries id])
          h     (or helpful-count 0)
          u     (or unhelpful-count 0)
          total (+ h u)]
      {:helpful-count   h
       :unhelpful-count u
       :total           total
       :ratio           (if (pos? total) (double (/ h total)) 0.0)}))

  ports/IMemoryStoreMetadataWrite

  (update-metadata! [this id updates]
    (ports/update-entry! this id (dissoc updates :content :type)))

  ports/IMemoryStoreWithStaleness

  (update-staleness! [this id staleness-opts]
    (let [{:keys [alpha beta source depth staleness-alpha staleness-beta
                  staleness-source staleness-depth]} staleness-opts]
      (ports/update-entry!
       this id
       (cond-> {}
         (or alpha staleness-alpha)   (assoc :staleness-alpha (or staleness-alpha alpha))
         (or beta staleness-beta)     (assoc :staleness-beta (or staleness-beta beta))
         (or source staleness-source) (assoc :staleness-source (or staleness-source source))
         (or depth staleness-depth)   (assoc :staleness-depth (or staleness-depth depth))))))

  (get-stale-entries [_this threshold _opts]
    (->> (vals (:entries @state))
         (filter (fn [e]
                   (let [a (or (:staleness-alpha e) 1)
                         b (or (:staleness-beta e) 1)]
                     (> (/ (double b) (+ a b)) threshold))))
         vec))

  (propagate-staleness! [_this _source-id _depth] 0))

;; =============================================================================
;; Construction + registration
;; =============================================================================

(defn ->stub
  "A fresh, connected StubMemoryStore. Optional ENTRIES seed it."
  ([] (->stub nil))
  ([entries]
   (let [store (->StubMemoryStore (atom {:entries {} :connected? true}))]
     (doseq [e entries] (ports/add-entry! store e))
     store)))

(defn seed!
  "Add ENTRIES to STORE. Returns the vector of assigned ids."
  [store entries]
  (mapv #(ports/add-entry! store %) entries))

(defn entries
  "Snapshot of STORE's {id -> entry} map."
  [store]
  (:entries @(:state store)))

(defn install!
  "Register STORE as the :default memory store. Returns STORE."
  [store]
  (registry/set-store! store)
  store)

(defn with-stub-store
  "clojure.test fixture: install a fresh stub as :default for the test, then
   restore whatever store registry was in place before."
  [f]
  (let [prior (registry/registered-stores)]
    (try
      (install! (->stub))
      (f)
      (finally
        (registry/reset-registry!)
        (doseq [[k s] prior] (registry/register-store! k s))))))
