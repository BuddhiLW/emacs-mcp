(ns hive-mcp.tools.consolidated.multi-tool-integration-test
  "Integration tests for the hive-mcp consolidated multi-tool pattern.

   Tests the cross-cutting concerns that apply to ALL consolidated tools:
   1. CLI dispatch framework (make-cli-handler, make-batch-handler)
   2. Tool definition structure compliance
   3. Handler map completeness
   4. Help command formatting
   5. Unknown command handling
   6. Batch operation patterns
   7. Deprecated alias routing
   8. Multi meta-facade routing (handle-multi)

   Then per-tool integration tests for tools lacking dedicated test files:
   - memory, kg, hivemind, kanban, preset, wave, magit, emacs, analysis,
     agora, olympus, project, session, config, workflow, migration, agent"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            ;; CLI framework
            [hive-mcp.tools.cli :as cli]
            ;; All consolidated tools
            [hive-mcp.tools.consolidated.memory :as memory]
            [hive-mcp.tools.consolidated.addon :as addon]
            [hive-mcp.tools.consolidated.kg :as kg]
            [hive-mcp.tools.consolidated.hivemind :as hivemind]
            [hive-mcp.tools.consolidated.kanban :as kanban]
            [hive-mcp.tools.consolidated.preset :as preset]
            [hive-mcp.tools.consolidated.wave :as wave]
            [hive-mcp.tools.consolidated.magit :as magit]
            [hive-mcp.tools.consolidated.emacs :as emacs]
            [hive-mcp.tools.composite :as composite]
            [hive-mcp.tools.consolidated.agora :as agora]
            [hive-mcp.tools.consolidated.olympus :as olympus]
            [hive-mcp.tools.consolidated.project :as project]
            ;; Previously untested consolidated tools
            [hive-mcp.tools.consolidated.session :as session]
            [hive-mcp.tools.consolidated.config :as config]
            [hive-mcp.tools.consolidated.workflow :as workflow]
            [hive-mcp.tools.consolidated.migration :as migration]
            [hive-mcp.tools.consolidated.agent :as agent]
            ;; Multi meta-facade
            [hive-mcp.tools.consolidated.multi :as multi]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn parse-response
  "Parse JSON response from handler."
  [result]
  (when (and result (not (:isError result)) (:text result))
    (try
      (json/read-str (:text result) :key-fn keyword)
      (catch Exception _ nil))))

;; =============================================================================
;; Part 1: CLI Framework Tests (make-cli-handler)
;; =============================================================================

(deftest test-make-cli-handler-dispatches-commands
  (testing "make-cli-handler dispatches to correct handler"
    (let [handler (cli/make-cli-handler
                   {:foo (fn [_] {:type "text" :text "foo-result"})
                    :bar (fn [_] {:type "text" :text "bar-result"})})]
      (is (= "foo-result" (:text (handler {:command "foo"}))))
      (is (= "bar-result" (:text (handler {:command "bar"})))))))

(deftest test-make-cli-handler-help-command
  (testing "make-cli-handler generates help for 'help' command"
    (let [handler (cli/make-cli-handler
                   {:status (fn [_] nil)
                    :create (fn [_] nil)})]
      (let [result (handler {:command "help"})]
        (is (not (:isError result)))
        (is (str/includes? (:text result) "Available commands"))
        (is (str/includes? (:text result) "status"))
        (is (str/includes? (:text result) "create"))))))

(deftest test-make-cli-handler-unknown-command
  (testing "make-cli-handler returns error for unknown command"
    (let [handler (cli/make-cli-handler
                   {:known (fn [_] nil)})]
      (let [result (handler {:command "unknown"})]
        (is (:isError result))
        (is (str/includes? (:text result) "Unknown command"))))))

(deftest test-make-cli-handler-nil-command
  (testing "make-cli-handler handles nil command"
    (let [handler (cli/make-cli-handler {:a (fn [_] nil)})]
      (let [result (handler {:command nil})]
        (is (:isError result))))))

(deftest test-make-cli-handler-keyword-command
  (testing "make-cli-handler handles keyword command values"
    (let [handler (cli/make-cli-handler
                   {:foo (fn [_] {:type "text" :text "worked"})})]
      (is (= "worked" (:text (handler {:command :foo})))))))

(deftest test-make-cli-handler-passes-full-params
  (testing "make-cli-handler passes full params map to handler"
    (let [received (atom nil)
          handler (cli/make-cli-handler
                   {:test (fn [params] (reset! received params)
                            {:type "text" :text "ok"})})]
      (handler {:command "test" :extra "data" :number 42})
      (is (= "test" (:command @received)))
      (is (= "data" (:extra @received)))
      (is (= 42 (:number @received))))))

;; =============================================================================
;; Part 1b: CLI Framework - N-depth Dispatch Tests
;; =============================================================================

(deftest test-parse-command-single-word
  (testing "parse-command handles single word"
    (is (= [:status] (cli/parse-command "status")))))

(deftest test-parse-command-multi-word
  (testing "parse-command handles multi-word command"
    (is (= [:dag :start] (cli/parse-command "dag start")))
    (is (= [:dag :status] (cli/parse-command "dag status")))))

(deftest test-parse-command-nil-and-blank
  (testing "parse-command handles nil and blank"
    (is (nil? (cli/parse-command nil)))
    (is (nil? (cli/parse-command "")))
    (is (nil? (cli/parse-command "   ")))))

(deftest test-resolve-handler-leaf
  (testing "resolve-handler finds leaf handler"
    (let [h (fn [_] :ok)
          result (cli/resolve-handler {:foo h} [:foo])]
      (is (= h (:handler result)))
      (is (= [:foo] (:path-used result))))))

(deftest test-resolve-handler-nested
  (testing "resolve-handler walks nested tree"
    (let [h (fn [_] :ok)
          result (cli/resolve-handler {:dag {:start h}} [:dag :start])]
      (is (= h (:handler result)))
      (is (= [:dag :start] (:path-used result))))))

(deftest test-resolve-handler-default-handler
  (testing "resolve-handler uses _handler fallback"
    (let [default-h (fn [_] :default)
          result (cli/resolve-handler {:dag {:_handler default-h
                                             :start (fn [_] :start)}}
                                      [:dag])]
      (is (= default-h (:handler result))))))

(deftest test-resolve-handler-not-found
  (testing "resolve-handler returns error for missing command"
    (let [result (cli/resolve-handler {:foo (fn [_] nil)} [:bar])]
      (is (= :not-found (:error result))))))

(deftest test-n-depth-dispatch-via-make-cli-handler
  (testing "make-cli-handler supports n-depth dispatch"
    (let [handler (cli/make-cli-handler
                   {:dag {:start  (fn [_] {:type "text" :text "started"})
                          :stop   (fn [_] {:type "text" :text "stopped"})
                          :_handler (fn [_] {:type "text" :text "dag-default"})}})]
      (is (= "started" (:text (handler {:command "dag start"}))))
      (is (= "stopped" (:text (handler {:command "dag stop"}))))
      (is (= "dag-default" (:text (handler {:command "dag"})))))))

;; =============================================================================
;; Part 1c: CLI Framework - Batch Handler Tests
;; =============================================================================

(deftest test-make-batch-handler-basic
  (testing "make-batch-handler processes array of operations"
    (let [batch-handler (cli/make-batch-handler
                         {:add (fn [{:keys [value]}]
                                 {:type "text" :text (str "added-" value)})})]
      (let [result (batch-handler {:operations [{:command "add" :value "a"}
                                                {:command "add" :value "b"}]})
            parsed (parse-response result)]
        (is (= 2 (get-in parsed [:summary :total])))
        (is (= 2 (get-in parsed [:summary :success])))
        (is (= 0 (get-in parsed [:summary :failed])))))))

(deftest test-make-batch-handler-parallel
  (testing "make-batch-handler supports parallel execution"
    (let [batch-handler (cli/make-batch-handler
                         {:noop (fn [_] {:type "text" :text "ok"})})]
      (let [result (batch-handler {:operations [{:command "noop"}
                                                {:command "noop"}
                                                {:command "noop"}]
                                   :parallel true})
            parsed (parse-response result)]
        (is (= 3 (get-in parsed [:summary :total])))
        (is (= 3 (get-in parsed [:summary :success])))))))

(deftest test-make-batch-handler-empty-operations
  (testing "make-batch-handler rejects empty operations"
    (let [batch-handler (cli/make-batch-handler {:add (fn [_] nil)})]
      (let [result (batch-handler {:operations []})]
        (is (:isError result))
        (is (str/includes? (:text result) "operations is required"))))))

(deftest test-make-batch-handler-nil-operations
  (testing "make-batch-handler rejects nil operations"
    (let [batch-handler (cli/make-batch-handler {:add (fn [_] nil)})]
      (let [result (batch-handler {})]
        (is (:isError result))))))

(deftest test-make-batch-handler-shared-params
  (testing "make-batch-handler merges shared params into each op"
    (let [received (atom [])
          batch-handler (cli/make-batch-handler
                         {:test (fn [params]
                                  (swap! received conj params)
                                  {:type "text" :text "ok"})})]
      (batch-handler {:operations [{:command "test" :specific "a"}
                                   {:command "test" :specific "b"}]
                      :shared-key "shared-val"})
      (is (every? #(= "shared-val" (:shared-key %)) @received))
      (is (= "a" (:specific (first @received))))
      (is (= "b" (:specific (second @received)))))))

(deftest test-make-batch-handler-per-op-wins-conflict
  (testing "make-batch-handler per-op params override shared params"
    (let [received (atom nil)
          batch-handler (cli/make-batch-handler
                         {:test (fn [params]
                                  (reset! received params)
                                  {:type "text" :text "ok"})})]
      (batch-handler {:operations [{:command "test" :key "per-op"}]
                      :key "shared"})
      (is (= "per-op" (:key @received))))))

(deftest test-make-batch-handler-exception-in-op
  (testing "make-batch-handler catches per-op exceptions without failing batch"
    (let [batch-handler (cli/make-batch-handler
                         {:boom (fn [_] (throw (ex-info "kaboom" {})))
                          :ok   (fn [_] {:type "text" :text "ok"})})]
      (let [result (batch-handler {:operations [{:command "boom"}
                                                {:command "ok"}]})
            parsed (parse-response result)]
        (is (= 2 (get-in parsed [:summary :total])))
        (is (= 1 (get-in parsed [:summary :success])))
        (is (= 1 (get-in parsed [:summary :failed])))))))

(deftest test-make-batch-handler-unknown-command-in-op
  (testing "make-batch-handler handles unknown command in operations"
    (let [batch-handler (cli/make-batch-handler
                         {:known (fn [_] {:type "text" :text "ok"})})]
      (let [result (batch-handler {:operations [{:command "known"}
                                                {:command "unknown"}]})
            parsed (parse-response result)]
        (is (= 1 (get-in parsed [:summary :success])))
        (is (= 1 (get-in parsed [:summary :failed])))))))

;; =============================================================================
;; Part 1d: format-help Tests
;; =============================================================================

(deftest test-format-help-flat
  (testing "format-help for flat handler map"
    (let [help (cli/format-help {:status (fn [_] nil)
                                 :create (fn [_] nil)})]
      (is (str/includes? help "Available commands"))
      (is (str/includes? help "create"))
      (is (str/includes? help "status")))))

(deftest test-format-help-nested
  (testing "format-help for nested handler map with subtrees"
    (let [help (cli/format-help {:dag {:start (fn [_] nil)
                                       :stop  (fn [_] nil)
                                       :_handler (fn [_] nil)}})]
      (is (str/includes? help "dag start"))
      (is (str/includes? help "dag stop"))
      ;; The parent :dag is also listed because it has :_handler
      (is (str/includes? help "dag")))))

;; =============================================================================
;; Part 2: Tool Definition Structure Compliance
;; =============================================================================

(def all-tool-defs
  "All consolidated tool definitions for cross-cutting tests."
  {:memory    memory/tool-def
   :addon     addon/tool-def
   :kg        kg/tool-def
   :hivemind  hivemind/tool-def
   :kanban    kanban/tool-def
   :preset    preset/tool-def
   :wave      wave/tool-def
   :magit     magit/tool-def
   :emacs     emacs/tool-def
   ;; :analysis — composite tool, tested separately
   :agora     agora/tool-def
   :olympus   olympus/tool-def
   :project   project/tool-def
   :session   session/tool-def
   :config    config/tool-def
   :workflow  workflow/tool-def
   :migration migration/tool-def
   :agent     agent/tool-def})

(def enum-less-tools
  "Tool keys whose tool-def deliberately omits :enum on \"command\".

   Their command set is contributed by addons at runtime, so a static enum
   would advertise a surface that does not match the live one. Discovery for
   these two is via command=\"help\"."
  #{:memory :project})

(def advertised-spawn-modes
  "The spawn modes a tool-def may advertise.

   'headless' is ABSTRACT: the concrete backend (:agent-sdk, :hive-agent, ...)
   is resolved at spawn time by the headless registry and is never advertised."
  #{"claude" "vterm" "headless"})

(deftest test-all-tool-defs-have-required-fields
  (testing "every consolidated tool-def has :name, :description, :inputSchema, :handler"
    (doseq [[tool-key tool-def] all-tool-defs]
      (is (string? (:name tool-def))
          (str tool-key " missing :name"))
      (is (string? (:description tool-def))
          (str tool-key " missing :description"))
      (is (map? (:inputSchema tool-def))
          (str tool-key " missing :inputSchema"))
      (is (fn? (:handler tool-def))
          (str tool-key " missing :handler")))))

(deftest test-all-tool-defs-are-consolidated
  (testing "every consolidated tool-def has :consolidated true"
    (doseq [[tool-key tool-def] all-tool-defs]
      (is (true? (:consolidated tool-def))
          (str tool-key " should have :consolidated true")))))

(deftest test-all-tool-defs-have-command-enum
  (testing "every consolidated tool-def declares 'command'; enum-bearing ones advertise 'help'"
    (doseq [[tool-key tool-def] all-tool-defs]
      (let [cmd-prop (get-in tool-def [:inputSchema :properties "command"])]
        (is (some? cmd-prop)
            (str tool-key " missing 'command' property"))
        (if (contains? enum-less-tools tool-key)
          (is (nil? (:enum cmd-prop))
              (str tool-key " now declares a command enum — drop it from enum-less-tools"))
          (do
            (is (vector? (:enum cmd-prop))
                (str tool-key " missing command enum"))
            (is (some #(= "help" %) (:enum cmd-prop))
                (str tool-key " missing 'help' in command enum"))))))))

(deftest test-all-tool-defs-require-command
  (testing "every consolidated tool-def requires 'command'"
    (doseq [[tool-key tool-def] all-tool-defs]
      (is (= ["command"] (get-in tool-def [:inputSchema :required]))
          (str tool-key " should require [\"command\"]")))))

(deftest test-all-tool-defs-have-tools-vector
  (testing "every consolidated module exposes a tools vector"
    (doseq [[tool-key {:keys [tools-var]}]
            {:memory    {:tools-var #'memory/tools}
             :addon     {:tools-var #'addon/tools}
             :kg        {:tools-var #'kg/tools}
             :hivemind  {:tools-var #'hivemind/tools}
             :kanban    {:tools-var #'kanban/tools}
             :preset    {:tools-var #'preset/tools}
             :wave      {:tools-var #'wave/tools}
             :magit     {:tools-var #'magit/tools}
             :emacs     {:tools-var #'emacs/tools}
             ;; :analysis — composite tool, no static tools var
             :agora     {:tools-var #'agora/tools}
             :olympus   {:tools-var #'olympus/tools}
             :project   {:tools-var #'project/tools}
             :session   {:tools-var #'session/tools}
             :config    {:tools-var #'config/tools}
             :workflow  {:tools-var #'workflow/tools}
             :migration {:tools-var #'migration/tools}
             :agent     {:tools-var #'agent/tools}}]
      (let [tools-val @tools-var]
        (is (= 1 (count tools-val))
            (str tool-key " should have exactly 1 tool in tools vector"))
        (is (= (get all-tool-defs tool-key) (first tools-val))
            (str tool-key " tools[0] should be tool-def"))))))

;; =============================================================================
;; Part 3: Handler Map Completeness
;; =============================================================================

(deftest test-all-handlers-maps-have-functions
  (testing "every handler in every consolidated tool's handlers map is a fn or map"
    (doseq [[tool-key handlers-map]
            {:memory    memory/canonical-handlers
             :addon     addon/handlers
             :kg        kg/handlers
             :hivemind  hivemind/handlers
             :kanban    kanban/handlers
             :preset    preset/handlers
             :wave      wave/handlers
             :magit     magit/handlers
             :emacs     emacs/handlers
             ;; :analysis — composite tool, handlers built dynamically
             :agora     agora/handlers
             :olympus   olympus/handlers
             :project   project/handlers
             :session   session/handlers
             :config    config/handlers
             :workflow  workflow/handlers
             :migration migration/handlers
             :agent     agent/handlers}]
      (doseq [[k v] handlers-map]
        (is (or (fn? v) (map? v))
            (str tool-key "/" k " should be a function or nested handler map"))))))

(deftest test-handler-map-commands-match-enum
  (testing "handler map keys match tool-def command enum (minus 'help')"
    (doseq [[tool-key {:keys [handlers-map tool-def-val]}]
            {:memory    {:handlers-map memory/canonical-handlers :tool-def-val memory/tool-def}
             :addon     {:handlers-map addon/handlers :tool-def-val addon/tool-def}
             :kg        {:handlers-map kg/handlers :tool-def-val kg/tool-def}
             :hivemind  {:handlers-map hivemind/handlers :tool-def-val hivemind/tool-def}
             :wave      {:handlers-map wave/handlers :tool-def-val wave/tool-def}
             :magit     {:handlers-map magit/handlers :tool-def-val magit/tool-def}
             :emacs     {:handlers-map emacs/handlers :tool-def-val emacs/tool-def}
             ;; :analysis — composite tool, tested separately
             :session   {:handlers-map session/handlers :tool-def-val session/tool-def}
             :config    {:handlers-map config/handlers :tool-def-val config/tool-def}
             :migration {:handlers-map migration/handlers :tool-def-val migration/tool-def}}
            :when (not (contains? enum-less-tools tool-key))]
      (let [enum-cmds    (set (get-in tool-def-val [:inputSchema :properties "command" :enum]))
            handler-keys (set (map name (keys handlers-map)))
            ;; A subdomain handler dispatches a family the enum lists leaf-wise:
            ;; handler :docs is advertised as "docs apropos", "docs list-packages", ...
            advertised?  (fn [hk]
                           (or (contains? enum-cmds hk)
                               (some #(str/starts-with? % (str hk " ")) enum-cmds)))]
        ;; Every handler should be in the enum (except internal keys like _handler)
        (doseq [hk handler-keys
                :when (not (str/starts-with? hk "_"))]
          (is (advertised? hk)
              (str tool-key ": handler '" hk "' not in command enum")))))))

;; =============================================================================
;; Part 4: Help Command Integration (all tools)
;; =============================================================================

(deftest test-all-tools-help-command
  (testing "every consolidated tool responds to 'help' command"
    (doseq [[tool-key handler]
            {:memory    memory/handle-memory
             :addon     addon/handle-addon
             :kg        kg/handle-kg
             :hivemind  hivemind/handle-hivemind
             :kanban    kanban/handle-kanban
             :preset    preset/handle-preset
             :wave      wave/handle-wave
             :magit     magit/handle-magit
             :emacs     emacs/handle-emacs
             :analysis  (composite/build-composite-handler "analysis")
             :agora     agora/handle-agora
             :olympus   olympus/handle-olympus
             :project   project/handle-project
             :session   session/handle-session
             :config    config/handle-config
             :workflow  workflow/handle-workflow
             :migration migration/handle-migration
             :agent     agent/handle-agent}]
      (let [result (handler {:command "help"})]
        (is (not (:isError result))
            (str tool-key " help should not error"))
        (is (str/includes? (:text result) "Available commands")
            (str tool-key " help should contain 'Available commands'"))))))

;; =============================================================================
;; Part 5: Unknown Command Handling (all tools)
;; =============================================================================

(deftest test-all-tools-unknown-command
  (testing "every consolidated tool returns error for unknown command"
    (doseq [[tool-key handler]
            {:memory    memory/handle-memory
             :addon     addon/handle-addon
             :kg        kg/handle-kg
             :hivemind  hivemind/handle-hivemind
             :kanban    kanban/handle-kanban
             :preset    preset/handle-preset
             :wave      wave/handle-wave
             :magit     magit/handle-magit
             :emacs     emacs/handle-emacs
             :analysis  (composite/build-composite-handler "analysis")
             :agora     agora/handle-agora
             :olympus   olympus/handle-olympus
             :project   project/handle-project
             :session   session/handle-session
             :config    config/handle-config
             :workflow  workflow/handle-workflow
             :migration migration/handle-migration
             :agent     agent/handle-agent}]
      (let [result (handler {:command "this-command-does-not-exist"})]
        (is (:isError result)
            (str tool-key " should error on unknown command"))
        (is (str/includes? (:text result) "Unknown command")
            (str tool-key " error should say 'Unknown command'"))))))

;; =============================================================================
;; Part 6: Memory Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-memory-handlers-completeness
  (testing "memory canonical-handlers has all expected commands"
    (is (contains? memory/canonical-handlers :add))
    (is (contains? memory/canonical-handlers :query))
    (is (contains? memory/canonical-handlers :metadata))
    (is (contains? memory/canonical-handlers :get))
    (is (contains? memory/canonical-handlers :search))
    (is (contains? memory/canonical-handlers :promote))
    (is (contains? memory/canonical-handlers :demote))
    (is (contains? memory/canonical-handlers :feedback))
    (is (contains? memory/canonical-handlers :tags))
    (is (contains? memory/canonical-handlers :cleanup))
    (is (contains? memory/canonical-handlers :expiring))
    (is (contains? memory/canonical-handlers :expire))
    (is (contains? memory/canonical-handlers :decay))
    (is (contains? memory/canonical-handlers :xpoll))
    (is (contains? memory/canonical-handlers :rename))
    (is (contains? memory/canonical-handlers :batch-add))
    (is (contains? memory/canonical-handlers :batch-feedback))
    (is (contains? memory/canonical-handlers :batch-get))))

(deftest test-memory-tool-def-schema-params
  (testing "memory tool-def schema has key params"
    (let [props (get-in memory/tool-def [:inputSchema :properties])]
      (is (contains? props "type"))
      (is (contains? props "content"))
      (is (contains? props "tags"))
      (is (contains? props "duration"))
      (is (contains? props "directory"))
      (is (contains? props "agent_id"))
      (is (contains? props "id"))
      (is (contains? props "ids"))
      (is (contains? props "query"))
      (is (contains? props "feedback"))
      (is (contains? props "operations"))
      (is (contains? props "parallel"))
      (is (contains? props "verbosity"))
      (is (contains? props "scope")))))

(deftest test-memory-description-mentions-batch
  (testing "memory description mentions batch commands"
    (is (str/includes? (:description memory/tool-def) "batch-add"))
    (is (str/includes? (:description memory/tool-def) "batch-feedback"))
    (is (str/includes? (:description memory/tool-def) "batch-get"))))

;; =============================================================================
;; Part 7: KG Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-kg-handlers-completeness
  (testing "KG handlers has all expected commands"
    (is (contains? kg/handlers :traverse))
    (is (contains? kg/handlers :edge))
    (is (contains? kg/handlers :impact))
    (is (contains? kg/handlers :subgraph))
    (is (contains? kg/handlers :stats))
    (is (contains? kg/handlers :path))
    (is (contains? kg/handlers :context))
    (is (contains? kg/handlers :promote))
    (is (contains? kg/handlers :reground))
    (is (contains? kg/handlers :batch-edge))
    (is (contains? kg/handlers :batch-traverse))))

(deftest test-kg-batch-handlers-are-functions
  (testing "KG batch handlers are valid functions"
    (is (fn? (:batch-edge kg/handlers)))
    (is (fn? (:batch-traverse kg/handlers)))))

(deftest test-kg-batch-edge-rejects-empty-operations
  (testing "batch-edge rejects empty operations"
    (let [result (kg/handle-batch-edge {:operations []})]
      (is (:isError result))
      (is (str/includes? (:text result) "operations is required")))))

(deftest test-kg-batch-traverse-rejects-empty-operations
  (testing "batch-traverse rejects empty operations"
    (let [result (kg/handle-batch-traverse {:operations []})]
      (is (:isError result))
      (is (str/includes? (:text result) "operations is required")))))

(deftest test-kg-tool-def-schema-params
  (testing "KG tool-def schema has key params"
    (let [props (get-in kg/tool-def [:inputSchema :properties])]
      (is (contains? props "start_node"))
      (is (contains? props "direction"))
      (is (contains? props "max_depth"))
      (is (contains? props "relations"))
      (is (contains? props "from"))
      (is (contains? props "to"))
      (is (contains? props "relation"))
      (is (contains? props "confidence"))
      (is (contains? props "node_id"))
      (is (contains? props "operations"))
      (is (contains? props "parallel")))))

;; =============================================================================
;; Part 8: Hivemind Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-hivemind-handlers-completeness
  (testing "hivemind handlers has all expected commands"
    (is (contains? hivemind/handlers :shout))
    (is (contains? hivemind/handlers :ask))
    (is (contains? hivemind/handlers :status))
    (is (contains? hivemind/handlers :respond))
    (is (contains? hivemind/handlers :messages))))

(deftest test-hivemind-handlers-are-functions
  (testing "all hivemind handlers are functions"
    (doseq [[k v] hivemind/handlers]
      (is (fn? v) (str "hivemind handler " k " should be a function")))))

(deftest test-hivemind-tool-def-schema-params
  (testing "hivemind tool-def schema has key params"
    (let [props (get-in hivemind/tool-def [:inputSchema :properties])]
      (is (contains? props "event_type"))
      (is (contains? props "task"))
      (is (contains? props "message"))
      (is (contains? props "question"))
      (is (contains? props "options"))
      (is (contains? props "agent_id"))
      (is (contains? props "ask_id"))
      (is (contains? props "decision"))
      (is (contains? props "directory")))))

(deftest test-hivemind-event-type-enum
  (testing "hivemind event_type has correct enum values"
    (let [enum (get-in hivemind/tool-def [:inputSchema :properties "event_type" :enum])]
      (is (= #{"progress" "completed" "error" "blocked" "started"} (set enum))))))

;; =============================================================================
;; Part 9: Kanban Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-kanban-handlers-completeness
  (testing "kanban handlers has all canonical + deprecated commands"
    ;; Canonical
    (is (contains? kanban/handlers :list))
    (is (contains? kanban/handlers :create))
    (is (contains? kanban/handlers :update))
    (is (contains? kanban/handlers :status))
    (is (contains? kanban/handlers :sync))
    (is (contains? kanban/handlers :plan-to-kanban))
    (is (contains? kanban/handlers :batch-update))
    ;; Deprecated aliases
    (is (contains? kanban/handlers :move))
    (is (contains? kanban/handlers :roadmap))
    (is (contains? kanban/handlers :my-tasks))))

(deftest test-kanban-deprecated-aliases-are-functions
  (testing "deprecated kanban aliases route to functions"
    (is (fn? (:move kanban/handlers)))
    (is (fn? (:roadmap kanban/handlers)))
    (is (fn? (:my-tasks kanban/handlers)))))

(deftest test-kanban-tool-def-schema-params
  (testing "kanban tool-def schema has key params"
    (let [props (get-in kanban/tool-def [:inputSchema :properties])]
      (is (contains? props "status"))
      (is (contains? props "title"))
      (is (contains? props "description"))
      (is (contains? props "task_id"))
      (is (contains? props "new_status"))
      (is (contains? props "plan_id"))
      (is (contains? props "plan_path"))
      (is (contains? props "operations"))
      (is (contains? props "directory")))))

(deftest test-kanban-status-enum
  (testing "kanban status enum is correct"
    (let [enum (get-in kanban/tool-def [:inputSchema :properties "status" :enum])]
      (is (= #{"todo" "inprogress" "inreview" "done"} (set enum))))))

;; =============================================================================
;; Part 10: Preset Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-preset-handlers-completeness
  (testing "preset handlers has canonical + deprecated commands"
    ;; Canonical
    (is (contains? preset/handlers :list))
    (is (contains? preset/handlers :get))
    (is (contains? preset/handlers :header))
    (is (contains? preset/handlers :search))
    (is (contains? preset/handlers :add))
    (is (contains? preset/handlers :delete))
    (is (contains? preset/handlers :status))
    (is (contains? preset/handlers :migrate))
    ;; Deprecated aliases
    (is (contains? preset/handlers :list_slim))
    (is (contains? preset/handlers :core))))

(deftest test-preset-canonical-handlers-exist
  (testing "preset canonical-handlers has only canonical commands"
    (is (contains? preset/canonical-handlers :list))
    (is (contains? preset/canonical-handlers :get))
    (is (contains? preset/canonical-handlers :header))
    (is (contains? preset/canonical-handlers :search))
    (is (contains? preset/canonical-handlers :add))
    (is (contains? preset/canonical-handlers :delete))
    (is (contains? preset/canonical-handlers :status))
    (is (contains? preset/canonical-handlers :migrate))
    ;; Should NOT have deprecated
    (is (not (contains? preset/canonical-handlers :list_slim)))
    (is (not (contains? preset/canonical-handlers :core)))))

(deftest test-preset-tool-def-verbosity-param
  (testing "preset tool-def has verbosity param with correct enum"
    (let [verbosity (get-in preset/tool-def [:inputSchema :properties "verbosity"])]
      (is (some? verbosity))
      (is (= #{"full" "slim" "core"} (set (:enum verbosity)))))))

;; =============================================================================
;; Part 11: Wave Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-wave-handlers-completeness
  (testing "wave handlers has all expected commands"
    (is (contains? wave/handlers :dispatch))
    (is (contains? wave/handlers :dispatch-validated))
    (is (contains? wave/handlers :status))
    (is (contains? wave/handlers :review))
    (is (contains? wave/handlers :approve))
    (is (contains? wave/handlers :reject))
    (is (contains? wave/handlers :auto-approve))))

(deftest test-wave-tool-def-schema-params
  (testing "wave tool-def schema has key params"
    (let [props (get-in wave/tool-def [:inputSchema :properties])]
      (is (contains? props "tasks"))
      (is (contains? props "preset"))
      (is (contains? props "trace"))
      (is (contains? props "validate"))
      (is (contains? props "max_retries"))
      (is (contains? props "wave_id"))
      (is (contains? props "diff_ids"))
      (is (contains? props "reason"))
      (is (contains? props "mode")))))

(deftest test-wave-dispatch-validates-mode-enum
  (testing "wave dispatch mode enum is correct"
    (let [enum (get-in wave/tool-def [:inputSchema :properties "mode" :enum])]
      (is (= #{"delegate" "agentic"} (set enum))))))

;; =============================================================================
;; Part 12: Magit Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-magit-handlers-completeness
  (testing "magit handlers has all expected commands"
    (is (contains? magit/handlers :status))
    (is (contains? magit/handlers :stage))
    (is (contains? magit/handlers :commit))
    (is (contains? magit/handlers :push))
    (is (contains? magit/handlers :branches))
    (is (contains? magit/handlers :log))
    (is (contains? magit/handlers :diff))
    (is (contains? magit/handlers :pull))
    (is (contains? magit/handlers :fetch))
    (is (contains? magit/handlers :feature-branches))
    (is (contains? magit/handlers :batch-commit))))

(deftest test-magit-batch-commit-is-function
  (testing "magit batch-commit handler is a function"
    (is (fn? (:batch-commit magit/handlers)))))

(deftest test-magit-batch-commit-rejects-empty-operations
  (testing "magit batch-commit rejects empty operations"
    (let [result ((:batch-commit magit/handlers) {:operations []})]
      (is (:isError result))
      (is (str/includes? (:text result) "operations is required")))))

(deftest test-magit-tool-def-schema-params
  (testing "magit tool-def schema has key params"
    (let [props (get-in magit/tool-def [:inputSchema :properties])]
      (is (contains? props "directory"))
      (is (contains? props "files"))
      (is (contains? props "message"))
      (is (contains? props "all"))
      (is (contains? props "set_upstream"))
      (is (contains? props "count"))
      (is (contains? props "target"))
      (is (contains? props "remote"))
      (is (contains? props "operations")))))

(deftest test-magit-diff-target-enum
  (testing "magit diff target enum is correct"
    (let [enum (get-in magit/tool-def [:inputSchema :properties "target" :enum])]
      (is (= #{"staged" "unstaged" "all"} (set enum))))))

;; =============================================================================
;; Part 13: Emacs Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-emacs-handlers-completeness
  (testing "emacs handlers has all expected commands"
    (is (contains? emacs/handlers :eval))
    (is (contains? emacs/handlers :buffers))
    (is (contains? emacs/handlers :notify))
    (is (contains? emacs/handlers :status))
    (is (contains? emacs/handlers :switch))
    (is (contains? emacs/handlers :find))
    (is (contains? emacs/handlers :save))
    (is (contains? emacs/handlers :current))))

(deftest test-emacs-tool-def-schema-params
  (testing "emacs tool-def schema has key params"
    (let [props (get-in emacs/tool-def [:inputSchema :properties])]
      (is (contains? props "code"))
      (is (contains? props "message"))
      (is (contains? props "level"))
      (is (contains? props "buffer"))
      (is (contains? props "file"))
      (is (contains? props "all")))))

(deftest test-emacs-notification-level-enum
  (testing "emacs notification level enum is correct"
    (let [enum (get-in emacs/tool-def [:inputSchema :properties "level" :enum])]
      (is (= #{"info" "warn" "error"} (set enum))))))

;; =============================================================================
;; Part 14: Analysis Composite Tool Integration Tests
;; =============================================================================

;; NOTE: Analysis is now a composite tool built dynamically from addon
;; command contributions. These tests verify the composite builder works
;; with mock contributions rather than static tool-def vars.

(deftest test-analysis-composite-builder
  (testing "composite builder produces valid tool-def from contributions"
    (let [ext-ns (requiring-resolve 'hive-mcp.extensions.registry/contribute-commands!)
          clear! (requiring-resolve 'hive-mcp.extensions.registry/clear-all!)]
      ;; Set up mock contributions
      (clear!)
      (ext-ns "analysis" :test-kondo
              {"lint" {:handler identity :params {"path" {:type "string"}} :description "test lint"}})
      (ext-ns "analysis" :test-scc
              {"scc" {:handler identity :params {"path" {:type "string"}} :description "test scc"}})
      (let [tool-def (composite/build-composite-tool "analysis" "Code analysis")]
        (is (string? (:name tool-def)))
        (is (= "analysis" (:name tool-def)))
        (is (true? (:consolidated tool-def)))
        (is (true? (:composite tool-def)))
        (is (string? (:description tool-def)))
        (is (map? (:inputSchema tool-def)))
        (is (ifn? (:handler tool-def)))
        ;; Schema has contributed params
        (let [props (get-in tool-def [:inputSchema :properties])]
          (is (contains? props "command"))
          (is (contains? props "path"))))
      (clear!))))

;; =============================================================================
;; Part 15: Agora Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-agora-handlers-completeness
  (testing "agora handlers has canonical + deprecated commands"
    ;; Canonical
    (is (contains? agora/handlers :dialogue))
    (is (contains? agora/handlers :dispatch))
    (is (contains? agora/handlers :consensus))
    (is (contains? agora/handlers :list))
    (is (contains? agora/handlers :join))
    (is (contains? agora/handlers :history))
    (is (contains? agora/handlers :debate))
    (is (contains? agora/handlers :debate-status))
    (is (contains? agora/handlers :continue))
    ;; Deprecated aliases
    (is (contains? agora/handlers :list-debates))
    (is (contains? agora/handlers :staged))
    (is (contains? agora/handlers :stage-status))))

(deftest test-agora-canonical-handlers-no-deprecated
  (testing "agora canonical-handlers does not contain deprecated aliases"
    (is (not (contains? agora/canonical-handlers :list-debates)))
    (is (not (contains? agora/canonical-handlers :staged)))
    (is (not (contains? agora/canonical-handlers :stage-status)))))

(deftest test-agora-tool-def-schema-params
  (testing "agora tool-def schema has key params"
    (let [props (get-in agora/tool-def [:inputSchema :properties])]
      (is (contains? props "participants"))
      (is (contains? props "topic"))
      (is (contains? props "dialogue_id"))
      (is (contains? props "message"))
      (is (contains? props "signal"))
      (is (contains? props "roles"))
      (is (contains? props "methodology"))
      (is (contains? props "staged"))
      (is (contains? props "type")))))

(deftest test-agora-signal-enum
  (testing "agora signal enum is correct"
    (let [enum (get-in agora/tool-def [:inputSchema :properties "signal" :enum])]
      (is (= #{"propose" "counter" "approve" "no-change" "defer"} (set enum))))))

;; =============================================================================
;; Part 17: Olympus Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-olympus-handlers-completeness
  (testing "olympus handlers has all expected commands"
    (is (contains? olympus/handlers :focus))
    (is (contains? olympus/handlers :arrange))
    (is (contains? olympus/handlers :tab))
    (is (contains? olympus/handlers :status))))

(deftest test-olympus-tool-def-schema-params
  (testing "olympus tool-def schema has key params"
    (let [props (get-in olympus/tool-def [:inputSchema :properties])]
      (is (contains? props "ling-id"))
      (is (contains? props "position"))
      (is (contains? props "restore"))
      (is (contains? props "mode"))
      (is (contains? props "direction"))
      (is (contains? props "tab")))))

;; =============================================================================
;; Part 18: Project Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-project-handlers-completeness
  (testing "project handlers has all expected commands"
    (is (contains? project/handlers :info))
    (is (contains? project/handlers :files))
    (is (contains? project/handlers :search))
    (is (contains? project/handlers :find))
    (is (contains? project/handlers :recent))
    (is (contains? project/handlers :list))
    (is (contains? project/handlers :scan))
    (is (contains? project/handlers :tree))
    (is (contains? project/handlers :staleness))))

(deftest test-project-tool-def-schema-params
  (testing "project tool-def schema has key params"
    (let [props (get-in project/tool-def [:inputSchema :properties])]
      (is (contains? props "filename"))
      (is (contains? props "pattern"))
      (is (contains? props "directory")))))

;; =============================================================================
;; Part 19: Cross-Tool Consistency Tests
;; =============================================================================

(deftest test-all-tool-names-unique
  (testing "all consolidated tool names are unique"
    (let [names (map (comp :name val) all-tool-defs)]
      (is (= (count names) (count (distinct names)))
          "duplicate tool names found"))))

(deftest test-all-tool-descriptions-non-empty
  (testing "all consolidated tool descriptions are non-empty"
    (doseq [[tool-key tool-def] all-tool-defs]
      (is (> (count (:description tool-def)) 20)
          (str tool-key " description too short")))))

(deftest test-all-tool-schemas-are-objects
  (testing "all consolidated tool schemas have type=object"
    (doseq [[tool-key tool-def] all-tool-defs]
      (is (= "object" (get-in tool-def [:inputSchema :type]))
          (str tool-key " schema should have type=object")))))

;; =============================================================================
;; Part 20: Handler-to-Tool Wiring Tests
;; =============================================================================

(deftest test-tool-handler-is-cli-handler
  (testing "each tool-def's :handler is the make-cli-handler result"
    ;; The handler should be a closure created by make-cli-handler
    ;; We verify by calling it - it should accept {:command "help"} and return
    ;; help text
    (doseq [[tool-key tool-def] all-tool-defs]
      (let [handler (:handler tool-def)
            result (handler {:command "help"})]
        (is (not (:isError result))
            (str tool-key " handler should respond to help"))
        (is (string? (:text result))
            (str tool-key " handler help should return text"))))))

;; =============================================================================
;; Part 21: Session Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-session-handlers-completeness
  (testing "session handlers has all expected commands"
    (is (contains? session/handlers :complete))
    (is (contains? session/handlers :wrap))
    (is (contains? session/handlers :whoami))
    (is (contains? session/handlers :catchup))
    (is (contains? session/handlers :context-put))
    (is (contains? session/handlers :context-get))
    (is (contains? session/handlers :context-query))
    (is (contains? session/handlers :context-evict))
    (is (contains? session/handlers :context-stats))
    (is (contains? session/handlers :context-reconstruct))))

(deftest test-session-handlers-are-functions
  (testing "all session handlers are functions"
    (doseq [[k v] session/handlers]
      (is (fn? v) (str "session handler " k " should be a function")))))

(deftest test-session-tool-def-schema-params
  (testing "session tool-def schema has key params"
    (let [props (get-in session/tool-def [:inputSchema :properties])]
      (is (contains? props "commit_msg"))
      (is (contains? props "task_ids"))
      (is (contains? props "agent_id"))
      (is (contains? props "directory"))
      (is (contains? props "data"))
      (is (contains? props "ctx_id"))
      (is (contains? props "tags"))
      (is (contains? props "ttl_ms"))
      (is (contains? props "limit"))
      (is (contains? props "ctx_refs"))
      (is (contains? props "kg_node_ids"))
      (is (contains? props "scope")))))

;; =============================================================================
;; Part 22: Config Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-config-handlers-completeness
  (testing "config handlers has all expected commands"
    (is (contains? config/handlers :get))
    (is (contains? config/handlers :set))
    (is (contains? config/handlers :list))
    (is (contains? config/handlers :reload))))

(deftest test-config-handlers-are-functions
  (testing "all config handlers are functions"
    (doseq [[k v] config/handlers]
      (is (fn? v) (str "config handler " k " should be a function")))))

(deftest test-config-tool-def-schema-params
  (testing "config tool-def schema has key params"
    (let [props (get-in config/tool-def [:inputSchema :properties])]
      (is (contains? props "key"))
      (is (contains? props "value")))))

;; =============================================================================
;; Part 23: Workflow Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-workflow-canonical-handlers-completeness
  (testing "workflow canonical-handlers has expected top-level commands"
    (is (contains? workflow/canonical-handlers :catchup))
    (is (contains? workflow/canonical-handlers :wrap))
    (is (contains? workflow/canonical-handlers :complete))
    (is (contains? workflow/canonical-handlers :forge))))

(deftest test-workflow-forge-nested-handlers
  (testing "workflow forge subtree has nested commands"
    (let [forge (:forge workflow/canonical-handlers)]
      (is (map? forge))
      (is (contains? forge :strike))
      (is (contains? forge :strike-imperative))
      (is (contains? forge :status))
      (is (contains? forge :quench))
      (is (contains? forge :_handler)))))

(deftest test-workflow-tool-def-schema-params
  (testing "workflow tool-def schema has key params"
    (let [props (get-in workflow/tool-def [:inputSchema :properties])]
      (is (contains? props "commit_msg"))
      (is (contains? props "task_ids"))
      (is (contains? props "agent_id"))
      (is (contains? props "directory"))
      (is (contains? props "max_slots"))
      (is (contains? props "presets"))
      (is (contains? props "spawn_mode"))
      (is (contains? props "model"))
      (is (contains? props "restart")))))

(deftest test-workflow-spawn-mode-enum
  (testing "workflow advertises the same spawn modes as agent"
    (let [enum (get-in workflow/tool-def [:inputSchema :properties "spawn_mode" :enum])]
      (is (= advertised-spawn-modes (set enum))))))

;; =============================================================================
;; Part 24: Migration Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-migration-handlers-completeness
  (testing "migration handlers has all expected commands"
    (is (contains? migration/handlers :status))
    (is (contains? migration/handlers :backup))
    (is (contains? migration/handlers :restore))
    (is (contains? migration/handlers :list))
    (is (contains? migration/handlers :switch))
    (is (contains? migration/handlers :sync))
    (is (contains? migration/handlers :export))
    (is (contains? migration/handlers :import))
    (is (contains? migration/handlers :validate))
    (is (contains? migration/handlers :adapters))))

(deftest test-migration-handlers-are-functions
  (testing "all migration handlers are functions"
    (doseq [[k v] migration/handlers]
      (is (fn? v) (str "migration handler " k " should be a function")))))

(deftest test-migration-tool-def-schema-params
  (testing "migration tool-def schema has key params"
    (let [props (get-in migration/tool-def [:inputSchema :properties])]
      (is (contains? props "scope"))
      (is (contains? props "dir"))
      (is (contains? props "adapter"))
      (is (contains? props "path"))
      (is (contains? props "latest"))
      (is (contains? props "dry-run"))
      (is (contains? props "target"))
      (is (contains? props "db-path"))
      (is (contains? props "backup"))
      (is (contains? props "backend"))
      (is (contains? props "limit"))
      (is (contains? props "directory")))))

(deftest test-migration-scope-enum
  (testing "migration scope enum is correct"
    (let [enum (get-in migration/tool-def [:inputSchema :properties "scope" :enum])]
      (is (= #{"kg" "memory" "full"} (set enum))))))

(deftest test-migration-target-enum
  (testing "migration target enum is correct"
    (let [enum (get-in migration/tool-def [:inputSchema :properties "target" :enum])]
      (is (= #{"datascript" "datalevin" "datahike"} (set enum))))))

(deftest test-migration-adapter-enum
  (testing "migration adapter enum is correct"
    (let [enum (get-in migration/tool-def [:inputSchema :properties "adapter" :enum])]
      (is (= #{"edn" "json"} (set enum))))))

;; =============================================================================
;; Part 25: Agent Consolidated Tool Integration Tests
;; =============================================================================

(deftest test-agent-canonical-handlers-completeness
  (testing "agent canonical-handlers has expected commands"
    (is (contains? agent/canonical-handlers :spawn))
    (is (contains? agent/canonical-handlers :status))
    (is (contains? agent/canonical-handlers :kill))
    (is (contains? agent/canonical-handlers :kill-batch))
    (is (contains? agent/canonical-handlers :batch-spawn))
    (is (contains? agent/canonical-handlers :dispatch))
    (is (contains? agent/canonical-handlers :claims))
    (is (contains? agent/canonical-handlers :collect))
    (is (contains? agent/canonical-handlers :broadcast))
    (is (contains? agent/canonical-handlers :cleanup))
    (is (contains? agent/canonical-handlers :dag))))

(deftest test-agent-dag-nested-handlers
  (testing "agent dag subtree has nested commands"
    (let [dag (:dag agent/canonical-handlers)]
      (is (map? dag))
      (is (contains? dag :start))
      (is (contains? dag :stop))
      (is (contains? dag :status))
      (is (contains? dag :_handler)))))

(deftest test-agent-tool-def-schema-params
  (testing "agent tool-def schema has key params"
    (let [props (get-in agent/tool-def [:inputSchema :properties])]
      (is (contains? props "type"))
      (is (contains? props "name"))
      (is (contains? props "cwd"))
      (is (contains? props "presets"))
      (is (contains? props "model"))
      (is (contains? props "task"))
      (is (contains? props "spawn_mode"))
      (is (contains? props "agent_id"))
      (is (contains? props "agent_ids"))
      (is (contains? props "operations"))
      (is (contains? props "parallel"))
      (is (contains? props "prompt"))
      (is (contains? props "files"))
      (is (contains? props "priority")))))

(deftest test-agent-type-enum
  (testing "agent type enum is correct"
    (let [enum (get-in agent/tool-def [:inputSchema :properties "type" :enum])]
      (is (= #{"ling" "drone"} (set enum))))))

(deftest test-agent-spawn-mode-enum
  (testing "agent advertises exactly the abstract spawn modes"
    (let [enum (get-in agent/tool-def [:inputSchema :properties "spawn_mode" :enum])]
      (is (= advertised-spawn-modes (set enum))))))

;; =============================================================================
;; Part 26: Multi Meta-Facade (consolidated/multi.clj) Integration Tests
;; =============================================================================

(deftest test-multi-tool-def-structure
  (testing "multi tool-def has required fields"
    (is (= "multi" (:name multi/tool-def)))
    (is (true? (:consolidated multi/tool-def)))
    (is (string? (:description multi/tool-def)))
    (is (map? (:inputSchema multi/tool-def)))
    (is (fn? (:handler multi/tool-def)))))

(deftest test-multi-tool-def-no-required-params
  (testing "multi tool-def has no required params (batch mode doesn't need tool)"
    (is (= [] (get-in multi/tool-def [:inputSchema :required])))))

(deftest test-multi-tool-def-has-tool-enum
  (testing "multi tool-def has tool enum listing all tools"
    (let [enum (set (get-in multi/tool-def [:inputSchema :properties "tool" :enum]))]
      (is (contains? enum "memory"))
      (is (contains? enum "kg"))
      (is (contains? enum "hivemind"))
      (is (contains? enum "kanban"))
      (is (contains? enum "preset"))
      (is (contains? enum "wave"))
      (is (contains? enum "magit"))
      (is (contains? enum "emacs"))
      (is (contains? enum "analysis"))
      (is (contains? enum "agora"))
      (is (contains? enum "olympus"))
      (is (contains? enum "project"))
      (is (contains? enum "session"))
      (is (contains? enum "config"))
      (is (contains? enum "workflow"))
      (is (contains? enum "migration"))
      (is (contains? enum "agent")))))

(deftest test-multi-handle-multi-routes-to-help
  (testing "handle-multi routes help command to consolidated tool"
    (let [result (multi/handle-multi {"tool" "memory" "command" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Available commands")))))

(deftest test-multi-handle-multi-nil-tool-shows-help
  (testing "handle-multi shows help when tool is nil"
    (let [result (multi/handle-multi {})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Multi tool")))))

(deftest test-multi-handle-multi-blank-tool-shows-help
  (testing "handle-multi shows help when tool is blank"
    (let [result (multi/handle-multi {"tool" ""})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Multi tool")))))

(deftest test-multi-handle-multi-help-tool-shows-help
  (testing "handle-multi shows help when tool is 'help'"
    (let [result (multi/handle-multi {"tool" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Multi tool")))))

(deftest test-multi-handle-multi-unknown-tool-errors
  (testing "handle-multi returns error for unknown tool"
    (let [result (multi/handle-multi {"tool" "nonexistent"})]
      (is (:isError result))
      (is (str/includes? (:text result) "Unknown tool")))))

(deftest test-multi-handle-multi-normalizes-string-keys
  (testing "handle-multi normalizes string keys to keywords"
    (let [result (multi/handle-multi {"tool" "preset" "command" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Available commands")))))

(deftest test-multi-handle-multi-case-insensitive-tool
  (testing "handle-multi tool lookup is case insensitive"
    (let [result (multi/handle-multi {"tool" "MEMORY" "command" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Available commands")))))

(deftest test-multi-handle-multi-forwards-params
  (testing "handle-multi forwards all params to target tool (minus :tool)"
    ;; Call kg help — should work because it forwards :command "help"
    (let [result (multi/handle-multi {"tool" "kg" "command" "help"})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Available commands")))))

(deftest test-multi-tools-vector
  (testing "multi module exposes tools vector with exactly 1 entry"
    (is (= 1 (count multi/tools)))
    (is (= multi/tool-def (first multi/tools)))))

(deftest test-multi-description-mentions-tools
  (testing "multi description mentions key tools"
    (let [desc (:description multi/tool-def)]
      (is (str/includes? desc "memory"))
      (is (str/includes? desc "agent"))
      (is (str/includes? desc "kg")))))

(deftest test-multi-description-advertises-dsl-aliases
  (testing "multi top-level description exposes DSL aliases"
    (let [desc (:description multi/tool-def)]
      (is (str/includes? desc "c=content"))
      (is (str/includes? desc "#=tags"))
      (is (str/includes? desc "d=directory")))))

;; =============================================================================
;; Part 26b: Multi Meta-Facade — Batch Dispatch Tests
;; =============================================================================

(deftest test-multi-batch-empty-ops-error
  (testing "batch mode rejects empty operations"
    (let [result (multi/handle-multi {"operations" []})]
      (is (:isError result))
      (is (str/includes? (:text result) "empty")))))

(deftest test-multi-batch-nil-ops-without-tool-shows-help
  (testing "nil operations without tool shows help (not batch error)"
    (let [result (multi/handle-multi {})]
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Multi tool")))))

(deftest test-multi-batch-dry-run
  (testing "batch mode dry run returns plan without executing"
    (let [result (multi/handle-multi
                  {"operations" [{"id" "op1" "tool" "memory" "command" "help"}
                                 {"id" "op2" "tool" "kg" "command" "help" "depends_on" ["op1"]}]
                   "dry_run" true})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (not (:isError result)))
      (is (true? (:success parsed)))
      (is (true? (:dry_run parsed)))
      (is (= 2 (get-in parsed [:summary :total])))
      ;; noop assign-waves: all ops land in wave 1
      (is (= 1 (get-in parsed [:summary :waves]))))))

(deftest test-multi-batch-dry-run-resolves-directory
  (testing "batch dry run shows effective caller directory and preserves per-op override"
    (let [caller-dir "/tmp/caller-project"
          explicit-dir "/tmp/explicit-project"
          result (multi/handle-multi
                  {"operations" [{"id" "op1" "tool" "memory" "command" "help"}
                                 {"id" "op2" "tool" "kg" "command" "help"
                                  "directory" explicit-dir}]
                   "directory" caller-dir
                   "dry_run" true})
          parsed (json/read-str (:text result) :key-fn keyword)
          ops (get-in parsed [:plan :wave_1])]
      (is (= caller-dir (:directory (first ops))))
      (is (= explicit-dir (:directory (second ops)))))))

(deftest test-multi-batch-execution
  (testing "batch mode executes operations and returns wave results"
    (let [result (multi/handle-multi
                  {"operations" [{"id" "op1" "tool" "memory" "command" "help"}
                                 {"id" "op2" "tool" "kg" "command" "help"}]})
          parsed (json/read-str (:text result) :key-fn keyword)]
      (is (not (:isError result)))
      (is (true? (:success parsed)))
      (is (= 2 (get-in parsed [:summary :total])))
      (is (= 2 (get-in parsed [:summary :success])))
      (is (= 0 (get-in parsed [:summary :failed]))))))

(deftest test-multi-batch-with-dependencies
  (testing "batch mode respects dependency ordering across waves"
    (let [result (multi/handle-multi
                  {"operations" [{"id" "op1" "tool" "memory" "command" "help"}
                                 {"id" "op2" "tool" "kg" "command" "help" "depends_on" ["op1"]}
                                 {"id" "op3" "tool" "preset" "command" "help"}]
                   "dry_run" true})
          parsed (json/read-str (:text result) :key-fn keyword)]
      ;; noop assign-waves: all ops land in wave 1
      (is (= 1 (get-in parsed [:summary :waves])))
      (is (= 3 (count (:wave_1 (:plan parsed))))))))

(deftest test-multi-batch-single-dispatch-wins-when-tool-present
  (testing "when both tool and operations are present, single dispatch wins"
    (let [result (multi/handle-multi
                  {"tool" "memory" "command" "help"
                   "operations" [{"id" "op1" "tool" "kg" "command" "help"}]})]
      ;; Should route to memory help (single dispatch), not batch
      (is (not (:isError result)))
      (is (str/includes? (:text result) "Available commands")))))

(deftest test-multi-help-mentions-batch
  (testing "multi help text mentions batch dispatch"
    (let [result (multi/handle-multi {})]
      (is (str/includes? (:text result) "Batch Dispatch"))
      (is (str/includes? (:text result) "operations")))))

(deftest test-multi-tool-def-has-dry-run-param
  (testing "multi tool-def schema includes dry_run parameter"
    (let [props (get-in multi/tool-def [:inputSchema :properties])]
      (is (contains? props "dry_run"))
      (is (= "boolean" (get-in props ["dry_run" :type]))))))

(deftest test-multi-tool-def-exposes-addon-doctor
  (testing "multi advertises addon routing and its doctor parameters"
    (let [props (get-in multi/tool-def [:inputSchema :properties])]
      (is (some #{"addon"} (get-in props ["tool" :enum])))
      (is (contains? props "addon_id"))
      (is (contains? props "emacs_features"))
      (is (contains? props "timeout_ms")))))

;; =============================================================================
;; Part 27: Multi Router Cross-Tool Integration
;; =============================================================================

(deftest test-multi-routes-to-all-tools-help
  (testing "handle-multi can route to every registered tool's help command"
    (doseq [tool-name (get-in multi/tool-def [:inputSchema :properties "tool" :enum])]
      (let [result (multi/handle-multi {"tool" tool-name "command" "help"})]
        (is (not (:isError result))
            (str "multi should route to " tool-name " help without error"))
        (is (string? (:text result))
            (str "multi should return text for " tool-name " help"))))))

(deftest test-multi-routes-unknown-command-to-tool
  (testing "handle-multi forwards unknown command — each tool errors correctly"
    (doseq [tool-name ["memory" "kg" "hivemind" "kanban" "preset" "config"]]
      (let [result (multi/handle-multi {"tool" tool-name "command" "nonexistent-cmd-xyz"})]
        (is (:isError result)
            (str tool-name " should error on unknown command via multi"))))))

(comment
  ;; Run all tests in this namespace via REPL
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'hive-mcp.tools.consolidated.multi-tool-integration-test))