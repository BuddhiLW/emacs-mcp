(ns hive-mcp.tools.magit-timeout-test
  "Per-call timeout for the Emacs-backed git tools.

   The client already accepted a timeout and already clamped it; what was
   missing was any way for a CALLER to ask. Three properties:

   1. Omitting `timeout_ms` routes through the untimed seam, so the client's
      own default still applies and nothing about existing calls changes.
   2. Passing it routes through the timed seam and the value arrives VERBATIM.
      It is deliberately not clamped here — `hive-emacs.client` is the last
      line of defense, and a second ceiling would be a second place the limit
      lives.
   3. EVERY git command honours it. A handler added later without threading
      the parameter is the regression this suite exists to catch, so the test
      enumerates the tool's own command map rather than a hand-kept list."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.test.stub.emacs-ext :as se]
            [hive-mcp.tools.consolidated.git :as git]
            [hive-mcp.tools.consolidated.magit :as magit]
            [hive-mcp.tools.core :as core]
            [hive-mcp.tools.magit :as tools]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private base-params
  {:directory "/tmp/repo" :message "m" :files "src/a.clj" :remote "origin"})

(defn- timed-calls
  "The [elisp timeout-ms] vectors that reached the TIMED seam."
  [stub]
  (se/calls-of stub :emacs/eval-elisp-with-timeout))

(defn- untimed-calls
  [stub]
  (se/calls-of stub :emacs/eval-elisp))

;;; ===========================================================================
;;; The parameter reader
;;; ===========================================================================

(deftest an-absent-timeout-is-nil-not-an-error
  (is (nil? (core/emacs-timeout-ms {})))
  (is (nil? (core/emacs-timeout-ms {:directory "/tmp"})))
  (is (nil? (core/emacs-timeout-ms {:timeout_ms nil}))))

(deftest a-timeout-is-read-and-coerced
  (is (= 60000 (core/emacs-timeout-ms {:timeout_ms 60000})))
  (is (= 20000 (core/emacs-timeout-ms {:timeout_ms "20000"}))
      "string spelling, per the house convention for numeric tool params"))

(deftest a-malformed-timeout-is-LOUD
  (testing "a caller who asked for 60s and silently got 5s cannot tell that
            apart from a command that was simply slow"
    (doseq [bad ["soon" "60s" {} []]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/emacs-timeout-ms {:timeout_ms bad}))
          (str "expected a throw for " (pr-str bad))))))

;;; ===========================================================================
;;; Routing
;;; ===========================================================================

(deftest without-a-timeout-nothing-changes
  (se/with-stub-emacs [stub {}]
    (tools/handle-magit-status {:directory "/tmp/repo"})
    (is (= 1 (count (untimed-calls stub)))
        "the untimed seam still carries a call that asked for no budget")
    (is (empty? (timed-calls stub)))))

(deftest a-passed-timeout-reaches-the-client-verbatim
  (se/with-stub-emacs [stub {}]
    (tools/handle-magit-push {:directory "/tmp/repo" :timeout_ms 60000})
    (is (empty? (untimed-calls stub)))
    (let [[[_elisp timeout]] (timed-calls stub)]
      (is (= 60000 timeout)
          "unclamped here on purpose — hive-emacs.client applies the ceiling"))))

(deftest a-string-timeout-arrives-as-a-number
  (se/with-stub-emacs [stub {}]
    (tools/handle-magit-push {:directory "/tmp/repo" :timeout_ms "45000"})
    (is (= 45000 (second (first (timed-calls stub)))))))

(def ^:private command-params
  "One representative invocation per git command that reaches Emacs.
   `batch-commit` is excluded: it is a fan-out over :commit, already covered."
  {:status           {}
   :stage            {:files "src/a.clj"}
   :commit           {:message "m"}
   :push             {:set_upstream true}
   :branches         {}
   :log              {:count 5}
   :diff             {:target "staged"}
   :pull             {}
   :fetch            {:remote "origin"}
   :feature-branches {}})

(deftest every-emacs-backed-git-command-honours-the-timeout
  (testing "a handler added later without threading timeout_ms is the
            regression this catches"
    (doseq [[command extra] command-params]
      (let [handler (get git/canonical-handlers command)]
        (is (some? handler) (str "no handler for " command))
        (se/with-stub-emacs [stub {}]
          (handler (merge base-params extra {:timeout_ms 12345}))
          (let [timed (timed-calls stub)]
            (is (seq timed)
                (str command " did not route through the timed seam"))
            (is (every? #(= 12345 (second %)) timed)
                (str command " reached Emacs with the wrong budget: "
                     (pr-str (mapv second timed))))
            (is (empty? (untimed-calls stub))
                (str command " still made an untimed call"))))))))

(deftest the-commands-covered-are-the-commands-the-tool-offers
  (testing "so a new command cannot be added without this suite noticing"
    (let [reachable (disj (set (keys git/canonical-handlers)) :batch-commit)]
      (is (= reachable (set (keys command-params)))
          "command-params has drifted from the tool's own handler map"))))

;;; ===========================================================================
;;; Schema — one definition, N tools
;;; ===========================================================================

(deftest both-git-tools-declare-the-parameter-from-one-definition
  (doseq [[label tool-def] [["git" git/tool-def] ["magit" magit/tool-def]]]
    (let [props (get-in tool-def [:inputSchema :properties])]
      (is (contains? props "timeout_ms")
          (str label " does not offer timeout_ms"))
      (is (= (get core/emacs-timeout-ms-property "timeout_ms")
             (get props "timeout_ms"))
          (str label " restates the property instead of splicing the one definition")))))

(deftest the-declared-parameter-is-an-integer-and-says-what-it-costs
  (let [p (get core/emacs-timeout-ms-property "timeout_ms")]
    (is (= "integer" (:type p)))
    (is (re-find #"5000" (:description p)) "names the default")
    (is (re-find #"30000" (:description p)) "names the ceiling")))
