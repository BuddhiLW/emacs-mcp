(ns hive-mcp.hivemind.event-registry-test
  (:require [clojure.test :refer [deftest testing is are]]
            [hive-mcp.hivemind.event-registry :as er]))

;; =============================================================================
;; Registry Structure Tests
;; =============================================================================

(deftest registry-structure
  (testing "Registry is an ordered map with expected keys"
    (is (map? er/registry))
    (is (= 9 (count er/registry)))
    (is (= [:started :progress :completed :error :blocked :wrap_notify
            :conversation/tell :conversation/ask :conversation/respond]
           (keys er/registry))))

  (testing "Each entry has required metadata keys"
    (doseq [[k v] er/registry]
      (testing (str "Variant " k)
        (is (string? (:description v)) (str k " missing :description"))
        (is (#{:info :warn :error} (:severity v)) (str k " invalid :severity"))
        (is (keyword? (:slave-status v)) (str k " missing :slave-status"))
        (is (boolean? (:terminal? v)) (str k " missing :terminal?"))
        (is (boolean? (:mcp? v)) (str k " missing :mcp?"))
        (is (map? (:format v)) (str k " missing :format"))
        (is (string? (get-in v [:format :icon])) (str k " missing :format :icon"))
        (is (string? (get-in v [:format :abbreviated])) (str k " missing :format :abbreviated"))))))

;; =============================================================================
;; Derived View Tests
;; =============================================================================

(deftest all-event-types-test
  (testing "all-event-types is the complete set"
    (is (= #{:started :progress :completed :error :blocked :wrap_notify
             :conversation/tell :conversation/ask :conversation/respond}
           er/all-event-types))))

(deftest all-event-type-strings-test
  (testing "all-event-type-strings matches keyword names"
    (is (= #{"started" "progress" "completed" "error" "blocked" "wrap_notify"
             "tell" "ask" "respond"}
           er/all-event-type-strings))))

(deftest mcp-event-types-test
  (testing "MCP types exclude non-MCP entries"
    (is (vector? er/mcp-event-types))
    (is (every? string? er/mcp-event-types))
    (is (contains? (set er/mcp-event-types) "started"))
    (is (contains? (set er/mcp-event-types) "progress"))
    (is (contains? (set er/mcp-event-types) "completed"))
    (is (contains? (set er/mcp-event-types) "error"))
    (is (contains? (set er/mcp-event-types) "blocked"))
    (is (not (contains? (set er/mcp-event-types) "wrap_notify")))
    (testing "Conversation events are NOT in MCP enum"
      (is (not (contains? (set er/mcp-event-types) "tell")))
      (is (not (contains? (set er/mcp-event-types) "ask")))
      (is (not (contains? (set er/mcp-event-types) "respond"))))))

(deftest event-type->slave-status-test
  (testing "Slave status mapping matches messaging.clj's original case statement"
    (is (= :working (get er/event-type->slave-status :started)))
    (is (= :working (get er/event-type->slave-status :progress)))
    (is (= :idle    (get er/event-type->slave-status :completed)))
    (is (= :error   (get er/event-type->slave-status :error)))
    (is (= :blocked (get er/event-type->slave-status :blocked)))
    (is (= :idle    (get er/event-type->slave-status :wrap_notify))))
  (testing "Conversation events default to :idle (orthogonal axis)"
    (is (= :idle (get er/event-type->slave-status :conversation/tell)))
    (is (= :idle (get er/event-type->slave-status :conversation/ask)))
    (is (= :idle (get er/event-type->slave-status :conversation/respond)))))

(deftest terminal-event-types-test
  (testing "Terminal events end workflows"
    (is (contains? er/terminal-event-types :completed))
    (is (contains? er/terminal-event-types :error))
    (is (contains? er/terminal-event-types :wrap_notify))
    (is (not (contains? er/terminal-event-types :started)))
    (is (not (contains? er/terminal-event-types :progress)))
    (is (not (contains? er/terminal-event-types :blocked))))
  (testing "Conversation: respond is terminal, tell/ask are not"
    (is (contains? er/terminal-event-types :conversation/respond))
    (is (not (contains? er/terminal-event-types :conversation/tell)))
    (is (not (contains? er/terminal-event-types :conversation/ask)))))

(deftest severity-levels-test
  (testing "Severity mapping"
    (is (= :info  (get er/severity-levels :started)))
    (is (= :info  (get er/severity-levels :progress)))
    (is (= :info  (get er/severity-levels :completed)))
    (is (= :error (get er/severity-levels :error)))
    (is (= :warn  (get er/severity-levels :blocked)))
    (is (= :info  (get er/severity-levels :wrap_notify)))))

;; =============================================================================
;; Function Tests
;; =============================================================================

(deftest valid-event-type?-test
  (testing "Keywords"
    (is (true? (er/valid-event-type? :started)))
    (is (true? (er/valid-event-type? :progress)))
    (is (true? (er/valid-event-type? :completed)))
    (is (true? (er/valid-event-type? :error)))
    (is (true? (er/valid-event-type? :blocked)))
    (is (true? (er/valid-event-type? :wrap_notify)))
    (is (true? (er/valid-event-type? :conversation/tell)))
    (is (true? (er/valid-event-type? :conversation/ask)))
    (is (true? (er/valid-event-type? :conversation/respond))))

  (testing "Strings"
    (is (true? (er/valid-event-type? "started")))
    (is (true? (er/valid-event-type? "progress")))
    (is (true? (er/valid-event-type? "completed")))
    (is (true? (er/valid-event-type? "wrap_notify"))))

  (testing "Invalid types"
    (is (false? (er/valid-event-type? :unknown)))
    (is (false? (er/valid-event-type? "bogus")))
    (is (false? (er/valid-event-type? :running)))))

(deftest slave-status-test
  (testing "Keyword input"
    (is (= :working (er/slave-status :started)))
    (is (= :idle    (er/slave-status :completed))))

  (testing "String input"
    (is (= :working (er/slave-status "progress")))
    (is (= :error   (er/slave-status "error"))))

  (testing "Unknown defaults to :idle"
    (is (= :idle (er/slave-status :unknown)))
    (is (= :idle (er/slave-status "nonexistent")))))

(deftest severity-test
  (testing "Known types"
    (is (= :info  (er/severity :started)))
    (is (= :error (er/severity :error)))
    (is (= :warn  (er/severity :blocked))))

  (testing "Unknown defaults to :info"
    (is (= :info (er/severity :unknown)))))

(deftest terminal?-test
  (testing "Terminal types"
    (is (true? (er/terminal? :completed)))
    (is (true? (er/terminal? :error)))
    (is (true? (er/terminal? :wrap_notify))))

  (testing "Non-terminal types"
    (is (false? (er/terminal? :started)))
    (is (false? (er/terminal? :progress)))
    (is (false? (er/terminal? :blocked))))

  (testing "Unknown types are non-terminal"
    (is (false? (er/terminal? :unknown)))))

(deftest valid-transition?-test
  (testing "Valid transitions from started"
    (is (true? (er/valid-transition? :started :progress)))
    (is (true? (er/valid-transition? :started :completed)))
    (is (true? (er/valid-transition? :started :error)))
    (is (true? (er/valid-transition? :started :blocked))))

  (testing "Invalid transitions from started"
    (is (false? (er/valid-transition? :started :started)))
    (is (false? (er/valid-transition? :started :wrap_notify))))

  (testing "Progress can loop"
    (is (true? (er/valid-transition? :progress :progress)))
    (is (true? (er/valid-transition? :progress :completed))))

  (testing "Completed only transitions to started"
    (is (true? (er/valid-transition? :completed :started)))
    (is (false? (er/valid-transition? :completed :progress))))

  (testing "Blocked can resume"
    (is (true? (er/valid-transition? :blocked :progress)))
    (is (true? (er/valid-transition? :blocked :completed)))
    (is (false? (er/valid-transition? :blocked :started))))

  (testing "wrap_notify has no transition rules (nil = any)"
    (is (true? (er/valid-transition? :wrap_notify :started)))
    (is (true? (er/valid-transition? :wrap_notify :anything))))

  (testing "String inputs work"
    (is (true? (er/valid-transition? "started" "progress")))
    (is (false? (er/valid-transition? "completed" "progress")))))

(deftest format-icon-test
  (testing "Known event types have icons"
    (is (= "▶" (er/format-icon :started)))
    (is (= "⟳" (er/format-icon :progress)))
    (is (= "✓" (er/format-icon :completed)))
    (is (= "✗" (er/format-icon :error)))
    (is (= "⏸" (er/format-icon :blocked))))

  (testing "Unknown defaults to bullet"
    (is (= "•" (er/format-icon :unknown))))

  (testing "Conversation icons"
    (is (= "→" (er/format-icon :conversation/tell)))
    (is (= "?" (er/format-icon :conversation/ask)))
    (is (= "!" (er/format-icon :conversation/respond)))))

(deftest mcp-enum-test
  (testing "mcp-enum returns the MCP-visible event type strings"
    (let [enum (er/mcp-enum)]
      (is (vector? enum))
      (is (= 5 (count enum)))
      (is (not (contains? (set enum) "wrap_notify")))
      (is (not (contains? (set enum) "tell")))
      (is (not (contains? (set enum) "ask")))
      (is (not (contains? (set enum) "respond"))))))

;; =============================================================================
;; Backward Compatibility Tests
;; =============================================================================

(deftest backward-compat-with-messaging-case-test
  (testing "Registry slave-status matches the original event-type->slave-status case in messaging.clj"
    ;; Original: (case event-type :started :working :progress :working
    ;;            :completed :idle :error :error :blocked :blocked :idle)
    (are [event-type expected-status]
         (= expected-status (er/slave-status event-type))
      :started   :working
      :progress  :working
      :completed :idle
      :error     :error
      :blocked   :blocked)))

(deftest backward-compat-mcp-enum-test
  (testing "MCP enum contains exactly the same values as hardcoded enums"
    (let [original-enum #{"progress" "completed" "error" "blocked" "started"}
          registry-enum (set (er/mcp-enum))]
      (is (= original-enum registry-enum)))))
