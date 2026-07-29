(ns hive-mcp.batch.ref-contract
  "The contract of the batch $ref extension seam (:bx/a - :bx/g), stated once.

   `hive-mcp.batch` reaches $ref parsing, resolution, ref collection and
   ref-dependency validation through `delegate-or-noop` extension keys, so the
   SEMANTICS live in a provider hive-mcp does not own:
   `hive-knowledge.agent.multi-batch` in production (absent from hive-mcp's
   committed deps), a port under `hive-mcp.test.stub.batch-extensions` in cold
   CI. A seam whose only specification is a docstring drifts silently; this
   namespace is the specification instead.

   What it holds:

     value objects  ParsedRef / MaybeParsedRef / Ops / ErrorStrings ... —
                    the malli shapes a provider's values must inhabit.
     judges         `parsed-ref-faithful?`, `undeclared-ref-pairs`,
                    `undeclared-ref-error` — decision procedures written
                    independently of every provider, so a provider is checked
                    against the rule rather than against another provider.
     corpora        `parse-ref-cases`, `resolve-ref-cases`,
                    `collect-ref-op-ids-cases`, `validate-ref-deps-cases` —
                    rows every provider must answer identically. The :bx/g
                    corpus carries no restated expectation at all: it is
                    derived from the judge, so a case cannot be quietly
                    re-pointed at whatever an implementation happens to do.

   Exercised by `hive-mcp.batch.ref-conformance-test`, which runs the corpora
   against every provider on the classpath."
  (:require [clojure.string :as str]
            [hive-mcp.dsl.param-domain :as pd]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ref-prefix
  "The marker that makes a string a machine reference rather than data."
  "$ref:")

;; =============================================================================
;; Value objects
;; =============================================================================

(def OpId
  "The id of the operation a $ref designates. A string, possibly empty:
   \"$ref:\" parses rather than throwing, and the batch validator — not the
   parser — refuses it."
  :string)

(def PathSegment
  "One dotted segment of a $ref path, as a STRING. Keywordisation belongs to
   :bx/c on lookup, so :bx/a's value stays free of the result map's key
   encoding."
  :string)

(def Path
  "The dotted tail of a $ref; empty when the ref designates a whole result."
  [:vector PathSegment])

(def ParsedRef
  "The value :bx/a (parse-ref) yields for a $ref string."
  [:map {:closed true}
   [:op-id OpId]
   [:path Path]])

(def MaybeParsedRef
  "The total return type of :bx/a: nil for anything that is not a $ref."
  [:maybe ParsedRef])

(def RefCandidate
  "The input domain :bx/a must be total over — $ref-shaped strings and
   ordinary ones, generated in both shapes."
  [:or
   [:string {:gen/fmap #(str ref-prefix % ".data.id")}]
   [:string {:gen/fmap #(str ref-prefix %)}]
   :string])

(def Op
  "One batch operation as the seam sees it: a keyword-keyed param map."
  [:map-of :keyword :any])

(def Ops
  "A batch."
  [:vector Op])

(def RefOps
  "Batches whose ops carry $ref params, depends_on declarations and prose —
   the input domain :bx/g's decision is interesting over. `:content` is a
   prose param, so a $ref inside it must never become a dependency."
  [:vector {:min 1 :max 4}
   [:map {:closed true}
    [:id [:enum "a" "b" "c"]]
    [:depends_on [:vector {:max 2} [:enum "a" "b" "c"]]]
    [:from [:or
            [:enum "$ref:a.data.id" "$ref:b.data.id" "$ref:c.data.id"
             "$ref:ghost.data.id"]
            :string]]
    [:content [:enum "$ref:a.data.id" "plain text"]]]])

(def ErrorStrings
  "The value :bx/g (validate-ref-deps) yields: one string per violation,
   empty when every $ref is declared."
  [:vector :string])

(def OpIds
  "The value :bx/f (collect-ref-op-ids) yields."
  [:set OpId])

;; =============================================================================
;; Judges — the rules, written independently of every provider
;; =============================================================================

(defn ref-string?
  "Is V a machine reference?"
  [v]
  (and (string? v) (str/starts-with? v ref-prefix)))

(defn parsed-ref-faithful?
  "Does OUT answer IN under the :bx/a contract? Decides WITHOUT re-parsing:

     - a non-$ref input yields nil;
     - a $ref yields {:op-id :path} whose op-id is a dot-free head, whose
       segments are dot-free strings, and which rejoined by \".\" reconstructs
       the ref body up to trailing empty segments (which `split` drops)."
  [in out]
  (if-not (ref-string? in)
    (nil? out)
    (and (map? out)
         (= #{:op-id :path} (set (keys out)))
         (string? (:op-id out))
         (vector? (:path out))
         (every? string? (:path out))
         (not (str/includes? (:op-id out) "."))
         (every? #(not (str/includes? % ".")) (:path out))
         (let [body   (subs in (count ref-prefix))
               joined (str/join "." (cons (:op-id out) (:path out)))]
           (and (str/starts-with? body joined)
                (every? #{\.} (subs body (count joined))))))))

(defn ref-op-id
  "The op-id S designates — the ref body up to the first dot. The
   specification's own reader: `undeclared-ref-pairs` must not borrow a
   provider's parser to judge that provider."
  [s]
  (when (ref-string? s)
    (let [body (subs s (count ref-prefix))
          i    (str/index-of body ".")]
      (if i (subs body 0 i) body))))

(defn ref-strings
  "Every $ref string inside V, at any depth."
  [v]
  (cond
    (ref-string? v)  [v]
    (map? v)         (into [] (mapcat ref-strings) (vals v))
    (sequential? v)  (into [] (mapcat ref-strings) v)
    :else            []))

(defn op-ref-op-ids
  "The set of op-ids OP references through its addressable params. Prose
   params are quotation and contribute nothing. The :bx/f rule."
  [op]
  (into #{}
        (comp (mapcat (fn [[_ v]] (ref-strings v)))
              (map ref-op-id))
        (pd/ref-walkable-entries op)))

(defn undeclared-ref-pairs
  "#{[op-id ref-id] ...} for every $ref in OPS whose target the referencing
   op does not declare in :depends_on. The :bx/g rule: a $ref is an ordering
   edge, not merely a name — an undeclared target may not have run yet."
  [ops]
  (into #{}
        (mapcat (fn [{:keys [id depends_on] :as op}]
                  (let [dep-set (set (or depends_on []))]
                    (for [ref-id (op-ref-op-ids op)
                          :when  (not (contains? dep-set ref-id))]
                      [id ref-id]))))
        ops))

(defn undeclared-ref-error
  "The error string :bx/g emits for one [op-id ref-id] violation."
  [op-id ref-id]
  (str "Operation '" op-id "' has $ref to '" ref-id
       "' but doesn't declare it in depends_on"))

(defn expected-ref-dep-errors
  "The set of error strings a conforming :bx/g answers OPS with."
  [ops]
  (into #{} (map (fn [[op-id ref-id]] (undeclared-ref-error op-id ref-id)))
        (undeclared-ref-pairs ops)))

(defn not-found-sentinel?
  "Is V a resolve-ref (:bx/c) not-found sentinel — a namespaced keyword named
   \"ref-not-found\"? The WEAK, provider-agnostic half of the :bx/c contract:
   a missing op must resolve to something no op-result could be, and never to
   nil. WHICH sentinel is a per-provider fact, pinned separately against the
   provider hive-mcp.batch actually has installed."
  [v]
  (and (keyword? v)
       (some? (namespace v))
       (= "ref-not-found" (name v))))

;; =============================================================================
;; Corpora — the rows every provider must answer identically
;; =============================================================================

(def parse-ref-cases
  "[label input expected] for :bx/a."
  [["a dotted ref splits into an op-id and a STRING path"
    "$ref:op-1.data.id"            {:op-id "op-1" :path ["data" "id"]}]
   ["a ref carrying no path designates the whole op-result"
    "$ref:op-1"                    {:op-id "op-1" :path []}]
   ["every segment survives, numeric ones included"
    "$ref:a.result.content.0.text" {:op-id "a" :path ["result" "content" "0" "text"]}]
   ["a plain string is not a ref"
    "not-a-ref"                    nil]
   ["nil is not a ref"
    nil                            nil]
   ["the prefix is case-sensitive"
    "$REF:op-1"                    nil]
   ["a bare prefix parses to an empty op-id — the validator refuses it, not the parser"
    "$ref:"                        {:op-id "" :path []}]
   ["a trailing dot leaves an empty segment"
    "$ref:a."                      {:op-id "a" :path [""]}]])

(def sample-results
  "The results-by-id map the :bx/c corpus resolves against."
  {"a" {:id "a" :success true :data {:id "mem-123" :status "ok" :field nil}}})

(def resolve-ref-cases
  "[label parsed-ref results expected] for :bx/c. The missing-op row is NOT
   here: providers agree that it yields a sentinel but not on which one, so
   the conformance suite checks that row by property (`not-found-sentinel?`)."
  [["string path segments are keywordised on lookup"
    {:op-id "a" :path ["data" "id"]}    sample-results "mem-123"]
   ["an empty path designates the whole op-result"
    {:op-id "a" :path []}               sample-results (get sample-results "a")]
   ["a top-level op-result key resolves"
    {:op-id "a" :path ["success"]}      sample-results true]
   ["a path that exists but holds nil resolves to nil, not to the sentinel"
    {:op-id "a" :path ["data" "field"]} sample-results nil]])

(def collect-ref-op-ids-cases
  "[label op] for :bx/f; the expectation is `op-ref-op-ids`, the rule itself."
  [["addressable params contribute their refs"
    {:id "b" :tool "kg" :command "edge"
     :from "$ref:a.data.id" :tags ["$ref:c.data.tag" "static"]
     :depends_on ["a" "c"]}]
   ["an op with no refs references nothing"
    {:id "a" :tool "memory" :command "add" :content "hello"}]
   ["meta params are plumbing — never a reference"
    {:id "$ref:fake" :tool "$ref:fake" :depends_on ["$ref:fake"]
     :wave "$ref:fake" :real-param "$ref:a.data.id"}]
   ["a $ref inside prose is quotation"
    {:id "b" :tool "kanban" :title "$ref:a.data.id" :content "$ref:a.data.id"}]
   ["refs nested in maps and vectors are found"
    {:id "b" :config {:target "$ref:a.data.id"} :list [["$ref:c.data.id"]]}]])

(def validate-ref-deps-cases
  "[label ops] for :bx/g. No expectation is restated: it is
   `expected-ref-dep-errors`, so a case cannot be re-pointed at an
   implementation without changing the rule."
  [["a $ref to a declared dependency is accepted"
    [{:id "a" :tool "memory" :command "add"}
     {:id "b" :tool "kg" :command "edge" :from "$ref:a.data.id" :depends_on ["a"]}]]
   ["a $ref to an op that is IN the batch but undeclared is refused"
    [{:id "a" :tool "memory" :command "add"}
     {:id "b" :tool "kg" :command "edge" :from "$ref:a.data.id"}]]
   ["a partially declared op is refused for the undeclared ref only"
    [{:id "a" :tool "memory" :command "add"}
     {:id "b" :tool "memory" :command "add"}
     {:id "c" :tool "kg" :command "edge"
      :from "$ref:a.data.id" :to "$ref:b.data.id" :depends_on ["a"]}]]
   ["every declared ref of a multi-ref op is accepted"
    [{:id "a" :tool "memory" :command "add"}
     {:id "b" :tool "memory" :command "add"}
     {:id "c" :tool "kg" :command "edge"
      :from "$ref:a.data.id" :to "$ref:b.data.id" :depends_on ["a" "b"]}]]
   ["a $ref to an op absent from the batch is refused"
    [{:id "b" :tool "kg" :command "edge" :from "$ref:ghost.data.id"}]]
   ["a $ref inside prose is quotation, never a dependency"
    [{:id "b" :tool "kanban" :command "create" :title "$ref:a.data.id"}]]
   ["a batch with no refs at all is accepted"
    [{:id "a" :tool "memory" :command "add"}
     {:id "b" :tool "kg" :command "stats" :depends_on ["a"]}]]
   ["an empty batch is accepted"
    []]])
