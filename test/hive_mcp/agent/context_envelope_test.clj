(ns hive-mcp.agent.context-envelope-test
  "Tests for the L2 Context Envelope seam.

   Envelope RENDERING is an addon concern: `hive-mcp.agent.context-envelope`
   delegates to `:ctx/enrich` / `:ctx/prepare-spawn` (falling back to the
   `hive-mcp.extensions.context` namespace, which core does not ship). What
   core owns, and what these tests pin, is the seam itself:

     - absent implementation degrades to nil, never throws
     - the implementation receives exactly the arguments core promises it
     - output is capped at `max-context-chars`
     - a non-string or empty result becomes nil
     - dispatch-context shapes route to the right call

   The envelope's text format is contract-tested where it is produced."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.context-envelope :as envelope]
            [hive-mcp.protocols.dispatch :as dispatch-ctx]
            [hive-mcp.channel.context-store :as context-store]
            [hive-mcp.test.stub.extensions :as ext-stub]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-context-store [f]
  (context-store/reset-all!)
  (f)
  (context-store/reset-all!))

;; The addon may be mounted in a live image; absence must be ARRANGED so these
;; tests read the same hot and cold.
(defn without-context-extensions [f]
  (ext-stub/without-extensions [:ctx/enrich :ctx/prepare-spawn] f))

(use-fixtures :each reset-context-store)

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-axioms
  [{:id "ax-1" :content "Never spawn drones from lings"}
   {:id "ax-2" :content "Cap 5-6 lings per Emacs daemon"}])

(defn- recording-enrich
  "An `:ctx/enrich` impl that records its args and returns RESULT."
  [seen result]
  (fn [ctx-refs kg-node-ids scope opts]
    (reset! seen {:ctx-refs ctx-refs :kg-node-ids kg-node-ids
                  :scope scope :opts opts})
    result))

;; =============================================================================
;; Degradation — no implementation registered
;; =============================================================================

(deftest enrich-context-returns-nil-for-empty-refs
  (testing "returns nil when no refs or KG nodes provided"
    (without-context-extensions
     (fn []
       (is (nil? (envelope/enrich-context {} [] nil)))
       (is (nil? (envelope/enrich-context nil nil nil)))))))

(deftest enrich-context-returns-nil-when-no-implementation
  (testing "an absent :ctx/enrich degrades to nil rather than throwing"
    (without-context-extensions
     (fn []
       (is (nil? (envelope/enrich-context {:axioms "ctx-123"} ["node-1"] "hive-mcp"
                                          {:mode :deferred})))))))

(deftest prepare-spawn-context-returns-nil-when-no-implementation
  (testing "an absent :ctx/prepare-spawn degrades to nil"
    (without-context-extensions
     (fn []
       (is (nil? (envelope/prepare-spawn-context "/tmp/project")))))))

(deftest enrich-context-swallows-implementation-failure
  (testing "a throwing implementation degrades to nil, never propagates"
    (ext-stub/with-extensions
      {:ctx/enrich (fn [& _] (throw (Exception. "mock failure")))}
      (fn []
        (is (nil? (envelope/enrich-context {:axioms "ctx-123"} [] "hive-mcp"
                                           {:mode :inline})))))))

;; =============================================================================
;; Delegation — the implementation gets what core promises
;; =============================================================================

(deftest enrich-context-passes-all-arguments-through
  (testing ":ctx/enrich receives ctx-refs, kg-node-ids, scope and opts"
    (let [seen (atom nil)]
      (ext-stub/with-extensions
        {:ctx/enrich (recording-enrich seen "<!-- L2-CONTEXT mode=deferred -->")}
        (fn []
          (let [result (envelope/enrich-context {:axioms "ctx-123" :decisions "ctx-456"}
                                                ["20260207-dec1"]
                                                "hive-mcp"
                                                {:mode :deferred})]
            (is (= "<!-- L2-CONTEXT mode=deferred -->" result))
            (is (= {:ctx-refs    {:axioms "ctx-123" :decisions "ctx-456"}
                    :kg-node-ids ["20260207-dec1"]
                    :scope       "hive-mcp"
                    :opts        {:mode :deferred}}
                   @seen))))))))

(deftest enrich-context-3-arity-defaults-opts-to-empty-map
  (testing "the 3-arity form hands the implementation an empty opts map"
    (let [seen (atom nil)]
      (ext-stub/with-extensions
        {:ctx/enrich (recording-enrich seen "envelope")}
        (fn []
          (envelope/enrich-context {:axioms "ctx-1"} [] "hive-mcp")
          (is (= {} (:opts @seen))))))))

(deftest prepare-spawn-context-passes-directory-and-opts
  (testing ":ctx/prepare-spawn receives directory and opts"
    (let [seen (atom nil)]
      (ext-stub/with-extensions
        {:ctx/prepare-spawn (fn [directory opts]
                              (reset! seen {:directory directory :opts opts})
                              "spawn-context")}
        (fn []
          (is (= "spawn-context" (envelope/prepare-spawn-context "/tmp/p" {:mode :inline})))
          (is (= {:directory "/tmp/p" :opts {:mode :inline}} @seen)))))))

;; =============================================================================
;; Output capping — core's own responsibility
;; =============================================================================

(deftest enrich-context-caps-oversized-output
  (testing "output longer than max-context-chars is truncated"
    (ext-stub/with-extensions
      {:ctx/enrich (fn [& _] (apply str (repeat (* 3 envelope/max-context-chars) "x")))}
      (fn []
        (let [result (envelope/enrich-context {:axioms "ctx-1"} [] "hive-mcp")]
          (is (= envelope/max-context-chars (count result))
              "capped to exactly max-context-chars"))))))

(deftest enrich-context-leaves-small-output-untouched
  (testing "output within the cap is returned verbatim"
    (ext-stub/with-extensions
      {:ctx/enrich (fn [& _] "small envelope")}
      (fn []
        (is (= "small envelope"
               (envelope/enrich-context {:axioms "ctx-1"} [] "hive-mcp")))))))

(deftest enrich-context-normalises-empty-and-non-string-results
  (testing "an empty string or a non-string result becomes nil"
    (ext-stub/with-extensions
      {:ctx/enrich (fn [& _] "")}
      (fn []
        (is (nil? (envelope/enrich-context {:axioms "ctx-1"} [] "hive-mcp")))))
    (ext-stub/with-extensions
      {:ctx/enrich (fn [& _] {:not "a string"})}
      (fn []
        (is (nil? (envelope/enrich-context {:axioms "ctx-1"} [] "hive-mcp")))))))

;; =============================================================================
;; envelope-from-dispatch-context
;; =============================================================================

(deftest envelope-from-text-context-returns-nil
  (testing "TextContext produces no L2 envelope (text dispatch is L1)"
    (let [text-ctx (dispatch-ctx/->text-context "Fix the bug in auth.clj")]
      (is (nil? (envelope/envelope-from-dispatch-context text-ctx))))))

(deftest envelope-from-nil-context-returns-nil
  (testing "nil dispatch-context returns nil"
    (is (nil? (envelope/envelope-from-dispatch-context nil)))))

(deftest envelope-from-ref-context-delegates-with-its-refs
  (testing "RefContext routes its refs, seeds and scope to :ctx/enrich"
    (let [seen (atom nil)
          ax-id (context-store/context-put! sample-axioms :tags #{"axioms"})
          ref-ctx (dispatch-ctx/->ref-context
                   "Fix the bug in auth.clj"
                   {:ctx-refs    {:axioms ax-id}
                    :kg-node-ids ["20260207-dec1"]
                    :scope       "hive-mcp"})]
      (ext-stub/with-extensions
        {:ctx/enrich (recording-enrich seen "<!-- L2-CONTEXT -->")}
        (fn []
          (let [result (envelope/envelope-from-dispatch-context ref-ctx)]
            (is (= "<!-- L2-CONTEXT -->" result))
            (is (= {:axioms ax-id} (:ctx-refs @seen)))
            (is (= ["20260207-dec1"] (:kg-node-ids @seen)))
            (is (= "hive-mcp" (:scope @seen)))))))))
