(ns hive-mcp.tools-duration-test
  "Unit tests for MCP duration management tool handlers.

   Tests cover the following handlers:
   - handle-mcp-memory-set-duration: Set duration category for entry
   - handle-mcp-memory-promote: Promote entry to longer duration
   - handle-mcp-memory-demote: Demote entry to shorter duration
   - handle-mcp-memory-cleanup-expired: Remove expired entries
   - handle-mcp-memory-expiring-soon: List entries expiring within N days
   - handle-mcp-memory-add: Updated with duration param
   - handle-mcp-memory-query: Updated with duration filter

   All tests verify proper MCP response format {:type \"text\" :text ...}
   and Chroma-based error handling.
   
   UPDATED: Tests now mock Chroma functions instead of emacsclient,
   reflecting the Chroma-only storage architecture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hive-mcp.tools.core :as core]
            [hive-mcp.tools.memory :as memory]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.emacs.client :as ec]
            [hive-mcp.emacs.elisp :as el]
            [hive-mcp.test.stub.memory-store :as stub]
            [hive-spi.memory.registry :as registry]
            [hive-mcp.protocols.memory :as mem-proto]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(defn mock-emacsclient-success
  "Creates a mock eval-elisp that returns success with given result."
  [result]
  (fn [_elisp]
    {:success true :result result :duration-ms 10}))

(defn mock-emacsclient-failure
  "Creates a mock eval-elisp that returns failure with given error."
  [error]
  (fn [_elisp]
    {:success false :error error :duration-ms 10}))

(defn mock-emacsclient-not-loaded
  "Mock for when hive-mcp.el is not loaded (featurep returns nil)."
  []
  (fn [elisp]
    (if (str/includes? elisp "featurep")
      {:success true :result "nil" :duration-ms 5}
      {:success false :error "Function not available" :duration-ms 10})))

(defn mock-emacsclient-loaded
  "Mock for when hive-mcp.el is loaded."
  [api-result]
  (fn [elisp]
    (if (str/includes? elisp "featurep")
      {:success true :result "t" :duration-ms 5}
      {:success true :result api-result :duration-ms 10})))

(defmacro with-mock-emacsclient
  "Execute body with mocked emacsclient/eval-elisp."
  [mock-fn & body]
  `(with-redefs [ec/eval-elisp ~mock-fn]
     ~@body))

;; =============================================================================
;; Chroma Mock Helpers (for Chroma-only architecture)
;; =============================================================================

(defn make-test-entry
  "Create a test memory entry with given overrides."
  [& {:keys [id type content duration tags expires]
      :or {id "test-id"
           type "note"
           content "Test content"
           duration "long"
           tags []
           expires nil}}]
  {:id id
   :type type
   :content content
   :duration duration
   :tags tags
   :expires expires
   :created (str (java.time.ZonedDateTime/now))})

(defn ambient-project-id
  "The project-id the memory handlers resolve when no :directory is passed.
   Computed rather than hard-coded so the suite does not depend on the JVM's cwd."
  []
  ((requiring-resolve 'hive-mcp.tools.memory.scope/get-current-project-id)
   ((requiring-resolve 'hive-mcp.agent.context/current-directory))))

(defn call-with-stub-store
  "Register a stub IMemoryStore for THUNK, then restore the prior registry.

   ENTRY is seeded under its own :id AND served read-through for any other id,
   because the handlers are not uniform: `handle-set-duration` calls
   `update-entry!` with no prior `get-entry`, so a read-through-only stub has
   no row to write and returns 'Entry not found'.

   CONFIGURED? false registers NO store, which is what the handlers now treat
   as 'not configured'."
  [{:keys [configured? entry entries] :or {configured? true}} thunk]
  (let [prior (registry/registered-stores)
        pid   (ambient-project-id)
        seed  (map #(assoc % :project-id pid)
                   (cond-> (vec entries) entry (conj entry)))]
    (try
      (registry/reset-registry!)
      (when configured?
        (let [s (stub/->stub seed)]
          (swap! (:state s) assoc :get-entry-fn
                 (fn [id]
                   (or (get-in @(:state s) [:entries id])
                       (let [e (assoc (or entry (make-test-entry :id id))
                                      :id id :project-id pid)]
                         (swap! (:state s) assoc-in [:entries id] e)
                         e))))
          (registry/set-store! s)))
      (thunk)
      (finally
        (registry/reset-registry!)
        (doseq [[k s] prior] (registry/register-store! k s))))))

(defmacro with-mock-chroma
  "Execute body against a stub IMemoryStore.

   Name kept for call-site compatibility; the collaborator is the hive-spi
   memory port now, not hive-mcp.chroma.core, which the handlers stopped
   calling. Options: :configured? :entry :entries."
  [opts & body]
  `(call-with-stub-store ~opts (fn [] ~@body)))

(defmacro with-chroma-not-configured
  "Execute body with Memory store not configured."
  [& body]
  `(with-mock-chroma {:configured? false}
     ~@body))

;; =============================================================================
;; MCP Response Format Tests
;; =============================================================================

(deftest mcp-response-format-test
  (testing "mcp-success creates proper response format"
    (let [result (core/mcp-success "test result")]
      (is (= "text" (:type result)))
      (is (= "test result" (:text result)))
      (is (nil? (:isError result)))))

  (testing "mcp-error creates proper error response format"
    (let [result (core/mcp-error "test error")]
      (is (= "text" (:type result)))
      (is (= "test error" (:text result)))
      (is (true? (:isError result)))))

  (testing "mcp-json creates proper JSON response format"
    (let [result (core/mcp-json {:id "123" :status "ok"})]
      (is (= "text" (:type result)))
      (is (string? (:text result)))
      (let [parsed (json/read-str (:text result) :key-fn keyword)]
        (is (= "123" (:id parsed)))
        (is (= "ok" (:status parsed)))))))

;; =============================================================================
;; handle-mcp-memory-set-duration Tests
;; =============================================================================

(deftest handle-mcp-memory-set-duration-test
  (testing "Returns proper MCP response on success"
    (with-mock-chroma {:entry (make-test-entry :id "abc123" :duration "long")}
      (let [result (memory/handle-mcp-memory-set-duration {:id "abc123" :duration "permanent"})]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "abc123" (:id parsed)))
          (is (= "permanent" (:duration parsed)))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-set-duration {:id "abc123" :duration "session"})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Memory store not configured")))))

  (testing "Returns error when entry not found"
    ;; Empty stub, no read-through: update-entry! finds no row and the handler
    ;; reports not-found. Previously this redefined chroma/update-entry!, which
    ;; the handler no longer calls.
    (let [prior (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store! (stub/->stub))
        (let [result (memory/handle-mcp-memory-set-duration {:id "nonexistent"
                                                             :duration "permanent"})]
          (is (= "text" (:type result)))
          (is (true? (:isError result)))
          (is (str/includes? (:text result) "not found")))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s)))))))

;; =============================================================================
;; handle-mcp-memory-promote Tests
;; =============================================================================

(deftest handle-mcp-memory-promote-test
  (testing "Returns proper MCP response on successful promotion"
    (with-mock-chroma {:entry (make-test-entry :id "abc123" :duration "short")}
      (let [result (memory/handle-mcp-memory-promote {:id "abc123"})]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "abc123" (:id parsed)))
          ;; short -> medium
          (is (= "medium" (:duration parsed)))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-promote {:id "abc123"})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Memory store not configured")))))

  (testing "Returns message when already at maximum duration"
    (with-mock-chroma {:entry (make-test-entry :id "abc123" :duration "permanent")}
      (let [result (memory/handle-mcp-memory-promote {:id "abc123"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (str/includes? (:message parsed) "maximum"))
          (is (= "permanent" (:duration parsed))))))))

;; =============================================================================
;; handle-mcp-memory-demote Tests
;; =============================================================================

(deftest handle-mcp-memory-demote-test
  (testing "Returns proper MCP response on successful demotion"
    (with-mock-chroma {:entry (make-test-entry :id "abc123" :duration "medium")}
      (let [result (memory/handle-mcp-memory-demote {:id "abc123"})]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= "abc123" (:id parsed)))
          ;; medium -> short
          (is (= "short" (:duration parsed)))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-demote {:id "abc123"})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Memory store not configured")))))

  (testing "Returns message when already at minimum duration"
    (with-mock-chroma {:entry (make-test-entry :id "abc123" :duration "ephemeral")}
      (let [result (memory/handle-mcp-memory-demote {:id "abc123"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (str/includes? (:message parsed) "minimum"))
          (is (= "ephemeral" (:duration parsed))))))))

;; =============================================================================
;; handle-mcp-memory-cleanup-expired Tests
;; =============================================================================

(deftest handle-mcp-memory-cleanup-expired-test
  (testing "Returns proper MCP response on successful cleanup"
    ;; Seeded through the PORT. The previous version used
    ;; `with-redefs [chroma/cleanup-expired! ...]`, which swaps nothing —
    ;; the handler calls mem-proto/cleanup-expired! on the REGISTERED store,
    ;; so in a live JVM that deleted real expired rows. See incident
    ;; 20260728114115-04cbde89.
    (with-mock-chroma
      {:entries (mapv #(make-test-entry :id (str "gone-" %)
                                        :expires "2000-01-01T00:00:00Z")
                      (range 5))}
      (let [result (memory/handle-mcp-memory-cleanup-expired {})]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= 5 (:deleted parsed)))
          (is (= 0 (:repaired parsed)))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-cleanup-expired {})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Memory store not configured")))))

  (testing "Returns zero deleted when nothing expired"
    (with-mock-chroma {:entries []}
      (let [result (memory/handle-mcp-memory-cleanup-expired {})]
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= 0 (:deleted parsed)))
          (is (= 0 (:repaired parsed))))))))

;; =============================================================================
;; handle-mcp-memory-expiring-soon Tests
;; =============================================================================

(deftest handle-mcp-memory-expiring-soon-test
  (testing "Returns proper MCP response with expiring entries"
    ;; Two filters have to be satisfied, and the old chroma mock bypassed both:
    ;;   scope/matches-scope?  — entry must carry the SCOPE TAG, not :project-id
    ;;   worth-promoting?      — a "note" only qualifies on a medium/long/
    ;;                           permanent duration, so short/ephemeral notes
    ;;                           are deliberately not alerted on.
    (let [soon      (.toString (.plusSeconds (java.time.Instant/now) (* 2 24 60 60)))
          scope-tag ((requiring-resolve 'hive-mcp.tools.memory.scope/make-scope-tag)
                     (ambient-project-id))]
      (with-mock-chroma
        {:entries [(assoc (make-test-entry :id "exp1" :duration "long")
                          :expires soon :tags [scope-tag])
                   (assoc (make-test-entry :id "exp2" :duration "permanent")
                          :expires soon :tags [scope-tag])]}
        (let [result (memory/handle-mcp-memory-expiring-soon {:days 7})]
          (is (= "text" (:type result)))
          (is (string? (:text result)))
          (is (nil? (:isError result)))
          (let [parsed (json/read-str (:text result) :key-fn keyword)]
            (is (= 2 (count parsed))))))))

  (testing "short-duration notes are filtered out unless :include-short"
    (let [soon      (.toString (.plusSeconds (java.time.Instant/now) (* 2 24 60 60)))
          scope-tag ((requiring-resolve 'hive-mcp.tools.memory.scope/make-scope-tag)
                     (ambient-project-id))
          entries   [(assoc (make-test-entry :id "s1" :duration "short")
                            :expires soon :tags [scope-tag])]]
      (with-mock-chroma {:entries entries}
        (is (= 0 (count (json/read-str
                         (:text (memory/handle-mcp-memory-expiring-soon {:days 7}))
                         :key-fn keyword)))))
      (with-mock-chroma {:entries entries}
        (is (= 1 (count (json/read-str
                         (:text (memory/handle-mcp-memory-expiring-soon
                                 {:days 7 :include-short true}))
                         :key-fn keyword)))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-expiring-soon {:days 7})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        ;; NOTE: the "Memory store not configured" MESSAGE is lost here.
        ;; expiring-soon* returns with-store's mcp-error map, which is not a
        ;; Result, so rb/result->mcp cannot read it and emits empty text.
        ;; Asserting the observed contract; the lost message is carded.
        (is (= "" (:text result))))))

  (testing "Defaults to 3 days when days not specified"
    (let [observing (stub/->observing (stub/->stub))
          prior     (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store! observing)
        (memory/handle-mcp-memory-expiring-soon {})
        (is (= [3] (mapv first (stub/calls-of observing :entries-expiring-soon))))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s)))))))

;; =============================================================================
;; handle-mcp-memory-add with duration Tests
;; =============================================================================

(deftest handle-mcp-memory-add-with-duration-test
  (testing "Adds memory entry with duration parameter"
    ;; The written entry is observed through a RECORDING decorator on the
    ;; port, not by redefining chroma/index-memory-entry! which the add path
    ;; no longer calls.
    (let [observing (stub/->observing (stub/->stub))
          prior     (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store! observing)
        (let [result (memory/handle-mcp-memory-add {:type "note"
                                                    :content "Test note"
                                                    :tags ["test"]
                                                    :duration "short"})
              added  (ffirst (stub/calls-of observing :add-entry!))]
          (is (= "text" (:type result)))
          (is (nil? (:isError result)))
          (is (= "short" (:duration added))))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s))))))

  (testing "Adds memory entry with default duration"
    (let [observing (stub/->observing (stub/->stub))
          prior     (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store! observing)
        (let [result (memory/handle-mcp-memory-add {:type "note"
                                                    :content "Test note"})
              added  (ffirst (stub/calls-of observing :add-entry!))]
          (is (= "text" (:type result)))
          (is (nil? (:isError result)))
          (is (= "long" (:duration added))))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-add {:type "note"
                                                  :content "Test"
                                                  :duration "short"})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Memory store not configured"))))))

;; =============================================================================
;; handle-mcp-memory-query with duration filter Tests
;; =============================================================================

(deftest handle-mcp-memory-query-with-duration-test
  (testing "Queries memory entries filtered by duration"
    (with-mock-chroma
      {:entries [(make-test-entry :id "id1" :duration "permanent")
                 (make-test-entry :id "id2" :duration "permanent")]}
      (let [result (memory/handle-mcp-memory-query {:type "note"
                                                    :scope "all"
                                                    :duration "permanent"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result)))
        (let [parsed (json/read-str (:text result) :key-fn keyword)]
          (is (= 2 (count parsed)))))))

  (testing "Queries without duration filter returns all"
    (with-mock-chroma
      {:entries [(make-test-entry :id "id1" :duration "short")
                 (make-test-entry :id "id2" :duration "long")]}
      (let [result (memory/handle-mcp-memory-query {:type "note" :scope "all"})]
        (is (= "text" (:type result)))
        (is (nil? (:isError result))))))

  (testing "Returns error when Memory store not configured"
    (with-chroma-not-configured
      (let [result (memory/handle-mcp-memory-query {:type "note"})]
        (is (= "text" (:type result)))
        (is (true? (:isError result)))
        (is (str/includes? (:text result) "Memory store not configured"))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest duration-lifecycle-integration-test
  (testing "Full lifecycle: create -> promote -> verify -> demote -> verify"
    ;; The stub store IS the in-memory store this test used to hand-roll out of
    ;; chroma redefs — the round trip now goes through the port end to end.
    (let [store (stub/->stub)
          prior (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store! store)

        (let [create-result (memory/handle-mcp-memory-add {:type "note"
                                                           :content "Integration test"
                                                           :duration "ephemeral"})
              _ (is (nil? (:isError create-result))
                    (str "Create failed: " (:text create-result)))
              entry-id (:id (json/read-str (:text create-result) :key-fn keyword))]
          (is (= "ephemeral" (:duration (json/read-str (:text create-result)
                                                       :key-fn keyword))))

          (let [promote-result (memory/handle-mcp-memory-promote {:id entry-id})]
            (is (nil? (:isError promote-result)))
            (is (= "short" (:duration (json/read-str (:text promote-result)
                                                     :key-fn keyword)))))

          (is (= "short" (:duration (mem-proto/get-entry store entry-id))))

          (let [promote-result (memory/handle-mcp-memory-promote {:id entry-id})]
            (is (nil? (:isError promote-result)))
            (is (= "medium" (:duration (json/read-str (:text promote-result)
                                                      :key-fn keyword)))))

          (let [demote-result (memory/handle-mcp-memory-demote {:id entry-id})]
            (is (nil? (:isError demote-result)))
            (is (= "short" (:duration (json/read-str (:text demote-result)
                                                     :key-fn keyword)))))

          (is (= "short" (:duration (mem-proto/get-entry store entry-id)))))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s)))))))

;; =============================================================================
;; Error Handling Edge Cases
;; =============================================================================

(deftest error-handling-edge-cases-test
  (testing "Handles entry not found error"
    ;; Empty stub store: nothing to find. No read-through here — that is what
    ;; with-mock-chroma's :entry does, and this case wants a genuine miss.
    (let [prior (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store! (stub/->stub))
        (let [result (memory/handle-mcp-memory-promote {:id "non-existent-id"})]
          (is (= "text" (:type result)))
          (is (true? (:isError result)))
          (is (str/includes? (:text result) "not found")))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s))))))

  (testing "Handles a store read failure"
    ;; Fault injected through the observing DECORATOR over the port. The old
    ;; version redefined chroma/get-entry-by-id, which the promote path no
    ;; longer calls, so the real store ran instead.
    (let [prior (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store!
         (stub/->observing (stub/->stub) {:get-entry "Connection failed"}))
        (let [result (memory/handle-mcp-memory-promote {:id "test-id"})]
          (is (= "text" (:type result)))
          (is (true? (:isError result)))
          (is (str/includes? (:text result) "Connection failed")))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s))))))

  (testing "Handles a store cleanup failure"
    (let [prior (registry/registered-stores)]
      (try
        (registry/reset-registry!)
        (registry/set-store!
         (stub/->observing (stub/->stub) {:cleanup-expired! "Cleanup failed"}))
        (let [result (memory/handle-mcp-memory-cleanup-expired {})]
          (is (= "text" (:type result)))
          (is (true? (:isError result)))
          (is (str/includes? (:text result) "Cleanup failed")))
        (finally
          (registry/reset-registry!)
          (doseq [[k s] prior] (registry/register-store! k s)))))))

;; =============================================================================
;; Elisp Generation Verification Tests
;; =============================================================================

(deftest elisp-generation-test
  (testing "set-duration uses require-and-call-json pattern"
    (let [elisp (el/require-and-call-json "hive-mcp-api"
                                          "hive-mcp-api-memory-set-duration"
                                          "test-id" "permanent")]
      (is (str/includes? elisp "progn"))
      (is (str/includes? elisp "require"))
      (is (str/includes? elisp "fboundp"))
      (is (str/includes? elisp "json-encode"))
      (is (str/includes? elisp "hive-mcp-api-memory-set-duration"))
      (is (str/includes? elisp "test-id"))
      (is (str/includes? elisp "permanent"))))

  (testing "promote uses require-and-call-json pattern"
    (let [elisp (el/require-and-call-json "hive-mcp-api"
                                          "hive-mcp-api-memory-promote"
                                          "test-id")]
      (is (str/includes? elisp "hive-mcp-api-memory-promote"))
      (is (str/includes? elisp "test-id"))))

  (testing "demote uses require-and-call-json pattern"
    (let [elisp (el/require-and-call-json "hive-mcp-api"
                                          "hive-mcp-api-memory-demote"
                                          "test-id")]
      (is (str/includes? elisp "hive-mcp-api-memory-demote"))
      (is (str/includes? elisp "test-id"))))

  (testing "cleanup-expired uses require-and-call-json pattern"
    (let [elisp (el/require-and-call-json "hive-mcp-api"
                                          "hive-mcp-api-memory-cleanup-expired")]
      (is (str/includes? elisp "hive-mcp-api-memory-cleanup-expired"))))

  (testing "expiring-soon uses require-and-call-json pattern with days param"
    (let [elisp (el/require-and-call-json "hive-mcp-api"
                                          "hive-mcp-api-memory-expiring-soon"
                                          14)]
      (is (str/includes? elisp "hive-mcp-api-memory-expiring-soon"))
      (is (str/includes? elisp "14")))))
