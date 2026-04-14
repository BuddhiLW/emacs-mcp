(ns hive-mcp.tools.consolidated.hivemind-nudge-test
  "Tests for hivemind nudge subcommand.

   Verifies:
   - nudge calls dispatch/handle-dispatch with correct agent_id + prompt
   - default nudge template used when no message provided
   - custom message overrides template
   - missing agent_id returns error"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [hive-mcp.tools.consolidated.hivemind :as hivemind]
            [hive-mcp.tools.agent.dispatch :as dispatch]))

;; =============================================================================
;; Unit tests — mock dispatch to verify nudge composition
;; =============================================================================

(deftest nudge-missing-agent-id-test
  (testing "nudge without agent_id returns error"
    (let [result (hivemind/handle-nudge {})]
      (is (some? result))
      ;; MCP errors have isError true
      (is (true? (:isError result))))))

(deftest nudge-blank-agent-id-test
  (testing "nudge with blank agent_id returns error"
    (let [result (hivemind/handle-nudge {:agent_id ""})]
      (is (true? (:isError result))))))

(deftest nudge-default-template-test
  (testing "nudge with no message uses default template"
    (let [dispatch-calls (atom [])]
      (with-redefs [dispatch/handle-dispatch
                    (fn [params]
                      (swap! dispatch-calls conj params)
                      {:content [{:type "text"
                                  :text (json/write-str {:success true
                                                         :agent-id (:agent_id params)
                                                         :task-id "task-123"})}]})]
        (hivemind/handle-nudge {:agent_id "test-ling-42"})

        (is (= 1 (count @dispatch-calls)))
        (let [{:keys [agent_id prompt]} (first @dispatch-calls)]
          (is (= "test-ling-42" agent_id))
          (is (= hivemind/default-nudge-template prompt)))))))

(deftest nudge-custom-message-test
  (testing "nudge with custom message overrides template"
    (let [dispatch-calls (atom [])]
      (with-redefs [dispatch/handle-dispatch
                    (fn [params]
                      (swap! dispatch-calls conj params)
                      {:content [{:type "text"
                                  :text (json/write-str {:success true})}]})]
        (hivemind/handle-nudge {:agent_id "test-ling-42"
                                :message  "Custom: report your KG progress now"})

        (is (= 1 (count @dispatch-calls)))
        (let [{:keys [prompt]} (first @dispatch-calls)]
          (is (= "Custom: report your KG progress now" prompt))
          (is (not= hivemind/default-nudge-template prompt)))))))

(deftest nudge-via-cli-handler-test
  (testing "nudge routed correctly through consolidated CLI handler"
    (let [dispatch-calls (atom [])]
      (with-redefs [dispatch/handle-dispatch
                    (fn [params]
                      (swap! dispatch-calls conj params)
                      {:content [{:type "text"
                                  :text (json/write-str {:success true})}]})]
        (hivemind/handle-hivemind {:command  "nudge"
                                   :agent_id "cli-ling-99"})

        (is (= 1 (count @dispatch-calls)))
        (is (= "cli-ling-99" (:agent_id (first @dispatch-calls))))))))

(deftest nudge-template-contains-key-instructions-test
  (testing "default nudge template contains required instructions"
    (is (re-find #"MIDFLIGHT NUDGE" hivemind/default-nudge-template))
    (is (re-find #"mcp__hive__hivemind" hivemind/default-nudge-template))
    (is (re-find #"progress" hivemind/default-nudge-template))
    (is (re-find #"don't batch" hivemind/default-nudge-template))))
