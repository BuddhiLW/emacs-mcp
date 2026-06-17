(ns hive-mcp.schema.memory-test
  "Tests for memory schema definitions.

   TDD via nREPL - evaluate in CIDER to run tests."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [hive-mcp.schema.memory :as mem]))

;; =============================================================================
;; MemoryType Tests
;; =============================================================================

(deftest memory-type-test
  (testing "well-known types validate (advisory registry set)"
    (is (m/validate mem/MemoryType "snippet"))
    (is (m/validate mem/MemoryType "note"))
    (is (m/validate mem/MemoryType "convention"))
    (is (m/validate mem/MemoryType "pattern"))
    (is (m/validate mem/MemoryType "decision"))
    (is (m/validate mem/MemoryType "axiom"))
    (is (m/validate mem/MemoryType "principle"))
    (is (m/validate mem/MemoryType "knowledge"))
    (is (m/validate mem/MemoryType "ingestion")))

  (testing "open registry — any safe custom token validates"
    ;; This is the behavioural change: the type field is OPEN, not a closed
    ;; enum. Unknown-but-safe tokens (e.g. 'insight') are valid and get
    ;; auto-registered with sane defaults at the handler boundary.
    (is (m/validate mem/MemoryType "insight"))
    (is (m/validate mem/MemoryType "my-custom-type"))
    (is (m/validate mem/MemoryType "adr_v2"))
    (is (m/validate mem/MemoryType "x")))

  (testing "unsafe / malformed types rejected (security gate)"
    (is (not (m/validate mem/MemoryType "bad type")))   ; whitespace
    (is (not (m/validate mem/MemoryType "type!")))      ; punctuation
    (is (not (m/validate mem/MemoryType "9lives")))     ; leading digit
    (is (not (m/validate mem/MemoryType "-foo")))       ; leading dash
    (is (not (m/validate mem/MemoryType "a;drop")))     ; injection char
    (is (not (m/validate mem/MemoryType (apply str (repeat 65 "a"))))) ; oversize
    (is (not (m/validate mem/MemoryType "")))
    (is (not (m/validate mem/MemoryType nil)))
    (is (not (m/validate mem/MemoryType 123)))))

;; =============================================================================
;; MemoryDuration Tests
;; =============================================================================

(deftest memory-duration-test
  (testing "valid durations"
    (is (m/validate mem/MemoryDuration "ephemeral"))
    (is (m/validate mem/MemoryDuration "short"))
    (is (m/validate mem/MemoryDuration "medium"))
    (is (m/validate mem/MemoryDuration "long"))
    (is (m/validate mem/MemoryDuration "permanent")))

  (testing "invalid durations rejected"
    (is (not (m/validate mem/MemoryDuration "forever")))
    (is (not (m/validate mem/MemoryDuration "")))
    (is (not (m/validate mem/MemoryDuration nil)))))

;; =============================================================================
;; MemoryTags Tests
;; =============================================================================

(deftest memory-tags-test
  (testing "valid tags"
    (is (m/validate mem/MemoryTags []))
    (is (m/validate mem/MemoryTags ["tag1"]))
    (is (m/validate mem/MemoryTags ["tag1" "tag2" "tag3"])))

  (testing "invalid tags rejected"
    (is (not (m/validate mem/MemoryTags [""])))  ; empty string tag
    (is (not (m/validate mem/MemoryTags [123]))) ; non-string tag
    (is (not (m/validate mem/MemoryTags "tag"))) ; not a vector
    (is (not (m/validate mem/MemoryTags nil)))))

;; =============================================================================
;; MemoryEntry Tests
;; =============================================================================

(deftest memory-entry-test
  (testing "minimal valid entry"
    (is (m/validate mem/MemoryEntry
                    {:id "20260131-abc123"
                     :type "decision"
                     :content "Test content"})))

  (testing "full entry with all optional fields"
    (is (m/validate mem/MemoryEntry
                    {:id "20260131-abc123"
                     :type "decision"
                     :content "Test content"
                     :tags ["architecture" "tooling"]
                     :duration "long"
                     :project-id "hive-mcp"
                     :created-at (java.util.Date.)
                     :expires "2027-01-31"
                     :content-hash "abc123def456"
                     :abstraction-level 4
                     :kg-outgoing ["edge-1" "edge-2"]
                     :kg-incoming ["edge-3"]})))

  (testing "missing required fields rejected"
    (is (not (m/validate mem/MemoryEntry
                         {:type "decision"
                          :content "Missing id"})))
    (is (not (m/validate mem/MemoryEntry
                         {:id "20260131"
                          :content "Missing type"})))
    (is (not (m/validate mem/MemoryEntry
                         {:id "20260131"
                          :type "decision"}))))  ; missing content

  (testing "unsafe type rejected (open registry, safe-token gate)"
    ;; 'invalid' is now a *valid* safe token (open registry); only unsafe
    ;; tokens are rejected.
    (is (m/validate mem/MemoryEntry
                    {:id "20260131"
                     :type "invalid"
                     :content "Test"}))
    (is (not (m/validate mem/MemoryEntry
                         {:id "20260131"
                          :type "bad type!"
                          :content "Test"})))))

;; =============================================================================
;; MemoryEntryMinimal Tests
;; =============================================================================

(deftest memory-entry-minimal-test
  (testing "valid minimal entry"
    (is (m/validate mem/MemoryEntryMinimal
                    {:type "snippet"
                     :content "(defn hello [] \"world\")"})))

  (testing "empty content rejected"
    (is (not (m/validate mem/MemoryEntryMinimal
                         {:type "snippet"
                          :content ""})))))

;; =============================================================================
;; AbstractionLevel Tests
;; =============================================================================

(deftest abstraction-level-test
  (testing "valid levels 1-4"
    (is (m/validate mem/AbstractionLevel 1))
    (is (m/validate mem/AbstractionLevel 2))
    (is (m/validate mem/AbstractionLevel 3))
    (is (m/validate mem/AbstractionLevel 4)))

  (testing "invalid levels rejected"
    (is (not (m/validate mem/AbstractionLevel 0)))
    (is (not (m/validate mem/AbstractionLevel 5)))
    (is (not (m/validate mem/AbstractionLevel -1)))
    (is (not (m/validate mem/AbstractionLevel 2.5)))))

;; =============================================================================
;; Validator Function Tests
;; =============================================================================

(deftest validator-functions-test
  (testing "valid-type? (permissive: safe tokens valid, unsafe rejected)"
    (is (mem/valid-type? "decision"))
    (is (mem/valid-type? "insight"))           ; safe custom token -> valid
    (is (not (mem/valid-type? "bad type!")))   ; unsafe -> rejected
    (is (not (mem/valid-type? nil))))

  (testing "valid-duration?"
    (is (mem/valid-duration? "long"))
    (is (not (mem/valid-duration? "forever"))))

  (testing "valid-entry?"
    (is (mem/valid-entry?
         {:id "123" :type "note" :content "test"}))
    (is (not (mem/valid-entry?
              {:type "note" :content "missing id"})))))

(comment
  ;; Run all tests in REPL
  (clojure.test/run-tests 'hive-mcp.schema.memory-test)

  ;; Run specific test
  (memory-type-test)
  (memory-entry-test))
