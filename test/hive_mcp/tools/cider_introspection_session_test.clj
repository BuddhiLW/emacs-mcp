(ns hive-mcp.tools.cider-introspection-session-test
  "Contract tests for session-scoped CIDER introspection.

   The elisp side (hive-mcp-cider-doc/-info/-apropos/-complete) takes an
   optional SESSION-NAME; these assert the MCP handlers actually pass it, and
   that a blank name degrades to nil rather than reaching Emacs as a session
   that cannot resolve.

   Kanban: 20260731195834-02e0f26e"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hive-mcp.tools.cider :as tools]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.test.stub.elisp :as elisp-stub]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each elisp-stub/with-elisp-builders)

(defn- capture-elisp
  "Run HANDLER on PARAMS with the transport mocked; return the elisp it built."
  [handler params]
  (let [seen (atom nil)]
    (with-redefs [ec/eval-elisp (fn [elisp]
                                  (reset! seen elisp)
                                  {:success true :result "{}" :duration-ms 1})]
      (handler params))
    @seen))

(def ^:private queries
  "Handler, its non-session params, and the elisp fn it must reach."
  [[#'tools/handle-cider-doc      {:symbol "map"}    "hive-mcp-cider-doc"]
   [#'tools/handle-cider-info     {:symbol "map"}    "hive-mcp-cider-info"]
   [#'tools/handle-cider-complete {:prefix "ma"}     "hive-mcp-cider-complete"]
   [#'tools/handle-cider-apropos  {:pattern "^map$"} "hive-mcp-cider-apropos"]])

(deftest session-name-reaches-every-introspection-query
  (testing "a named session is threaded through as the trailing elisp argument"
    (doseq [[handler params fn-name] queries]
      (let [elisp (capture-elisp handler (assoc params :session_name "worker-1"))]
        (is (str/includes? elisp fn-name)
            (str fn-name " must be the elisp fn called"))
        (is (str/includes? elisp "\"worker-1\"")
            (str fn-name " must receive the session name"))))))

(deftest omitted-session-name-stays-nil
  (testing "no session means the trailing argument is elisp nil, not a name"
    (doseq [[handler params fn-name] queries]
      (let [elisp (capture-elisp handler params)]
        (is (str/includes? elisp "nil)")
            (str fn-name " must pass nil for the absent session"))))))

(deftest blank-session-name-degrades-to-nil
  (testing "a blank name never reaches Emacs as a session to resolve"
    (doseq [[handler params fn-name] queries
            blank ["" "   "]]
      (let [elisp (capture-elisp handler (assoc params :session_name blank))]
        (is (not (str/includes? elisp (str \" blank \")))
            (str fn-name " must not forward a blank session name"))))))

(deftest apropos-keeps-search-docs-before-session
  (testing "the docs flag stays in its own position when a session is given"
    (let [elisp (capture-elisp #'tools/handle-cider-apropos
                               {:pattern "map" :search_docs true
                                :session_name "worker-1"})]
      (is (str/includes? elisp "t \"worker-1\"")
          "search_docs must precede the session name"))))
