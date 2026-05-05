(ns hive-mcp.crystal.persist-test
  "Step-8 + Step-9: per-scope writer fan-out + explicit-pid honoring.
   Mocks IMemoryStore via `with-redefs` of `mem-proto/store-set?`,
   `get-store`, and `add-entry!` to capture per-write payloads."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-mcp.crystal.persist :as persist]
            [hive-mcp.protocols.memory :as mem-proto]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Stub IMemoryStore — captures every add-entry! into an atom and returns
;; a fake id so persist-one! reports success.
;; =============================================================================

(defn- with-stub-store
  "Run `f`, redefining the IMemoryStore protocol surface so add-entry!
   pushes the payload onto `calls-atom` and returns a fake id."
  [calls-atom f]
  (let [stub-store (reify Object)]
    (with-redefs [mem-proto/store-set? (fn [] true)
                  mem-proto/get-store  (fn ([] stub-store) ([_k] stub-store))
                  mem-proto/add-entry! (fn [_store entry]
                                         (swap! calls-atom conj entry)
                                         (str "mem-" (count @calls-atom)))]
      (f))))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- multi-scope-wraps
  "Two real-scope wraps + one umbrella wrap, shaped like step-5+step-6 output."
  []
  [{:pid "hive"
    :entry {:type :note
            :content "## Hive synth"
            :tags ["scope:project:hive" "session-summary" "wrap-generated"]
            :duration :short}}
   {:pid "funeraria"
    :entry {:type :note
            :content "## Funeraria synth"
            :tags ["scope:project:funeraria" "session-summary" "wrap-generated"]
            :duration :short}}
   {:pid bs/umbrella-sentinel
    :entry {:type :note
            :content "## Umbrella synth"
            :tags ["scope:multi-project" "session-summary" "wrap-generated"]
            :duration :short}}])

;; =============================================================================
;; pid → project-id resolution
;; =============================================================================

(deftest pid->project-id--strings-pass-through
  (is (= "hive" (persist/pid->project-id "hive")))
  (is (= "funeraria" (persist/pid->project-id "funeraria")))
  (is (= "sisf-crm" (persist/pid->project-id "sisf-crm"))))

(deftest pid->project-id--umbrella-becomes-multi-project
  (is (= "multi-project" (persist/pid->project-id bs/umbrella-sentinel))))

;; =============================================================================
;; Per-write explicit-pid honoring (the heart of step-9)
;; =============================================================================

(deftest persist-wraps--each-entry-carries-explicit-project-id
  (let [calls (atom [])]
    (with-stub-store calls
      (fn []
        (let [result (persist/persist-wraps! (multi-scope-wraps))]
          (testing "result counts"
            (is (= 3 (:total result)))
            (is (= 3 (:persisted result)))
            (is (= 0 (:failed result))))
          (testing "every captured add-entry! payload has explicit :project-id"
            (let [pids (mapv :project-id @calls)]
              (is (= ["hive" "funeraria" "multi-project"] pids))))
          (testing "scope tag from step-6 survives normalisation"
            (let [tags (mapv :tags @calls)]
              (is (= "scope:project:hive" (first (first tags))))
              (is (= "scope:project:funeraria" (first (second tags))))
              (is (= "scope:multi-project" (first (last tags)))))))))))

(deftest persist-wraps--no-pwd-derivation
  (testing "stub does not call scope/get-current-project-id; explicit-pid path"
    (let [calls (atom [])]
      (with-stub-store calls
        (fn []
          (persist/persist-wraps! [{:pid "hive"
                                     :entry {:type :note :content "x"
                                             :tags ["scope:project:hive"]
                                             :duration :short}}])))
      (is (= 1 (count @calls)))
      (is (= "hive" (:project-id (first @calls)))
          "project-id came from :pid, not from scope-deriver"))))

;; =============================================================================
;; Normalisation
;; =============================================================================

(deftest persist-wraps--stamps-duration-and-expires
  (let [calls (atom [])]
    (with-stub-store calls
      (fn []
        (persist/persist-wraps! [{:pid "hive"
                                   :entry {:type :note :content "x"
                                           :tags ["scope:project:hive"]
                                           :duration :short}}])))
    (let [entry (first @calls)]
      (is (= "short" (:duration entry)))
      (is (string? (:expires entry)))
      (is (= "note" (:type entry))))))

(deftest persist-wraps--keeps-content-and-tags-intact
  (let [calls (atom [])]
    (with-stub-store calls
      (fn []
        (persist/persist-wraps! (multi-scope-wraps))))
    (let [contents (mapv :content @calls)]
      (is (= ["## Hive synth" "## Funeraria synth" "## Umbrella synth"] contents)))))

;; =============================================================================
;; Failure semantics
;; =============================================================================

(deftest persist-wraps--store-not-set-fails-gracefully
  (with-redefs [mem-proto/store-set? (fn [] false)]
    (let [result (persist/persist-wraps! (multi-scope-wraps))]
      (is (= 3 (:total result)))
      (is (= 0 (:persisted result)))
      (is (= 3 (:failed result)))
      (is (string? (:error result))))))

(deftest persist-wraps--per-entry-failure-isolated
  (let [calls (atom [])
        stub-store (reify Object)]
    (with-redefs [mem-proto/store-set? (fn [] true)
                  mem-proto/get-store  (fn ([] stub-store) ([_k] stub-store))
                  mem-proto/add-entry! (fn [_store entry]
                                         (swap! calls conj entry)
                                         (if (= "funeraria" (:project-id entry))
                                           (throw (ex-info "simulated failure" {}))
                                           (str "mem-" (count @calls))))]
      (let [result (persist/persist-wraps! (multi-scope-wraps))]
        (is (= 3 (:total result)))
        (is (= 2 (:persisted result)))
        (is (= 1 (:failed result)))
        (let [funeraria-result (first (filter #(= "funeraria" (:project-id %))
                                              (:results result)))]
          (is (false? (:success? funeraria-result)))
          (is (string? (:error funeraria-result))))))))