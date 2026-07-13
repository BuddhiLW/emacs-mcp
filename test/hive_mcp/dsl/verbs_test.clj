(ns hive-mcp.dsl.verbs-test
  "Tests for hive-mcp.dsl.verbs — compressed verb DSL.

   Coverage:
   1. Verb table structure and completeness
   2. Verb families (memory, kg, agent, kanban, session, magit, wave, hivemind, preset, config)
   3. Parameter alias expansion
   4. $ref target extraction and collection
   5. parse-sentence — verb resolution, alias expansion, unknowns
   6. parse-dsl — multiple sentences
   7. compile-paragraph — auto IDs, auto depends_on, error passthrough, explicit deps merge"
  (:require [clojure.test :refer [deftest testing is are]]
            [hive-mcp.dsl.verbs :as verbs]))

;; =============================================================================
;; Part 1: Verb Table Structure
;; =============================================================================

(deftest verb-table-structure-test
  (testing "verb-table has 41 verbs"
    (is (= 41 (count verbs/verb-table))))
  (testing "all keys are strings"
    (is (every? string? (keys verbs/verb-table))))
  (testing "all values have :tool and :command"
    (is (every? #(and (string? (:tool %))
                      (string? (:command %)))
                (vals verbs/verb-table))))
  (testing "all tool names match consolidated tool-handlers"
    (let [known-tools #{"memory" "kg" "agent" "kanban" "session"
                        "magit" "wave" "hivemind" "preset" "config"}]
      (is (every? #(contains? known-tools (:tool %))
                  (vals verbs/verb-table))))))

;; =============================================================================
;; Part 2: Verb Families — Spot-Check Each Tool
;; =============================================================================

(deftest memory-verbs-test
  (testing "memory verb family"
    (are [verb cmd] (= {:tool "memory" :command cmd} (get verbs/verb-table verb))
      "m+" "add"
      "m?" "query"
      "m@" "get"
      "m/" "search")))

(deftest kg-verbs-test
  (testing "kg verb family"
    (are [verb cmd] (= {:tool "kg" :command cmd} (get verbs/verb-table verb))
      "k>" "edge"
      "k^" "traverse"
      "k!" "impact"
      "k#" "stats")))

(deftest agent-verbs-test
  (testing "agent verb family"
    (are [verb cmd] (= {:tool "agent" :command cmd} (get verbs/verb-table verb))
      "a+" "spawn"
      "a?" "status"
      "a!" "dispatch"
      "ax" "kill")))

(deftest kanban-verbs-test
  (testing "kanban verb family"
    (are [verb cmd] (= {:tool "kanban" :command cmd} (get verbs/verb-table verb))
      "b+" "create"
      "b>" "update"
      "b?" "list"
      "b#" "status")))

(deftest session-verbs-test
  (testing "session verb family"
    (are [verb cmd] (= {:tool "session" :command cmd} (get verbs/verb-table verb))
      "s." "complete"
      "s~" "wrap"
      "s?" "whoami"
      "s<" "catchup")))

(deftest magit-verbs-test
  (testing "magit verb family"
    (are [verb cmd] (= {:tool "magit" :command cmd} (get verbs/verb-table verb))
      "g?" "status"
      "g+" "stage"
      "g!" "commit"
      "g>" "push")))

(deftest wave-verbs-test
  (testing "wave verb family"
    (are [verb cmd] (= {:tool "wave" :command cmd} (get verbs/verb-table verb))
      "w!" "dispatch"
      "w?" "status"
      "wy" "approve"
      "wn" "reject")))

(deftest hivemind-verbs-test
  (testing "hivemind verb family"
    (are [verb cmd] (= {:tool "hivemind" :command cmd} (get verbs/verb-table verb))
      "h!" "shout"
      "h?" "ask")))

(deftest preset-verbs-test
  (testing "preset verb family"
    (are [verb cmd] (= {:tool "preset" :command cmd} (get verbs/verb-table verb))
      "p?" "list"
      "p@" "get"
      "p/" "search")))

(deftest config-verbs-test
  (testing "config verb family"
    (are [verb cmd] (= {:tool "config" :command cmd} (get verbs/verb-table verb))
      "c?" "get"
      "c!" "set"
      "c*" "list")))

;; =============================================================================
;; Part 3: Parameter Aliases
;; =============================================================================

(deftest param-aliases-completeness-test
  (testing "all 11 aliases present"
    (is (= 11 (count verbs/param-aliases))))
  (testing "each alias maps correctly"
    (are [alias kw] (= kw (get verbs/param-aliases alias))
      "c"   :content
      "t"   :type
      "#"   :tags
      "d"   :directory
      "q"   :query
      "n"   :name
      "id"  :id
      "p"   :prompt
      "f"   :files
      "rel" :relation
      "idk" :idempotency_key)))

;; =============================================================================
;; Part 4: expand-param-key
;; =============================================================================

(deftest expand-param-key-string-alias-test
  (testing "string aliases expand to keywords"
    (is (= :content   (verbs/expand-param-key "c")))
    (is (= :tags      (verbs/expand-param-key "#")))
    (is (= :directory (verbs/expand-param-key "d")))
    (is (= :id        (verbs/expand-param-key "id")))))

(deftest expand-param-key-string-non-alias-test
  (testing "non-alias strings become keywords"
    (is (= :relation (verbs/expand-param-key "relation")))
    (is (= :foo      (verbs/expand-param-key "foo")))
    (is (= :from     (verbs/expand-param-key "from")))))

(deftest expand-param-key-keyword-alias-test
  (testing "keyword aliases expand via (name k) lookup"
    (is (= :content (verbs/expand-param-key :c)))
    (is (= :tags    (verbs/expand-param-key :#)))
    (is (= :type    (verbs/expand-param-key :t)))))

(deftest expand-param-key-keyword-non-alias-test
  (testing "non-alias keywords pass through"
    (is (= :relation (verbs/expand-param-key :relation)))
    (is (= :from     (verbs/expand-param-key :from)))))

;; =============================================================================
;; Part 5: expand-params
;; =============================================================================

(deftest expand-params-mixed-keys-test
  (testing "mixed alias and full keys"
    (is (= {:content "hi" :type "note" :relation "implements"}
           (verbs/expand-params {"c" "hi" "t" "note" "relation" "implements"})))))

(deftest expand-params-nil-test
  (testing "nil returns nil"
    (is (nil? (verbs/expand-params nil)))))

(deftest expand-params-empty-test
  (testing "empty map returns empty"
    (is (= {} (verbs/expand-params {})))))

(deftest expand-params-keyword-keys-test
  (testing "keyword keys also expand aliases"
    (is (= {:content "x" :tags ["a" "b"]}
           (verbs/expand-params {:c "x" :# ["a" "b"]})))))

(deftest expand-params-preserves-values-test
  (testing "values pass through unchanged — vectors, maps, nested"
    (let [result (verbs/expand-params {"#" ["a" "b"] "f" ["file.clj"]})]
      (is (= ["a" "b"] (:tags result)))
      (is (= ["file.clj"] (:files result))))))

;; =============================================================================
;; Part 6: ref-target
;; =============================================================================

(deftest ref-target-with-path-test
  (testing "extracts op-id from $ref with path"
    (is (= "$0"   (verbs/ref-target "$ref:$0.data.id")))
    (is (= "op-1" (verbs/ref-target "$ref:op-1.result.content")))))

(deftest ref-target-without-path-test
  (testing "extracts op-id from $ref without path"
    (is (= "op-1" (verbs/ref-target "$ref:op-1")))
    (is (= "$0"   (verbs/ref-target "$ref:$0")))))

(deftest ref-target-non-ref-test
  (testing "returns nil for non-ref strings"
    (is (nil? (verbs/ref-target "hello")))
    (is (nil? (verbs/ref-target "")))
    (is (nil? (verbs/ref-target "ref:no-dollar")))
    (is (nil? (verbs/ref-target "$refx:bad")))))

(deftest ref-target-nil-test
  (testing "returns nil for nil"
    (is (nil? (verbs/ref-target nil)))))

;; =============================================================================
;; Part 7: collect-refs
;; =============================================================================

(deftest collect-refs-flat-params-test
  (testing "collects refs from flat string params"
    (is (= #{"$0"}
           (verbs/collect-refs {:tool "kg" :command "edge"
                                :from "$ref:$0.data.id" :to "node-2"})))))

(deftest collect-refs-multiple-refs-test
  (testing "collects multiple distinct refs"
    (is (= #{"$0" "$1"}
           (verbs/collect-refs {:tool "kg" :command "edge"
                                :from "$ref:$0.data.id"
                                :to   "$ref:$1.data.id"})))))

(deftest collect-refs-nested-in-vector-test
  (testing "collects refs inside vectors"
    (is (= #{"$1"}
           (verbs/collect-refs {:tool "memory" :command "add"
                                :tags ["$ref:$1.data.tags" "extra"]})))))

(deftest collect-refs-nested-in-map-test
  (testing "collects refs inside nested maps"
    (is (= #{"$0"}
           (verbs/collect-refs {:tool "agent" :command "dispatch"
                                :data {:target "$ref:$0.data.id"}})))))

(deftest collect-refs-deeply-nested-test
  (testing "collects refs from deep nesting"
    (is (= #{"$0" "$2"}
           (verbs/collect-refs {:tool "wave" :command "dispatch"
                                :tasks [{"file" "$ref:$0.data.path"
                                         "task" "$ref:$2.data.task"}]})))))

(deftest collect-refs-skips-meta-keys-test
  (testing "ignores refs in :tool :command :id :depends_on :wave"
    (is (= #{}
           (verbs/collect-refs {:tool "$ref:nope" :command "$ref:nope"
                                :id "$ref:nope" :depends_on ["$ref:nope"]
                                :wave "$ref:nope"})))))

(deftest collect-refs-no-refs-test
  (testing "returns empty set when no refs"
    (is (= #{} (verbs/collect-refs {:tool "memory" :command "add"
                                     :content "plain text"})))))

(deftest collect-refs-same-ref-twice-test
  (testing "deduplicates same ref appearing multiple times"
    (is (= #{"$0"}
           (verbs/collect-refs {:tool "kg" :command "edge"
                                :from "$ref:$0.data.id"
                                :to   "$ref:$0.data.name"})))))

;; =============================================================================
;; Part 8: parse-sentence
;; =============================================================================

(deftest parse-sentence-basic-test
  (testing "verb resolution with param aliases"
    (is (= {:tool "memory" :command "add" :content "hello" :type "note"}
           (verbs/parse-sentence ["m+" {"c" "hello" "t" "note"}])))))

(deftest parse-sentence-no-params-test
  (testing "verb with nil params"
    (is (= {:tool "magit" :command "status"}
           (verbs/parse-sentence ["g?" nil]))))
  (testing "verb with empty params"
    (is (= {:tool "magit" :command "status"}
           (verbs/parse-sentence ["g?" {}])))))

(deftest parse-sentence-full-keys-test
  (testing "non-alias keys pass through as keywords"
    (is (= {:tool "kg" :command "edge" :from "a" :to "b" :relation "implements"}
           (verbs/parse-sentence ["k>" {"from" "a" "to" "b" "relation" "implements"}])))))

(deftest parse-sentence-mixed-alias-and-full-test
  (testing "mix of aliases and full keys"
    (is (= {:tool "memory" :command "add" :content "hi" :type "note" :directory "/proj"}
           (verbs/parse-sentence ["m+" {"c" "hi" "t" "note" "d" "/proj"}])))))

(deftest parse-sentence-tags-vector-test
  (testing "tags alias with vector value"
    (let [result (verbs/parse-sentence ["m+" {"c" "test" "#" ["a" "b"]}])]
      (is (= ["a" "b"] (:tags result))))))

(deftest parse-sentence-unknown-verb-test
  (testing "unknown verb returns error map"
    (let [result (verbs/parse-sentence ["zz" {}])]
      (is (string? (:error result)))
      (is (= "zz" (:verb result)))
      (is (nil? (:tool result))))))

(deftest parse-sentence-unknown-verb-with-params-test
  (testing "unknown verb ignores params (no expansion attempted)"
    (let [result (verbs/parse-sentence ["xx" {"c" "ignored"}])]
      (is (:error result))
      (is (= "xx" (:verb result)))
      (is (nil? (:content result))))))

(deftest parse-sentence-with-ref-values-test
  (testing "$ref strings pass through unexpanded in parse-sentence"
    (let [result (verbs/parse-sentence ["k>" {"from" "$ref:$0.data.id" "to" "b"}])]
      (is (= "$ref:$0.data.id" (:from result)))
      (is (= "kg" (:tool result))))))

(deftest parse-sentence-each-tool-family-test
  (testing "one verb from each family resolves correctly"
    (are [verb tool cmd]
        (let [r (verbs/parse-sentence [verb {}])]
          (and (= tool (:tool r)) (= cmd (:command r))))
      "m+" "memory"   "add"
      "k>" "kg"       "edge"
      "a+" "agent"    "spawn"
      "b+" "kanban"   "create"
      "s." "session"  "complete"
      "g?" "magit"    "status"
      "w!" "wave"     "dispatch"
      "h!" "hivemind" "shout"
      "p?" "preset"   "list"
      "c?" "config"   "get")))

;; =============================================================================
;; Part 9: parse-dsl
;; =============================================================================

(deftest parse-dsl-multiple-test
  (testing "parses multiple sentences"
    (let [results (verbs/parse-dsl [["m+" {"c" "hi" "t" "note"}]
                                    ["g?" {}]
                                    ["h!" {"p" "done"}]])]
      (is (= 3 (count results)))
      (is (= "memory"   (:tool (nth results 0))))
      (is (= "magit"    (:tool (nth results 1))))
      (is (= "hivemind" (:tool (nth results 2))))
      (is (= "done"     (:prompt (nth results 2)))))))

(deftest parse-dsl-empty-test
  (testing "empty input returns empty vector"
    (is (= [] (verbs/parse-dsl [])))))

(deftest parse-dsl-with-errors-test
  (testing "errors mixed with valid sentences"
    (let [results (verbs/parse-dsl [["m+" {"c" "ok"}]
                                    ["zz" {}]
                                    ["g?" {}]])]
      (is (= 3 (count results)))
      (is (= "memory" (:tool (nth results 0))))
      (is (:error (nth results 1)))
      (is (= "magit" (:tool (nth results 2)))))))

(deftest parse-dsl-single-sentence-test
  (testing "single sentence wraps correctly"
    (let [results (verbs/parse-dsl [["k#" {}]])]
      (is (= 1 (count results)))
      (is (= {:tool "kg" :command "stats"} (first results))))))

;; =============================================================================
;; Part 10: compile-paragraph
;; =============================================================================

(deftest compile-paragraph-auto-ids-test
  (testing "auto-assigns sequential $N IDs"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "a"}]
                                        ["m+" {"c" "b"}]
                                        ["g?" {}]])]
      (is (= "$0" (:id (nth ops 0))))
      (is (= "$1" (:id (nth ops 1))))
      (is (= "$2" (:id (nth ops 2)))))))

(deftest compile-paragraph-no-deps-test
  (testing "ops without refs have no :depends_on"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "a"}]
                                        ["g?" {}]])]
      (is (nil? (:depends_on (nth ops 0))))
      (is (nil? (:depends_on (nth ops 1)))))))

(deftest compile-paragraph-single-ref-test
  (testing "auto-detects $ref and adds depends_on"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "hello" "t" "note"}]
                                        ["k>" {"from" "$ref:$0.data.id"
                                               "to" "node-2"
                                               "relation" "implements"}]])]
      (is (= 2 (count ops)))
      (is (nil? (:depends_on (nth ops 0))))
      (is (= ["$0"] (:depends_on (nth ops 1))))
      ;; Verify $ref string is preserved (not resolved at compile time)
      (is (= "$ref:$0.data.id" (:from (nth ops 1)))))))

(deftest compile-paragraph-multi-ref-test
  (testing "multiple refs create sorted depends_on"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "a"}]
                                        ["m+" {"c" "b"}]
                                        ["k>" {"from" "$ref:$0.data.id"
                                               "to"   "$ref:$1.data.id"}]])]
      (is (= ["$0" "$1"] (:depends_on (nth ops 2)))))))

(deftest compile-paragraph-nested-ref-in-vector-test
  (testing "refs inside vector params are detected"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "src"}]
                                        ["m+" {"c" "x" "#" ["$ref:$0.data.tags"]}]])]
      (is (= ["$0"] (:depends_on (nth ops 1)))))))

(deftest compile-paragraph-nested-ref-in-map-test
  (testing "refs inside nested map params are detected"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "data"}]
                                        ["a!" {"prompt" "go"
                                               "data" {"target" "$ref:$0.data.id"}}]])]
      (is (= ["$0"] (:depends_on (nth ops 1)))))))

(deftest compile-paragraph-error-gets-id-test
  (testing "error sentences get $N ID assigned"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "hi"}]
                                        ["zz" {"x" "y"}]])]
      (is (= "$0" (:id (nth ops 0))))
      (is (= "$1" (:id (nth ops 1))))
      (is (:error (nth ops 1))))))

(deftest compile-paragraph-error-no-ref-extraction-test
  (testing "error sentences skip ref extraction (no depends_on added)"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "hi"}]
                                        ["zz" {"from" "$ref:$0.data.id"}]])]
      (is (nil? (:depends_on (nth ops 1)))))))

(deftest compile-paragraph-empty-test
  (testing "empty input returns empty vector"
    (is (= [] (verbs/compile-paragraph [])))))

(deftest compile-paragraph-preserves-expanded-params-test
  (testing "params are fully expanded in compiled output"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "hello" "t" "note" "#" ["tag1"]}]])]
      (is (= "hello" (:content (first ops))))
      (is (= "note" (:type (first ops))))
      (is (= ["tag1"] (:tags (first ops)))))))

(deftest compile-paragraph-explicit-depends-on-merges-test
  (testing "explicit depends_on in params merges with auto-detected refs"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "a"}]
                                        ["m+" {"c" "b"}]
                                        ["k>" {"from" "$ref:$1.data.id"
                                               "to" "node"
                                               "depends_on" ["$0"]}]])]
      ;; Should have both $0 (explicit) and $1 (from $ref)
      (is (= ["$0" "$1"] (:depends_on (nth ops 2)))))))

(deftest compile-paragraph-self-ref-not-filtered-test
  (testing "a $ref quoted in prose content wires NO dependency (quotation)"
    ;; Prose params (param-domain) are quotation: the self-dep incident class
    ;; is impossible from :content. Addressable params still collect refs.
    (let [ops (verbs/compile-paragraph [["m+" {"c" "$ref:$0.data.id"}]])]
      (is (nil? (:depends_on (first ops)))))))

(deftest compile-paragraph-chained-refs-test
  (testing "refs wire deps only from addressable params; prose quotes"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "first"}]
                                        ["m+" {"c" "$ref:$0.data.id"}]
                                        ["k>" {"from" "$ref:$1.data.id" "to" "x"}]])]
      (is (nil? (:depends_on (nth ops 0))))
      (is (nil? (:depends_on (nth ops 1))) "content ref is quotation, no dep")
      (is (= ["$1"] (:depends_on (nth ops 2))) "node-id ref still wires"))))

(deftest compile-paragraph-duplicate-ref-deduped-test
  (testing "same ref appearing twice in params is deduped in depends_on"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "x"}]
                                        ["k>" {"from" "$ref:$0.data.id"
                                               "to"   "$ref:$0.data.name"}]])]
      (is (= ["$0"] (:depends_on (nth ops 1)))))))

;; =============================================================================
;; Part 11: Integration — DSL → Multi-compatible format
;; =============================================================================

(deftest integration-compiled-ops-have-required-multi-fields-test
  (testing "compiled ops have :id :tool :command — ready for multi/validate-ops"
    (let [ops (verbs/compile-paragraph [["m+" {"c" "hi" "t" "note"}]
                                        ["k#" {}]
                                        ["g?" {}]])]
      (is (every? :id ops))
      (is (every? :tool ops))
      (is (every? :command ops))
      ;; No errors
      (is (not-any? :error ops)))))

(deftest integration-realistic-paragraph-test
  (testing "realistic workflow: add memory → create KG edge → shout"
    (let [ops (verbs/compile-paragraph
                [["m+" {"c" "Found bug in auth" "t" "note" "#" ["bug"]}]
                 ["k>" {"from" "$ref:$0.data.id" "to" "auth-module"
                         "relation" "applies-to"}]
                 ["h!" {"message" "Found auth bug, created memory + KG edge"
                         "event_type" "progress"}]])]
      (is (= 3 (count ops)))
      ;; Op 0: memory add
      (is (= "memory" (:tool (nth ops 0))))
      (is (= "Found bug in auth" (:content (nth ops 0))))
      (is (= ["bug"] (:tags (nth ops 0))))
      (is (nil? (:depends_on (nth ops 0))))
      ;; Op 1: kg edge depends on op 0
      (is (= "kg" (:tool (nth ops 1))))
      (is (= ["$0"] (:depends_on (nth ops 1))))
      (is (= "$ref:$0.data.id" (:from (nth ops 1))))
      ;; Op 2: hivemind shout (no deps)
      (is (= "hivemind" (:tool (nth ops 2))))
      (is (nil? (:depends_on (nth ops 2)))))))
