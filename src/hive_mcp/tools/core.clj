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
            [hive-mcp.emacs-ext.client :as ec]))
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

(def ^:private protocol-poison-pattern
  "Regex for the Clojure protocol-class-poisoning failure mode: a
   protocol var was redefined (typically via :reload-all) so existing
   instances point to the old protocol's interface and dispatch can't
   find an impl for them.

   See convention 20260429195405-2425b388. The recovery is a server
   restart — `extend` cannot patch around it because the JVM-level
   `implements` slot is locked at defrecord time."
  #"No implementation of method: (\S+) of protocol: #'(\S+) found for class: (\S+)")

(def ^:private known-poisonable-protocols
  "Protocols we've actually seen poisoned in production hive-mcp sessions
   (lifecycle classes whose deftype/defrecord instances are long-lived).
   Used to lift the recovery hint from \"likely\" to \"almost certainly\"."
  #{"iapetos.collector/Collector"
    "milvus-clj.client/IMilvusCore"
    "hive-weave.pool/IBindingConveyor"})

(defn detect-protocol-poisoning
  "Inspect a Throwable or message string for the protocol-class-poisoning
   error pattern. Returns

     {:poisoned? true
      :protocol \"<ns>/<Proto>\"
      :method \":<m>\"
      :class \"<TypeImpl>\"
      :known-poisonable? true|false}

   when matched, else nil. Pure — no logging or side effects."
  [x]
  (let [msg (cond
              (string? x)        x
              (instance? Throwable x) (or (.getMessage ^Throwable x) "")
              :else              (str x))]
    (when-let [[_ method protocol kls] (re-find protocol-poison-pattern msg)]
      {:poisoned?         true
       :method            method
       :protocol          protocol
       :class             kls
       :known-poisonable? (contains? known-poisonable-protocols protocol)})))

(defn recovery-hint
  "Produce an actionable error message for a protocol-poisoned exception.

   Replaces the cascading 'No implementation of method' line with the
   one piece of advice that actually unblocks the user: restart the
   server. Carto edits on disk are intact — only runtime invocation
   through MCP tools is blocked.

   Returns nil when the input doesn't match the poisoning pattern, so
   callers can use it as a soft enrichment:
     (or (recovery-hint e) (.getMessage e))"
  [x]
  (when-let [{:keys [protocol method class known-poisonable?]} (detect-protocol-poisoning x)]
    (str "Protocol dispatch poisoned (server restart required).\n"
         "  protocol: " protocol "\n"
         "  method:   " method "\n"
         "  class:    " class "\n"
         (if known-poisonable?
           "  This is a known-poisonable lifecycle protocol — :reload-all on a\n"
           "  Likely root cause: :reload-all was run on a ns whose dep tree\n")
         "  source ns rebinds defprotocol/deftype vars so existing instances\n"
         "  point to the old protocol's interface. JVM `implements` slot is\n"
         "  locked at defrecord time; `extend` cannot repair this in-process.\n"
         "  Recovery: restart the JVM (no in-process recovery exists).\n"
         "  Convention: memory id 20260429195405-2425b388.")))

(defn mcp-error
  "Create an error MCP response.

   When `message` matches the protocol-class-poisoning pattern (see
   convention 20260429195405-2425b388), the response is enriched with
   a recovery hint so the operator sees \"server restart required\"
   instead of a cascade of low-level dispatch failures (audit kanban
   20260429203448)."
  [message]
  (let [text (or (recovery-hint message) message)]
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