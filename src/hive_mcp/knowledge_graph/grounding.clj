(ns hive-mcp.knowledge-graph.grounding
  "Re-grounding workflow for knowledge entries.
   Verifies entries against source files and updates grounding timestamps."

  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.memory.temporal :as temporal]
            [hive-dsl.result :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

(defn entry-attr
  "Read a grounding attribute from an entry, tolerating both backend shapes.

   Chroma's metadata->entry FLATTENS :grounded-at/:source-file/:source-hash to
   top-level keys; a nested {:metadata {...}} map is the legacy shape. Blank
   strings (Chroma's metadata-defaults writes \"\") count as absent.

   This is the single lookup used by BOTH reground-entry-impl and
   backfill-grounding! — they must never disagree on what 'has a source' means."
  [entry k]
  (let [v (or (get-in entry [:metadata k]) (get entry k))]
    (when-not (str/blank? (str v))
      v)))

(defn- grounded-instant
  "Coerce a :grounded-at value to an Instant, or nil if never grounded.
   Accepts java.util.Date (what this ns stamps) or an ISO-8601 String (what
   Chroma round-trips it back as). Unparseable values are treated as absent."
  [grounded-at]
  (r/rescue nil
            (cond
              (instance? java.util.Date grounded-at) (.toInstant ^java.util.Date grounded-at)
              (string? grounded-at) (Instant/parse ^String grounded-at)
              :else nil)))

(defn needs-regrounding?
  "Returns true if entry needs re-grounding.

   An entry needs re-grounding if:
   - :grounded-at is absent/blank/unparseable (never grounded)
   - :grounded-at is older than max-age-days

   Arguments:
     entry        - Knowledge entry map with :grounded-at key
     max-age-days - Maximum age in days before regrounding is needed

   Returns:
     true if entry needs re-grounding, false otherwise"
  [entry max-age-days]
  (when entry
    (let [inst (grounded-instant (entry-attr entry :grounded-at))]
      (or (nil? inst)
          (.isBefore (.plus inst (Duration/ofDays max-age-days)) (Instant/now))))))

(defn- read-source-hash
  "Read file and compute hash.

   Arguments:
     source-path - Path to source file

   Returns:
     {:hash \"SHA256-HASH\" :exists? boolean} if file exists
     nil if file does not exist

   Note: Uses SHA-256 for content fingerprinting."
  [source-path]
  (r/rescue nil
            (let [file (io/file source-path)]
              (if (.exists file)
                (let [content (slurp file)
                      hash-bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                          (.getBytes content "UTF-8"))
                      hash-hex (apply str (map #(format "%02x" (bit-and % 0xff)) hash-bytes))]
                  {:hash hash-hex :exists? true})
                {:exists? false}))))

(defn- grounding-persisted?
  "Read the entry back and report whether :grounded-at actually survived the write.

   The Milvus record schema is a closed column set with no grounded_at column, so
   `update-entry!` accepts the key and silently discards it — the caller still gets
   a merged map that LOOKS written. That silent write-loss is what hid the broken
   grounding channel; on a schedule it would become a success report on a timer.
   This read-back is the loud signal: false means the store cannot hold grounding."
  [entry-id]
  (boolean
   (r/rescue nil
             (some-> (mem-proto/get-entry (mem-proto/get-store) entry-id)
                     (entry-attr :grounded-at)))))

(defn- reground-entry-impl
  "Pure implementation of re-grounding logic (no top-level try/catch)."
  [entry-id]
  (log/info "Regrounding entry" {:entry-id entry-id})
  (let [entry (mem-proto/get-entry (mem-proto/get-store) entry-id)]
    (if (nil? entry)
      {:status :not-found :entry-id entry-id}
      (let [source-file (some-> (entry-attr entry :source-file) str)]
        (if (nil? source-file)
          {:status :no-source-metadata :entry-id entry-id}
          (let [source-info (read-source-hash source-file)]
            (if (nil? source-info)
              {:status :hash-failed :entry-id entry-id :source-file source-file}
              (let [{:keys [hash exists?]} source-info
                    stored-hash (some-> (entry-attr entry :source-hash) str)
                    hash-differs? (and stored-hash hash (not= stored-hash hash))
                    status (cond
                             (not exists?) :source-missing
                             hash-differs? :needs-review
                             :else :regrounded)
                    update-data (when exists?
                                  {:grounded-at (java.util.Date.)
                                   :source-hash hash})
                    wrote? (boolean (and update-data (not= :source-missing status)))
                    persisted? (when wrote?
                                 (mem-proto/update-entry! (mem-proto/get-store) entry-id update-data)
                                 ;; Temporal dual-write: record reground verification
                                 (temporal/record-mutation-silent!
                                  {:entry-id   entry-id
                                   :op         :reground
                                   :data       {:status status
                                                :drift? hash-differs?
                                                :source-file source-file
                                                :current-hash hash}
                                   :previous-value {:source-hash stored-hash}})
                                 (grounding-persisted? entry-id))]
                (when (false? persisted?)
                  (log/warn "Grounding write did not survive read-back — the store dropped :grounded-at"
                            {:entry-id entry-id :source-file source-file}))
                {:status status
                 :drift? hash-differs?
                 :entry-id entry-id
                 :source-file source-file
                 :source-exists? exists?
                 :stored-hash stored-hash
                 :current-hash hash
                 :updated? wrote?
                 :persisted? persisted?}))))))))

(defn reground-entry!
  "Re-ground a single knowledge entry by verifying against source file.

   Steps:
   1. Fetch entry by ID from Chroma
   2. Check if entry has :source-file metadata
   3. Compute current source hash
   4. Compare with stored :source-hash (if any)
   5. Update entry with new grounding timestamp

   Arguments:
     entry-id - Knowledge entry ID

   Returns:
     {:status :regrounded|:needs-review|:source-missing
      :drift? boolean
      :entry-id id
      :source-file path}

   Status meanings:
     :regrounded     - Entry successfully verified and updated
     :needs-review   - Source exists but hash differs (potential drift)
     :source-missing - Source file not found"
  [entry-id]
  (let [result (r/try-effect* :kg/reground-failed (reground-entry-impl entry-id))]
    (if (r/ok? result)
      (:ok result)
      (do (log/error "Failed to reground entry" {:entry-id entry-id :error (:message result)})
          {:status :error :error (:message result) :entry-id entry-id}))))

(defn reground-batch!
  "Re-ground multiple knowledge entries.

   Arguments:
     entry-ids - Collection of knowledge entry IDs

   Returns:
     {:total int
      :by-status {status count}
      :results [{:status ... :entry-id ...}]
      :drifted-entries [entry-id]}"
  [entry-ids]
  (log/info "Regrounding batch" {:count (count entry-ids)})

  (let [results (doall (map reground-entry! entry-ids))
        by-status (frequencies (map :status results))
        drifted-entries (->> results
                             (filter :drift?)
                             (map :entry-id)
                             vec)]
    {:total (count results)
     :by-status by-status
     :results results
     :drifted-entries drifted-entries
     ;; Writes the store accepted and then discarded. Non-zero means the backend
     ;; cannot represent grounding — the batch "succeeded" and persisted nothing.
     :persistence-lost (count (filter #(false? (:persisted? %)) results))}))

(defn backfill-grounding!
  "Discover and ground all Chroma entries that have source-file metadata.

   Queries Chroma for all entries, filters to those with :source-file in
   metadata, then computes source-hash and sets grounded-at for each.

   Arguments:
     opts - Optional map with:
       :project-id    - Filter to specific project (default: all)
       :limit         - Max entries to process (default: 500)
       :force?        - Re-ground even if already grounded (default: false)
       :max-age-days  - Only re-ground if older than N days (default: 7)

   Returns:
     {:total-scanned int
      :with-source int
      :processed int
      :by-status {status count}
      :drifted-entries [entry-id]}"
  [& [{:keys [project-id limit force? max-age-days]
       :or {limit 500 force? false max-age-days 7}}]]
  (log/info "backfill-grounding! starting"
            {:project-id project-id :limit limit :force? force? :max-age-days max-age-days})
  (let [result (r/try-effect* :kg/backfill-grounding-failed
                              (let [entries (mem-proto/query-entries (mem-proto/get-store)
                                                                     {:project-id project-id
                                                                      :limit limit
                                                                      :include-expired? true})
                                    total-scanned (count entries)
                                    with-source (->> entries
                                                     (filter #(entry-attr % :source-file))
                                                     vec)
                                    needs-work (if force?
                                                 with-source
                                                 (filter #(needs-regrounding? % max-age-days) with-source))
                                    entry-ids (mapv :id needs-work)
                                    batch-result (when (seq entry-ids)
                                                   (reground-batch! entry-ids))]
                                (log/info "backfill-grounding! complete"
                                          {:total-scanned total-scanned
                                           :with-source (count with-source)
                                           :processed (count entry-ids)
                                           :by-status (:by-status batch-result)
                                           :persistence-lost (:persistence-lost batch-result)})
                                {:total-scanned total-scanned
                                 :with-source (count with-source)
                                 :processed (count entry-ids)
                                 :by-status (or (:by-status batch-result) {})
                                 :persistence-lost (or (:persistence-lost batch-result) 0)
                                 :drifted-entries (or (:drifted-entries batch-result) [])}))]
    (if (r/ok? result)
      (:ok result)
      (do (log/error "backfill-grounding! failed" {:error (:message result)})
          {:error (:message result)
           :total-scanned 0
           :processed 0}))))