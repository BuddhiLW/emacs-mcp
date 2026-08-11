(ns hive-mcp.tools.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-mcp.tools.cli :as cli]))

;; =============================================================================
;; format-help tests (flat handlers)
;; =============================================================================

(deftest format-help-test
  (testing "formats help text with all command names"
    (let [handlers {:foo (fn [_] nil)
                    :bar (fn [_] nil)
                    :baz (fn [_] nil)}
          help-text (cli/format-help handlers)]
      (is (str/includes? help-text "Available commands:"))
      (is (str/includes? help-text "foo"))
      (is (str/includes? help-text "bar"))
      (is (str/includes? help-text "baz"))))

  (testing "handles empty handlers map"
    (let [help-text (cli/format-help {})]
      (is (str/includes? help-text "Available commands:")))))

;; =============================================================================
;; format-help tests (nested handler trees)
;; =============================================================================

(deftest format-help-nested-test
  (testing "nested tree shows full paths"
    (let [handlers {:spawn (fn [_] nil)
                    :status {:list (fn [_] nil)
                             :detail (fn [_] nil)}}
          help-text (cli/format-help handlers)]
      (is (str/includes? help-text "spawn"))
      (is (str/includes? help-text "status list"))
      (is (str/includes? help-text "status detail"))))

  (testing "nested tree with _handler shows parent as valid"
    (let [handlers {:status {:_handler (fn [_] "default status")
                             :list (fn [_] nil)
                             :detail (fn [_] nil)}}
          help-text (cli/format-help handlers)]
      ;; "status" alone should be listed (because of _handler)
      (is (str/includes? help-text "  - status\n") "parent with _handler is listed")
      (is (str/includes? help-text "status detail"))
      (is (str/includes? help-text "status list"))))

  (testing "deeply nested tree"
    (let [handlers {:agent {:status {:list (fn [_] nil)
                                     :detail (fn [_] nil)}
                            :spawn (fn [_] nil)}}
          help-text (cli/format-help handlers)]
      (is (str/includes? help-text "agent spawn"))
      (is (str/includes? help-text "agent status list"))
      (is (str/includes? help-text "agent status detail"))))

  (testing "_handler entries are not listed as command names"
    (let [handlers {:foo {:_handler (fn [_] nil)
                          :bar (fn [_] nil)}}
          help-text (cli/format-help handlers)]
      (is (not (str/includes? help-text "_handler"))))))

;; =============================================================================
;; parse-command tests
;; =============================================================================

(deftest parse-command-test
  (testing "single word"
    (is (= [:status] (cli/parse-command "status"))))

  (testing "multi-word"
    (is (= [:status :list] (cli/parse-command "status list"))))

  (testing "trims whitespace"
    (is (= [:status :list] (cli/parse-command "  status  list  "))))

  (testing "nil returns nil"
    (is (nil? (cli/parse-command nil))))

  (testing "blank returns nil"
    (is (nil? (cli/parse-command "")))
    (is (nil? (cli/parse-command "   ")))))

;; =============================================================================
;; resolve-handler tests
;; =============================================================================

(deftest resolve-handler-flat-test
  (let [handler-fn (fn [_] :found)
        handlers {:status handler-fn :spawn handler-fn}]

    (testing "resolves flat handler"
      (let [result (cli/resolve-handler handlers [:status])]
        (is (= handler-fn (:handler result)))
        (is (= [:status] (:path-used result)))))

    (testing "returns error for unknown"
      (let [result (cli/resolve-handler handlers [:bogus])]
        (is (= :not-found (:error result)))))))

(deftest resolve-handler-nested-test
  (let [list-fn (fn [_] :list)
        detail-fn (fn [_] :detail)
        default-fn (fn [_] :default)
        handlers {:status {:list list-fn
                           :detail detail-fn
                           :_handler default-fn}
                  :spawn (fn [_] :spawn)}]

    (testing "resolves nested handler"
      (let [result (cli/resolve-handler handlers [:status :list])]
        (is (= list-fn (:handler result)))
        (is (= [:status :list] (:path-used result)))))

    (testing "resolves _handler fallback when no more path"
      (let [result (cli/resolve-handler handlers [:status])]
        (is (= default-fn (:handler result)))
        (is (= [:status] (:path-used result)))))

    (testing "_handler fallback for unknown subcommand"
      (let [result (cli/resolve-handler handlers [:status :unknown])]
        (is (= default-fn (:handler result)))
        (is (= [:status] (:path-used result)))
        (is (= [:unknown] (:remaining result)))))

    (testing "flat handler still works"
      (let [result (cli/resolve-handler handlers [:spawn])]
        (is (fn? (:handler result)))
        (is (= [:spawn] (:path-used result)))))))

(deftest resolve-handler-deep-nested-test
  (let [leaf-fn (fn [_] :leaf)
        handlers {:a {:b {:c leaf-fn}}}]

    (testing "3-depth resolution"
      (let [result (cli/resolve-handler handlers [:a :b :c])]
        (is (= leaf-fn (:handler result)))
        (is (= [:a :b :c] (:path-used result)))))

    (testing "partial path without _handler returns tree"
      (let [result (cli/resolve-handler handlers [:a :b])]
        (is (nil? (:handler result)))
        (is (map? (:tree result)))))))

;; =============================================================================
;; make-cli-handler dispatcher tests (backward compat - flat handlers)
;; =============================================================================

(deftest make-cli-handler-dispatches-correctly
  (let [handlers {:status (fn [_] {:ok true})
                  :spawn (fn [{:keys [name]}] {:spawned name})}
        cli-handler (cli/make-cli-handler handlers)]

    (testing "dispatches to status command"
      (is (= {:ok true} (cli-handler {:command "status"}))))

    (testing "dispatches to spawn with params"
      (is (= {:spawned "foo"} (cli-handler {:command "spawn" :name "foo"}))))

    (testing "dispatches with keyword command"
      (is (= {:ok true} (cli-handler {:command :status}))))

    (testing "passes all params through to handler"
      (let [handlers {:echo (fn [params] params)}
            handler (cli/make-cli-handler handlers)
            params {:command "echo" :a 1 :b 2 :nested {:x "y"}}]
        (is (= params (handler params)))))))

(deftest make-cli-handler-returns-handler-result
  (testing "returns exact result from handler"
    (let [expected {:type "text" :text "result" :meta {:id 123}}
          handlers {:cmd (fn [_] expected)}
          handler (cli/make-cli-handler handlers)]
      (is (= expected (handler {:command "cmd"})))))

  (testing "returns nil if handler returns nil"
    (let [handlers {:noop (fn [_] nil)}
          handler (cli/make-cli-handler handlers)]
      (is (nil? (handler {:command "noop"}))))))

;; =============================================================================
;; make-cli-handler n-depth dispatch tests
;; =============================================================================

(deftest make-cli-handler-ndepth-dispatch
  (let [list-fn (fn [_] {:result :list})
        detail-fn (fn [_] {:result :detail})
        default-fn (fn [_] {:result :default-status})
        spawn-fn (fn [{:keys [name]}] {:spawned name})
        handlers {:status {:list list-fn
                           :detail detail-fn
                           :_handler default-fn}
                  :spawn spawn-fn}
        cli-handler (cli/make-cli-handler handlers)]

    (testing "n-depth dispatch: 'status list'"
      (is (= {:result :list} (cli-handler {:command "status list"}))))

    (testing "n-depth dispatch: 'status detail'"
      (is (= {:result :detail} (cli-handler {:command "status detail"}))))

    (testing "_handler fallback: 'status' alone"
      (is (= {:result :default-status} (cli-handler {:command "status"}))))

    (testing "_handler fallback: 'status unknown'"
      (is (= {:result :default-status} (cli-handler {:command "status unknown"}))))

    (testing "flat handler still works alongside nested"
      (is (= {:spawned "foo"} (cli-handler {:command "spawn" :name "foo"}))))

    (testing "help shows nested commands"
      (let [result (cli-handler {:command "help"})]
        (is (= "text" (:type result)))
        (is (str/includes? (:text result) "spawn"))
        (is (str/includes? (:text result) "status"))
        (is (str/includes? (:text result) "status list"))
        (is (str/includes? (:text result) "status detail"))))))

(deftest make-cli-handler-ndepth-deep
  (let [leaf-fn (fn [_] {:depth 3})
        b-default (fn [_] {:depth 2})
        handlers {:a {:b {:c leaf-fn
                          :_handler b-default}}}
        cli-handler (cli/make-cli-handler handlers)]

    (testing "3-depth dispatch: 'a b c'"
      (is (= {:depth 3} (cli-handler {:command "a b c"}))))

    (testing "2-depth with _handler fallback: 'a b'"
      (is (= {:depth 2} (cli-handler {:command "a b"}))))

    (testing "unknown at depth 2 falls back to _handler: 'a b xyz'"
      (is (= {:depth 2} (cli-handler {:command "a b xyz"}))))

    (testing "unknown at depth 1 without _handler: 'a' returns error"
      ;; :a is a map without _handler, so no handler found
      (let [result (cli-handler {:command "a"})]
        (is (:isError result))))))

;; =============================================================================
;; Unknown command handling tests
;; =============================================================================

(deftest unknown-command-returns-error
  (let [handlers {:status (fn [_] :status)
                  :spawn (fn [_] :spawn)
                  :kill (fn [_] :kill)}
        cli-handler (cli/make-cli-handler handlers)]

    (testing "unknown command returns error map"
      (let [result (cli-handler {:command "bogus"})]
        (is (:isError result) "should have :isError flag")
        (is (string? (:text result)) "should have error text")))

    (testing "error mentions the unknown command"
      (let [result (cli-handler {:command "nonexistent"})]
        (is (str/includes? (:text result) "nonexistent"))))

    (testing "error lists available commands"
      (let [result (cli-handler {:command "bad"})]
        (is (str/includes? (:text result) "status"))
        (is (str/includes? (:text result) "spawn"))
        (is (str/includes? (:text result) "kill"))))

    (testing "doesn't throw exceptions for unknown commands"
      (is (map? (cli-handler {:command ""})))
      (is (map? (cli-handler {:command nil})))
      (is (map? (cli-handler {:command "!@#$%"}))))))

(deftest unknown-command-suggests-the-subdomain-qualified-form
  (let [handlers    {:cider   {:eval (fn [_] :eval)}
                     :clojure {:_handler (fn [_] :clojure)}
                     :carto   {:_handler (fn [_] :carto)}}
        cli-handler (cli/make-cli-handler handlers)]

    (testing "a bare subcommand names the qualified form under each delegating subdomain"
      (let [text (:text (cli-handler {:command "scan"}))]
        (is (str/includes? text "carto scan"))
        (is (str/includes? text "clojure scan"))))

    (testing "subdomains WITHOUT :_handler are not offered — their subcommands
              are already visible in this tree, so an unknown token is not theirs"
      (is (not (str/includes? (:text (cli-handler {:command "scan"})) "cider scan"))))

    (testing "a tree with no delegating subdomain adds no suggestion"
      (let [flat (cli/make-cli-handler {:status (fn [_] :status)})
            text (:text (flat {:command "scan"}))]
        (is (str/includes? text "Unknown command"))
        (is (not (str/includes? text "SUBCOMMAND")))))

    (testing "a nil/blank command still errors without a bogus suggestion"
      (doseq [c [nil ""]]
        (let [r (cli-handler {:command c})]
          (is (:isError r))
          (is (not (str/includes? (:text r) "SUBCOMMAND"))))))))

(deftest unknown-command-suggests-metadata-marked-opaque-roots
  (testing "a contributed subdomain (bare fn + ::opaque-roots metadata) is
            offered alongside the core :_handler ones"
    (let [handlers (with-meta {:clojure {:_handler (fn [_] :clojure)}
                               :carto   (fn [_] :carto)}
                     {:hive-mcp.tools.cli/opaque-roots #{:carto}})
          text     (:text ((cli/make-cli-handler handlers) {:command "scan"}))]
      (is (str/includes? text "carto scan") "the regression this fixes")
      (is (str/includes? text "clojure scan"))))

  (testing "the stuttering carto_* form names exactly one subdomain"
    (let [handlers (with-meta {:clojure  {:_handler (fn [_] :clojure)}
                               :analysis {:_handler (fn [_] :analysis)}
                               :carto    (fn [_] :carto)}
                     {:hive-mcp.tools.cli/opaque-roots #{:carto}})
          text     (:text ((cli/make-cli-handler handlers)
                           {:command "carto_definition"}))]
      (is (str/includes? text "carto carto_definition"))
      (is (not (str/includes? text "clojure carto_definition")))
      (is (not (str/includes? text "analysis carto_definition")))))

  (testing "an unmarked leaf fn is still never offered as a subdomain"
    (let [text (:text ((cli/make-cli-handler {:status (fn [_] :status)})
                       {:command "scan"}))]
      (is (not (str/includes? text "SUBCOMMAND"))))))

(deftest unknown-command-names-exact-owners-when-enumerable
  (testing "a subcommand this tree CAN see is named precisely, never guessed"
    (let [handlers {:kanban  {:list (fn [_] :k)}
                    :session {:list (fn [_] :s)}
                    :carto   {:_handler (fn [_] :c)}}
          text     (:text ((cli/make-cli-handler handlers) {:command "list"}))]
      (is (str/includes? text "kanban list"))
      (is (str/includes? text "session list"))
      (is (not (str/includes? text "carto list"))
          "an opaque root is not offered once a real owner is known"))))

(deftest unknown-command-suggests-nearest-name
  (testing "a near miss gets an edit-distance suggestion"
    (let [handlers {:status (fn [_] :s) :spawn (fn [_] :p)}
          text     (:text ((cli/make-cli-handler handlers) {:command "staus"}))]
      (is (str/includes? text "Did you mean"))
      (is (str/includes? text "status"))))

  (testing "a far miss gets none"
    (let [handlers {:status (fn [_] :s)}
          text     (:text ((cli/make-cli-handler handlers) {:command "zzzzzz"}))]
      (is (not (str/includes? text "Did you mean")))))

  (testing "a nested path is suggested by its full form"
    (let [handlers {:status {:list (fn [_] :l)}}
          text     (:text ((cli/make-cli-handler handlers)
                           {:command "status lst"}))]
      (is (str/includes? text "status list")))))

;; =============================================================================
;; Help command generation tests
;; =============================================================================

(deftest help-command-generation
  (let [handlers {:status (fn [_] nil)
                  :spawn (fn [_] nil)
                  :collect (fn [_] nil)}
        cli-handler (cli/make-cli-handler handlers)]

    (testing "help command returns text type"
      (let [result (cli-handler {:command "help"})]
        (is (= "text" (:type result)))))

    (testing "help lists all available subcommands"
      (let [result (cli-handler {:command "help"})]
        (is (str/includes? (:text result) "status"))
        (is (str/includes? (:text result) "spawn"))
        (is (str/includes? (:text result) "collect"))))

    (testing "help works with keyword command"
      (let [result (cli-handler {:command :help})]
        (is (= "text" (:type result)))
        (is (str/includes? (:text result) "Available commands:"))))))
