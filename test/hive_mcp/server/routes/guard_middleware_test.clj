;; SPDX-License-Identifier: LicenseRef-Proprietary
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(ns hive-mcp.server.routes.guard-middleware-test
  "Contract tests for the MCP tool-dispatch gate — the vendor-independent floor.

   The gate is a SOFT seam: with no `:guard/decide` extension registered, every
   call must behave exactly as it did before the gate existed. The two
   properties that matter are that a :deny actually prevents execution, and
   that a broken guard cannot brick the server."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.server.routes.middleware :as mw]))

(defn- with-seam*
  "Register `f` as the :guard/decide extension for the duration of `body-fn`."
  [f body-fn]
  (try
    (ext/register! :guard/decide f)
    (body-fn)
    (finally (ext/deregister! :guard/decide))))

(defn- counting-handler
  "A handler that records every args map it was called with."
  [calls]
  (fn [args] (swap! calls conj args) [{:type "text" :text "ran"}]))

(defn- deny-seam [& {:as decision}]
  (fn [_harness _raw]
    (merge {:guard/verdict :deny
            :guard/reason "structural edits go through carto"
            :guard/rule-id :guard/carto-first
            :guard/citations ["20260529133309-1805d6d4"]}
           decision)))

(defn- text-of [content] (apply str (map :text content)))

;;; ===========================================================================
;;; The soft seam
;;; ===========================================================================

(deftest with-no-seam-registered-the-call-is-untouched
  (ext/deregister! :guard/decide)
  (let [calls (atom [])
        h (mw/wrap-handler-guard (counting-handler calls) "mcp__hive__bash")
        out (h {:command "sed -i x foo.clj"})]
    (is (= [{:command "sed -i x foo.clj"}] @calls)
        "an unguarded server must behave exactly as before the gate existed")
    (is (= [{:type "text" :text "ran"}] out))))

(deftest an-allow-is-a-passthrough
  (with-seam*
    (fn [_ _] {:guard/verdict :allow :guard/enforcing? true})
    (fn []
      (let [calls (atom [])
            h (mw/wrap-handler-guard (counting-handler calls) "t")
            out (h {:x 1})]
        (is (= 1 (count @calls)))
        (is (= [{:type "text" :text "ran"}] out)
            "an allow must not decorate the response")))))

;;; ===========================================================================
;;; Deny — the property the floor exists for
;;; ===========================================================================

(deftest a-deny-prevents-execution
  (with-seam*
    (deny-seam)
    (fn []
      (let [calls (atom [])
            h (mw/wrap-handler-guard (counting-handler calls) "mcp__hive__bash")
            out (h {:command "sed -i x foo.clj"})]
        (is (empty? @calls)
            "the handler must never run for a denied call")
        (is (true? (:isError (first out))))
        (is (re-find #"REFUSED" (text-of out)))))))

(deftest a-deny-is-arguable
  (with-seam*
    (deny-seam)
    (fn []
      (let [h (mw/wrap-handler-guard (constantly :never) "mcp__hive__bash")
            txt (text-of (h {:command "sed -i x"}))]
        (testing "the refusal carries what the caller needs to argue with it"
          (is (re-find #"structural edits go through carto" txt)
              "the reason")
          (is (re-find #"20260529133309-1805d6d4" txt)
              "the axiom it was derived from")
          (is (re-find #"carto-first" txt)
              "the rule id")
          (is (re-find #"mcp__hive__bash" txt)
              "the tool it refused"))))))

;;; ===========================================================================
;;; Warn — advisory, never silent
;;; ===========================================================================

(deftest a-warn-runs-the-tool-and-appends-the-advisory
  (with-seam*
    (fn [_ _] {:guard/verdict :warn
               :guard/reason "prefer carto search"
               :guard/rule-id :guard/carto-first-shell-search})
    (fn []
      (let [calls (atom [])
            h (mw/wrap-handler-guard (counting-handler calls) "Bash")
            out (h {:command "grep -r x"})]
        (is (= 1 (count @calls)) "a warn must not prevent execution")
        (is (= 2 (count out)) "the advisory is appended to the tool's own content")
        (is (= "ran" (:text (first out))))
        (is (re-find #"GUARD WARNING" (:text (second out))))
        (is (re-find #"prefer carto search" (:text (second out))))))))

;;; ===========================================================================
;;; A broken guard must not brick the server
;;; ===========================================================================

(deftest a-throwing-seam-does-not-break-the-call
  (with-seam*
    (fn [_ _] (throw (ex-info "guard exploded" {})))
    (fn []
      (let [calls (atom [])
            h (mw/wrap-handler-guard (counting-handler calls) "t")
            out (h {:x 1})]
        (is (= 1 (count @calls))
            "a guard that throws must not take every tool down with it")
        (is (= [{:type "text" :text "ran"}] out))))))

(deftest a-seam-returning-junk-does-not-deny
  (doseq [junk [nil {} {:guard/verdict :nonsense} "not a map" 42]]
    (with-seam*
      (fn [_ _] junk)
      (fn []
        (let [calls (atom [])
              h (mw/wrap-handler-guard (counting-handler calls) "t")]
          (h {:x 1})
          (is (= 1 (count @calls))
              (str "only an explicit :deny may refuse; got " (pr-str junk))))))))

;;; ===========================================================================
;;; What the seam is told
;;; ===========================================================================

(deftest the-seam-is-told-the-harness-tool-and-input
  (let [seen (atom nil)]
    (with-seam*
      (fn [harness raw] (reset! seen [harness raw]) nil)
      (fn []
        (let [h (mw/wrap-handler-guard (constantly :ok) "mcp__hive__git")]
          (h {:command "stage" :files "all"})
          (let [[harness raw] @seen]
            (is (= :mcp harness))
            (is (= "mcp__hive__git" (:tool raw)))
            (is (= {:command "stage" :files "all"} (:input raw)))))))))

;;; ===========================================================================
;;; Placement in the chain
;;; ===========================================================================

(deftest the-gate-runs-OUTSIDE-async-so-a-denied-call-is-never-spawned
  (with-seam*
    (deny-seam)
    (fn []
      (let [calls (atom [])
            chained (-> (counting-handler calls)
                        (mw/wrap-handler-async "mcp__hive__bash")
                        (mw/wrap-handler-guard "mcp__hive__bash"))
            out (chained {:command "sed -i x" :async true})]
        (is (empty? @calls)
            "a denied async call must not be spawned as a future")
        (is (re-find #"REFUSED" (text-of out))
            "the caller gets the refusal, not a queued-task ack")
        (is (not (re-find #"queued" (text-of out))))))))

(deftest the-full-chain-refuses-before-the-handler
  (with-seam*
    (deny-seam)
    (fn []
      (let [calls (atom [])
            chained (mw/build-middleware-chain (counting-handler calls)
                                               "mcp__hive__bash" nil)
            out (chained {"command" "sed -i x foo.clj"})]
        (is (empty? @calls))
        (is (re-find #"REFUSED" (text-of (:content out)))
            "the refusal survives normalize/compress/piggybacks/response")))))

(deftest the-full-chain-is-untouched-with-no-seam
  (ext/deregister! :guard/decide)
  (let [calls (atom [])
        chained (mw/build-middleware-chain (counting-handler calls)
                                           "mcp__hive__bash" nil)
        out (chained {"command" "ls"})]
    (is (= 1 (count @calls)))
    (is (re-find #"ran" (text-of (:content out))))))
