(ns hive-mcp.workflows.sub-fsms.llm-call
  "LLM-call sub-FSM — reusable FSM for LLM API interactions.

   States: build-messages -> api-call -> parse-response -> ::fsm/end

   Extracts tool calls from LLM responses, detects completion language,
   and normalizes response shapes for the parent drone-loop FSM.

   ## Sub-FSM Composition

   Called from parent FSM handler via:
     (fsm/run compiled-llm-call resources {:data input-map})

   The parent handler merges the sub-FSM result into its own data.

   ## Data Flow

   Input (from parent):
     {:task          string    ;; task description
      :files         [string]  ;; target files
      :messages      [{:role :content}]  ;; conversation history (optional)
      :turn          int       ;; current turn number
      :system-prompt string    ;; system prompt (optional, built if absent)}

   Output (to parent):
     {:messages       [{:role :content}]  ;; updated messages with response
      :response-type  :text|:tool_calls|:error
      :tool-calls     [{:id :name :arguments}]  ;; extracted tool calls (or nil)
      :text-content   string   ;; text response content (or nil)
      :completion?    boolean  ;; true if completion language detected
      :error          string   ;; error message (or nil)
      :usage          {:input :output :total}  ;; token usage (or nil)}

   ## Resources (injected at run time)

     :backend         — LLMBackend instance (required)
     :tool-schemas    — vector of tool schema maps (or nil for no tools)
     :system-prompt-fn — (fn [task-spec] -> string) (optional, default provided)"

  (:require [hive.events.fsm :as fsm]
            [hive-mcp.agent.protocol :as proto]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Completion Language Detection (shared with drone/loop.clj)
;; =============================================================================

(def ^:private completion-patterns
  "Regex patterns indicating the LLM considers its task complete.
   Structural heuristic — keyword matching on response text."
  [#"(?i)task\s+(is\s+)?complet(e|ed)"
   #"(?i)i('ve|\s+have)\s+(successfully\s+)?(complet|finish|done)"
   #"(?i)all\s+(changes|modifications|updates)\s+(have\s+been\s+)?(made|applied|complet)"
   #"(?i)the\s+(fix|implementation|change|update)\s+(is|has been)\s+(ready|complet|done|applied)"
   #"(?i)successfully\s+(implemented|applied|fixed|updated|created|modified)"
   #"(?i)here('s| is)\s+the\s+(summary|result|final)"
   #"(?i)(nothing|no)\s+(more|else|further)\s+(to|needs?\s+to\s+be)\s+(do|change|fix)"
   #"(?i)all\s+done"])

(defn completion-language?
  "Check if text contains language indicating task completion.
   Returns true if any completion pattern matches."
  [text]
  (when (and text (not (str/blank? text)))
    (boolean (some #(re-find % text) completion-patterns))))

;; =============================================================================
;; Default System Prompt Builder
;; =============================================================================

(defn default-system-prompt
  "Build a default system prompt for drone LLM calls.
   Instructs the LLM to use propose_diff for file changes."
  [{:keys [files]}]
  (str "You are a coding drone. You MUST use the propose_diff tool for ALL file changes. "
       "NEVER output code as plain text or markdown code blocks. "
       "Every file modification MUST go through the propose_diff tool with file_path, old_content, and new_content parameters. "
       "If you output code as text instead of calling propose_diff, your changes will be LOST and the task will FAIL. "
       "Use read_file to examine files, then propose_diff to make changes. Be concise and focused."
       (when (seq files)
         (str "\n\nTarget files: " (str/join ", " files)))))

;; =============================================================================
;; FSM Handlers (Pure: resources, data -> data')
;; =============================================================================

(defn handle-build-messages
  "Build the LLM message array from task context.

   If :messages already exists in data (continuation turn), uses them directly.
   Otherwise, constructs initial messages from task + system prompt.

   Resources used: :system-prompt-fn (optional)"
  [resources data]
  (let [{:keys [task files messages system-prompt _turn]} data
        system-prompt-fn (or (:system-prompt-fn resources) default-system-prompt)]
    (if (seq messages)
      ;; Continuation: messages already built by parent
      (assoc data :messages-ready messages)
      ;; Initial: build from task spec
      (let [sys-prompt (or system-prompt
                           (system-prompt-fn {:task task :files files}))
            built-messages [{:role "system" :content sys-prompt}
                            {:role "user"   :content (or task "No task provided")}]]
        (assoc data
               :messages-ready built-messages
               :system-prompt sys-prompt)))))

(defn handle-api-call
  "Call the LLM backend with built messages and tool schemas.

   Resources used: :backend (LLMBackend, required), :tool-schemas (optional)

   On success: assocs :raw-response with the LLM response map.
   On nil backend: assocs :error for graceful degradation.
   On exception: assocs :error with exception message."
  [resources data]
  (let [backend (:backend resources)
        tool-schemas (:tool-schemas resources)
        messages (:messages-ready data)
        turn (or (:turn data) 0)]
    (if-not backend
      (do
        (log/warn "LLM-call sub-FSM: no backend provided")
        (assoc data
               :raw-response nil
               :error "No LLM backend provided"
               :response-type :error))
      ;; Call the backend
      (try
        (log/debug "LLM-call: invoking backend"
                   {:model (proto/model-name backend)
                    :turn turn
                    :message-count (count messages)
                    :has-tools? (boolean (seq tool-schemas))})
        (let [response (proto/chat backend messages tool-schemas)]
          (log/debug "LLM-call: response received"
                     {:type (:type response)
                      :turn turn
                      :usage (:usage response)})
          (assoc data :raw-response response))
        (catch Throwable e
          (log/error e "LLM-call: backend exception" {:turn turn})
          (assoc data
                 :raw-response nil
                 :error (str "LLM backend exception: " (ex-message e))
                 :response-type :error))))))

(defn handle-parse-response
  "Parse the raw LLM response into normalized output fields.

   Handles three response types from LLMBackend.chat:
     :text       — text-only response (may indicate completion)
     :tool_calls — LLM wants to call tools
     :error      — LLM returned error/empty response

   Also detects completion language in text responses."
  [_resources data]
  (if (:error data)
    ;; Error already set by api-call handler
    (assoc data
           :response-type :error
           :tool-calls nil
           :text-content nil
           :completion? false)
    ;; Parse the raw response
    (let [response (:raw-response data)
          usage (:usage response)]
      (case (:type response)
        :text
        (let [content (:content response)
              completion? (completion-language? content)]
          (assoc data
                 :response-type :text
                 :text-content content
                 :tool-calls nil
                 :completion? completion?
                 :usage usage))

        :tool_calls
        (let [calls (:calls response)]
          (assoc data
                 :response-type :tool_calls
                 :tool-calls calls
                 :text-content nil
                 :completion? false
                 :usage usage))

        :error
        (assoc data
               :response-type :error
               :error (or (:error response) "Unknown LLM error")
               :tool-calls nil
               :text-content nil
               :completion? false
               :usage usage)

        ;; Unknown response type
        (do
          (log/warn "LLM-call: unknown response type" {:type (:type response)})
          (assoc data
                 :response-type :error
                 :error (str "Unknown response type: " (:type response))
                 :tool-calls nil
                 :text-content nil
                 :completion? false
                 :usage usage))))))

(defn handle-end
  "Terminal state handler (note: ::fsm/end handler is NOT executed by the
   FSM engine — cleanup happens in run-llm-call wrapper instead)."
  [_resources data]
  data)

(defn handle-error
  "Error terminal state handler (note: ::fsm/error handler is NOT executed
   by the FSM engine — cleanup happens in run-llm-call wrapper instead)."
  [_resources data]
  data)

;; =============================================================================
;; FSM Spec (Clojure-native, not EDN)
;; =============================================================================

(def llm-call-spec
  "LLM-call sub-FSM specification.

   States: build-messages -> api-call -> parse-response -> ::fsm/end
   Error path: any state -> ::fsm/error on :error in data"
  {:fsm {::fsm/start
         {:handler    handle-build-messages
          :dispatches [[::api-call (constantly true)]]}

         ::api-call
         {:handler    handle-api-call
          :dispatches [[::parse-response (fn [data] (nil? (:error data)))]
                       [::fsm/error (fn [data] (some? (:error data)))]]}

         ::parse-response
         {:handler    handle-parse-response
          :dispatches [[::fsm/end (constantly true)]]}

         ::fsm/end
         {:handler handle-end}

         ::fsm/error
         {:handler handle-error}}

   :opts {:max-trace 10}})

;; Compile once, reuse across all invocations
(def compiled
  "Pre-compiled LLM-call sub-FSM. Use with:
     (fsm/run compiled resources {:data input-map})"
  (delay (fsm/compile llm-call-spec)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- clean-internal-fields
  "Remove FSM-internal fields from the result data.
   ::fsm/end handler does NOT execute (engine design), so cleanup
   happens here in the public API wrapper."
  [data]
  (dissoc data :raw-response :messages-ready))

(defn run-llm-call
  "Execute the LLM-call sub-FSM.

   Convenience wrapper over fsm/run for direct invocation.

   Args:
     data      - Input map (see ns docstring for shape)
     resources - Map with :backend (LLMBackend), :tool-schemas, etc.

   Returns: Final data map with :response-type, :tool-calls, :text-content, etc.
            (Extracts :data from the FSM state map returned by fsm/run.)"
  [data resources]
  (let [fsm-result (fsm/run @compiled resources {:data data})]
    (-> (:data fsm-result)
        clean-internal-fields)))

(defn run-llm-call-with-backend
  "Execute LLM-call with just a backend and task description.

   Simplified entry point for common case.

   Args:
     backend      - LLMBackend instance
     task         - Task description string
     tool-schemas - Vector of tool schema maps (or nil)
     opts         - Optional map with :files, :turn, :messages, :system-prompt

   Returns: Final data map."
  [backend task tool-schemas & [opts]]
  (let [resources {:backend backend
                   :tool-schemas tool-schemas}
        data (merge {:task task
                     :turn (or (:turn opts) 0)}
                    (select-keys opts [:files :messages :system-prompt]))]
    (run-llm-call data resources)))

(defn run-sub-fsm
  "Helper for embedding LLM-call in a parent FSM handler.

   Extracts :task, :files, :messages, :turn, :system-prompt from parent data,
   runs the LLM-call sub-FSM, and merges result keys back into parent data.

   Resources resolution (in priority order):
     1. :llm-call-resources key in parent resources (namespaced sub-resources)
     2. :backend + :tool-schemas + :system-prompt-fn from parent resources
     3. Bare minimum: requires at least :backend

   Usage in parent handler:
     (defn handle-llm-call [resources parent-data]
       (llm-call/run-sub-fsm resources parent-data))

   Or with explicit overrides:
     (llm-call/run-sub-fsm resources parent-data
       {:task \"override task\" :turn 5})"
  ([resources parent-data]
   (run-sub-fsm resources parent-data nil))
  ([resources parent-data overrides]
   (let [input (merge (select-keys parent-data [:task :files :messages :turn :system-prompt])
                      overrides)
         sub-resources (or (:llm-call-resources resources)
                           (select-keys resources [:backend :tool-schemas :system-prompt-fn]))
         result (run-llm-call input sub-resources)]
     (merge parent-data
            (select-keys result [:response-type :tool-calls :text-content
                                 :completion? :error :usage :messages
                                 :system-prompt])))))
