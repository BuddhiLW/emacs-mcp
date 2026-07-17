(ns hive-mcp.knowledge-graph.disc-test
  "Integration tests for disc facade and CRUD operations.

   Tests cover:
   - Facade re-exports (kg-first-context, staleness-warnings, etc.)
   - CRUD operations via sub-namespace (add-disc!, get-disc, update-disc!, etc.)
   - touch-disc! read tracking
   - Staleness scoring integration

   Each test uses a fresh DataScript connection via fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.knowledge-graph.disc :as disc]
            [hive-mcp.knowledge-graph.disc.crud :as crud]
            [hive-mcp.knowledge-graph.disc.hash :as hash]
            [hive-mcp.knowledge-graph.store.fixtures :as fixtures]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each fixtures/datascript-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn gen-path
  "Generate a unique file path for testing."
  []
  (str "/test/path/" (subs (str (java.util.UUID/randomUUID)) 0 8) ".clj"))

;; =============================================================================
;; Hash Utility Tests
;; =============================================================================

(deftest compute-hash-test
  (testing "compute-hash returns consistent SHA-256 hex string"
    (let [hash1 (hash/compute-hash "hello world")
          hash2 (hash/compute-hash "hello world")]
      (is (string? hash1))
      (is (= 64 (count hash1)))  ; SHA-256 = 32 bytes = 64 hex chars
      (is (= hash1 hash2)))))

(deftest compute-hash-different-content-test
  (testing "compute-hash returns different hashes for different content"
    (let [hash1 (hash/compute-hash "hello")
          hash2 (hash/compute-hash "world")]
      (is (not= hash1 hash2)))))

;; =============================================================================
;; add-disc! Tests
;; =============================================================================

(deftest add-disc-creates-entity-test
  (testing "add-disc! creates a new disc entity"
    (let [path (gen-path)
          eid (crud/add-disc! {:path path :content-hash "abc123"})]
      (is (number? eid))
      (is (crud/disc-exists? path)))))

(deftest add-disc-stores-all-fields-test
  (testing "add-disc! stores all provided fields"
    (let [path (gen-path)
          analyzed-at (java.util.Date.)
          _ (crud/add-disc! {:path path
                             :content-hash "hash123"
                             :analyzed-at analyzed-at
                             :git-commit "abc1234"
                             :project-id "test-project"})
          d (disc/get-disc path)]
      (is (= path (:disc/path d)))
      (is (= "hash123" (:disc/content-hash d)))
      (is (= analyzed-at (:disc/analyzed-at d)))
      (is (= "abc1234" (:disc/git-commit d)))
      (is (= "test-project" (:disc/project-id d))))))

(deftest add-disc-defaults-project-id-test
  (testing "add-disc! defaults project-id to 'global'"
    (let [path (gen-path)
          _ (crud/add-disc! {:path path :content-hash "test"})
          d (disc/get-disc path)]
      (is (= "global" (:disc/project-id d))))))

(deftest add-disc-upserts-on-same-path-test
  (testing "add-disc! upserts when path already exists"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "hash1"})
      (crud/add-disc! {:path path :content-hash "hash2"})
      (let [d (disc/get-disc path)]
        (is (= "hash2" (:disc/content-hash d)))))))

(deftest add-disc-rejects-nil-path-test
  (testing "add-disc! rejects nil path"
    (is (thrown? AssertionError
                 (crud/add-disc! {:path nil :content-hash "test"})))))

(deftest add-disc-rejects-empty-path-test
  (testing "add-disc! rejects empty path"
    (is (thrown? AssertionError
                 (crud/add-disc! {:path "" :content-hash "test"})))))

;; =============================================================================
;; get-disc Tests (facade re-export)
;; =============================================================================

(deftest get-disc-returns-entity-test
  (testing "get-disc returns the disc entity for a path"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (let [d (disc/get-disc path)]
        (is (some? d))
        (is (= path (:disc/path d)))))))

(deftest get-disc-returns-nil-for-nonexistent-test
  (testing "get-disc returns nil for non-existent path"
    (is (nil? (disc/get-disc "/nonexistent/path.clj")))))

;; =============================================================================
;; update-disc! Tests
;; =============================================================================

(deftest update-disc-modifies-entity-test
  (testing "update-disc! modifies the disc entity"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "old-hash"})
      (crud/update-disc! path {:disc/content-hash "new-hash"})
      (let [d (disc/get-disc path)]
        (is (= "new-hash" (:disc/content-hash d)))))))

(deftest update-disc-returns-updated-entity-test
  (testing "update-disc! returns the updated entity"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "hash1"})
      (let [updated (crud/update-disc! path {:disc/git-commit "commit123"})]
        (is (= "commit123" (:disc/git-commit updated)))))))

(deftest update-disc-returns-nil-for-nonexistent-test
  (testing "update-disc! returns nil for non-existent path"
    (is (nil? (crud/update-disc! "/nonexistent/path.clj" {:disc/content-hash "new"})))))

;; =============================================================================
;; remove-disc! Tests
;; =============================================================================

(deftest remove-disc-deletes-entity-test
  (testing "remove-disc! deletes the disc entity"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (is (crud/disc-exists? path))
      (crud/remove-disc! path)
      (is (not (crud/disc-exists? path))))))

(deftest remove-disc-returns-true-on-success-test
  (testing "remove-disc! returns true on success"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (is (true? (crud/remove-disc! path))))))

(deftest remove-disc-returns-nil-for-nonexistent-test
  (testing "remove-disc! returns nil for non-existent path"
    (is (nil? (crud/remove-disc! "/nonexistent/path.clj")))))

;; =============================================================================
;; get-all-discs Tests
;; =============================================================================

(deftest get-all-discs-returns-all-test
  (testing "get-all-discs returns all disc entities"
    (let [path1 (gen-path)
          path2 (gen-path)]
      (crud/add-disc! {:path path1 :content-hash "hash1"})
      (crud/add-disc! {:path path2 :content-hash "hash2"})
      (let [all (crud/get-all-discs)]
        (is (= 2 (count all)))))))

(deftest get-all-discs-filters-by-project-test
  (testing "get-all-discs filters by project-id"
    (let [path1 (gen-path)
          path2 (gen-path)]
      (crud/add-disc! {:path path1 :content-hash "hash1" :project-id "project-a"})
      (crud/add-disc! {:path path2 :content-hash "hash2" :project-id "project-b"})
      (let [project-a-discs (crud/get-all-discs :project-id "project-a")]
        (is (= 1 (count project-a-discs)))
        (is (= "project-a" (:disc/project-id (first project-a-discs))))))))

(deftest get-all-discs-empty-when-no-discs-test
  (testing "get-all-discs returns empty when no disc entities"
    (is (empty? (crud/get-all-discs)))))

;; =============================================================================
;; disc-exists? Tests
;; =============================================================================

(deftest disc-exists-returns-true-for-existing-test
  (testing "disc-exists? returns true for existing path"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (is (true? (crud/disc-exists? path))))))

(deftest disc-exists-returns-false-for-nonexistent-test
  (testing "disc-exists? returns false for non-existent path"
    (is (false? (crud/disc-exists? "/nonexistent/path.clj")))))

;; =============================================================================
;; Volatility Integration Tests (CRUD + volatility priors)
;; =============================================================================

(deftest initial-alpha-by-volatility-test
  (testing "add-disc! sets initial alpha based on volatility class"
    ;; Stable files start with higher alpha (more confident)
    (crud/add-disc! {:path "/test-volatility/test-deps.edn" :content-hash "abc123"})
    (let [stable-disc (disc/get-disc "/test-volatility/test-deps.edn")]
      (is (= 7.0 (:disc/certainty-alpha stable-disc)))
      (is (= :stable (:disc/volatility-class stable-disc))))

    ;; Volatile files start with lower alpha (less confident)
    (crud/add-disc! {:path "/test-volatility/test.log" :content-hash "def456"})
    (let [volatile-disc (disc/get-disc "/test-volatility/test.log")]
      (is (= 3.0 (:disc/certainty-alpha volatile-disc)))
      (is (= :volatile (:disc/volatility-class volatile-disc))))

    ;; Moderate files in between
    (crud/add-disc! {:path "/test-volatility/src/core.clj" :content-hash "ghi789"})
    (let [moderate-disc (disc/get-disc "/test-volatility/src/core.clj")]
      (is (= 5.0 (:disc/certainty-alpha moderate-disc)))
      (is (= :moderate (:disc/volatility-class moderate-disc))))))

;; =============================================================================
;; touch-disc! Tests (facade re-export)
;; =============================================================================

(deftest touch-disc-creates-if-not-exists-test
  (testing "touch-disc! creates disc entity if it doesn't exist"
    (let [path (str "/touch-test/" (subs (str (java.util.UUID/randomUUID)) 0 8) ".clj")]
      (is (nil? (disc/get-disc path)))
      (disc/touch-disc! path)
      (let [d (disc/get-disc path)]
        (is (some? d))
        (is (= 1 (:disc/read-count d)))
        (is (some? (:disc/last-read-at d)))))))

(deftest touch-disc-increments-read-count-test
  (testing "touch-disc! increments read-count on existing disc"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "xyz"})
      (disc/touch-disc! path)
      (is (= 1 (:disc/read-count (disc/get-disc path))))
      (disc/touch-disc! path)
      (is (= 2 (:disc/read-count (disc/get-disc path))))
      (disc/touch-disc! path)
      (is (= 3 (:disc/read-count (disc/get-disc path)))))))

(deftest touch-disc-updates-last-read-at-test
  (testing "touch-disc! updates last-read-at timestamp"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (let [before-touch (disc/get-disc path)
            _ (Thread/sleep 10)  ;; Small delay to ensure timestamp differs
            _ (disc/touch-disc! path)
            after-touch (disc/get-disc path)]
        (is (some? (:disc/last-read-at after-touch)))
        ;; If before had last-read-at, after should be later
        (when (:disc/last-read-at before-touch)
          (is (> (.getTime ^java.util.Date (:disc/last-read-at after-touch))
                 (.getTime ^java.util.Date (:disc/last-read-at before-touch)))))))))

;; =============================================================================
;; kg-first-context Classification Tests (facade re-export)
;; =============================================================================

(deftest kg-first-context-unknown-file-needs-read-test
  (testing "kg-first-context classifies unknown files as :needs-read"
    (let [result (disc/kg-first-context ["/unknown/file1.clj"
                                         "/unknown/file2.clj"])]
      (is (= 2 (count (:needs-read result))))
      (is (contains? (set (:needs-read result)) "/unknown/file1.clj"))
      (is (contains? (set (:needs-read result)) "/unknown/file2.clj"))
      (is (empty? (:kg-known result)))
      (is (empty? (:stale result))))))

(deftest kg-first-context-fresh-file-kg-known-test
  (testing "kg-first-context classifies fresh tracked files as :kg-known"
    (let [path (gen-path)]
      ;; Add a disc with high certainty (recent, no hash mismatch)
      (crud/add-disc! {:path path :content-hash "abc123"})
      ;; Touch it to set last-read-at to now
      (disc/touch-disc! path)

      (let [result (disc/kg-first-context [path])]
        ;; File should be in kg-known with metadata
        (is (= 1 (count (:kg-known result))))
        (is (contains? (:kg-known result) path))
        (is (empty? (:needs-read result)))
        (is (empty? (:stale result)))
        ;; Summary should reflect counts
        (is (= 1 (:known (:summary result))))))))

(deftest kg-first-context-stale-file-test
  (testing "kg-first-context classifies stale files correctly"
    (let [path (gen-path)]
      ;; Add a disc and simulate staleness by setting old last-read-at
      (crud/add-disc! {:path path :content-hash "old-hash"})
      ;; Manually update to make it stale (old last-read-at)
      (let [old-date (java.util.Date. (- (System/currentTimeMillis)
                                         (* 45 24 60 60 1000)))] ;; 45 days ago
        (crud/update-disc! path
                           {:disc/last-read-at old-date
                            :disc/analyzed-at old-date}))

      (let [result (disc/kg-first-context [path])]
        ;; File should be in stale list
        (is (contains? (set (:stale result)) path))
        (is (= 1 (:stale (:summary result))))))))

(deftest kg-first-context-mixed-files-test
  (testing "kg-first-context handles mixed fresh/stale/unknown files"
    (let [fresh-path (gen-path)
          stale-path (gen-path)
          unknown-path (gen-path)]
      ;; Setup fresh file
      (crud/add-disc! {:path fresh-path :content-hash "fresh"})
      (disc/touch-disc! fresh-path)

      ;; Setup stale file
      (crud/add-disc! {:path stale-path :content-hash "stale"})
      (let [old-date (java.util.Date. (- (System/currentTimeMillis)
                                         (* 45 24 60 60 1000)))]
        (crud/update-disc! stale-path
                           {:disc/last-read-at old-date
                            :disc/analyzed-at old-date}))

      (let [result (disc/kg-first-context [fresh-path stale-path unknown-path])]
        (is (= 1 (:known (:summary result))))
        (is (= 1 (:stale (:summary result))))
        (is (= 1 (:needs-read (:summary result))))
        (is (= 3 (:total (:summary result))))))))

(deftest kg-first-context-staleness-threshold-test
  (testing "kg-first-context respects custom staleness-threshold"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "x"})
      (disc/touch-disc! path)

      ;; With very loose threshold (1.0), everything is considered fresh
      (let [loose-result (disc/kg-first-context [path]
                                                {:staleness-threshold 1.0})]
        (is (= 1 (:known (:summary loose-result))))))))

(deftest kg-first-context-filters-invalid-paths-test
  (testing "kg-first-context filters out nil and empty paths"
    (let [valid-path (gen-path)]
      (crud/add-disc! {:path valid-path :content-hash "test"})
      (disc/touch-disc! valid-path)

      (let [result (disc/kg-first-context [valid-path nil "" valid-path])]
        ;; Should deduplicate and filter invalid
        (is (= 1 (:total (:summary result))))))))

;; =============================================================================
;; Staleness Warnings Tests (facade re-export)
;; =============================================================================

(deftest staleness-warnings-returns-empty-for-fresh-files-test
  (testing "staleness-warnings returns empty for fresh files"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "x"})
      (disc/touch-disc! path)
      (let [warnings (disc/staleness-warnings [path])]
        (is (empty? warnings))))))

(deftest staleness-warnings-returns-empty-for-unknown-files-test
  (testing "staleness-warnings returns empty for unknown files (zero noise)"
    (let [warnings (disc/staleness-warnings ["/completely/unknown.clj"])]
      (is (empty? warnings)))))

(deftest staleness-warnings-returns-warnings-for-stale-files-test
  (testing "staleness-warnings returns warnings for stale tracked files"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "old"})
      (let [old-date (java.util.Date. (- (System/currentTimeMillis)
                                         (* 45 24 60 60 1000)))]
        (crud/update-disc! path
                           {:disc/last-read-at old-date
                            :disc/analyzed-at old-date}))

      (let [warnings (disc/staleness-warnings [path])]
        (is (= 1 (count warnings)))
        (is (= path (:path (first warnings))))
        (is (> (:staleness (first warnings)) 0.3))
        (is (string? (:message (first warnings))))))))

(deftest format-staleness-warnings-test
  (testing "format-staleness-warnings produces markdown output"
    (let [warnings [{:path "/a.clj" :staleness 0.5 :message "File /a.clj is stale"}
                    {:path "/b.clj" :staleness 0.7 :message "File /b.clj is stale"}]
          formatted (disc/format-staleness-warnings warnings)]
      (is (string? formatted))
      (is (.contains formatted "file-level Staleness Warnings"))
      (is (.contains formatted "/a.clj"))
      (is (.contains formatted "/b.clj")))))

(deftest format-staleness-warnings-nil-for-empty-test
  (testing "format-staleness-warnings returns nil for empty"
    (is (nil? (disc/format-staleness-warnings [])))))

;; =============================================================================
;; Staleness Score Tests (facade re-export)
;; =============================================================================

(deftest staleness-score-fresh-file-test
  (testing "staleness-score returns low score for fresh files"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (disc/touch-disc! path)
      (let [d (disc/get-disc path)
            score (disc/staleness-score d)]
        ;; Fresh file should have low staleness
        (is (< score 0.3))))))

(deftest staleness-score-old-file-test
  (testing "staleness-score returns higher score for old files"
    (let [path (gen-path)]
      (crud/add-disc! {:path path :content-hash "test"})
      (let [old-date (java.util.Date. (- (System/currentTimeMillis)
                                         (* 45 24 60 60 1000)))]
        (crud/update-disc! path {:disc/last-read-at old-date
                                 :disc/analyzed-at old-date}))
      (let [d (disc/get-disc path)
            score (disc/staleness-score d)]
        ;; Old file should have higher staleness
        (is (> score 0.3))))))

;; =============================================================================
;; Top Stale Files Tests (facade re-export)
;; =============================================================================

(deftest top-stale-files-returns-sorted-list-test
  (testing "top-stale-files returns files sorted by staleness"
    ;; Create files with varying staleness
    (let [path1 (gen-path)
          path2 (gen-path)]
      (crud/add-disc! {:path path1 :content-hash "h1"})
      (crud/add-disc! {:path path2 :content-hash "h2"})
      ;; Make path1 very old (more stale)
      (let [very-old (java.util.Date. (- (System/currentTimeMillis)
                                         (* 60 24 60 60 1000)))] ;; 60 days
        (crud/update-disc! path1 {:disc/last-read-at very-old
                                  :disc/analyzed-at very-old}))
      ;; Make path2 moderately old
      (let [moderately-old (java.util.Date. (- (System/currentTimeMillis)
                                               (* 15 24 60 60 1000)))] ;; 15 days
        (crud/update-disc! path2 {:disc/last-read-at moderately-old
                                  :disc/analyzed-at moderately-old}))

      (let [top-stale (disc/top-stale-files :n 10 :threshold 0.3)]
        ;; Should have at least one stale file
        (when (seq top-stale)
          ;; Results should be sorted by score descending
          (is (apply >= (map :score top-stale))))))))
