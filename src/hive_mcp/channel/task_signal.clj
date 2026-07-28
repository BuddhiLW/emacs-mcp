;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.channel.task-signal
  "Task cue extraction from a tool call, allowlisted per tool.

   `task-tokens` returns a set of at most `max-tokens` lowercase identifier
   tokens drawn only from the argument keys allowlisted for that tool name in
   `tool-arg-allowlist`. Keys in `denied-arg-keys` never contribute, whatever
   an allowlist says. A `:command` read under :argv0 mode contributes argv[0]
   only. A tool name absent from `tool-arg-allowlist` contributes nothing.

   Pure — no IO, deterministic."
  (:require [clojure.string :as str]))

(def max-tokens
  "Hard ceiling on the number of tokens `task-tokens` returns."
  64)

(def min-token-chars
  "Tokens shorter than this are dropped."
  2)

(def max-token-chars
  "Tokens longer than this are dropped."
  32)

(def max-value-chars
  "String argument values longer than this are not tokenised at all."
  300)

(def command-max-chars
  "A :command value longer than this is never tokenised."
  400)

(def max-coll-items
  "Only the first N items of a collection argument value are inspected."
  32)

(def denied-arg-keys
  "Argument keys that must never contribute tokens, overriding any allowlist."
  #{:content :code :body :text :prompt :source :new-body :new_body
    :helper-body :boundary-body :caller-body :proposed :template
    :env :environment :secret :secrets :password :passwd :key
    :token :access-token :access_token :refresh-token :refresh_token
    :api-key :api_key :apikey :credentials :authorization :headers
    :datalog :datalog_query :bindings :facts :config :goal :ops :operations
    :arguments :args :message :caption :verification :smell})

(def tool-arg-allowlist
  "Tool name -> ordered vector of [arg-key extraction-mode] pairs.

   Modes: :tokens (word-split the value), :argv0 (first shell word only).
   Iteration follows the vector, so output is order-deterministic."
  {"bash"         [[:command :argv0] [:description :tokens]]
   "code"         [[:command :tokens] [:ns :tokens] [:qn :tokens]
                   [:namespace :tokens] [:function :tokens] [:symbol :tokens]
                   [:file :tokens] [:file_path :tokens] [:path :tokens]
                   [:paths :tokens] [:scope :tokens] [:query :tokens]
                   [:name-pattern :tokens] [:form-type :tokens]
                   [:detector :tokens] [:recipe :tokens] [:verb :tokens]
                   [:formula :tokens] [:lang :tokens]]
   "memory"       [[:command :tokens] [:type :tokens] [:tags :tokens]
                   [:scope :tokens] [:query :tokens] [:duration :tokens]
                   [:relation :tokens] [:predicate :tokens] [:directory :tokens]]
   "fs"           [[:command :tokens] [:path :tokens] [:paths :tokens]
                   [:pattern :tokens]]
   "git"          [[:command :tokens] [:path :tokens] [:branch :tokens]]
   "project"      [[:command :tokens] [:directory :tokens] [:scope :tokens]
                   [:project_id :tokens]]
   "workflow"     [[:command :tokens] [:directory :tokens] [:scope :tokens]]
   "session"      [[:command :tokens] [:directory :tokens] [:scope :tokens]]
   "swarm"        [[:command :tokens] [:agent_id :tokens] [:task_type :tokens]
                   [:scope :tokens] [:project_id :tokens]]
   "agent"        [[:command :tokens] [:agent_id :tokens] [:task_type :tokens]]
   "hivemind"     [[:command :tokens] [:agent_id :tokens] [:scope :tokens]]
   "kanban"       [[:command :tokens] [:scope :tokens] [:tags :tokens]
                   [:status :tokens] [:project_id :tokens]]
   "kg"           [[:command :tokens] [:relation :tokens] [:node_id :tokens]
                   [:scope :tokens]]
   "multi"        [[:command :tokens] [:tool :tokens]]
   "web"          [[:command :tokens] [:query :tokens]]
   "emacs"        [[:command :tokens] [:file :tokens] [:name :tokens]]
   "auth"         [[:command :tokens]]
   "clojure_eval" []})

(def ^:private token-shape-re
  #"^[a-z][a-z0-9]*(?:[-_.][a-z0-9]+)*$")

(def ^:private credential-word-re
  #"secret|passwd|password|apikey|api-key|api_key|bearer|credential")

(defn- credential-shaped?
  "True for long identifiers mixing letters and digits — key/hash material."
  [^String s]
  (and (>= (count s) 12)
       (some? (re-find #"[a-z]" s))
       (some? (re-find #"[0-9]" s))))

(defn- safe-token
  "Normalise x to a token string, or nil when it fails the shape gate."
  [x]
  (let [s (str/lower-case (str x))]
    (when (and (>= (count s) min-token-chars)
               (<= (count s) max-token-chars)
               (some? (re-matches token-shape-re s))
               (nil? (re-find credential-word-re s))
               (not (credential-shaped? s)))
      s)))

(defn- split-tokens
  "Split a string into candidate tokens, expanding -/_/. compounds."
  [s]
  (let [words (remove str/blank? (str/split (str/lower-case (str s)) #"[^a-z0-9_.-]+"))]
    (into []
          (mapcat (fn [w] (cons w (remove str/blank? (str/split w #"[-_.]+")))))
          words)))

(defn- argv0
  "First shell word of a command string, assignment- and path-stripped."
  [s]
  (let [words (remove str/blank? (str/split (str/trim (str s)) #"\s+"))
        w (first (remove #(str/includes? % "=") words))]
    (when w
      (safe-token (str/replace w #"^.*/" "")))))

(defn- value-tokens
  "Candidate tokens for one argument value read under one extraction mode."
  [k mode v]
  (let [limit (if (= :command k) command-max-chars max-value-chars)]
    (cond
      (nil? v) []
      (= :argv0 mode) (if (string? v) (or (some-> (argv0 v) vector) []) [])
      (string? v) (if (> (count v) limit) [] (split-tokens v))
      (keyword? v) (split-tokens (name v))
      (symbol? v) (split-tokens (name v))
      (coll? v) (into []
                      (comp (take max-coll-items)
                            (filter #(or (string? %) (keyword? %) (symbol? %)))
                            (mapcat #(value-tokens k mode %)))
                      v)
      :else [])))

(defn- arg-value
  "Read k from args, tolerating string keys and -/_ spelling variants."
  [args k]
  (let [n (name k)]
    (or (get args k)
        (get args n)
        (get args (keyword (str/replace n "-" "_")))
        (get args (keyword (str/replace n "_" "-"))))))

(defn task-tokens
  "Task cue tokens for one tool call.

   Returns a set of at most `max-tokens` tokens taken only from the argument
   keys allowlisted for `tool-name`. Keys in `denied-arg-keys` are skipped.
   A `:command` under :argv0 mode yields argv[0] and nothing else. An unknown
   tool name yields the empty set."
  [tool-name args]
  (let [tname (some-> tool-name str str/trim str/lower-case)
        spec (get tool-arg-allowlist tname)
        m (when (map? args) args)]
    (if (and (seq spec) m)
      (into #{}
            (comp (keep safe-token) (distinct) (take max-tokens))
            (into []
                  (comp (remove (fn [[k _]] (contains? denied-arg-keys k)))
                        (mapcat (fn [[k mode]] (value-tokens k mode (arg-value m k)))))
                  spec))
      #{})))

(def ^:dynamic *enabled?*
  "Overrides the env check when bound to true or false. nil defers to the env."
  nil)

(def ^:private truthy-env
  #{"1" "true" "yes" "on"})

(defn enabled?
  "True when task-cue harvesting is on.

   Defaults to FALSE: with no override and no HIVE_TWO_LANE_DRAIN env var set to
   one of 1/true/yes/on, `cues` yields the empty set and the memory drain stays
   FIFO. Binding `*enabled?*` takes precedence over the env."
  []
  (if (some? *enabled?*)
    (boolean *enabled?*)
    (contains? truthy-env
               (some-> (System/getenv "HIVE_TWO_LANE_DRAIN")
                       str/trim str/lower-case))))

(defn cues
  "`task-tokens` for one tool call, or the empty set when `enabled?` is false.

   Call sites use this; `task-tokens` stays pure and unconditional."
  [tool-name args]
  (if (enabled?)
    (task-tokens tool-name args)
    #{}))
