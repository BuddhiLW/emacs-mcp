(ns hive-mcp.presets.core
  "Chroma vector database integration for swarm presets.

   Provides:
   - Semantic search over presets (e.g., 'find testing-focused preset')
   - Migration from .md files to Chroma
   - File-based fallback when Chroma unavailable

   Collection Schema:
     id: preset name (e.g., 'tdd', 'clarity')
     content: full markdown content
     metadata:
       - name: human-readable name
       - category: coding, testing, coordination, architecture, workflow
       - tags: comma-separated tags for filtering
       - source: 'file' or 'memory'
       - file-path: original .md file path (if from file)

   Usage:
     ;; Migrate all presets from directory
     (migrate-presets-from-dir! \"/path/to/presets\")

     ;; Semantic search
     (search-presets \"testing discipline\" :limit 3)

     ;; Get specific preset
     (get-preset \"tdd\")"
  (:require [hive-mcp.chroma.core :as chroma]
            [hive-mcp.dns.result :as result]
            [hive-weave.safe :as ws]
            [clojure-chroma-client.api :as chroma-api]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================
;;; Configuration
;;; ============================================================

(def ^:private collection-name "hive-mcp-presets")

(def ^:private category-keywords
  "Keywords to auto-detect preset category from content."
  {"testing" #"(?i)test|TDD|red.?green|assertion|mock"
   "coding" #"(?i)SOLID|clean.?code|refactor|DRY|KISS"
   "architecture" #"(?i)DDD|domain|layer|boundary|aggregate"
   "coordination" #"(?i)hivemind|swarm|spawn|coordinate|master"
   "workflow" #"(?i)workflow|task|step|process|pipeline"})

;;; ============================================================
;;; Collection Management
;;; ============================================================

(defonce ^:private collection-cache (atom nil))

(defn- try-get-existing-collection
  "Try to get existing collection. Returns nil on failure."
  []
  (result/rescue nil (ws/deref-safe! (chroma-api/get-collection collection-name) 15000)))

(defn- delete-collection!
  "Delete the presets collection. Returns true on success."
  []
  (result/rescue false
                 (when-let [coll (try-get-existing-collection)]
                   (ws/deref-safe! (chroma-api/delete-collection coll) 30000)
      ;; Give Chroma time to process the deletion
                   (Thread/sleep 50))
                 true))

(defn- create-collection-with-dimension
  "Create a new collection with the given dimension.
   Returns fresh collection reference to avoid stale cache issues."
  [dim]
  ;; Verify collection doesn't exist before creating (belt and suspenders)
  (when-let [_stale (try-get-existing-collection)]
    (log/warn "Stale collection found after delete, forcing re-delete")
    (delete-collection!))
  ;; Create the collection
  (ws/deref-safe! (chroma-api/create-collection
                   collection-name
                   {:metadata {:dimension dim
                               :created-by "hive-mcp"
                               :purpose "swarm-presets"}})
                  30000)
  ;; IMPORTANT: Get fresh reference - chroma client may cache stale references
  ;; The create-collection return value may reference the old collection ID
  (Thread/sleep 50) ;; Allow Chroma to settle
  (or (try-get-existing-collection)
      (throw (ex-info "Failed to get collection after creation"
                      {:collection collection-name :dimension dim}))))

(defn- get-or-create-collection
  "Get existing presets collection or create new one.

   Flex Embedding Dimensions:
   If the existing collection's dimension doesn't match the current provider,
   the collection is automatically recreated with the correct dimension.
   This handles provider switches (e.g., Ollama 768 → OpenRouter 4096).

   COLLECTION-AWARE: Uses chroma/get-provider-for to get the provider
   configured specifically for this collection (if any), falling back
   to global provider."
  []
  (if-let [coll @collection-cache]
    coll
    (let [provider (chroma/get-provider-for collection-name)]
      (when-not provider
        (throw (ex-info "Embedding provider not configured for presets collection."
                        {:type :no-embedding-provider
                         :collection collection-name})))
      (let [required-dim (chroma/embedding-dimension provider)
            existing (try-get-existing-collection)]
        (if existing
          ;; Check dimension match
          (let [existing-dim (get-in existing [:metadata :dimension])]
            (if (= existing-dim required-dim)
              ;; Dimension matches - reuse existing collection
              (do
                (reset! collection-cache existing)
                (log/info "Using existing presets collection:" collection-name "dimension:" existing-dim)
                existing)
              ;; Dimension mismatch - recreate collection!
              (do
                (log/warn "Embedding dimension changed:" existing-dim "→" required-dim ". Recreating collection.")
                (delete-collection!)
                (reset! collection-cache nil)
                (let [new-coll (create-collection-with-dimension required-dim)]
                  (reset! collection-cache new-coll)
                  (log/info "Recreated presets collection:" collection-name "dimension:" required-dim)
                  new-coll))))
          ;; No existing collection - create new
          (let [new-coll (create-collection-with-dimension required-dim)]
            (reset! collection-cache new-coll)
            (log/info "Created presets collection:" collection-name "dimension:" required-dim)
            new-coll))))))

(defn reset-collection-cache!
  "Reset the collection cache."
  []
  (reset! collection-cache nil))

;;; ============================================================
;;; Category Detection
;;; ============================================================

(defn- detect-category
  "Auto-detect category from preset content."
  [content]
  (let [matches (for [[cat pattern] category-keywords
                      :when (re-find pattern content)]
                  cat)]
    (or (first matches) "general")))

(defn- extract-title
  "Extract title from markdown content (first H1)."
  [content]
  (when-let [[_ title] (re-find #"^#\s+(.+?)(?:\n|$)" content)]
    (str/trim title)))

(defn- extract-tags-from-content
  "Extract potential tags from markdown content."
  [content name]
  (let [keywords (set (re-seq #"\*\*([A-Za-z-]+)\*\*" content))
        h2-headings (re-seq #"##\s+([A-Za-z ]+)" content)
        base-tags [name (detect-category content)]]
    (->> (concat base-tags
                 (map second keywords)
                 (map (comp str/lower-case str/trim second) h2-headings))
         (map str/lower-case)
         (filter #(< (count %) 30))
         (take 10)
         distinct
         (str/join ","))))

;;; ============================================================
;;; File Operations
;;; ============================================================

(defn- read-preset-file
  "Read a preset .md file and return parsed structure."
  [file-path]
  (let [content (slurp file-path)
        name (-> file-path io/file .getName (str/replace #"\.md$" ""))
        title (or (extract-title content) name)]
    {:id name
     :name name
     :title title
     :content content
     :category (detect-category content)
     :tags (extract-tags-from-content content name)
     :source "file"
     :file-path file-path}))

(defn scan-presets-dir
  "Scan directory for .md preset files."
  [dir-path]
  (let [dir (io/file dir-path)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(and (.isFile %)
                         (str/ends-with? (.getName %) ".md")
                         (not= (.getName %) "README.md")))
           (map #(.getAbsolutePath %))
           (map read-preset-file)))))

;;; ============================================================
;;; Indexing
;;; ============================================================

(defn- preset-to-document
  "Convert preset to searchable document string."
  [{:keys [name title content category tags]}]
  (str "Preset: " name "\n"
       "Title: " title "\n"
       "Category: " category "\n"
       "Tags: " tags "\n\n"
       content))

(defn index-preset!
  "Index a single preset in Chroma.
   Returns preset ID on success.

   COLLECTION-AWARE: Uses collection-specific embedding provider."
  [{:keys [id name title _content category tags source file-path] :as preset}]
  (let [coll (get-or-create-collection)
        provider (chroma/get-provider-for collection-name)
        doc-text (preset-to-document preset)
        embedding (chroma/embed-text provider doc-text)]
    (ws/deref-safe! (chroma-api/add coll [{:id id
                                          :embedding embedding
                                          :document doc-text
                                          :metadata {:name name
                                                     :title (or title name)
                                                     :category category
                                                     :tags (or tags "")
                                                     :source source
                                                     :file-path (or file-path "")}}]
                                        :upsert? true)
                    30000)
    (log/debug "Indexed preset:" id)
    id))

(defn index-presets!
  "Index multiple presets in batch.

   COLLECTION-AWARE: Uses collection-specific embedding provider."
  [presets]
  (let [coll (get-or-create-collection)
        provider (chroma/get-provider-for collection-name)
        docs (mapv preset-to-document presets)
        embeddings (chroma/embed-batch provider docs)
        records (mapv (fn [preset doc emb]
                        {:id (:id preset)
                         :embedding emb
                         :document doc
                         :metadata {:name (:name preset)
                                    :title (or (:title preset) (:name preset))
                                    :category (:category preset)
                                    :tags (or (:tags preset) "")
                                    :source (:source preset)
                                    :file-path (or (:file-path preset) "")}})
                      presets docs embeddings)]
    (ws/deref-safe! (chroma-api/add coll records :upsert? true) 30000)
    (log/info "Indexed" (count presets) "presets")
    (mapv :id presets)))

;;; ============================================================
;;; Migration
;;; ============================================================

(defn migrate-presets-from-dir!
  "Migrate all .md preset files from directory to Chroma.
   Returns {:migrated [ids] :failed [{:name :error}]}"
  [dir-path]
  (log/info "Migrating presets from:" dir-path)
  (let [presets (scan-presets-dir dir-path)]
    (if (empty? presets)
      {:migrated [] :failed [] :message "No preset files found"}
      (let [r (result/try-effect* :chroma/migrate-failed
                                  (index-presets! presets))]
        (if (result/ok? r)
          (let [ids (:ok r)]
            {:migrated ids
             :failed []
             :count (count ids)
             :message (str "Successfully migrated " (count ids) " presets")})
          {:migrated []
           :failed (mapv (fn [p] {:name (:name p) :error (:message r)}) presets)
           :message (str "Migration failed: " (:message r))})))))

;;; ============================================================
;;; Semantic Search
;;; ============================================================

(defn search-presets
  "Search presets using semantic similarity.

   Options:
     :limit - Max results (default: 5)
     :category - Filter by category

   COLLECTION-AWARE: Uses collection-specific embedding provider.

   Returns seq of {:id, :name, :title, :category, :tags, :distance, :preview}"
  [query-text & {:keys [limit category] :or {limit 5}}]
  (let [coll (get-or-create-collection)
        provider (chroma/get-provider-for collection-name)
        query-embedding (chroma/embed-text provider query-text)
        where-clause (when category {:category category})
        results (ws/deref-safe! (chroma-api/query coll query-embedding
                                                  :num-results limit
                                                  :where where-clause
                                                  :include #{:documents :metadatas :distances})
                               15000)]
    (log/debug "Preset search for:" (subs query-text 0 (min 50 (count query-text)))
               "found:" (count results))
    (mapv (fn [{:keys [id document metadata distance]}]
            {:id id
             :name (get metadata :name)
             :title (get metadata :title)
             :category (get metadata :category)
             :tags (when-let [t (get metadata :tags)]
                     (when (not= t "")
                       (str/split t #",")))
             :source (get metadata :source)
             :distance distance
             :preview (when document
                        (subs document 0 (min 300 (count document))))})
          results)))

;;; ============================================================
;;; Retrieval
;;; ============================================================

(defn get-preset
  "Get a specific preset by ID/name from Chroma.
   Returns full content or nil if not found."
  [preset-id]
  (result/rescue nil
                 (let [coll (get-or-create-collection)
                       results (ws/deref-safe! (chroma-api/get coll :ids [preset-id] :include #{:documents :metadatas}) 15000)]
                   (when-let [{:keys [id document metadata]} (first results)]
                     {:id id
                      :name (get metadata :name)
                      :title (get metadata :title)
                      :category (get metadata :category)
                      :tags (when-let [t (get metadata :tags)]
                              (when (not= t "")
                                (str/split t #",")))
                      :source (get metadata :source)
                      :_content document}))))

(defn list-presets
  "List all presets in Chroma collection.
   Returns seq of {:id :name :title :category :source}"
  []
  (result/rescue []
                 (let [coll (get-or-create-collection)
                       results (ws/deref-safe! (chroma-api/get coll :include [:metadatas]) 15000)]
                   (mapv (fn [{:keys [id metadata]}]
                           {:id id
                            :name (get metadata :name)
                            :title (get metadata :title)
                            :category (get metadata :category)
                            :source (get metadata :source)})
                         results))))

;;; ============================================================
;;; Fallback (File-based)
;;; ============================================================

(defn get-preset-from-file
  "Fallback: get preset directly from .md file."
  [preset-dir preset-name]
  (let [file-path (str preset-dir "/" preset-name ".md")]
    (when (.exists (io/file file-path))
      (read-preset-file file-path))))

;;; ============================================================
;;; Status
;;; ============================================================

(defn status
  "Get presets integration status."
  []
  (let [base {:collection collection-name
              :chroma-configured? (chroma/embedding-configured?)}]
    (if (chroma/embedding-configured?)
      (let [r (result/try-effect* :chroma/status-failed
                                  (list-presets))]
        (if (result/ok? r)
          (let [presets (:ok r)]
            (assoc base
                   :count (count presets)
                   :categories (frequencies (map :category presets))
                   :sources (frequencies (map :source presets))))
          (assoc base :error (:message r))))
      base)))

(defn delete-preset!
  "Delete a preset from the Chroma index."
  [preset-id]
  (let [coll (get-or-create-collection)]
    (ws/deref-safe! (chroma-api/delete coll :ids [preset-id]) 30000)
    (log/debug "Deleted preset from Chroma:" preset-id)
    preset-id))

;;; ============================================================
;;; Preset Core Extraction (Lazy Loading)
;;; ============================================================

(defn- extract-first-paragraph
  "Extract the first non-heading paragraph from markdown content."
  [content]
  (let [lines (str/split-lines content)
        ;; Skip title and empty lines, find first content paragraph
        para-lines (->> lines
                        (drop-while #(or (str/blank? %)
                                         (str/starts-with? % "#")))
                        (take-while #(not (or (str/blank? %)
                                              (str/starts-with? % "#")
                                              (str/starts-with? % "-")
                                              (str/starts-with? % "*")))))]
    (when (seq para-lines)
      (str/trim (str/join " " para-lines)))))

(defn- extract-key-bullets
  "Extract first N bullet points from markdown content."
  [content max-bullets]
  (let [bullet-pattern #"^[\s]*[-*]\s+(.+)$"
        lines (str/split-lines content)]
    (->> lines
         (keep #(when-let [[_ text] (re-matches bullet-pattern %)]
                  (str/trim text)))
         (take max-bullets)
         vec)))

(defn extract-preset-core
  "Extract minimal summary (~200 tokens) from a preset for lazy loading.

   Input: preset map with :_content or :content (markdown string)
   Output: {:name, :category, :summary (first paragraph), :key-points (first 3-5 bullets)}

   This enables lazy loading - lings get summaries instead of full ~1500 token content."
  [{:keys [name title category _content content]}]
  (let [md-content (or _content content "")
        summary (extract-first-paragraph md-content)
        key-points (extract-key-bullets md-content 5)]
    {:name name
     :title (or title name)
     :category (or category (detect-category md-content))
     :summary (or summary "No summary available")
     :key-points (if (seq key-points) key-points [])}))
