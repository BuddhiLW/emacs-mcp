(ns hive-mcp.plan.parser.edn
  "EDN plan parser — extracts and parses EDN plan structures from content.

   Supports:
   1. Raw EDN content with :steps or :plan/steps
   2. ```edn code blocks containing plan structures
   3. Phase-based plans ({:phase N :tasks [...]}) across multiple blocks
   4. Mixed markdown/text with embedded EDN

   Architecture: Parser combinator pattern — strategies composed via `some`,
   normalizers as composable transform pipeline, state machine for brace matching.

   CC-free constructs used throughout:
   - if-let, when-let, when-not (FREE) instead of if, when, cond
   - case (FREE) instead of cond for character dispatch
   - cond-> (FREE) for conditional map building"

  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [hive-mcp.dns.result :as result]
            [hive-mcp.plan.schema :as schema]
            [clojure.tools.logging :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; EDN Block Extraction
;; =============================================================================

(def ^:private edn-block-pattern
  "Regex pattern to match ```edn ... ``` code blocks"
  #"(?s)```edn\s*\n(.*?)\n```")

(defn- extract-edn-blocks
  "Extract all EDN code blocks from content.
   Returns: vector of EDN strings found in ```edn ... ``` blocks"
  [content]
  (->> (re-seq edn-block-pattern content)
       (mapv second)))

;; =============================================================================
;; Detection Predicates
;; =============================================================================
;;
;; Detection is AST-based, not regex-based. Regex on EDN is fragile —
;; nested maps before :steps, multi-line forms with reader macros, and
;; namespaced map literals all defeat naïve patterns. Instead we extract
;; candidate substrings, parse each with clojure.edn/read-string, and walk
;; the resulting AST looking for plan-shape (:steps / :plan/steps) or
;; phase-shape (:phase + :tasks) maps.
;;
;; References:
;;   - clojure.edn API:  https://clojure.github.io/clojure/clojure.edn-api.html
;;   - EDN spec:         https://edn-format.dev/

(defn contains-edn-block?
  "Check if content contains any ```edn ... ``` blocks."
  [content]
  (boolean (re-find edn-block-pattern content)))

(def ^:private edn-read-opts
  "Pass-through opts for clojure.edn reads. `:default` swallows unknown
   reader tags (returning their value) so user-tagged literals like
   `#myapp/Foo {...}` do not abort parsing during detection."
  {:default (fn [_tag v] v)})

(defn- safe-read-edn
  "Parse a string as a single EDN form. Returns the value or nil on error."
  [s]
  (try
    (edn/read-string edn-read-opts s)
    (catch Exception _ nil)))

(defn- read-all-forms
  "Sequentially read every top-level EDN form from `content` using
   clojure.edn/read against a PushbackReader. The EDN reader natively
   handles every spec feature — commas as whitespace, `;` line comments,
   `#_` discard, character literals (`\\}`), tagged elements (`#inst`,
   `#uuid`, user `#ns/tag`), namespaced maps (`#:ns{...}`), sets
   (`#{...}`), lists, and nested collections. Stops on EOF or first
   parse failure (read advances past whitespace/comments between forms)."
  [content]
  (when (string? content)
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. content))
          eof ::eof]
      (loop [acc []]
        (let [form (try
                     (edn/read (assoc edn-read-opts :eof eof) rdr)
                     (catch Exception _ eof))]
          (if (= form eof)
            acc
            (recur (conj acc form))))))))

;; Forward declaration — implementation below in the Balanced Brace section.
;; Needed here because candidate-parsed-forms uses balanced extraction as a
;; fallback when prose-prefixed EDN (e.g. memory-stored plans wrapped with
;; "Plan Entry [draft]\nType: plan\n...\n\n{:plan/steps [...]}") trips up the
;; top-level reader on non-EDN preamble tokens.
(declare find-balanced-edn)

(defn- balanced-edn-substrings
  "Yield every balanced `{...}` substring found in `content`, in left-to-right
   order. For each `{` position, attempt `find-balanced-edn` and skip past it
   on success; otherwise advance one char. Returns a vector of substrings."
  [content]
  (when (string? content)
    (let [len (count content)]
      (loop [idx 0
             acc (transient [])]
        (if (>= idx len)
          (persistent! acc)
          (if (= \{ (.charAt ^String content idx))
            (if-let [edn-str (find-balanced-edn content idx)]
              (recur (+ idx (count edn-str))
                     (conj! acc edn-str))
              (recur (inc idx) acc))
            (recur (inc idx) acc)))))))

(defn- candidate-parsed-forms
  "Return all parseable EDN forms found anywhere in `content`:
     - top-level forms (sequential reads against the whole content);
     - the body of every ```edn fenced block (parsed independently);
     - every balanced `{...}` substring (handles prose-wrapped EDN — e.g.
       memory-stored plans with a `Plan Entry [draft]\\n…` preamble that
       would otherwise abort the top-level reader before it reaches the
       map literal).
   Returns empty seq for non-strings."
  [content]
  (when (string? content)
    (concat (read-all-forms content)
            (mapcat read-all-forms (extract-edn-blocks content))
            (keep safe-read-edn (balanced-edn-substrings content)))))

(defn- is-plan-shape?
  "True iff `x` is a map carrying :steps or :plan/steps with a sequential value.
   Accepts vectors and lists — EDN authored with parens (`:steps (...)`) is
   normalized to a vector downstream by `normalize-edn-plan`'s `mapv`."
  [x]
  (and (map? x)
       (let [steps (or (:steps x) (:plan/steps x))]
         (sequential? steps))))

(defn- is-phase-shape?
  "True iff `x` is a map carrying both a phase identifier and a :tasks
   sequential. Accepts vectors and lists — list `:tasks` are normalized
   to a vector by `phase-tasks->steps`'s `map-indexed`/`vec`."
  [x]
  (and (map? x)
       (or (contains? x :phase) (contains? x :phase/id))
       (or (sequential? (:tasks x)) (sequential? (:phase/tasks x)))))

(defn- plan-or-phase-anywhere?
  "Walk the parsed AST and return true if any subform is plan- or phase-shaped."
  [parsed]
  (some (some-fn is-plan-shape? is-phase-shape?)
        (tree-seq coll? seq parsed)))

(defn contains-edn-plan?
  "Check if content contains an EDN plan or phase block.

   AST-based: read every parseable EDN form from the content (top-level
   forms + bodies of ```edn fenced blocks) using `clojure.edn/read`, then
   walk each AST via `tree-seq` looking for a plan-shape map (:steps or
   :plan/steps with vector value) or a phase-shape map (:phase + :tasks).

   The EDN reader handles all spec features for us — comments, `#_`
   discard, namespaced maps, sets, tagged literals (unknown tags are
   passed through via `:default`). Returns false on non-strings."
  [content]
  (boolean (some plan-or-phase-anywhere? (candidate-parsed-forms content))))

;; =============================================================================
;; Safe EDN Parsing
;; =============================================================================

(defn- try-parse-edn
  "Safely attempt to parse EDN string.
   Returns: Result — {:ok data} or {:error :edn/parse-failed ...}"
  [edn-str]
  (result/try-effect* :edn/parse-failed
                      (edn/read-string edn-str)))

;; =============================================================================
;; Balanced Brace Extraction (State Machine)
;; =============================================================================

(defn- advance-brace-state
  "Pure state transition for balanced-brace parser.

   Uses case (CC-free) for character dispatch, if-let (CC-free) for guards.
   Returns updated state map with :action (:continue or :done)."
  [{:keys [depth in-string escape-next]} c]
  (if-let [_ escape-next]
    ;; After escape char: consume and clear flag
    {:depth depth :in-string in-string :escape-next false :action :continue}
    (if-let [_ in-string]
      ;; Inside string literal: only escape and close-quote matter
      (case c
        \\ {:depth depth :in-string true :escape-next true :action :continue}
        \" {:depth depth :in-string false :escape-next false :action :continue}
        {:depth depth :in-string true :escape-next false :action :continue})
      ;; Outside string: handle braces and open-quote
      (case c
        \" {:depth depth :in-string true :escape-next false :action :continue}
        \{ {:depth (inc depth) :in-string false :escape-next false :action :continue}
        \} (let [new-depth (dec depth)]
             {:depth new-depth :in-string false :escape-next false
              :action (if-let [_ (when-not (pos? new-depth) :balanced)] :done :continue)})
        {:depth depth :in-string false :escape-next false :action :continue}))))

(defn- find-balanced-edn
  "Find the first balanced {} substring starting from start-idx.
   Returns: EDN substring or nil if unbalanced."
  [content start-idx]
  (let [len (count content)]
    (loop [idx start-idx
           state {:depth 0 :in-string false :escape-next false}]
      (when-let [c (when-not (>= idx len) (nth content idx))]
        (let [new-state (advance-brace-state state c)]
          (case (:action new-state)
            :done (subs content start-idx (inc idx))
            :continue (recur (inc idx) new-state)))))))

(defn- extract-edn-from-content
  "Extract EDN map from mixed markdown/text content.
   Finds the first balanced {} that contains :steps or :plan/steps."
  [content]
  (when-let [start-idx (and (string? content) (str/index-of content "{"))]
    (when-let [edn-str (find-balanced-edn content start-idx)]
      (when-let [_ (re-find #":(?:plan/)?steps\s*\[" edn-str)]
        edn-str))))

;; =============================================================================
;; Structural Predicates
;; =============================================================================

(defn- get-steps-key
  "Get steps from EDN data, checking both namespaced and non-namespaced keys."
  [data]
  (if-let [steps (:steps data)] steps (:plan/steps data)))

(defn- is-plan-edn?
  "Check if parsed EDN looks like a plan (has :steps or :plan/steps key).
   Accepts sequential values (vectors or lists); normalization upstream
   coerces lists to vectors before schema validation."
  [data]
  (when-let [_ (map? data)]
    (when-let [steps (get-steps-key data)]
      (sequential? steps))))

(defn- is-phase-edn?
  "Check if parsed EDN looks like a phase block ({:phase N :tasks [...]})."
  [data]
  (when-let [_ (map? data)]
    (when-let [_ (if-let [t (:tasks data)] t (:phase/tasks data))]
      (if-let [p (:phase data)] p (:phase/id data)))))

;; =============================================================================
;; Step Normalization Pipeline (Composable Transforms)
;; =============================================================================

(defn- strip-namespace
  "Remove namespace from a keyword if present."
  [k]
  (if-let [_ (keyword? k)]
    (keyword (name k))
    k))

(defn- keyword->string
  "Convert keyword to string using name, pass strings through unchanged."
  [v]
  (if-let [_ (keyword? v)] (name v) v))

(defn- strip-all-namespaces
  "Remove namespaces from all keys in a map. First stage of normalization."
  [step]
  (reduce-kv (fn [m k v] (assoc m (strip-namespace k) v)) {} step))

(defn- coerce-id
  "Coerce :id from keyword to string (schema requires string)."
  [step]
  (if-let [id (:id step)]
    (assoc step :id (keyword->string id))
    step))

(defn- alias-dependencies
  "Normalize :dependencies / :blockedBy alias -> :depends-on.
   SAA plans use :dependencies; some EDN dialects emit :blockedBy.
   Canonical key is :depends-on; existing :depends-on takes precedence."
  [step]
  (if-let [deps (when-not (contains? step :depends-on)
                  (or (:dependencies step) (:blockedBy step)))]
    (-> step
        (assoc :depends-on deps)
        (dissoc :dependencies :blockedBy))
    (dissoc step :blockedBy)))

(defn- coerce-depends-on
  "Coerce :depends-on items from keywords to strings."
  [step]
  (if-let [deps (:depends-on step)]
    (assoc step :depends-on (mapv keyword->string deps))
    step))

(defn- alias-file
  "Normalize :file (singular string) -> :files (vector)."
  [step]
  (if-let [f (when-not (contains? step :files) (:file step))]
    (-> step
        (assoc :files (if-let [_ (string? f)] [f] (vec f)))
        (dissoc :file))
    step))

(def ^:private known-step-keys
  "Step keys recognized by the parser (post strip-namespaces, post-aliasing).
   Anything outside this set triggers a warn — surfaces silent drops like :wave."
  #{:id :title :description :depends-on :priority :files :estimate :tags
    :dependencies :blockedBy :file
    :why :validation :details :deliverable :est-tokens
    :files-read :files-write})

(defn- warn-unknown-keys
  "Log warn for step keys not in `known-step-keys`. Pure pass-through."
  [step]
  (let [unknown (remove known-step-keys (keys step))]
    (when (seq unknown)
      (clojure.tools.logging/warn
       "[plan-parser] step has unknown keys (silently dropped):"
       {:step-id (:id step) :unknown (vec unknown)}))
    step))

(defn- normalize-edn-step
  "Normalize an EDN step map via composable transform pipeline.
   Pipeline: warn-unknown -> strip-namespaces -> coerce-id ->
             alias-deps -> coerce-deps -> alias-file"
  [step]
  (-> step
      warn-unknown-keys
      strip-all-namespaces
      coerce-id
      alias-dependencies
      coerce-depends-on
      alias-file))

;; =============================================================================
;; Plan Normalization
;; =============================================================================

(defn- normalize-edn-plan
  "Normalize an EDN plan map, converting namespaced keys to non-namespaced.
   Also normalizes nested step maps and coerces plan-level :id to string."
  [data]
  (let [base-map (-> data strip-all-namespaces coerce-id)
        steps (get-steps-key data)]
    (cond-> base-map
      steps (assoc :steps (mapv normalize-edn-step steps)))))

;; =============================================================================
;; Phase-Based Plan Parsing (Multi-Block EDN)
;; =============================================================================

(defn- ns-key
  "Retrieve value trying plain key, then namespaced variant. CC-free via if-let."
  [m plain-k ns-k]
  (if-let [v (plain-k m)] v (ns-k m)))

(defn- ns-key-or
  "Retrieve value trying plain key, namespaced, then default. CC-free via if-let."
  [m plain-k ns-k default]
  (if-let [v (plain-k m)] v (if-let [v2 (ns-k m)] v2 default)))

(defn- normalize-phase-task
  "Normalize a single task within a phase context.

   Resolves namespaced keys, injects cross-phase dependencies for first task,
   and normalizes desc/estimate fields."
  [task cross-deps is-first-task?]
  (let [task-id   (keyword->string (ns-key task :id :task/id))
        task-deps (mapv keyword->string (ns-key-or task :depends-on :task/depends-on []))
        all-deps  (if-let [_ is-first-task?] (into cross-deps task-deps) task-deps)]
    (cond-> (normalize-edn-step task)
      true (assoc :id task-id)
      true (assoc :depends-on all-deps)

      (ns-key task :desc :task/desc)
      (assoc :description (ns-key task :desc :task/desc))

      (ns-key task :estimate :task/estimate)
      (assoc :estimate (keyword->string (ns-key task :estimate :task/estimate))))))

(defn- phase-tasks->steps
  "Convert a phase's :tasks to plan :steps with cross-phase dependency injection.

   First task of phase inherits cross-phase deps (lightweight ordering)."
  [phase prior-phase-last-tasks]
  (let [tasks      (ns-key phase :tasks :phase/tasks)
        phase-deps (ns-key-or phase :depends-on :phase/depends-on [])
        cross-deps (vec (keep #(get prior-phase-last-tasks %) phase-deps))]
    (vec (map-indexed
          (fn [idx task]
            (normalize-phase-task task cross-deps (zero? idx)))
          tasks))))

(defn- phases->plan
  "Flatten multiple phase blocks into a single plan with :steps.

   Phases sorted by :phase number. Cross-phase deps create ordering edges."
  [phase-blocks & {:keys [title]}]
  (let [sorted-phases (sort-by #(ns-key-or % :phase :phase/id 0) phase-blocks)
        {:keys [steps]}
        (reduce (fn [{:keys [steps last-tasks]} phase]
                  (let [phase-num   (ns-key phase :phase :phase/id)
                        phase-steps (phase-tasks->steps phase last-tasks)
                        last-id     (:id (last phase-steps))]
                    {:steps      (into steps phase-steps)
                     :last-tasks (assoc last-tasks phase-num last-id)}))
                {:steps [] :last-tasks {}}
                sorted-phases)]
    {:steps steps
     :title (if-let [t title] t
                    (if-let [n (:name (first sorted-phases))] n
                            "Untitled Plan"))}))

;; =============================================================================
;; Plan Finalization
;; =============================================================================

(defn- finalize-edn-plan
  "Normalize and validate parsed EDN plan data.

   Applies defaults, normalization, and schema validation.
   Returns: Result — {:ok plan} or {:error :edn/validation-failed ...}"
  [plan-data]
  (let [normalized-edn (normalize-edn-plan plan-data)
        plan-with-defaults
        (cond-> normalized-edn
          (not (:id normalized-edn))
          (assoc :id (str "plan-" (System/currentTimeMillis)))

          (not (:title normalized-edn))
          (assoc :title "Untitled Plan"))
        normalized (schema/normalize-plan
                    (assoc plan-with-defaults :source-format :edn))]
    (if-let [_ (schema/valid-plan? normalized)]
      (result/ok normalized)
      (result/err :edn/validation-failed
                  {:message "Plan failed schema validation"
                   :details (schema/explain-plan normalized)}))))

;; =============================================================================
;; Parse Strategy Functions (Combinator Pattern)
;; =============================================================================

(defn- try-direct-parse
  "Strategy 1: Parse content directly as EDN plan."
  [content _opts]
  (when-let [data (:ok (try-parse-edn content))]
    (when-let [_ (is-plan-edn? data)]
      (finalize-edn-plan data))))

(defn- try-embedded-extraction
  "Strategy 2: Extract balanced {} containing :steps from mixed content."
  [content _opts]
  (when-let [extracted-edn (extract-edn-from-content content)]
    (when-let [data (:ok (try-parse-edn extracted-edn))]
      (when-let [_ (is-plan-edn? data)]
        (finalize-edn-plan data)))))

(defn- try-single-edn-block
  "Strategy 3: Find single ```edn block with :steps."
  [content _opts]
  (let [blocks (extract-edn-blocks content)]
    (when-let [plan-data (first (keep (fn [block]
                                        (when-let [data (:ok (try-parse-edn block))]
                                          (when-let [_ (is-plan-edn? data)]
                                            data)))
                                      blocks))]
      (finalize-edn-plan plan-data))))

(defn- try-phase-block-parse
  "Strategy 4: Multiple phase blocks ({:phase N :tasks [...]}).

   Detects pattern where each block is {:phase N :tasks [...]} and
   flattens them into a single plan with :steps."
  [content {:keys [title]}]
  (let [blocks (extract-edn-blocks content)
        parsed-blocks (keep (fn [block] (:ok (try-parse-edn block)))
                            blocks)
        phase-blocks (filter is-phase-edn? parsed-blocks)]
    (when-let [_ (when-not (< (count phase-blocks) 2) :enough)]
      (finalize-edn-plan (phases->plan phase-blocks :title title)))))

;; =============================================================================
;; EDN Plan Parsing (Public API)
;; =============================================================================

(def ^:private parse-strategies
  "Ordered vector of parse strategy functions.
   Each takes [content opts] and returns Result or nil.
   First non-nil result wins (combinator pattern via `some`)."
  [try-direct-parse
   try-embedded-extraction
   try-single-edn-block
   try-phase-block-parse])

(defn- result->success
  "Convert Result to {:success true/false} for backward-compatible public API.
   Preserves contract consumed by parser.clj, fsm.clj, and gate.clj."
  [r]
  (if-let [plan (:ok r)]
    {:success true :plan plan}
    {:success false
     :error (or (:message r) (str (:error r)))
     :details (:details r)}))

(defn parse-edn-plan
  "Parse plan from EDN content or EDN blocks.

   Tries four strategies via combinator pattern (first success wins):
   1. Parse content directly as EDN (for raw EDN plans)
   2. Extract balanced {} containing :steps from mixed content
   3. Find single ```edn block with :steps
   4. Collect multiple ```edn phase blocks and flatten to unified :steps

   Supports both namespaced (:plan/steps, :step/id) and plain keys.

   Args:
   - content: String containing EDN (raw or in code blocks)
   - opts: Optional map with :title for plan title extraction

   Returns:
   - {:success true :plan ...} with normalized plan
   - {:success false :error ...} if no valid plan found"
  ([content] (parse-edn-plan content {}))
  ([content opts]
   (if-let [r (some #(% content opts) parse-strategies)]
     (result->success r)
     {:success false
      :error "No EDN plan found (tried direct parse, embedded extraction, ```edn blocks, and phase blocks)"})))