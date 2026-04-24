(ns hive-mcp.plan.parser-property-test
  "Property tests for hive-mcp.plan parser and plan->task-specs.

   Consumes hive-mcp.plan.generators for inputs. The tests codify the
   contract between a well-formed plan, its rendered form (EDN / markdown),
   and the parsed-back result — so drift in parser behaviour surfaces as a
   failing property rather than a silent UX regression."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [hive-mcp.plan.generators :as gen-plan]
            [hive-mcp.plan.parser :as parser]
            [hive-mcp.plan.parser.util :as util]
            [hive-mcp.plan.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Render helpers — tiny, intentionally naive. If the parser is right, these
;; shapes roundtrip cleanly. If it's wrong, the properties fail.
;; =============================================================================

(defn- render-edn-block
  "Render a plan as a single ```edn code block."
  [plan]
  (str "# " (:title plan) "\n\n"
       "```edn\n"
       (pr-str (select-keys plan [:id :title :steps]))
       "\n```\n"))

(defn- render-md-annotations
  "Render step fields as markdown inline annotations."
  [{:keys [id priority estimate depends-on files]}]
  (cond-> (str "[id: " id "]")
    priority          (str " [priority: " (name priority) "]")
    estimate          (str " [estimate: " (name estimate) "]")
    (seq depends-on)  (str " [depends: " (str/join ", " depends-on) "]")
    (seq files)       (str " [files: " (str/join ", " files) "]")))

(defn- render-markdown
  "Render a plan as markdown with H2 step headers and inline annotations."
  [plan]
  (str "# " (:title plan) "\n\n"
       (->> (:steps plan)
            (map (fn [step]
                   (str "## " (:title step) " " (render-md-annotations step)
                        (when (:description step)
                          (str "\n" (:description step)))
                        "\n")))
            (str/join "\n"))))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- step-by-id [plan id]
  (some #(when (= id (:id %)) %) (:steps plan)))

;; =============================================================================
;; Properties: EDN roundtrip
;; =============================================================================

(defspec prop-edn-roundtrip-preserves-step-count 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-edn-block plan)
          result   (parser/parse-plan rendered)]
      (and (:success result)
           (= (count (:steps plan))
              (count (:steps (:plan result))))))))

(defspec prop-edn-roundtrip-preserves-step-ids 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-edn-block plan)
          result   (parser/parse-plan rendered)]
      (and (:success result)
           (= (mapv :id (:steps plan))
              (mapv :id (:steps (:plan result))))))))

(defspec prop-edn-roundtrip-preserves-dependencies 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-edn-block plan)
          {:keys [success plan]} (parser/parse-plan rendered)]
      (and success
           (every? (fn [step]
                     (= (:depends-on step)
                        (:depends-on (step-by-id plan (:id step)))))
                   (:steps plan))))))

;; =============================================================================
;; Properties: Markdown roundtrip (structural — description is lossy)
;; =============================================================================

(defspec prop-md-roundtrip-preserves-step-count 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-markdown plan)
          result   (parser/parse-plan rendered {:prefer-format :markdown})]
      (and (:success result)
           (= (count (:steps plan))
              (count (:steps (:plan result))))))))

(defspec prop-md-roundtrip-preserves-step-ids 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-markdown plan)
          result   (parser/parse-plan rendered {:prefer-format :markdown})]
      (and (:success result)
           (= (set (map :id (:steps plan)))
              (set (map :id (:steps (:plan result)))))))))

(defspec prop-md-roundtrip-preserves-dependencies 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-markdown plan)
          {:keys [success plan]} (parser/parse-plan rendered
                                                    {:prefer-format :markdown})]
      (and success
           (every? (fn [step]
                     (let [orig (step-by-id plan (:id step))]
                       (= (set (:depends-on step))
                          (set (:depends-on orig)))))
                   (:steps plan))))))

(defspec prop-md-roundtrip-preserves-priority 50
  (prop/for-all [plan gen-plan/gen-plan]
    (let [rendered (render-markdown plan)
          {:keys [success plan]} (parser/parse-plan rendered
                                                    {:prefer-format :markdown})]
      (and success
           (every? (fn [step]
                     (= (:priority step)
                        (:priority (step-by-id plan (:id step)))))
                   (:steps plan))))))

;; =============================================================================
;; Properties: plan->task-specs invariants
;; =============================================================================

(defspec prop-task-specs-one-per-step 50
  (prop/for-all [plan gen-plan/gen-plan]
    (= (count (:steps plan))
       (count (util/plan->task-specs plan)))))

(defspec prop-task-specs-preserve-step-id 50
  (prop/for-all [plan gen-plan/gen-plan]
    (= (mapv :id (:steps plan))
       (mapv :plan-step-id (util/plan->task-specs plan)))))

(defspec prop-task-specs-preserve-deps 50
  (prop/for-all [plan gen-plan/gen-plan]
    (= (mapv :depends-on (:steps plan))
       (mapv :depends-on (util/plan->task-specs plan)))))

(defspec prop-task-specs-priority-is-canonical-string 50
  (prop/for-all [plan gen-plan/gen-plan]
    (every? #{"high" "medium" "low"}
            (map :priority (util/plan->task-specs plan)))))

;; =============================================================================
;; Properties: normalize-priority clamps every alias onto canonical
;; =============================================================================

(defspec prop-normalize-priority-always-canonical 200
  (prop/for-all [p gen-plan/gen-priority-lenient]
    (contains? (set gen-plan/priorities)
               (schema/normalize-priority p))))

(defspec prop-normalize-priority-idempotent 100
  (prop/for-all [p gen-plan/gen-priority]
    (= p (schema/normalize-priority p))))

;; =============================================================================
;; Known limitation: per-step EDN maps embedded in markdown are NOT parsed
;;
;; Reproduces the "7 H2 entries" UX bug — when a plan.md has per-step EDN
;; metadata maps {:file ... :status ...} instead of the [key: value]
;; annotation grammar, markdown parsing silently drops the metadata.
;;
;; This test pins the current behaviour; flip the is/is-not when we either
;; extend the markdown parser to recognise EDN maps, or deprecate markdown
;; mode in favour of EDN.
;; =============================================================================

(deftest markdown-with-inline-edn-maps-drops-metadata
  (testing "current parser ignores per-step {:file ...} EDN maps in markdown"
    (let [content (str "# Plan\n\n"
                       "## Add schema\n{:file \"src/schema.clj\" :status :todo}\n\n"
                       "## Add handler\n{:file \"src/handler.clj\" :status :todo}\n")
          {:keys [success plan]} (parser/parse-plan content
                                                    {:prefer-format :markdown})]
      (is success)
      (is (= 2 (count (:steps plan))))
      ;; Neither the :file nor the :status leak into the parsed step — they
      ;; end up in the description blob at best, never as first-class fields.
      (is (every? #(= [] (:files %)) (:steps plan))
          "parser does not recognise {:file ...} as a step-level field"))))
