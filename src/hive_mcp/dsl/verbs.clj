(ns hive-mcp.dsl.verbs
  "Compressed verb DSL for the Multi-tool batch engine.

   Maps short verb codes to {:tool :command} pairs, with parameter aliases
   for token-efficient batch composition. ~60% token reduction vs verbose JSON.

   Three layers of abstraction:
   1. parse-sentence  — single [verb params] → standard operation map
   2. parse-dsl       — vector of sentences → vector of operations
   3. compile-paragraph — sentences with $ref → batch ops with auto IDs + depends_on

   Usage:
     (parse-sentence [\"m+\" {\"c\" \"hello\" \"t\" \"note\"}])
     ;=> {:tool \"memory\" :command \"add\" :content \"hello\" :type \"note\"}

     (compile-paragraph [[\"m+\" {\"c\" \"hello\"}]
                          [\"k>\" {\"from\" \"$ref:$0.data.id\" \"to\" \"node-2\"
                                  \"relation\" \"implements\"}]])
     ;=> [{:id \"$0\" :tool \"memory\" :command \"add\" :content \"hello\"}
          {:id \"$1\" :tool \"kg\" :command \"edge\" :from \"$ref:$0.data.id\"
           :to \"node-2\" :relation \"implements\" :depends_on [\"$0\"]}]

   Design decision: 20260211212019-b8499942 (Multi DSL plan, Phase 2)"
  (:require [clojure.string :as str]
            [hive-mcp.dsl.param-domain :as pd]
            [hive-mcp.extensions.delegate :refer [delegate-or-noop]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Extension Delegation Helper
;; =============================================================================

;; =============================================================================
;; Verb Table — 36 verbs covering all consolidated tools
;; =============================================================================
;;
;; Convention: first char = tool family, remaining char(s) = action.
;;   + create/add    ? query/status/list   @ get       / search
;;   > move/push     ! dispatch/action     # stats     ~ wrap
;;   < catchup       . complete            x kill      y/n approve/reject
;;   ^ traverse      * list-all

(def verb-table
  "Maps compressed verb codes to {:tool :command} pairs.
   36 verbs across 10 tool families."
  {;; Memory (m)
   "m+" {:tool "memory"   :command "add"}
   "m?" {:tool "memory"   :command "query"}
   "m@" {:tool "memory"   :command "get"}
   "m/" {:tool "memory"   :command "search"}
   "m="  {:tool "memory"  :command "edit"}
   "m#"  {:tool "memory"  :command "tags"}
   "m^"  {:tool "memory"  :command "promote"}
   "m_"  {:tool "memory"  :command "demote"}

   ;; Knowledge Graph (k)
   "k>" {:tool "kg"       :command "edge"}
   "k^" {:tool "kg"       :command "traverse"}
   "k!" {:tool "kg"       :command "impact"}
   "k#" {:tool "kg"       :command "stats"}

   ;; Agent (a)
   "a+" {:tool "agent"    :command "spawn"}
   "a?" {:tool "agent"    :command "status"}
   "a!" {:tool "agent"    :command "dispatch"}
   "ax" {:tool "agent"    :command "kill"}

   ;; Kanban (b — "board")
   "b+" {:tool "kanban"   :command "create"}
   "b>" {:tool "kanban"   :command "update"}
   "b?" {:tool "kanban"   :command "list"}
   "b#" {:tool "kanban"   :command "status"}
   "b-" {:tool "kanban"   :command "delete"}

   ;; Session (s)
   "s." {:tool "session"  :command "complete"}
   "s~" {:tool "session"  :command "wrap"}
   "s?" {:tool "session"  :command "whoami"}
   "s<" {:tool "session"  :command "catchup"}

   ;; Magit/Git (g)
   "g?" {:tool "magit"    :command "status"}
   "g+" {:tool "magit"    :command "stage"}
   "g!" {:tool "magit"    :command "commit"}
   "g>" {:tool "magit"    :command "push"}

   ;; Wave (w)
   "w!" {:tool "wave"     :command "dispatch"}
   "w?" {:tool "wave"     :command "status"}
   "wy" {:tool "wave"     :command "approve"}
   "wn" {:tool "wave"     :command "reject"}

   ;; Hivemind (h)
   "h!" {:tool "hivemind" :command "shout"}
   "h?" {:tool "hivemind" :command "ask"}

   ;; Preset (p)
   "p?" {:tool "preset"   :command "list"}
   "p@" {:tool "preset"   :command "get"}
   "p/" {:tool "preset"   :command "search"}

   ;; Config (c)
   "c?" {:tool "config"   :command "get"}
   "c!" {:tool "config"   :command "set"}
   "c*" {:tool "config"   :command "list"}})

;; =============================================================================
;; Parameter Aliases — Short keys → full keywords
;; =============================================================================

(def param-aliases
  "Maps single-char (or short) parameter aliases to full keywords.
   Applied during sentence parsing to expand compressed params."
  {"c"   :content
   "t"   :type
   "#"   :tags
   "d"   :directory
   "q"   :query
   "n"   :name
   "id"  :id
   "p"   :prompt
   "f"   :files
   ;; k> edge: docs/examples use "rel"; map to the handler's :relation key.
   "rel" :relation
   ;; b+ idempotency-key: short alias so the verb stays terse.
   ;; Underscored long form is the canonical handler param shape;
   ;; both the alias and the snake-case name reach the same handler
   ;; param via memory-kanban/normalize-idempotency-key.
   "idk" :idempotency_key})

;; =============================================================================
;; Parameter Expansion
;; =============================================================================

(defn expand-param-key
  "Expand a parameter key using aliases.
   String keys check alias table first, then keywordize.
   Keyword keys check alias table via (name k), then pass through.
   Other types are coerced to keyword via str."
  [k]
  (cond
    (keyword? k) (get param-aliases (name k) k)
    (string? k)  (get param-aliases k (keyword k))
    :else        (keyword (str k))))

(defn expand-params
  "Expand all param aliases in a map. Returns map with full keyword keys.
   nil input returns nil. Empty map returns empty map."
  [params]
  (when params
    (reduce-kv (fn [m k v]
                 (assoc m (expand-param-key k) v))
               {}
               params)))

;; =============================================================================
;; $ref Collection (for auto depends_on in compile-paragraph)
;; =============================================================================

(defn ref-target
  "Extract the op-id from a $ref string. Returns nil if not a ref.
   \"$ref:$0.data.id\" => \"$0\"
   \"$ref:op-1\"       => \"op-1\"
   \"not-a-ref\"       => nil"
  [v]
  (when (and (string? v) (str/starts-with? v "$ref:"))
    (let [body    (subs v 5)
          dot-idx (str/index-of body ".")]
      (if dot-idx
        (subs body 0 dot-idx)
        body))))

(defn collect-refs
  "Collect all $ref op-id targets from an operation's param values.
   Walks maps and vectors recursively. Skips meta keys AND prose params
   (pd/prose-param-keys) — a `$ref:` inside prose is quotation, not a
   dependency. Returns a set of op-id strings."
  [op]
  (let [refs (volatile! #{})]
    (letfn [(walk [v]
              (cond
                (string? v)     (when-let [target (ref-target v)]
                                  (vswap! refs conj target))
                (map? v)        (run! walk (vals v))
                (sequential? v) (run! walk v)
                :else           nil))]
      (doseq [[_ v] (pd/ref-walkable-entries op)]
        (walk v)))
    @refs))

;; =============================================================================
;; Sentence Parsing
;; =============================================================================

(defn parse-sentence
  "Parse a DSL sentence [verb params-map] into a standard operation map.
   Resolves verb code to {:tool :command}, expands parameter aliases.

   For kanban task-keyed verbs (b> update, b- delete) the advertised `id`
   alias is remapped to :task_id — the key those handlers require — and the
   op-local :id is freed so the batch compiler can assign its \"$N\" ref id.

   Returns expanded operation map, or {:error \"...\" :verb verb} for unknowns.

   Examples:
     (parse-sentence [\"m+\" {\"c\" \"hello\" \"t\" \"note\"}])
     ;=> {:tool \"memory\" :command \"add\" :content \"hello\" :type \"note\"}

     (parse-sentence [\"b>\" {\"id\" \"20260101-abcd\" \"new_status\" \"done\"}])
     ;=> {:tool \"kanban\" :command \"update\" :task_id \"20260101-abcd\" :new_status \"done\"}

     (parse-sentence [\"zz\" {}])
     ;=> {:error \"Unknown verb: zz\" :verb \"zz\"}"
  [[verb params]]
  (if-let [{:keys [tool command]} (get verb-table verb)]
    (let [op (merge {:tool tool :command command}
                    (expand-params params))]
      (if (and (= tool "kanban")
               (contains? #{"update" "delete"} command)
               (contains? op :id)
               (not (contains? op :task_id)))
        (-> op (assoc :task_id (:id op)) (dissoc :id))
        op))
    {:error (str "Unknown verb: " verb)
     :verb  verb}))

;; =============================================================================
;; DSL Parsing (multiple sentences)
;; =============================================================================

(defn parse-dsl
  "Parse a vector of DSL sentences into standard operation maps.
   Each sentence is [verb params-map]. Returns vector of expanded operations.
   Unknown verbs produce {:error ...} entries in the output."
  [sentences]
  (mapv parse-sentence sentences))

;; =============================================================================
;; Paragraph Compilation (auto IDs + auto depends_on from $ref)
;; =============================================================================

(defn- compile-paragraph-local
  "Local fallback: assign sequential IDs and collect $ref dependencies.
   Used when extension is not available."
  [sentences]
  (let [parsed (parse-dsl sentences)]
    (mapv (fn [idx op]
            (let [id         (str "$" idx)
                  op-with-id (assoc op :id id)]
              (if (:error op)
                op-with-id
                (let [ref-deps     (collect-refs op-with-id)
                      explicit-deps (when-let [d (:depends_on op)]
                                      (cond
                                        (string? d) #{d}
                                        (sequential? d) (set d)
                                        :else #{}))
                      all-deps     (into (or explicit-deps #{}) ref-deps)]
                  (if (seq all-deps)
                    (assoc op-with-id :depends_on (vec (sort all-deps)))
                    op-with-id)))))
          (range)
          parsed)))

;; =============================================================================
;; Extension Stub — delegates to extension or returns local fallback
;; =============================================================================

(defn compile-verb
  "Compile DSL sentences into batch operations.
   Delegates to extension if available."
  [sentences]
  (delegate-or-noop :dv/compile
                    (compile-paragraph-local sentences)
                    [sentences]))

(defn compile-paragraph
  "Compile DSL sentences into a fully-resolved batch operations vector.

   Auto-assigns sequential IDs (\"$0\", \"$1\", \"$2\"...).
   Detects $ref strings in params and auto-populates :depends_on.
   Merges auto-detected refs with any explicit :depends_on from params.
   Error sentences (unknown verbs) pass through with ID assigned.

   Returns vector ready for multi-tool batch execution (handle-batch).

   Example:
     (compile-paragraph [[\"m+\" {\"c\" \"hello\" \"t\" \"note\"}]
                          [\"k>\" {\"from\" \"$ref:$0.data.id\" \"to\" \"node-2\"
                                  \"relation\" \"implements\"}]])
     ;=> [{:id \"$0\" :tool \"memory\" :command \"add\" :content \"hello\" :type \"note\"}
          {:id \"$1\" :tool \"kg\" :command \"edge\" :from \"$ref:$0.data.id\" :to \"node-2\"
           :relation \"implements\" :depends_on [\"$0\"]}]"
  [sentences]
  (compile-verb sentences))
