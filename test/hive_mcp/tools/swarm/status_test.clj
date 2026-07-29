(ns hive-mcp.tools.swarm.status-test
  "Tests for swarm status handlers - broadcast fix verification.

   BUG FIX: hivemind_broadcast/swarm_broadcast was silently succeeding
   even when no slaves were available. Now returns error with clear message.

   Kanban: 20260130114548"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.tools.swarm.status :as status]
            [clojure.data.json :as json]
            [hive-mcp.test.stub.emacs-ext :as emacs]
            [hive-mcp.test.stub.extensions :as ext-stub]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; handle-swarm-broadcast Tests
;; =============================================================================

(defn- broadcast-responses
  "Scripted `:emacs/eval-elisp-with-timeout` answers for a broadcast round-trip:
   the hive-mcp-swarm feature reports present, and the broadcast call answers
   with ANSWER (a full {:success :result :timed-out} envelope)."
  [answer]
  [["featurep" {:success true :result "t"}]
   ["hive-mcp-swarm-broadcast" answer]])

(deftest handle-swarm-broadcast-no-targets-returns-error
  (testing "broadcast returns error when no slaves available (bug fix)"
    (emacs/with-stub-emacs
      [stub {:responses (broadcast-responses
                         ;; elisp returns an empty list: no slaves
                         {:success true :result "[]" :timed-out false})}]
      (let [result (status/handle-swarm-broadcast {:prompt "test prompt"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        ;; Should be an error, not success
        (is (:isError result)
            "Should return isError when no targets")
        (is (= "no-targets" (:error parsed))
            "Error type should be 'no-targets'")
        (is (zero? (:delivered-count parsed))
            "Delivered count should be 0")
        (is (string? (:message parsed))
            "Should have helpful error message")
        (is (some #(re-find #"test prompt" (first %))
                  (emacs/calls-of stub :emacs/eval-elisp-with-timeout))
            "Prompt should reach the elisp transport")))))

(deftest handle-swarm-broadcast-success-returns-count
  (testing "broadcast returns delivery count on success"
    (emacs/with-stub-emacs
      [stub {:responses (broadcast-responses
                         ;; elisp returns a list of task-ids
                         {:success true
                          :result "[\"task-1\", \"task-2\", \"task-3\"]"
                          :timed-out false})}]
      (let [result (status/handle-swarm-broadcast {:prompt "test prompt"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        ;; Should NOT be an error
        (is (not (:isError result))
            "Should not be an error when slaves received broadcast")
        (is (= 3 (:delivered-count parsed))
            "Should report correct delivery count")
        (is (= ["task-1" "task-2" "task-3"] (:task-ids parsed))
            "Should include task IDs")
        (is (string? (:message parsed))
            "Should have success message")
        (is (= 2 (count (emacs/calls-of stub :emacs/eval-elisp-with-timeout)))
            "Availability probe then broadcast, both over the extension seam")))))

(deftest handle-swarm-broadcast-timeout
  (testing "broadcast returns timeout error when elisp times out"
    (emacs/with-stub-emacs
      [_stub {:responses (broadcast-responses
                          {:success false :result nil :timed-out true})}]
      (let [result (status/handle-swarm-broadcast {:prompt "test"})
            parsed (json/read-str (:text result) :key-fn keyword)]
        (is (:isError result)
            "Should return error on timeout")
        (is (= "timeout" (:status parsed))
            "Status should be timeout")))))

(deftest handle-swarm-broadcast-addon-not-loaded
  (testing "broadcast returns error when the swarm feature is absent from Emacs"
    (emacs/with-stub-emacs
      [_stub {:responses [["featurep" {:success true :result "nil"}]]}]
      (let [result (status/handle-swarm-broadcast {:prompt "test"})]
        (is (:isError result)
            "Should return error when addon not loaded")
        (is (re-find #"not loaded" (:text result))
            "Should mention addon not loaded"))))
  (testing "broadcast returns error when the elisp transport itself is absent"
    (ext-stub/without-extensions
     [:emacs/eval-elisp-with-timeout]
     (fn []
       (let [result (status/handle-swarm-broadcast {:prompt "test"})]
         (is (:isError result)
             "Should return error when hive-emacs is not loaded")
         (is (re-find #"not loaded" (:text result))
             "Should mention addon not loaded"))))))
