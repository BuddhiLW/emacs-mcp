(ns hive-mcp.tools.memory-pinning-test
  "Pinning tests for MCP memory tool handlers.

   Handler contract:
   - Success: {:type \"text\" :text \"<json>\"}
   - Error:   {:type \"text\" :text \"<plain message>\" :isError true}

   Collaborators arrive through the hive-spi memory port: a stub store is
   registered in a fixture, a fault-injecting decorator drives the error
   paths. Nothing here redefines a concrete backend."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-mcp.tools.memory :as memory]
            [hive-spi.memory.registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures + helpers
;; =============================================================================

(use-fixtures :each stub/with-stub-store)

(defn gen-test-id
  "Generate unique test ID."
  []
  (str "test-" (java.util.UUID/randomUUID)))

(defn make-test-entry
  "Create a test memory entry with defaults.

   :project-id defaults to \"global\" — that is what
   `scope/get-current-project-id` resolves to with no directory, and the
   store filters on project-id, not on the scope tag."
  [& {:keys [id type content tags duration project-id created]
      :or {id (gen-test-id)
           type "note"
           content "Test memory content"
           tags ["scope:global"]
           duration "long"
           project-id "global"
           created "2024-01-01T00:00:00Z"}}]
  {:id id
   :type type
   :content content
   :tags tags
   :duration duration
   :project-id project-id
   :created created})

(defn parse-response-text
  "Parse the JSON text from a handler response."
  [response]
  (json/read-str (:text response) :key-fn keyword))

(defn store
  "The stub registered by the fixture."
  []
  (mem-proto/get-store))

(defn seed!
  "Add ENTRIES to the registered stub. Returns the assigned ids."
  [entries]
  (stub/seed! (store) entries))

(defn with-faulty-store
  "Swap the registered store for one that throws on FAULTS ({op message}),
   run F, then restore. Returns F's value."
  [faults f]
  (let [prior (store)]
    (try
      (registry/set-store! (stub/->observing prior faults))
      (f)
      (finally (registry/set-store! prior)))))

(defn with-no-store
  "Run F with an empty store registry, then restore it."
  [f]
  (let [prior (registry/registered-stores)]
    (try
      (registry/reset-registry!)
      (f)
      (finally
        (registry/reset-registry!)
        (doseq [[k s] prior] (registry/register-store! k s))))))

;; =============================================================================
;; Test: Response Format Contract
;; =============================================================================

(deftest test-response-format-success
  (testing "Success response has correct structure"
    (let [response (memory/handle-mcp-memory-add
                    {:type "note" :content "Test content"})]
      (is (= "text" (:type response)))
      (is (string? (:text response)))
      (is (nil? (:isError response))))))

(deftest test-response-format-error
  (testing "Error response has correct structure"
    (with-no-store
      (fn []
        (let [response (memory/handle-mcp-memory-add
                        {:type "note" :content "Test content"})]
          (is (= "text" (:type response)))
          (is (string? (:text response)))
          (is (true? (:isError response))))))))

;; =============================================================================
;; Test: handle-mcp-memory-add
;; =============================================================================

(deftest test-handle-mcp-memory-add-success
  (testing "Successfully adds a memory entry"
    (let [response (memory/handle-mcp-memory-add
                    {:type "note"
                     :content "Important note"
                     :tags ["work"]
                     :duration "long"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (string? (:id parsed)))
      (is (= "note" (:type parsed)))
      (testing "and the entry really landed in the store"
        (is (some? (mem-proto/get-entry (store) (:id parsed))))))))

(deftest test-handle-mcp-memory-add-duplicate-merge
  (testing "Merges tags when duplicate found"
    (let [content "Duplicate content"
          first-resp (memory/handle-mcp-memory-add
                      {:type "note" :content content :tags ["old-tag"]})
          first-id   (:id (parse-response-text first-resp))
          response   (memory/handle-mcp-memory-add
                      {:type "note" :content content :tags ["new-tag"]})
          parsed     (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (testing "the second add resolves to the same entry"
        (is (= first-id (:id parsed))))
      (testing "no second entry was created"
        (is (= 1 (count (stub/entries (store))))))
      (testing "both tags survive the merge"
        (let [tags (set (:tags (mem-proto/get-entry (store) first-id)))]
          (is (contains? tags "old-tag"))
          (is (contains? tags "new-tag")))))))

(deftest test-handle-mcp-memory-add-no-store
  (testing "Returns error when no memory store is registered"
    (with-no-store
      (fn []
        (let [response (memory/handle-mcp-memory-add
                        {:type "note" :content "Test"})]
          (is (= "text" (:type response)))
          (is (true? (:isError response)))
          (is (= "Memory store not configured" (:text response))))))))

(deftest test-handle-mcp-memory-add-exception-handling
  (testing "Handles store exceptions gracefully"
    (with-faulty-store {:add-entry! "index write failed"}
      (fn []
        (let [response (memory/handle-mcp-memory-add
                        {:type "note" :content "Test"})]
          (is (= "text" (:type response)))
          (is (true? (:isError response)))
          (is (string? (:text response))))))))

;; =============================================================================
;; Test: handle-mcp-memory-query
;; =============================================================================

(deftest test-handle-mcp-memory-query-success
  (testing "Successfully queries memory entries"
    (seed! [(make-test-entry :id "q1" :type "note" :content "First note"
                             :tags ["scope:global"])
            (make-test-entry :id "q2" :type "note" :content "Second note"
                             :tags ["scope:global"])])
    (let [response (memory/handle-mcp-memory-query
                    {:type "note" :scope "all" :limit 10})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (vector? parsed))
      (is (= 2 (count parsed))))))

(deftest test-handle-mcp-memory-query-with-scope-filter
  (testing "Filters by scope when specified"
    (seed! [(make-test-entry :id "g1" :project-id "global" :tags ["scope:global"])
            (make-test-entry :id "p1" :project-id "test-project"
                             :tags ["scope:project:test-project"])])
    (let [response (memory/handle-mcp-memory-query
                    {:type "note" :scope "global"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 1 (count parsed)))
      (is (= "g1" (:id (first parsed)))))))

(deftest test-handle-mcp-memory-query-with-tag-filter
  (testing "Filters by tags when specified"
    (seed! [(make-test-entry :id "t1" :tags ["scope:global" "important"])
            (make-test-entry :id "t2" :tags ["scope:global"])])
    (let [response (memory/handle-mcp-memory-query
                    {:type "note" :scope "all" :tags ["important"]})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (= 1 (count parsed)))
      (is (= "t1" (:id (first parsed)))))))

(deftest test-handle-mcp-memory-query-empty-results
  (testing "Returns empty array when no matches"
    (let [response (memory/handle-mcp-memory-query {:type "note" :scope "all"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= [] parsed)))))

(deftest test-handle-mcp-memory-query-no-store
  (testing "Returns error when no memory store is registered"
    (with-no-store
      (fn []
        (let [response (memory/handle-mcp-memory-query {:type "note"})]
          (is (= "text" (:type response)))
          (is (true? (:isError response)))
          (is (= "Memory store not configured" (:text response))))))))

;; =============================================================================
;; Test: handle-mcp-memory-get-full
;; =============================================================================

(deftest test-handle-mcp-memory-get-full-success
  (testing "Successfully retrieves full entry by ID"
    (seed! [(make-test-entry :id "full-123"
                             :content "Full content here"
                             :tags ["tag1" "tag2"])])
    (let [response (memory/handle-mcp-memory-get-full {:id "full-123"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= "full-123" (:id parsed)))
      (is (= "Full content here" (:content parsed)))
      (is (= ["tag1" "tag2"] (:tags parsed))))))

(deftest test-handle-mcp-memory-get-full-not-found
  (testing "Reports not-found as a successful payload carrying :error"
    (let [response (memory/handle-mcp-memory-get-full {:id "nonexistent"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= "Entry not found" (:error parsed)))
      (is (= "nonexistent" (:id parsed))))))

(deftest test-handle-mcp-memory-get-full-no-store
  (testing "Returns error when no memory store is registered"
    (with-no-store
      (fn []
        (let [response (memory/handle-mcp-memory-get-full {:id "test-id"})]
          (is (= "text" (:type response)))
          (is (true? (:isError response)))
          (is (= "Memory store not configured" (:text response))))))))

(deftest test-handle-mcp-memory-get-full-exception-handling
  (testing "Handles store exceptions gracefully"
    (with-faulty-store {:get-entry "DB error"}
      (fn []
        (let [response (memory/handle-mcp-memory-get-full {:id "test-id"})]
          (is (= "text" (:type response)))
          (is (true? (:isError response)))
          (is (string? (:text response))))))))

;; =============================================================================
;; Test: handle-mcp-memory-query-metadata
;; =============================================================================

(deftest test-handle-mcp-memory-query-metadata-success
  (testing "Returns metadata-only format with preview"
    (seed! [(make-test-entry
             :id "meta-1"
             :content "This is a longer content that should be truncated in preview"
             :tags ["scope:global" "work"])])
    (let [response (memory/handle-mcp-memory-query-metadata
                    {:type "note" :scope "all"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (vector? parsed))
      (is (= 1 (count parsed)))
      (let [meta-entry (first parsed)]
        (is (contains? meta-entry :id))
        (is (contains? meta-entry :type))
        (is (contains? meta-entry :preview))
        (is (contains? meta-entry :tags))
        (is (contains? meta-entry :created))
        (is (not (contains? meta-entry :content)))))))

(deftest test-handle-mcp-memory-query-metadata-preview-truncation
  (testing "Truncates long content in preview"
    (seed! [(make-test-entry :id "long-1"
                             :content (apply str (repeat 200 "x"))
                             :tags ["scope:global"])])
    (let [response (memory/handle-mcp-memory-query-metadata
                    {:type "note" :scope "all"})
          parsed (parse-response-text response)
          preview (:preview (first parsed))]
      (is (<= (count preview) 103)))))

;; =============================================================================
;; Test: Duration and Expiration Handlers
;; =============================================================================

(deftest test-handle-mcp-memory-set-duration
  (testing "Sets duration on existing entry"
    (seed! [(make-test-entry :id "dur-1" :duration "medium")])
    (let [response (memory/handle-mcp-memory-set-duration
                    {:id "dur-1" :duration "permanent"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= "permanent" (:duration parsed))))))

(deftest test-handle-mcp-memory-promote
  (testing "Promotes entry to longer duration"
    (seed! [(make-test-entry :id "promo-1" :duration "medium")])
    (let [response (memory/handle-mcp-memory-promote {:id "promo-1"})]
      (is (= "text" (:type response)))
      (is (nil? (:isError response))))))

(deftest test-handle-mcp-memory-demote
  (testing "Demotes entry to shorter duration"
    (seed! [(make-test-entry :id "demo-1" :duration "long")])
    (let [response (memory/handle-mcp-memory-demote {:id "demo-1"})]
      (is (= "text" (:type response)))
      (is (nil? (:isError response))))))

;; =============================================================================
;; Test: Feedback and Access Tracking
;; =============================================================================

(deftest test-handle-mcp-memory-feedback-helpful
  (testing "Records helpful feedback"
    (seed! [(make-test-entry :id "fb-1")])
    (let [response (memory/handle-mcp-memory-feedback
                    {:id "fb-1" :feedback "helpful"})]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 1 (:helpful-count (mem-proto/get-entry (store) "fb-1")))))))

(deftest test-handle-mcp-memory-feedback-unhelpful
  (testing "Records unhelpful feedback"
    (seed! [(make-test-entry :id "fb-2")])
    (let [response (memory/handle-mcp-memory-feedback
                    {:id "fb-2" :feedback "unhelpful"})]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 1 (:unhelpful-count (mem-proto/get-entry (store) "fb-2")))))))

(deftest test-handle-mcp-memory-helpfulness-ratio
  (testing "Calculates helpfulness ratio"
    (seed! [(assoc (make-test-entry :id "ratio-1")
                   :helpful-count 3
                   :unhelpful-count 1)])
    (let [response (memory/handle-mcp-memory-helpfulness-ratio {:id "ratio-1"})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 0.75 (:ratio parsed)))
      (is (= 3 (:helpful parsed)))
      (is (= 1 (:unhelpful parsed))))))

(deftest test-handle-mcp-memory-log-access
  (testing "Increments access count"
    (seed! [(assoc (make-test-entry :id "access-1") :access-count 5)])
    (let [response (memory/handle-mcp-memory-log-access {:id "access-1"})]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 6 (:access-count (mem-proto/get-entry (store) "access-1")))))))

;; =============================================================================
;; Test: Cleanup and Expiration
;; =============================================================================

(deftest test-handle-mcp-memory-cleanup-expired
  (testing "Cleans up expired entries"
    (seed! (for [i (range 5)]
             (assoc (make-test-entry :id (str "gone-" i))
                    :expires "2000-01-01T00:00:00Z")))
    (let [response (memory/handle-mcp-memory-cleanup-expired {})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 5 (:deleted parsed))))))

(deftest test-handle-mcp-memory-expiring-soon
  (testing "Lists entries expiring soon"
    (let [soon (.toString (.plusSeconds (java.time.Instant/now) (* 2 24 60 60)))]
      (seed! [(assoc (make-test-entry :id "exp-1") :expires soon)
              (assoc (make-test-entry :id "exp-2") :expires soon)]))
    (let [response (memory/handle-mcp-memory-expiring-soon {:days 7})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (= 2 (count parsed))))))

;; =============================================================================
;; Test: Duplicate Detection
;; =============================================================================

(deftest test-handle-mcp-memory-check-duplicate-found
  (testing "Detects existing duplicate"
    ;; :directory is passed to BOTH calls on purpose — handle-add defaults it
    ;; to (ctx/current-directory) while handle-check-duplicate defaults it to
    ;; nil, so omitting it makes the two resolve different project scopes.
    (let [content "Some content"
          dir     "/tmp"
          _ (memory/handle-mcp-memory-add
             {:type "note" :content content :directory dir})
          response (memory/handle-mcp-memory-check-duplicate
                    {:type "note" :content content :directory dir})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (true? (:exists parsed)))
      (is (some? (:entry parsed))))))

(deftest test-handle-mcp-memory-check-duplicate-not-found
  (testing "Reports no duplicate when content is new"
    (let [content "New unique content"
          response (memory/handle-mcp-memory-check-duplicate
                    {:type "note" :content content})
          parsed (parse-response-text response)]
      (is (= "text" (:type response)))
      (is (nil? (:isError response)))
      (is (false? (:exists parsed)))
      (is (nil? (:entry parsed)))
      (is (= (mem-proto/content-hash content) (:content_hash parsed))))))
