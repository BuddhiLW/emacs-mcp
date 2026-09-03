(ns hive-mcp.crystal.hooks-property-test
  "Property-based tests for crystal/hooks.clj.

   Verifies algebraic properties that must hold for ALL valid inputs:

   Pure functions (no external deps):
   - P1:  extract-task structural completeness (map content)
   - P2:  extract-task structural completeness (string/legacy content)
   - P3:  extract-task ID preservation
   - P4:  extract-task priority defaulting
   - P5:  extract-task title extraction (map content)
   - P5b: extract-task title extraction (string content)
   - P5c: extract-task legacy format defaults

   Result DSL helpers:
   - P6:  rescue success path returns fn result
   - P7:  rescue failure path returns fallback
   - P8:  rescue failure + map fallback — error in metadata
   - P9:  rescue totality (never throws)

   JSON safety:
   - P10: parse-json-safe totality (never throws for any string)
   - P11: parse-json-safe valid JSON roundtrip

   Surface-rescue-error:
   - P12: surface-rescue-error extracts result/error-key metadata into :error key

   Crystallize-session structure (stub store + mocked summarizer):
   - P13: result always contains every synthesis/lifecycle-stat-keys key
   - P14: result always contains :session and :project-id

   Memory access tracking:
   - P15: on-memory-accessed tracked count equals entry-ids count
   - P16: on-memory-accessed explicit? detection by source"
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-mcp.crystal.hooks :as hooks]
            [hive-mcp.crystal.harvest.collect :as collect]
            [hive-mcp.crystal.synthesis :as synthesis]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.dns.result :as result]
            [clojure.data.json :as json]
            [hive-mcp.test.stub.memory-store :as stub-store]
            [hive-mcp.test.stub.extensions :as stub-ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Private Var References (for testing private helpers)
;; =============================================================================

;; parse-json-safe moved hooks.clj -> harvest/collect.clj in the harvest decomposition.
(def parse-json-safe @#'collect/parse-json-safe)
(def surface-rescue-error @#'synthesis/surface-rescue-error)

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-entry-id
  "Generator for unique entry IDs (avoids gen/uuid classloader issue)."
  (gen/fmap (fn [[a b]] (str "entry-" (java.util.UUID. a b)))
            (gen/tuple gen/large-integer gen/large-integer)))

(def gen-title
  "Generator for non-empty task titles."
  (gen/such-that (complement empty?) gen/string-alphanumeric 100))

(def gen-priority
  "Generator for priority values."
  (gen/elements ["low" "medium" "high" "critical"]))

(def gen-status
  "Generator for task status values."
  (gen/elements ["todo" "inprogress" "inreview" "done"]))

(def gen-context
  "Generator for optional context strings."
  (gen/one-of [(gen/return nil) gen/string-alphanumeric]))

(def gen-timestamp
  "Generator for ISO timestamp strings."
  (gen/fmap #(str "2026-01-" (format "%02d" (inc (mod % 28))) "T10:00:00Z")
            (gen/choose 0 27)))

(def gen-kanban-entry-map-content
  "Generator for kanban entries with map content."
  (gen/let [id gen-entry-id
            title gen-title
            context gen-context
            priority (gen/one-of [(gen/return nil) gen-priority])
            started (gen/one-of [(gen/return nil) gen-timestamp])
            status gen-status]
    {:id id
     :content {:title title
               :context context
               :priority priority
               :started started
               :status status}}))

(def gen-kanban-entry-string-content
  "Generator for kanban entries with string content (legacy format)."
  (gen/let [id gen-entry-id
            content gen/string-alphanumeric]
    {:id id
     :content content}))

(def gen-kanban-entry
  "Generator for any kanban entry (map or string content)."
  (gen/one-of [gen-kanban-entry-map-content
               gen-kanban-entry-string-content]))

(def gen-json-primitive
  "Generator for JSON-safe primitive values that roundtrip exactly."
  (gen/one-of [(gen/return nil)
               gen/boolean
               gen/small-integer
               gen/string-alphanumeric]))

(def gen-fallback-map
  "Generator for map fallbacks (safe-effect)."
  (gen/let [k gen/keyword
            v gen/small-integer]
    {k v}))

(def gen-fallback
  "Generator for any fallback value."
  (gen/one-of [(gen/return nil)
               gen/small-integer
               gen/string-alphanumeric
               gen-fallback-map
               (gen/return [])]))

;; =============================================================================
;; P1: extract-task structural completeness (map content)
;;     Result always contains all 6 keys regardless of map content shape
;; =============================================================================

(def ^:private required-task-keys
  "All keys that extract-task-from-kanban-entry must always return."
  #{:id :title :context :priority :started :status})

(defspec p1-extract-task-map-structural-completeness 100
  (prop/for-all [entry gen-kanban-entry-map-content]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (= required-task-keys (set (keys result))))))

;; =============================================================================
;; P2: extract-task structural completeness (string/legacy content)
;;     Legacy entries also produce all 6 keys
;; =============================================================================

(defspec p2-extract-task-string-structural-completeness 100
  (prop/for-all [entry gen-kanban-entry-string-content]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (= required-task-keys (set (keys result))))))

;; =============================================================================
;; P3: extract-task ID preservation
;;     Entry :id always maps to result :id (never lost or mutated)
;; =============================================================================

(defspec p3-extract-task-id-preservation 100
  (prop/for-all [entry gen-kanban-entry]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (= (:id entry) (:id result)))))

;; =============================================================================
;; P4: extract-task priority defaulting
;;     When content has no :priority key, result defaults to "medium"
;; =============================================================================

(defspec p4-extract-task-priority-default 100
  (prop/for-all [entry (gen/let [id gen-entry-id
                                 title gen-title
                                 status gen-status]
                         {:id id
                          :content {:title title :status status}})]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (= "medium" (:priority result)))))

;; =============================================================================
;; P5: extract-task title extraction (map content)
;;     Map content :title always maps to result :title
;; =============================================================================

(defspec p5-extract-task-title-map 100
  (prop/for-all [entry gen-kanban-entry-map-content]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (= (get-in entry [:content :title]) (:title result)))))

;; =============================================================================
;; P5b: extract-task title extraction (string content)
;;      String content is stringified as title
;; =============================================================================

(defspec p5b-extract-task-title-string 100
  (prop/for-all [entry gen-kanban-entry-string-content]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (= (str (:content entry)) (:title result)))))

;; =============================================================================
;; P5c: extract-task legacy format defaults
;;      String content entries always: status="done", priority="medium",
;;      context=nil, started=nil
;; =============================================================================

(defspec p5c-extract-task-legacy-defaults 100
  (prop/for-all [entry gen-kanban-entry-string-content]
                (let [result (hooks/extract-task-from-kanban-entry entry)]
                  (and (= "done" (:status result))
                       (= "medium" (:priority result))
                       (nil? (:context result))
                       (nil? (:started result))))))

;; =============================================================================
;; P6: rescue success path — returns body result, not fallback
;; =============================================================================

(defspec p6-rescue-success-returns-body-result 100
  (prop/for-all [v gen/small-integer
                 fallback gen-fallback]
                (let [r (result/rescue fallback v)]
                  (= v r))))

;; =============================================================================
;; P7: rescue failure path — returns fallback, not exception
;; =============================================================================

(defspec p7-rescue-failure-returns-fallback 100
  (prop/for-all [fallback gen/small-integer]
                (let [r (result/rescue fallback (throw (Exception. "boom")))]
                  (= fallback r))))

;; =============================================================================
;; P8: rescue failure + map fallback — error in metadata, not in map
;; =============================================================================

(defspec p8-rescue-failure-map-has-error-metadata 100
  (prop/for-all [fallback gen-fallback-map]
                (let [r   (result/rescue fallback (throw (Exception. "test-error")))
                      err (get (meta r) result/error-key)]
                  (and (map? r)
                       (some? err)
                       (= "test-error" (:message err))))))

;; =============================================================================
;; P9: rescue totality — never throws regardless of body behavior
;; =============================================================================

(defspec p9-rescue-totality-success 100
  (prop/for-all [v gen/small-integer
                 fallback gen-fallback]
                (try
                  (result/rescue fallback v)
                  true
                  (catch Throwable _ false))))

(defspec p9-rescue-totality-failure 100
  (prop/for-all [fallback gen-fallback]
                (try
                  (result/rescue fallback (throw (Exception. "boom")))
                  true
                  (catch Throwable _ false))))

;; =============================================================================
;; P10: parse-json-safe totality — never throws for any string input
;; =============================================================================

(defspec p10-parse-json-safe-totality 200
  (prop/for-all [s gen/string]
                (try
                  (parse-json-safe s)
                  true
                  (catch Throwable _ false))))

;; =============================================================================
;; P11: parse-json-safe valid JSON roundtrip
;;      json/write-str then parse-json-safe recovers original value
;; =============================================================================

(defspec p11-parse-json-safe-roundtrip 100
  (prop/for-all [v gen-json-primitive]
                (let [json-str (json/write-str v)
                      parsed (parse-json-safe json-str)]
                  (= v parsed))))

;; =============================================================================
;; P12: surface-rescue-error extracts ::result/error metadata into :error key
;;      When rescue metadata present, :error is surfaced as string message
;;      When no metadata, map passes through unchanged
;; =============================================================================

(defspec p12-surface-rescue-error-extracts-metadata 50
  (prop/for-all [fallback gen-fallback-map
                 msg gen/string-alphanumeric]
                (let [with-meta-fb (with-meta fallback
                                     {result/error-key {:message msg :form "test"}})
                      surfaced (surface-rescue-error with-meta-fb)]
                  (and (map? surfaced)
                       (= msg (:error surfaced))
                       ;; original keys preserved
                       (every? #(contains? surfaced %) (keys fallback))))))

(defspec p12b-surface-rescue-error-no-metadata-passthrough 50
  (prop/for-all [m gen-fallback-map]
                (let [result (surface-rescue-error m)]
                  (= m result))))

;; =============================================================================
;; Mock helper for crystallize-session property tests
;; =============================================================================

(defmacro ^:private with-lifecycle-mocks
  "Bind all crystallize-session dependencies for property tests.
   summary-result: what summarize-session-progress returns.

   The memory store is a real IMemoryStore stub installed in the store
   registry — the DIP seam `facade/index-memory-entry!` resolves through —
   so the store branch is exercised instead of erroring on a missing backend.
   `:cc/summarize-memory` is deregistered so a nil summary-result really is
   the no-content path whether the JVM is cold or has addons loaded."
  [summary-result & body]
  `(stub-ext/without-extensions
    [:cc/summarize-memory]
    (fn []
      (stub-store/with-stub-store
       (fn []
         (with-redefs [crystal/summarize-session-progress
                       (fn [& _#] ~summary-result)
                       crystal/session-id
                       (fn [] "prop-test-session")
                       scope/get-current-project-id
                       (fn [_#] "prop-test-project")
                       dur/calculate-expires
                       (fn [_#] "2026-12-31T00:00:00Z")]
           ~@body))))))

(def ^:private lifecycle-stat-keys
  "Lifecycle stat keys crystallize-session must return. Owned by
   hive-mcp.crystal.synthesis — read from the source, never restated here."
  synthesis/lifecycle-stat-keys)

;; =============================================================================
;; P13: crystallize-session — result always contains every lifecycle stat key
;;      Regardless of whether content exists (minimal-wrap vs synthesized)
;; =============================================================================

(defspec p13-crystallize-session-lifecycle-stats-present 50
  (prop/for-all [has-content gen/boolean]
                (with-lifecycle-mocks
                  (when has-content {:content "generated summary" :tags ["test"]})
                  (let [harvested {:progress-notes (if has-content [{:content "work done"}] [])
                                   :completed-tasks []
                                   :git-commits (if has-content ["abc1234 feat: test"] [])
                                   :directory "/tmp/prop-test"
                                   :recalls {}
                                   :summary {:progress-count 0 :task-count 0
                                             :commit-count 0 :recall-count 0}}
                        result (synthesis/synthesize harvested)]
                    (every? #(contains? result %) lifecycle-stat-keys)))))

;; =============================================================================
;; P14: crystallize-session — result always contains :session and :project-id
;; =============================================================================

(defspec p14-crystallize-session-always-has-session-and-project 50
  (prop/for-all [has-content gen/boolean]
                (with-lifecycle-mocks
                  (when has-content {:content "summary" :tags []})
                  (let [harvested {:progress-notes (if has-content [{:content "x"}] [])
                                   :completed-tasks []
                                   :git-commits (if has-content ["abc fix: y"] [])
                                   :directory "/tmp/prop-test"
                                   :recalls {}
                                   :summary {:progress-count 0 :task-count 0
                                             :commit-count 0 :recall-count 0}}
                        result (synthesis/synthesize harvested)]
                    (and (= "prop-test-session" (:session result))
                         (= "prop-test-project" (:project-id result)))))))

;; =============================================================================
;; P15: on-memory-accessed — tracked count equals entry-ids count
;; =============================================================================

(defspec p15-on-memory-accessed-tracked-count 100
  (prop/for-all [entry-ids (gen/vector gen-entry-id 0 20)
                 source (gen/elements ["catchup" "wrap" "query" "search" "feedback"])]
                (with-redefs [crystal/session-id (fn [] "prop-test-session")
                              recall/create-recall-event
                              (fn [_] {:context :test :timestamp "now"})
                              recall/buffer-recall! (fn [_ _] nil)]
                  (let [result (hooks/on-memory-accessed {:entry-ids entry-ids :source source})]
                    (and (= (count entry-ids) (:tracked result))
                         (= source (:source result)))))))

;; =============================================================================
;; P16: on-memory-accessed — explicit? detection by source
;;      catchup/wrap sources → explicit? is false
;;      other sources → explicit? is true
;; =============================================================================

(defspec p16-on-memory-accessed-explicit-detection 100
  (prop/for-all [source (gen/elements ["catchup" "wrap" "query" "search" "feedback"])
                 entry-id gen-entry-id]
                (let [captured-params (atom nil)]
                  (with-redefs [crystal/session-id (fn [] "prop-test-session")
                                recall/create-recall-event
                                (fn [params]
                                  (reset! captured-params params)
                                  {:context :test :timestamp "now"})
                                recall/buffer-recall! (fn [_ _] nil)]
                    (hooks/on-memory-accessed {:entry-ids [entry-id] :source source})
                    (let [params @captured-params]
                      (if (contains? #{"catchup" "wrap"} source)
                        (false? (:explicit? params))
                        (true? (:explicit? params))))))))
