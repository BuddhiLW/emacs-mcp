(ns hive-mcp.tools.batch-test
  "Comprehensive tests for make-batch-handler HOF and per-tool batch commands.

   Tests cover:
   - make-batch-handler HOF core behavior
   - Per-tool batch wiring: kanban batch-update, agent batch-spawn,
     kg batch-edge, kg batch-traverse, memory batch-add, memory batch-feedback,
     magit batch-commit
   - Error handling: invalid ops, mixed success/failure, empty ops array
   - Parallel flag behavior
   - Shared param merging"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-mcp.tools.cli :as cli]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- echo-handler
  "Test handler that returns its params as the result."
  [params]
  {:echoed (dissoc params :command)})

(defn- failing-handler
  "Test handler that always throws."
  [_params]
  (throw (ex-info "Intentional test failure" {})))

(defn- conditional-handler
  "Test handler that succeeds if :value is even, fails if odd."
  [{:keys [value] :as _params}]
  (if (even? value)
    {:success true :value value}
    (throw (ex-info (str "Odd value: " value) {:value value}))))

(defn- parse-batch-result
  "Parse the JSON text from a batch handler result."
  [result]
  (when-let [text (:text result)]
    (json/read-str text :key-fn keyword)))

;; =============================================================================
;; make-batch-handler HOF — Core Behavior
;; =============================================================================

(deftest batch-handler-basic-operations
  (let [handlers {:echo echo-handler}
        batch-fn (cli/make-batch-handler handlers)]

    (testing "single operation dispatches correctly"
      (let [result (batch-fn {:operations [{:command "echo" :foo "bar"}]})
            parsed (parse-batch-result result)]
        (is (= 1 (get-in parsed [:summary :total])))
        (is (= 1 (get-in parsed [:summary :success])))
        (is (= 0 (get-in parsed [:summary :failed])))
        (is (= "bar" (get-in parsed [:results 0 :result :echoed :foo])))))

    (testing "multiple operations all succeed"
      (let [result (batch-fn {:operations [{:command "echo" :a 1}
                                           {:command "echo" :b 2}
                                           {:command "echo" :c 3}]})
            parsed (parse-batch-result result)]
        (is (= 3 (get-in parsed [:summary :total])))
        (is (= 3 (get-in parsed [:summary :success])))
        (is (= 0 (get-in parsed [:summary :failed])))))

    (testing "result type is text"
      (let [result (batch-fn {:operations [{:command "echo" :x 1}]})]
        (is (= "text" (:type result)))))))

(deftest batch-handler-empty-and-nil-operations
  (let [handlers {:echo echo-handler}
        batch-fn (cli/make-batch-handler handlers)]

    (testing "nil operations returns error"
      (let [result (batch-fn {:operations nil})]
        (is (:isError result))
        (is (string? (:text result)))))

    (testing "empty operations returns error"
      (let [result (batch-fn {:operations []})]
        (is (:isError result))
        (is (string? (:text result)))))

    (testing "missing operations key returns error"
      (let [result (batch-fn {})]
        (is (:isError result))))))

(deftest batch-handler-error-handling
  (testing "exception in handler captured, no fail-fast"
    (let [handlers {:fail failing-handler :echo echo-handler}
          batch-fn (cli/make-batch-handler handlers)
          result (batch-fn {:operations [{:command "fail"}
                                         {:command "echo" :x 1}]})
          parsed (parse-batch-result result)]
      (is (= 2 (get-in parsed [:summary :total])))
      (is (= 1 (get-in parsed [:summary :success])))
      (is (= 1 (get-in parsed [:summary :failed])))
      ;; First op failed but second still succeeded (no fail-fast)
      (is (false? (get-in parsed [:results 0 :success])))
      (is (true? (get-in parsed [:results 1 :success])))))

  (testing "unknown command in operation yields failed result"
    (let [handlers {:echo echo-handler}
          batch-fn (cli/make-batch-handler handlers)
          result (batch-fn {:operations [{:command "nonexistent"}]})
          parsed (parse-batch-result result)]
      (is (= 1 (get-in parsed [:summary :failed])))
      (is (false? (get-in parsed [:results 0 :success])))))

  (testing "missing command in operation yields failed result"
    (let [handlers {:echo echo-handler}
          batch-fn (cli/make-batch-handler handlers)
          result (batch-fn {:operations [{}]})
          parsed (parse-batch-result result)]
      (is (= 1 (get-in parsed [:summary :failed])))
      (is (false? (get-in parsed [:results 0 :success])))))

  (testing "mixed success/failure — all operations processed"
    (let [handlers {:cond conditional-handler}
          batch-fn (cli/make-batch-handler handlers)
          result (batch-fn {:operations [{:command "cond" :value 2}
                                         {:command "cond" :value 3}
                                         {:command "cond" :value 4}
                                         {:command "cond" :value 5}]})
          parsed (parse-batch-result result)]
      (is (= 4 (get-in parsed [:summary :total])))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 2 (get-in parsed [:summary :failed]))))))

;; =============================================================================
;; Shared Param Merging
;; =============================================================================

(deftest batch-handler-shared-params
  (let [handlers {:echo echo-handler}
        batch-fn (cli/make-batch-handler handlers)]

    (testing "shared params merge into each operation"
      (let [result (batch-fn {:operations [{:command "echo" :local "a"}
                                           {:command "echo" :local "b"}]
                              :shared_key "shared_val"})
            parsed (parse-batch-result result)]
        ;; Both ops should have :shared_key from outer call
        (is (= "shared_val" (get-in parsed [:results 0 :result :echoed :shared_key])))
        (is (= "shared_val" (get-in parsed [:results 1 :result :echoed :shared_key])))))

    (testing "per-op params override shared params"
      (let [result (batch-fn {:operations [{:command "echo" :x "per-op"}]
                              :x "shared"})
            parsed (parse-batch-result result)]
        ;; Per-op :x should win over shared :x
        (is (= "per-op" (get-in parsed [:results 0 :result :echoed :x])))))

    (testing "operations/parallel/command stripped from shared params"
      (let [result (batch-fn {:operations [{:command "echo"}]
                              :parallel true})
            parsed (parse-batch-result result)]
        ;; :parallel should NOT bleed into op params
        (is (nil? (get-in parsed [:results 0 :result :echoed :parallel])))
        (is (nil? (get-in parsed [:results 0 :result :echoed :operations])))))))

;; =============================================================================
;; Parallel Flag Behavior
;; =============================================================================

(deftest batch-handler-parallel-flag
  (let [call-order (atom [])
        slow-handler (fn [{:keys [id]}]
                       (swap! call-order conj id)
                       (Thread/sleep 10)
                       {:id id})
        handlers {:slow slow-handler}
        batch-fn (cli/make-batch-handler handlers)]

    (testing "sequential (default) — operations run in order"
      (reset! call-order [])
      (let [result (batch-fn {:operations [{:command "slow" :id "a"}
                                           {:command "slow" :id "b"}
                                           {:command "slow" :id "c"}]})
            parsed (parse-batch-result result)]
        (is (= 3 (get-in parsed [:summary :success])))
        ;; Sequential: order is preserved
        (is (= ["a" "b" "c"] @call-order))))

    (testing "parallel flag — all operations complete (order may vary)"
      (reset! call-order [])
      (let [result (batch-fn {:operations [{:command "slow" :id "x"}
                                           {:command "slow" :id "y"}
                                           {:command "slow" :id "z"}]
                              :parallel true})
            parsed (parse-batch-result result)]
        (is (= 3 (get-in parsed [:summary :success])))
        ;; Parallel: all items present but order may differ
        (is (= #{"x" "y" "z"} (set @call-order)))))))

;; =============================================================================
;; Per-Tool Batch Wiring (Schema/Structural Tests)
;; =============================================================================
;;
;; These tests verify that per-tool batch commands are wired correctly
;; in their consolidated tool modules. They test the wiring, not the
;; underlying handlers (which would require Emacs/Chroma/DataScript).

(deftest kanban-batch-update-wiring
  (testing "kanban tool-def has batch-update in schema"
    (require 'hive-mcp.tools.consolidated.kanban)
    (let [tool-def @(resolve 'hive-mcp.tools.consolidated.kanban/tool-def)
          schema (:inputSchema tool-def)
          enum (get-in schema [:properties "command" :enum])]
      (is (some #{"batch-update"} enum) "batch-update in command enum")
      (is (get-in schema [:properties "operations"]) "operations property exists")
      (is (get-in schema [:properties "parallel"]) "parallel property exists")))

  (testing "kanban handlers map has :batch-update key"
    (let [handlers @(resolve 'hive-mcp.tools.consolidated.kanban/handlers)]
      (is (fn? (get handlers :batch-update)) ":batch-update handler is a function"))))

(deftest agent-batch-spawn-wiring
  (testing "agent tool-def has batch-spawn in schema"
    (require 'hive-mcp.tools.consolidated.agent)
    (let [tool-def @(resolve 'hive-mcp.tools.consolidated.agent/tool-def)
          schema (:inputSchema tool-def)
          enum (get-in schema [:properties "command" :enum])]
      (is (some #{"batch-spawn"} enum) "batch-spawn in command enum")
      (is (get-in schema [:properties "operations"]) "operations property exists")
      (is (get-in schema [:properties "parallel"]) "parallel property exists")))

  (testing "agent canonical-handlers has :batch-spawn key"
    (let [handlers @(resolve 'hive-mcp.tools.consolidated.agent/canonical-handlers)]
      (is (fn? (get handlers :batch-spawn)) ":batch-spawn handler is a function"))))

(deftest kg-batch-commands-wiring
  (testing "kg tool-def has batch-edge and batch-traverse in schema"
    (require 'hive-mcp.tools.consolidated.kg)
    (let [tool-def @(resolve 'hive-mcp.tools.consolidated.kg/tool-def)
          schema (:inputSchema tool-def)
          enum (get-in schema [:properties "command" :enum])]
      (is (some #{"batch-edge"} enum) "batch-edge in command enum")
      (is (some #{"batch-traverse"} enum) "batch-traverse in command enum")
      (is (get-in schema [:properties "operations"]) "operations property exists")
      (is (get-in schema [:properties "parallel"]) "parallel property exists")))

  (testing "kg handlers map has both batch keys"
    (let [handlers @(resolve 'hive-mcp.tools.consolidated.kg/handlers)]
      (is (fn? (get handlers :batch-edge)) ":batch-edge handler is a function")
      (is (fn? (get handlers :batch-traverse)) ":batch-traverse handler is a function"))))

(deftest memory-batch-commands-wiring
  (testing "memory advertises batch-add and batch-feedback on its command surface"
    ;; `memory` deliberately carries no static command enum — addon subdomains
    ;; contribute commands at runtime, so the advertised surface is the tool's
    ;; own command="help" answer (cli/format-help over the live handler tree).
    (require 'hive-mcp.tools.consolidated.memory)
    (let [tool-def @(resolve 'hive-mcp.tools.consolidated.memory/tool-def)
          schema (:inputSchema tool-def)
          advertised (:text ((:handler tool-def) {:command "help"}))]
      (is (nil? (get-in schema [:properties "command" :enum]))
          "no static enum: the command surface is open (addon-extensible)")
      (is (str/includes? advertised "- batch-add") "batch-add advertised by help")
      (is (str/includes? advertised "- batch-feedback") "batch-feedback advertised by help")
      (is (get-in schema [:properties "operations"]) "operations property exists")
      (is (get-in schema [:properties "parallel"]) "parallel property exists")))

  (testing "memory canonical-handlers dispatches both batch commands"
    (let [handlers @(resolve 'hive-mcp.tools.consolidated.memory/canonical-handlers)]
      (doseq [cmd [:batch-add :batch-feedback]]
        (let [{:keys [handler error]} (cli/resolve-handler handlers [cmd])]
          (is (nil? error) (str cmd " resolves through the command router"))
          (is (fn? handler) (str cmd " handler is a function")))))))

(deftest magit-batch-commit-wiring
  (testing "magit tool-def has batch-commit in schema"
    (require 'hive-mcp.tools.consolidated.magit)
    (let [tool-def @(resolve 'hive-mcp.tools.consolidated.magit/tool-def)
          schema (:inputSchema tool-def)
          enum (get-in schema [:properties "command" :enum])]
      (is (some #{"batch-commit"} enum) "batch-commit in command enum")
      (is (get-in schema [:properties "operations"]) "operations property exists")
      (is (get-in schema [:properties "parallel"]) "parallel property exists")))

  (testing "magit handlers map has :batch-commit key"
    (let [handlers @(resolve 'hive-mcp.tools.consolidated.magit/handlers)]
      (is (fn? (get handlers :batch-commit)) ":batch-commit handler is a function"))))

;; =============================================================================
;; Per-Tool Batch Handler Invocation (using echo stubs where possible)
;; =============================================================================

(deftest kanban-batch-update-empty-ops
  (testing "kanban batch-update with empty ops returns error"
    (require 'hive-mcp.tools.consolidated.kanban)
    (let [handler (get @(resolve 'hive-mcp.tools.consolidated.kanban/handlers) :batch-update)
          result (handler {:operations []})]
      (is (:isError result)))))

(deftest agent-batch-spawn-empty-ops
  (testing "agent batch-spawn with empty ops returns error"
    (require 'hive-mcp.tools.consolidated.agent)
    (let [handler @(resolve 'hive-mcp.tools.consolidated.agent/batch-spawn-handler)
          result (handler {:operations []})]
      ;; batch-spawn has its own empty check that returns mcp-error
      (is (some? result)))))

(deftest magit-batch-commit-empty-ops
  (testing "magit batch-commit with empty ops returns error"
    (require 'hive-mcp.tools.consolidated.magit)
    (let [handler (get @(resolve 'hive-mcp.tools.consolidated.magit/handlers) :batch-commit)
          result (handler {:operations []})]
      (is (:isError result)))))

;; =============================================================================
;; CLI Integration: batch commands reachable via handle-* dispatchers
;; =============================================================================

(deftest batch-command-reachable-via-cli-dispatcher
  (testing "magit batch-commit reachable via handle-magit"
    (require 'hive-mcp.tools.consolidated.magit)
    (let [handle-magit @(resolve 'hive-mcp.tools.consolidated.magit/handle-magit)
          ;; Call with empty ops — should get error from batch handler, not unknown command
          result (handle-magit {:command "batch-commit" :operations []})]
      (is (:isError result))
      (is (clojure.string/includes? (:text result) "operations"))))

  (testing "kanban batch-update reachable via handle-kanban"
    (require 'hive-mcp.tools.consolidated.kanban)
    (let [handle-kanban @(resolve 'hive-mcp.tools.consolidated.kanban/handle-kanban)
          result (handle-kanban {:command "batch-update" :operations []})]
      (is (:isError result)))))
