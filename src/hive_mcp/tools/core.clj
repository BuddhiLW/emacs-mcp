(ns hive-mcp.tools.core
  "Core utilities for MCP tool responses.

   All tool handler modules should require this namespace for
   consistent response formatting.

   Piggyback system delegates to hive-mcp.channel.piggyback for:
   - Instruction queue (hivemind → ling)
   - Message cursors (ling reads hivemind broadcasts)"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.tools.diagnostics :as diag]
            [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Instruction Queue (delegated to piggyback)
;; =============================================================================

(def push-instruction!
  "Push an instruction to an agent's queue. Delegates to piggyback module."
  piggyback/push-instruction!)

(def drain-instructions!
  "Drain all pending instructions for an agent. Delegates to piggyback module."
  piggyback/drain-instructions!)

(defn- attach-instructions
  "Attach pending instructions to a response if agent-id provided and instructions exist."
  [response agent-id]
  (if agent-id
    (let [instructions (drain-instructions! agent-id)]
      (if (seq instructions)
        (assoc response :pending_instructions instructions)
        response))
    response))

;; =============================================================================
;; MCP Response Constructors
;; =============================================================================

(defn mcp-success
  "Create a successful MCP response. Text can be string or will be pr-str'd.

   Options:
   - :agent-id - If provided, drains and attaches pending instructions for this agent"
  [text & {:keys [agent-id]}]
  (-> {:type "text" :text (if (string? text) text (pr-str text))}
      (attach-instructions agent-id)))

(defn mcp-error
  "Create an error MCP response.

   When `message` matches the protocol-class-poisoning pattern, the
   response is enriched with an operator recovery hint (see
   hive-mcp.tools.diagnostics)."
  [message]
  (let [text (or (diag/recovery-hint message) message)]
    {:type "text" :text text :isError true}))

(defn mcp-json
  "Create a successful MCP response with JSON-encoded data.

   Options:
   - :agent-id - If provided, drains and attaches pending instructions for this agent"
  [data & {:keys [agent-id]}]
  (-> {:type "text" :text (json/write-str data)}
      (attach-instructions agent-id)))

;; =============================================================================
;; Addon Availability Checks (DRY Pattern)
;; =============================================================================

(def ^:private addon-feature-map
  "Maps addon keywords to their Elisp feature names.

   Note: :kanban uses 'hive-mcp-org-kanban (with org- prefix)
         All others use 'hive-mcp-<addon-name>"
  {:kanban     "hive-mcp-org-kanban"
   :docs       "hive-mcp-docs"
   :swarm      "hive-mcp-swarm"
   :magit      "hive-mcp-magit"
   :projectile "hive-mcp-projectile"})

(defn addon-available?
  "Check if an addon is loaded in Emacs.

   Accepts keyword from addon-feature-map or string for custom features:
     (addon-available? :kanban)     ; checks 'hive-mcp-org-kanban
     (addon-available? :swarm)      ; checks 'hive-mcp-swarm
     (addon-available? \"my-feat\") ; checks 'my-feat"

  [addon-key]
  (let [feature-name (if (keyword? addon-key)
                       (get addon-feature-map addon-key
                            (str "hive-mcp-" (name addon-key)))
                       addon-key)]
    (try
      (let [{:keys [success result]} (ec/eval-elisp (format "(featurep '%s)" feature-name))]
        (and success (= "t" (str/trim (or result "")))))
      (catch Exception _ false))))

(defn addon-not-loaded-error
  "Create error response for unavailable addon.

   Accepts keyword or string, generates appropriate error message."
  [addon-key]
  (let [addon-name (if (keyword? addon-key) (name addon-key) addon-key)]
    (mcp-error (str addon-name " addon not available"))))

(defmacro with-addon
  "Execute body only if addon is available, else return error.

   DRYs up the repeated pattern:
     (if (kanban-addon-available?)
       (do-kanban-thing)
       (mcp-error \"kanban addon not available\"))

   Usage:
     (with-addon :kanban
       (let [result (ec/eval-elisp ...)]
         (mcp-success result)))"

  [addon-key & body]
  `(if (addon-available? ~addon-key)
     (do ~@body)
     (addon-not-loaded-error ~addon-key)))

;; =============================================================================
;; Elisp Execution Wrapper (DRY Pattern)
;; =============================================================================

(defmacro call-elisp-safe
  "Execute elisp and return MCP response. Handles success/error uniformly.

   Eliminates the repeated pattern:
     (let [{:keys [success result error]} (ec/eval-elisp elisp)]
       (if success
         (mcp-json result)
         (mcp-json {:error error})))

   Usage:
     ;; Simple case - returns mcp-json wrapped result
     (call-elisp-safe \"(my-elisp)\")

     ;; With custom success transform
     (call-elisp-safe \"(my-elisp)\"
       :on-success #(mcp-json {:transformed (:data %)}))

     ;; With custom error handler
     (call-elisp-safe \"(my-elisp)\"
       :on-error #(mcp-error (str \"Custom: \" %)))

   Options:
     :on-success - Function (fn [result] ...) called on success. Default: mcp-json
     :on-error   - Function (fn [error-msg] ...) called on error. Default: (mcp-json {:error msg})"
  [elisp-expr & {:keys [on-success on-error]}]
  `(let [{:keys [~'success ~'result ~'error]} (ec/eval-elisp ~elisp-expr)]
     (if ~'success
       ~(if on-success
          `(~on-success ~'result)
          `(mcp-json ~'result))
       ~(if on-error
          `(~on-error ~'error)
          `(mcp-json {:error ~'error})))))

;; =============================================================================
;; Validation Wrapper (DRY Pattern)
;; =============================================================================

(defmacro with-validation
  "Execute body only if validation passes, else return error response.

   DRYs up the repeated pattern:
     (if-let [validation-error (validate-params params)]
       (mcp-json validation-error)
       (do-actual-work))

   Usage:
     (with-validation [params validate-fn]
       (let [result (process params)]
         (mcp-success result)))

   The validator function should return:
     - nil if valid
     - error map (e.g., {:error \"message\"}) if invalid"

  [[params validator] & body]
  `(if-let [errors# (~validator ~params)]
     (mcp-json errors#)
     (do ~@body)))

;; =============================================================================
;; Parameter Coercion (Elm-style Helpful Errors)
;; =============================================================================

(defn coerce-int
  "Coerce value to integer with Elm-style helpful error on failure.

   Returns {:ok value} on success, {:error message} on failure.

   Examples:
     (coerce-int 42 :limit)         => {:ok 42}
     (coerce-int \"42\" :limit)       => {:ok 42}
     (coerce-int \"abc\" :limit)      => {:error \"I was expecting...\"}
     (coerce-int nil :limit 20)      => {:ok 20}  ; uses default"

  ([value param-name] (coerce-int value param-name nil))
  ([value param-name default]
   (cond
     ;; nil with default -> use default
     (nil? value)
     (if (some? default)
       {:ok default}
       {:error (format "I was expecting a number for `%s` but got nothing.

HINT: This parameter is required. Provide an integer value.

    %s: 10    ← example valid value"
                       (name param-name) (name param-name))})

     ;; Already an integer
     (integer? value)
     {:ok value}

     ;; String that looks like a number
     (string? value)
     (try
       {:ok (Long/parseLong value)}
       (catch NumberFormatException _
         {:error (format "I was expecting a number for `%s` but got the string \"%s\".

This looks like a string that isn't a valid number.

HINT: Pass an integer, not a string:

    WRONG:  %s: \"%s\"
    RIGHT:  %s: %s"
                         (name param-name) value
                         (name param-name) value
                         (name param-name) (or default "10"))}))

     ;; Some other type
     :else
     {:error (format "I was expecting a number for `%s` but got a %s: %s

HINT: Pass an integer value:

    %s: 10    ← example valid value"
                     (name param-name)
                     (.getSimpleName (class value))
                     (pr-str value)
                     (name param-name))})))""

(defn coerce-int!
  "Coerce value to integer, throwing on failure with Elm-style message.

   Use in handlers where you want to fail fast with a helpful error.

   Example:
     (let [limit (coerce-int! limit :limit 20)]
       ...)  ; limit is guaranteed to be an integer"
  ([value param-name] (coerce-int! value param-name nil))
  ([value param-name default]
   (let [{:keys [ok error]} (coerce-int value param-name default)]
     (if error
       (throw (ex-info error {:param param-name :value value :type :coercion-error}))
       ok))))

;; =============================================================================
;; Per-call Emacs timeout (one definition, N tool schemas)
;; =============================================================================

(def emacs-timeout-ms-property
  "The `timeout_ms` inputSchema property for every Emacs-backed tool.

   Spliced into a tool's :properties rather than restated, so the parameter a
   caller sees is described in one place across git, magit, cider and any tool
   that later routes through emacsclient."
  {"timeout_ms"
   {:type "integer"
    :description (str "Per-call timeout in milliseconds for the Emacs round-trip. "
                      "Omit for the 5000ms default; raise it for a command that "
                      "legitimately takes longer — a push to a slow remote, a large "
                      "diff, a fetch. Capped at 30000 by the client.")}})

(def git-files-property
  "The `files` inputSchema property for every git-staging tool.

   Spliced into a tool's :properties rather than restated, so the git and magit
   tools cannot disagree about whether a path LIST is accepted."
  {"files"
   {:type ["string" "array"]
    :items {:type "string"}
    :description (str "Paths to stage: a LIST of paths, a single path, or several "
                      "paths in one whitespace-separated string. 'all' stages every "
                      "modified file. Listed paths are staged and verified before a "
                      "commit — a path that does not exist, or a stage that leaves "
                      "the index empty for those paths, fails the operation.")}})

(defn emacs-timeout-ms
  "The caller's per-call Emacs timeout from `params`, or nil when none was passed.

   nil means 'use the callee's default' — it is NOT an error to omit it.
   A malformed value THROWS (via `coerce-int!`) rather than falling back to the
   default: a caller who asked for 60s and silently got 5s cannot tell that from
   a command that was simply slow.

   Nothing is clamped here. `hive-emacs.client` applies `*max-timeout-ms*` as the
   last line of defense, and a second ceiling in this namespace would be a second
   place the limit lives."
  [{:keys [timeout_ms]}]
  (when (some? timeout_ms)
    (coerce-int! timeout_ms :timeout_ms)))

;; =============================================================================
;; Vector/Array Coercion (ELM Principle)
;; =============================================================================

(defn coerce-vec
  "Coerce value to vector with Elm-style helpful error on failure.

   Handles:
   - nil -> default or error
   - vector/list/seq -> vec
   - JSON string '[\"a\",\"b\"]' -> parsed vec
   - other -> error

   Returns {:ok vec} on success, {:error message} on failure.

   ELM Principle: User-facing errors should be helpful, not internal stack traces."
  ([value param-name] (coerce-vec value param-name nil))
  ([value param-name default]
   (cond
     ;; nil with default -> use default
     (nil? value)
     (if (some? default)
       {:ok default}
       {:ok []})  ; Default to empty vec for optional arrays

     ;; Already a vector
     (vector? value)
     {:ok value}

     ;; List or seq -> convert to vec
     (sequential? value)
     {:ok (vec value)}

     ;; JSON string -> try to parse
     (string? value)
     (if (str/starts-with? (str/trim value) "[")
       (try
         (let [parsed (json/read-str value)]
           (if (sequential? parsed)
             {:ok (vec parsed)}
             {:error (format "I was expecting `%s` to be an array, but the JSON parsed to a %s.

HINT: Provide a JSON array like [\"item1\", \"item2\"]

    %s: [\"tag1\", \"tag2\"]    ← correct format"
                             (name param-name)
                             (.getSimpleName (class parsed))
                             (name param-name))}))
         (catch Exception e
           {:error (format "I was expecting `%s` to be a valid JSON array, but parsing failed: %s

HINT: Make sure the JSON is properly formatted.

    %s: [\"tag1\", \"tag2\"]    ← correct format
    %s: %s                      ← your input (invalid)"
                           (name param-name)
                           (.getMessage e)
                           (name param-name)
                           (name param-name)
                           (pr-str value))}))
       {:error (format "I was expecting `%s` to be an array, but got a string that doesn't look like JSON: %s

HINT: Provide a JSON array, not a plain string.

    %s: [\"tag1\", \"tag2\"]    ← correct (JSON array)
    %s: \"tag1, tag2\"          ← incorrect (plain string)"
                       (name param-name)
                       (pr-str (subs value 0 (min 50 (count value))))
                       (name param-name)
                       (name param-name))})

     ;; Other types -> error
     :else
     {:error (format "I was expecting `%s` to be an array, but got a %s: %s

HINT: Provide a JSON array like [\"item1\", \"item2\"]

    %s: [\"tag1\", \"tag2\"]    ← correct format"
                     (name param-name)
                     (.getSimpleName (class value))
                     (pr-str value)
                     (name param-name))})))""

(defn coerce-vec!
  "Coerce value to vector, throwing on failure with Elm-style message.

   Use in handlers where you want to fail fast with a helpful error.

   Example:
     (let [tags (coerce-vec! tags :tags [])]
       ...)  ; tags is guaranteed to be a vector

   ELM Principle: User-facing errors should be helpful, not internal stack traces."
  ([value param-name] (coerce-vec! value param-name nil))
  ([value param-name default]
   (let [{:keys [ok error]} (coerce-vec value param-name default)]
     (if error
       (throw (ex-info error {:param param-name :value value :type :coercion-error}))
       ok))))

(defmacro with-coerced-params
  "Coerce multiple parameters with Elm-style errors, returning early on failure.

   Usage:
     (with-coerced-params [{:keys [limit offset]} params
                           limit  [:limit 20]
                           offset [:offset 0]]
       (query-with limit offset))

   Returns mcp-error response on coercion failure."

  [[bindings params & coercions] & body]
  (let [coercion-pairs (partition 2 coercions)
        coercion-checks (for [[var-name [param-name default]] coercion-pairs]
                          `(let [result# (coerce-int ~var-name ~param-name ~default)]
                             (when (:error result#)
                               (throw (ex-info (:error result#)
                                               {:param ~param-name :type :coercion-error})))
                             (:ok result#)))]
    `(let [~bindings ~params]
       (try
         (let [~@(interleave (map first coercion-pairs) coercion-checks)]
           ~@body)
         (catch clojure.lang.ExceptionInfo e#
           (if (= :coercion-error (:type (ex-data e#)))
             (mcp-error (.getMessage e#))
             (throw e#)))))))

;; =============================================================================
;; Hivemind Message Piggyback (delegated to piggyback)
;; =============================================================================

(defn get-hivemind-piggyback
  "Get new hivemind messages since last tool call for specific agent+project.
   Delegates to piggyback module. Returns nil if no new messages.

   Arguments:
   - agent-id: Requesting agent's identifier
   - :project-id: Optional project-id for scoped retrieval

   CRITICAL: project-id scoping prevents cross-project shout leakage."
  [agent-id & {:keys [project-id]}]
  (piggyback/get-messages agent-id :project-id project-id))

(def fetch-hivemind-history
  "Get hivemind messages without marking as read. Delegates to piggyback module."
  piggyback/fetch-history)

(defn mcp-success+hm
  "MCP success response with hivemind piggyback for coordinator.
   Includes any new agent shouts since last tool call.

   Arguments:
   - text: Response text
   - :agent-id: Required to track per-agent read cursors
   - :project-id: Optional project-id for scoped message retrieval

   CRITICAL: project-id scoping ensures coordinators only see their
   project's shouts, preventing cross-project message consumption."
  [text & {:keys [agent-id project-id]}]
  (let [base (mcp-success text :agent-id agent-id)
        hm (when agent-id (get-hivemind-piggyback agent-id :project-id project-id))]
    (if (seq hm)
      (assoc base :_hm hm)
      base)))

(def McpErrorResponse
  "MCP error response map: text content flagged with :isError.
   :text is nil only when a nil exception message reaches mcp-error."
  [:map
   [:type [:= "text"]]
   [:text [:maybe :string]]
   [:isError [:= true]]])

(def McpJsonResponse
  "MCP success response map with JSON-encoded :text.
   :pending_instructions is attached only when the agent had queued
   instructions; payloads are opaque pass-through values."
  [:map
   [:type [:= "text"]]
   [:text :string]
   [:pending_instructions {:optional true} [:vector :any]]])

(m/=> mcp-error [:=> [:cat [:maybe :string]] McpErrorResponse])

(m/=> mcp-json [:=> [:cat :any [:? [:cat [:= :agent-id] [:maybe :string]]]] McpJsonResponse])
