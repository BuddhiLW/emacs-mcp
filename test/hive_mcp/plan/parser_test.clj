(ns hive-mcp.plan.parser-test
  "Tests for plan parser (EDN and markdown modes)."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [hive-mcp.plan.parser :as parser]
            [hive-mcp.plan.schema :as schema]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; EDN Parsing Tests
;; =============================================================================

(deftest contains-edn-block?-test
  (testing "detects EDN blocks"
    (is (parser/contains-edn-block? "```edn\n{:foo 1}\n```"))
    (is (parser/contains-edn-block? "Some text\n```edn\n{:x 1}\n```\nMore text")))

  (testing "returns false when no EDN blocks"
    (is (not (parser/contains-edn-block? "No EDN here")))
    (is (not (parser/contains-edn-block? "```clojure\n(+ 1 2)\n```")))
    (is (not (parser/contains-edn-block? "")))))

(deftest contains-edn-plan?-test
  (testing "detects EDN blocks"
    (is (parser/contains-edn-plan? "```edn\n{:steps []}\n```")))

  (testing "detects raw EDN with :plan/steps"
    (is (parser/contains-edn-plan? "{:plan/steps [{:step/id \"s1\"}]}"))
    (is (parser/contains-edn-plan? "{:plan/id \"x\" :plan/steps []}")))

  (testing "detects raw EDN with :steps"
    (is (parser/contains-edn-plan? "{:steps [{:id \"s1\"}]}"))
    (is (parser/contains-edn-plan? "{:id \"plan-1\" :steps []}")))

  (testing "detects EDN with nested map before :steps (regression — old `\\{[^}]*` form choked on inner `}`)"
    (is (parser/contains-edn-plan?
         "{:title \"P\" :metadata {:author \"a\"} :steps [{:id \"s1\"}]}"))
    (is (parser/contains-edn-plan?
         "{:plan/id \"p\" :plan/meta {:k :v} :plan/steps [{:step/id \"s1\"}]}")))

  (testing "detects namespaced map literal"
    (is (parser/contains-edn-plan? "#:plan{:steps [{:step/id \"s1\"}]}")))

  (testing "detects phase-block vectors"
    (is (parser/contains-edn-plan? "[{:phase 1 :tasks [{:id \"t1\"}]}]"))
    (is (parser/contains-edn-plan? "[{:phase/id 1 :phase/tasks [{:task/id \"t1\"}]}]")))

  (testing "detects EDN embedded in prose"
    (is (parser/contains-edn-plan? "Some text. {:steps [{:id \"s1\"}]} more text.")))

  (testing "handles EDN spec features the reader gives us for free"
    ;; Commas as whitespace (EDN spec).
    (is (parser/contains-edn-plan? "{:steps, [{:id, \"s1\"}]}"))
    ;; `;` line comments.
    (is (parser/contains-edn-plan? "; preamble\n{:steps [{:id \"s1\"}]}"))
    ;; `#_` discard does not hide a following plan form.
    (is (parser/contains-edn-plan? "#_ {:fake :map} {:steps [{:id \"s1\"}]}"))
    ;; Unknown user reader tag wrapping the plan map (passed through via :default).
    (is (parser/contains-edn-plan? "#myapp/Plan {:steps [{:id \"s1\"}]}"))
    ;; Set / list collections containing a plan map.
    (is (parser/contains-edn-plan? "#{{:steps [{:id \"s1\"}]}}"))
    (is (parser/contains-edn-plan? "({:phase 1 :tasks [{:id \"t1\"}]})"))
    ;; `\}` character literal must not confuse a hand-rolled brace counter.
    (is (parser/contains-edn-plan? "{:char \\} :steps [{:id \"s1\"}]}")))

  (testing "returns false for non-plan content"
    (is (not (parser/contains-edn-plan? "Just some text")))
    (is (not (parser/contains-edn-plan? "# Markdown header")))
    (is (not (parser/contains-edn-plan? "{:foo :bar}")))
    (is (not (parser/contains-edn-plan? "We mention :steps in prose")))
    (is (not (parser/contains-edn-plan? "{:steps [unbalanced")))
    (is (not (parser/contains-edn-plan? nil)))))

(deftest parse-edn-plan-test
  (testing "parses valid EDN plan from code block"
    (let [content "Here's the plan:\n```edn\n{:title \"My Plan\"\n :steps [{:id \"step-1\" :title \"First step\"}]}\n```"
          result (parser/parse-edn-plan content)]
      (is (:success result))
      (is (= "My Plan" (-> result :plan :title)))
      (is (= 1 (count (-> result :plan :steps))))
      (is (= "step-1" (-> result :plan :steps first :id)))
      (is (= :edn (-> result :plan :source-format)))))

  (testing "parses raw EDN plan with :plan/steps"
    (let [content "{:plan/id \"l1-p2-transitive-staleness\"
                    :plan/title \"L1 Phase 2: Transitive Staleness\"
                    :plan/steps
                    [{:step/id \"step-1\"
                      :step/title \"Add staleness fields\"
                      :step/depends-on []
                      :step/priority :high
                      :step/files [\"src/file.clj\"]}
                     {:step/id \"step-2\"
                      :step/title \"Create function\"
                      :step/depends-on [\"step-1\"]
                      :step/priority :high
                      :step/files [\"src/other.clj\"]}]}"
          result (parser/parse-edn-plan content)]
      (is (:success result))
      (is (= "L1 Phase 2: Transitive Staleness" (-> result :plan :title)))
      (is (= "l1-p2-transitive-staleness" (-> result :plan :id)))
      (is (= 2 (count (-> result :plan :steps))))
      (is (= "step-1" (-> result :plan :steps first :id)))
      (is (= "step-2" (-> result :plan :steps second :id)))
      (is (= ["step-1"] (-> result :plan :steps second :depends-on)))
      (is (= :high (-> result :plan :steps first :priority)))
      (is (= ["src/file.clj"] (-> result :plan :steps first :files)))
      (is (= :edn (-> result :plan :source-format)))))

  (testing "parses raw EDN plan with plain :steps key"
    (let [content "{:id \"test-plan\"
                    :title \"Test Plan\"
                    :steps [{:id \"s1\" :title \"Step 1\"}
                            {:id \"s2\" :title \"Step 2\" :depends-on [\"s1\"]}]}"
          result (parser/parse-edn-plan content)]
      (is (:success result))
      (is (= "Test Plan" (-> result :plan :title)))
      (is (= 2 (count (-> result :plan :steps))))))

  (testing "parses plan with dependencies"
    (let [content "```edn\n{:steps [{:id \"a\" :title \"A\"}\n {:id \"b\" :title \"B\" :depends-on [\"a\"]}]}\n```"
          result (parser/parse-edn-plan content)]
      (is (:success result))
      (is (= ["a"] (-> result :plan :steps second :depends-on)))))

  (testing "parses plan with priority"
    (let [content "```edn\n{:steps [{:id \"x\" :title \"X\" :priority :high}]}\n```"
          result (parser/parse-edn-plan content)]
      (is (:success result))
      (is (= :high (-> result :plan :steps first :priority)))))

  (testing "normalizes string priority to keyword"
    (let [content "```edn\n{:steps [{:id \"x\" :title \"X\" :priority \"high\"}]}\n```"
          result (parser/parse-edn-plan content)]
      (is (:success result))
      (is (= :high (-> result :plan :steps first :priority)))))

  (testing "fails gracefully on invalid EDN"
    (let [content "```edn\n{:invalid\n```"
          result (parser/parse-edn-plan content)]
      (is (not (:success result)))
      (is (string? (:error result)))))

  (testing "fails when no plan structure in EDN"
    (let [content "```edn\n{:foo \"bar\"}\n```"
          result (parser/parse-edn-plan content)]
      (is (not (:success result)))))

  (testing "fails when no EDN plan found"
    (let [result (parser/parse-edn-plan "No EDN here")]
      (is (not (:success result)))
      (is (string? (:error result))))))

;; =============================================================================
;; Markdown Parsing Tests
;; =============================================================================

(deftest parse-markdown-plan-test
  (testing "parses simple markdown plan"
    (let [content "# My Plan\n\n## First Step\n\nDo something.\n\n## Second Step\n\nDo more."
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= "My Plan" (-> result :plan :title)))
      (is (= 2 (count (-> result :plan :steps))))
      (is (= "First Step" (-> result :plan :steps first :title)))
      (is (= :markdown (-> result :plan :source-format)))))

  (testing "extracts dependencies from header"
    (let [content "# Plan\n\n## Step A\n\n## Step B [depends: step-a-1]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= ["step-a-1"] (-> result :plan :steps second :depends-on)))))

  (testing "extracts multiple dependencies"
    (let [content "# Plan\n\n## Step A\n\n## Step B\n\n## Step C [depends: step-a-1, step-b-2]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= ["step-a-1" "step-b-2"] (-> result :plan :steps (nth 2) :depends-on)))))

  (testing "extracts priority from header"
    (let [content "# Plan\n\n## Important Task [priority: high]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= :high (-> result :plan :steps first :priority)))))

  (testing "extracts explicit ID from header"
    (let [content "# Plan\n\n## My Step [id: custom-id]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= "custom-id" (-> result :plan :steps first :id)))))

  (testing "combines all annotations"
    (let [content "# Plan\n\n## Task [id: t1] [priority: high] [depends: t0]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (let [step (-> result :plan :steps first)]
        (is (= "t1" (:id step)))
        (is (= :high (:priority step)))
        (is (= ["t0"] (:depends-on step)))
        (is (= "Task" (:title step))))))

  (testing "extracts estimate from header"
    (let [content "# Plan\n\n## Quick Fix [estimate: small]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= :small (-> result :plan :steps first :estimate)))))

  (testing "extracts files from header"
    (let [content "# Plan\n\n## Update Schema [files: src/schema.clj, src/core.clj]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= ["src/schema.clj" "src/core.clj"] (-> result :plan :steps first :files)))))

  (testing "combines all annotations including estimate and files"
    (let [content "# Plan\n\n## Task [id: t1] [priority: high] [estimate: large] [files: src/a.clj] [depends: t0]"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (let [step (-> result :plan :steps first)]
        (is (= "t1" (:id step)))
        (is (= :high (:priority step)))
        (is (= :large (:estimate step)))
        (is (= ["src/a.clj"] (:files step)))
        (is (= ["t0"] (:depends-on step)))
        (is (= "Task" (:title step))))))

  (testing "generates step IDs from title"
    (let [content "# Plan\n\n## Create Database Schema"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (str/starts-with? (-> result :plan :steps first :id) "create-database-schema"))))

  (testing "captures content as description"
    (let [content "# Plan\n\n## Step\n\nThis is the description.\nWith multiple lines."
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (str/includes? (-> result :plan :steps first :description) "This is the description"))))

  (testing "fails when no headers found"
    (let [result (parser/parse-markdown-plan "No headers here")]
      (is (not (:success result)))
      (is (= "No ## headers found in content" (:error result)))))

  (testing "handles plan without # title"
    (let [content "## Step One\n\n## Step Two"
          result (parser/parse-markdown-plan content)]
      (is (:success result))
      (is (= "Untitled Plan" (-> result :plan :title))))))

;; =============================================================================
;; Unified Parsing Tests
;; =============================================================================

(deftest parse-plan-test
  (testing "auto-detects EDN format from code block"
    (let [content "```edn\n{:steps [{:id \"a\" :title \"A\"}]}\n```"
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :edn (-> result :plan :source-format)))))

  (testing "auto-detects raw EDN format with :plan/steps"
    (let [content "{:plan/id \"test\" :plan/title \"Test\" :plan/steps [{:step/id \"a\" :step/title \"A\"}]}"
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :edn (-> result :plan :source-format)))
      (is (= "Test" (-> result :plan :title)))))

  (testing "auto-detects raw EDN format with :steps"
    (let [content "{:id \"test\" :title \"Test\" :steps [{:id \"a\" :title \"A\"}]}"
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :edn (-> result :plan :source-format)))))

  (testing "auto-detects markdown format"
    (let [content "# Plan\n\n## Step"
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :markdown (-> result :plan :source-format)))))

  (testing "falls back to markdown when EDN parse fails"
    (let [content "# Plan\n\n## Step\n\n```edn\n{:not-a-plan true}\n```"
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :markdown (-> result :plan :source-format)))))

  (testing "respects :prefer-format option"
    (let [content "# Plan\n\n## Step\n\n```edn\n{:steps [{:id \"x\" :title \"X\"}]}\n```"]
      (is (= :edn (-> (parser/parse-plan content {:prefer-format :edn}) :plan :source-format)))
      (is (= :markdown (-> (parser/parse-plan content {:prefer-format :markdown}) :plan :source-format)))))

  (testing "attaches memory-id when provided"
    (let [content "# Plan\n\n## Step"
          result (parser/parse-plan content {:memory-id "20260128-abc"})]
      (is (:success result))
      (is (= "20260128-abc" (-> result :plan :memory-id))))))

;; =============================================================================
;; Regression: plan_id path (prose-wrapped EDN from memory)
;; =============================================================================
;;
;; When `plan-to-kanban` is called with `plan_id`, content is fetched from the
;; plans collection via `plans/get-plan`, which returns the document text as
;; stored by `entry-to-document` — a wrapped form like:
;;
;;   "Plan Entry [draft]\nType: plan\nProject: ...\nTags: ...\n\n<EDN>"
;;
;; The non-EDN preamble used to abort the top-level reader before it could
;; reach the embedded plan map, causing `contains-edn-plan?` to return false
;; and the parser to fall through to markdown — failing with
;; "No ## headers found in content". The fix scans for balanced `{...}`
;; substrings as additional candidate forms.

(defn- chroma-style-wrap
  "Mirror plans/entry-to-document — prepend the same prose preamble that
   plans collection writes around plan content before storing in Chroma."
  [edn-content]
  (str "Plan Entry [draft]\n"
       "Type: plan\n"
       "Project: hive-mcp\n"
       "Steps: 2\n"
       "Tags: plan,SAA\n\n"
       edn-content))

(deftest parse-plan-prose-wrapped-edn-test
  (testing "auto-detects raw EDN with :plan/steps inside Chroma prose preamble"
    (let [edn "{:plan/id \"test-plan\" :plan/title \"Wrapped EDN\"
                :plan/steps [{:step/id \"s1\" :step/title \"First\"}
                             {:step/id \"s2\" :step/title \"Second\"
                              :step/depends-on [\"s1\"]}]}"
          content (chroma-style-wrap edn)
          result (parser/parse-plan content)]
      (is (parser/contains-edn-plan? content)
          "contains-edn-plan? must see through the prose preamble")
      (is (:success result)
          "parse-plan must auto-detect EDN despite the prose prefix")
      (is (= :edn (-> result :plan :source-format)))
      (is (= "Wrapped EDN" (-> result :plan :title)))
      (is (= 2 (count (-> result :plan :steps))))
      (is (= ["s1"] (-> result :plan :steps second :depends-on)))))

  (testing "auto-detects raw EDN with plain :steps inside Chroma prose preamble"
    (let [edn "{:id \"plan-x\" :title \"Plain Steps\"
                :steps [{:id \"a\" :title \"A\"}]}"
          content (chroma-style-wrap edn)
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :edn (-> result :plan :source-format)))
      (is (= "Plain Steps" (-> result :plan :title)))))

  (testing "regression: kanban task 20260428091059-3dc5cc34 — plan_id+EDN combo"
    ;; Reproduces the exact failure: content retrieved via plan_id used to
    ;; bypass EDN auto-detect and fail with "No ## headers found in content".
    (let [edn "{:plan/id \"saa-plan\"
                :plan/title \"SAA Plan\"
                :plan/steps [{:step/id \"explore\" :step/title \"Explore\" :step/priority :high}
                             {:step/id \"abstract\" :step/title \"Abstract\"
                              :step/depends-on [\"explore\"]}
                             {:step/id \"act\" :step/title \"Act\"
                              :step/depends-on [\"abstract\"]}]}"
          content (chroma-style-wrap edn)
          result (parser/parse-plan content {:memory-id "20260428091059-saa"})]
      (is (:success result)
          "must not fail with 'No ## headers found in content'")
      (is (not= "No ## headers found in content" (:error result)))
      (is (= "SAA Plan" (-> result :plan :title)))
      (is (= 3 (count (-> result :plan :steps))))
      (is (= "20260428091059-saa" (-> result :plan :memory-id))))))

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(deftest plan->task-specs-test
  (testing "converts plan to task specs"
    (let [plan {:steps [{:id "s1"
                         :title "Task 1"
                         :description "Do this"
                         :priority :high
                         :tags ["tag1"]
                         :depends-on ["s0"]}]}
          specs (parser/plan->task-specs plan)]
      (is (= 1 (count specs)))
      (is (= "Task 1" (:title (first specs))))
      (is (= "Do this" (:description (first specs))))
      (is (= "high" (:priority (first specs))))
      (is (= ["tag1"] (:tags (first specs))))
      (is (= ["s0"] (:depends-on (first specs))))
      (is (= "s1" (:plan-step-id (first specs)))))))

(deftest validate-dependencies-test
  (testing "valid when all dependencies exist"
    (let [plan {:steps [{:id "a" :title "A" :depends-on []}
                        {:id "b" :title "B" :depends-on ["a"]}]}
          result (parser/validate-dependencies plan)]
      (is (:valid result))))

  (testing "invalid when dependency missing"
    (let [plan {:steps [{:id "a" :title "A" :depends-on ["missing"]}]}
          result (parser/validate-dependencies plan)]
      (is (not (:valid result)))
      (is (= ["missing"] (:missing result)))
      (is (= "a" (:step result)))))

  (testing "valid with empty dependencies"
    (let [plan {:steps [{:id "a" :title "A" :depends-on []}]}
          result (parser/validate-dependencies plan)]
      (is (:valid result)))))

;; =============================================================================
;; Schema Validation Tests
;; =============================================================================

(deftest schema-validation-test
  (testing "valid step passes validation"
    (let [step {:id "step-1"
                :title "Test Step"
                :depends-on []
                :priority :medium
                :estimate :medium
                :files []
                :tags []}]
      (is (schema/valid-step? step))))

  (testing "step with missing id fails"
    (let [step {:title "No ID"}]
      (is (not (schema/valid-step? step)))))

  (testing "valid plan passes validation"
    (let [plan {:id "plan-1"
                :title "Test Plan"
                :steps [{:id "s1" :title "Step 1" :depends-on [] :priority :medium :estimate :medium :files [] :tags []}]
                :source-format :edn
                :tags []}]
      (is (schema/valid-plan? plan))))

  (testing "plan with invalid priority fails"
    (let [plan {:id "plan-1"
                :title "Test"
                :steps [{:id "s1" :title "Step" :priority :invalid}]
                :source-format :edn}]
      (is (not (schema/valid-plan? plan))))))

;; =============================================================================
;; Normalization Tests
;; =============================================================================

(deftest normalization-test
  (testing "normalizes string priority"
    (is (= :high (schema/normalize-priority "HIGH")))
    (is (= :low (schema/normalize-priority "low")))
    (is (= :medium (schema/normalize-priority nil))))

  (testing "normalizes string estimate"
    (is (= :small (schema/normalize-estimate "SMALL")))
    (is (= :large (schema/normalize-estimate "large")))
    (is (= :medium (schema/normalize-estimate nil))))

  (testing "normalizes step with defaults"
    (let [step (schema/normalize-step {:id "x" :title "X"})]
      (is (= [] (:depends-on step)))
      (is (= :medium (:priority step)))
      (is (= :medium (:estimate step)))
      (is (= [] (:files step)))
      (is (= [] (:tags step))))))

(deftest parse-plan-edn-schema-error-surfaces-test
  (testing "kanban 20260429135746-3fabed1d: when contains-edn-plan? is true and EDN parse fails schema validation, surface the schema :details — do not silently fall back to markdown"
    (let [content "{:steps [{:id :title :priority :estimate :files :depends-on}]}"
          edn-result (parser/parse-edn-plan content)
          plan-result (parser/parse-plan content)]
      (is (parser/contains-edn-plan? content)
          "test setup: content must trigger EDN auto-detect")
      (is (false? (:success edn-result))
          "test setup: EDN parse must fail schema validation")
      (is (some? (:details edn-result))
          "test setup: EDN parser must report schema :details")
      (is (false? (:success plan-result)))
      (is (= (:details edn-result) (:details plan-result))
          "parse-plan must propagate the EDN schema :details, not swallow them")
      (is (not= "No ## headers found in content" (:error plan-result))
          "regression guard: must not return the misleading markdown fallback error")))

  (testing "non-EDN-plan content with bad EDN block still falls through to markdown"
    (let [content "# Plan\n\n## Step\n\n```edn\n{:not-a-plan true}\n```"
          result (parser/parse-plan content)]
      (is (:success result))
      (is (= :markdown (-> result :plan :source-format))
          "fallback path preserved when contains-edn-plan? is false"))))
