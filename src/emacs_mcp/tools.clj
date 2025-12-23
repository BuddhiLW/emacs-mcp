(ns emacs-mcp.tools
  "MCP tool definitions for Emacs interaction."
  (:require [emacs-mcp.emacsclient :as ec]
            [emacs-mcp.telemetry :as telemetry]
            [emacs-mcp.validation :as v]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))

;; Tool handlers

(defn handle-eval-elisp
  "Execute arbitrary elisp code with telemetry."
  [params]
  (try
    (v/validate-code (:code params))
    (let [{:keys [code]} params]
      (telemetry/with-eval-telemetry :elisp code nil
        (let [{:keys [success result error]} (ec/eval-elisp code)]
          (if success
            {:type "text" :text result}
            {:type "text" :text (str "Error: " error) :isError true}))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

(defn handle-list-buffers
  "List all open buffers."
  [_]
  (log/info "list-buffers")
  {:type "text" :text (ec/buffer-list)})

(defn handle-get-buffer-content
  "Get content of a specific buffer."
  [params]
  (try
    (v/validate-buffer-request params)
    (let [{:keys [buffer_name]} params]
      (log/info "get-buffer-content:" buffer_name)
      (try
        {:type "text" :text (ec/buffer-content buffer_name)}
        (catch Exception e
          {:type "text" :text (str "Error: " (.getMessage e)) :isError true})))
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

(defn handle-current-buffer
  "Get current buffer name and file."
  [_]
  (log/info "current-buffer")
  {:type "text"
   :text (str "Buffer: " (ec/current-buffer) "\n"
              "File: " (or (ec/current-file) "(not visiting file)"))})

(defn handle-switch-to-buffer
  "Switch to a buffer."
  [{:keys [buffer_name]}]
  (log/info "switch-to-buffer:" buffer_name)
  (ec/switch-to-buffer buffer_name)
  {:type "text" :text (str "Switched to buffer: " buffer_name)})

(defn handle-find-file
  "Open a file in Emacs."
  [{:keys [file_path]}]
  (log/info "find-file:" file_path)
  (ec/find-file file_path)
  {:type "text" :text (str "Opened file: " file_path)})

(defn handle-save-buffer
  "Save the current buffer."
  [_]
  (log/info "save-buffer")
  (ec/save-buffer)
  {:type "text" :text "Buffer saved"})

(defn handle-goto-line
  "Go to a specific line."
  [params]
  (try
    (v/validate-goto-line-request params)
    (let [{:keys [line]} params]
      (log/info "goto-line:" line)
      (ec/goto-line line)
      {:type "text" :text (str "Moved to line " line)})
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

(defn handle-insert-text
  "Insert text at cursor position."
  [{:keys [text]}]
  (log/info "insert-text:" (subs text 0 (min 50 (count text))) "...")
  (ec/insert-text text)
  {:type "text" :text "Text inserted"})

(defn handle-project-root
  "Get the current project root directory."
  [_]
  (log/info "project-root")
  {:type "text" :text (or (ec/project-root) "No project detected")})

(defn handle-recent-files
  "Get list of recently opened files."
  [_]
  (log/info "recent-files")
  {:type "text" :text (ec/recent-files)})

(defn handle-emacs-status
  "Check if Emacs is running and get basic info."
  [_]
  (log/info "emacs-status")
  (if (ec/emacs-running?)
    {:type "text"
     :text (str "Emacs is running\n"
                "Current buffer: " (ec/current-buffer) "\n"
                "Current file: " (or (ec/current-file) "none"))}
    {:type "text" :text "Emacs server is not running" :isError true}))

;; ============================================================================
;; emacs-mcp.el Integration Tools
;; These tools require emacs-mcp.el to be loaded in Emacs
;; ============================================================================

(defn emacs-mcp-el-available?
  "Check if emacs-mcp.el is loaded in Emacs."
  []
  (let [{:keys [success result]} (ec/eval-elisp "(featurep 'emacs-mcp-api)")]
    (and success (= result "t"))))

(defn handle-mcp-get-context
  "Get full context from Emacs including buffer, project, git, and memory."
  [_]
  (log/info "mcp-get-context")
  (if (emacs-mcp-el-available?)
    (let [{:keys [success result error]} (ec/eval-elisp "(json-encode (emacs-mcp-api-get-context))")]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))
    {:type "text" :text "Error: emacs-mcp.el is not loaded. Run (require 'emacs-mcp) and (emacs-mcp-mode 1) in Emacs." :isError true}))

(defn handle-mcp-memory-add
  "Add an entry to project memory."
  [{:keys [type content tags]}]
  (log/info "mcp-memory-add:" type)
  (if (emacs-mcp-el-available?)
    (let [tags-str (if (seq tags) (str "'" (pr-str tags)) "nil")
          elisp (format "(json-encode (emacs-mcp-api-memory-add %s %s %s))"
                        (pr-str type)
                        (pr-str content)
                        tags-str)
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))
    {:type "text" :text "Error: emacs-mcp.el is not loaded." :isError true}))

(defn handle-mcp-memory-query
  "Query project memory by type."
  [{:keys [type tags limit]}]
  (log/info "mcp-memory-query:" type)
  (if (emacs-mcp-el-available?)
    (let [tags-str (if (seq tags) (str "'" (pr-str tags)) "nil")
          limit-val (or limit 20)
          elisp (format "(json-encode (emacs-mcp-api-memory-query %s %s %d))"
                        (pr-str type)
                        tags-str
                        limit-val)
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))
    {:type "text" :text "Error: emacs-mcp.el is not loaded." :isError true}))

(defn handle-mcp-run-workflow
  "Run a user-defined workflow."
  [{:keys [name args]}]
  (log/info "mcp-run-workflow:" name)
  (if (emacs-mcp-el-available?)
    (let [elisp (if args
                  (format "(json-encode (emacs-mcp-api-run-workflow %s '%s))"
                          (pr-str name)
                          (pr-str args))
                  (format "(json-encode (emacs-mcp-api-run-workflow %s))"
                          (pr-str name)))
          {:keys [success result error]} (ec/eval-elisp elisp)]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))
    {:type "text" :text "Error: emacs-mcp.el is not loaded." :isError true}))

(defn handle-mcp-notify
  "Show notification to user in Emacs."
  [{:keys [message type]}]
  (log/info "mcp-notify:" message)
  (let [type-str (or type "info")
        elisp (format "(emacs-mcp-api-notify %s %s)"
                      (pr-str message)
                      (pr-str type-str))
        {:keys [success error]} (ec/eval-elisp elisp)]
    (if success
      {:type "text" :text "Notification sent"}
      {:type "text" :text (str "Error: " error) :isError true})))

(defn handle-mcp-list-workflows
  "List available workflows."
  [_]
  (log/info "mcp-list-workflows")
  (if (emacs-mcp-el-available?)
    (let [{:keys [success result error]} (ec/eval-elisp "(json-encode (emacs-mcp-api-list-workflows))")]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))
    {:type "text" :text "Error: emacs-mcp.el is not loaded." :isError true}))

(defn handle-mcp-capabilities
  "Check emacs-mcp.el availability and capabilities."
  [_]
  (log/info "mcp-capabilities")
  (if (emacs-mcp-el-available?)
    (let [{:keys [success result error]} (ec/eval-elisp "(json-encode (emacs-mcp-api-capabilities))")]
      (if success
        {:type "text" :text result}
        {:type "text" :text (str "Error: " error) :isError true}))
    {:type "text"
     :text (json/write-str {:available false
                            :message "emacs-mcp.el is not loaded. Run (require 'emacs-mcp) and (emacs-mcp-mode 1) in Emacs."})}))

(defn handle-mcp-watch-buffer
  "Get recent content from a buffer for monitoring (e.g., *Messages*)."
  [{:keys [buffer_name lines]}]
  (log/info "mcp-watch-buffer:" buffer_name)
  (let [num-lines (or lines 50)
        elisp (format "(with-current-buffer %s
                         (save-excursion
                           (goto-char (point-max))
                           (forward-line (- %d))
                           (buffer-substring-no-properties (point) (point-max))))"
                      (pr-str buffer_name)
                      num-lines)
        {:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      {:type "text" :text result}
      {:type "text" :text (str "Error: " error) :isError true})))

(defn handle-mcp-list-special-buffers
  "List special buffers useful for monitoring (*Messages*, *Warnings*, etc.)."
  [_]
  (log/info "mcp-list-special-buffers")
  (let [elisp "(mapcar #'buffer-name
                 (seq-filter
                   (lambda (buf)
                     (string-match-p \"^\\\\*\" (buffer-name buf)))
                   (buffer-list)))"
        {:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      {:type "text" :text result}
      {:type "text" :text (str "Error: " error) :isError true})))

(defn handle-mcp-buffer-info
  "Get detailed info about a buffer including size, modified time, mode."
  [{:keys [buffer_name]}]
  (log/info "mcp-buffer-info:" buffer_name)
  (let [elisp (format "(with-current-buffer %s
                         (json-encode
                           (list :name (buffer-name)
                                 :size (buffer-size)
                                 :lines (count-lines (point-min) (point-max))
                                 :mode (symbol-name major-mode)
                                 :modified (buffer-modified-p)
                                 :file (buffer-file-name)
                                 :point (point)
                                 :point-max (point-max))))"
                      (pr-str buffer_name))
        {:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      {:type "text" :text result}
      {:type "text" :text (str "Error: " error) :isError true})))

;; ============================================================================
;; CIDER Integration Tools
;; ============================================================================

(defn handle-cider-status
  "Get CIDER connection status."
  [_]
  (log/info "cider-status")
  (let [elisp "(require 'emacs-mcp-cider nil t)
               (if (fboundp 'emacs-mcp-cider-status)
                   (json-encode (emacs-mcp-cider-status))
                 (json-encode (list :connected nil :error \"emacs-mcp-cider not loaded\")))"
        {:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      {:type "text" :text result}
      {:type "text" :text (str "Error: " error) :isError true})))

(defn handle-cider-eval-silent
  "Evaluate Clojure code via CIDER silently with telemetry."
  [params]
  (try
    (v/validate-cider-eval-request params)
    (let [{:keys [code]} params]
      (telemetry/with-eval-telemetry :cider-silent code nil
        (let [elisp (format "(require 'emacs-mcp-cider nil t)
                             (if (fboundp 'emacs-mcp-cider-eval-silent)
                                 (emacs-mcp-cider-eval-silent %s)
                               (error \"emacs-mcp-cider not loaded\"))"
                            (pr-str code))
              {:keys [success result error]} (ec/eval-elisp elisp)]
          (if success
            {:type "text" :text result}
            {:type "text" :text (str "Error: " error) :isError true}))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

(defn handle-cider-eval-explicit
  "Evaluate Clojure code via CIDER interactively (shows in REPL) with telemetry."
  [params]
  (try
    (v/validate-cider-eval-request params)
    (let [{:keys [code]} params]
      (telemetry/with-eval-telemetry :cider-explicit code nil
        (let [elisp (format "(require 'emacs-mcp-cider nil t)
                             (if (fboundp 'emacs-mcp-cider-eval-explicit)
                                 (emacs-mcp-cider-eval-explicit %s)
                               (error \"emacs-mcp-cider not loaded\"))"
                            (pr-str code))
              {:keys [success result error]} (ec/eval-elisp elisp)]
          (if success
            {:type "text" :text result}
            {:type "text" :text (str "Error: " error) :isError true}))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

;;; ============================================================================
;;; Kanban Tools (org-kanban integration)
;;; ============================================================================

(defn kanban-addon-available?
  "Check if emacs-mcp-org-kanban addon is loaded."
  []
  (let [{:keys [success result]} (ec/eval-elisp "(featurep 'emacs-mcp-org-kanban)")]
    (and success (= result "t"))))

(defn handle-mcp-kanban-status
  "Get kanban status including tasks by status, progress, and backend info."
  [_]
  (if (kanban-addon-available?)
    (let [result (ec/eval-elisp "(json-encode (emacs-mcp-kanban-api-status))")]
      {:content [{:type "text"
                  :text (str result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded. Run (emacs-mcp-addon-load 'org-kanban)"}]}))

(defn handle-mcp-kanban-list-tasks
  "List kanban tasks, optionally filtered by status."
  [{:keys [status]}]
  (if (kanban-addon-available?)
    (let [elisp (if status
                  (format "(json-encode (emacs-mcp-kanban-list-tasks nil \"%s\"))" status)
                  "(json-encode (emacs-mcp-kanban-list-tasks))")
          result (ec/eval-elisp elisp)]
      {:content [{:type "text"
                  :text (str result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

(defn handle-mcp-kanban-create-task
  "Create a new kanban task."
  [{:keys [title description]}]
  (if (kanban-addon-available?)
    (let [elisp (if description
                  (format "(json-encode (emacs-mcp-kanban-create-task \"%s\" \"%s\"))"
                          (v/escape-elisp-string title)
                          (v/escape-elisp-string description))
                  (format "(json-encode (emacs-mcp-kanban-create-task \"%s\"))"
                          (v/escape-elisp-string title)))
          result (ec/eval-elisp elisp)]
      {:content [{:type "text"
                  :text (str "Created task: " result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

(defn handle-mcp-kanban-update-task
  "Update a kanban task's status or title."
  [{:keys [task_id status title]}]
  (if (kanban-addon-available?)
    (let [props (cond-> ""
                  status (str (format ":status \"%s\" " status))
                  title (str (format ":title \"%s\" " (v/escape-elisp-string title))))
          elisp (format "(emacs-mcp-kanban-update-task \"%s\" %s)" task_id props)
          result (ec/eval-elisp elisp)]
      {:content [{:type "text"
                  :text (str "Updated task: " result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

(defn handle-mcp-kanban-move-task
  "Move a task to a new status column."
  [{:keys [task_id new_status]}]
  (if (kanban-addon-available?)
    (let [elisp (format "(emacs-mcp-kanban-move-task \"%s\" \"%s\")" task_id new_status)
          result (ec/eval-elisp elisp)]
      {:content [{:type "text"
                  :text (str "Moved task to " new_status)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

(defn handle-mcp-kanban-roadmap
  "Get roadmap view with milestones and progress."
  [_]
  (if (kanban-addon-available?)
    (let [result (ec/eval-elisp "(json-encode (emacs-mcp-kanban-api-roadmap))")]
      {:content [{:type "text"
                  :text (str result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

(defn handle-mcp-kanban-my-tasks
  "Get tasks assigned to or modified by the current agent."
  [_]
  (if (kanban-addon-available?)
    (let [result (ec/eval-elisp "(json-encode (emacs-mcp-kanban-api-my-tasks))")]
      {:content [{:type "text"
                  :text (str result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

(defn handle-mcp-kanban-sync
  "Sync tasks between vibe-kanban and standalone backends."
  [_]
  (if (kanban-addon-available?)
    (let [result (ec/eval-elisp "(emacs-mcp-kanban-sync-all)")]
      {:content [{:type "text"
                  :text (str "Sync complete: " result)}]})
    {:content [{:type "text"
                :text "emacs-mcp-org-kanban addon not loaded."}]}))

;; Tool definitions

(def tools
  [{:name "eval_elisp"
    :description "Execute arbitrary Emacs Lisp code in the running Emacs instance. Returns the result of the evaluation."
    :inputSchema {:type "object"
                  :properties {"code" {:type "string"
                                       :description "The Emacs Lisp code to evaluate"}}
                  :required ["code"]}
    :handler handle-eval-elisp}

   {:name "emacs_status"
    :description "Check if Emacs server is running and get basic status information."
    :inputSchema {:type "object" :properties {}}
    :handler handle-emacs-status}

   {:name "list_buffers"
    :description "List all open buffers in Emacs."
    :inputSchema {:type "object" :properties {}}
    :handler handle-list-buffers}

   {:name "get_buffer_content"
    :description "Get the full content of a specific Emacs buffer."
    :inputSchema {:type "object"
                  :properties {"buffer_name" {:type "string"
                                              :description "Name of the buffer to read"}}
                  :required ["buffer_name"]}
    :handler handle-get-buffer-content}

   {:name "current_buffer"
    :description "Get the name of the current buffer and its associated file path."
    :inputSchema {:type "object" :properties {}}
    :handler handle-current-buffer}

   {:name "switch_to_buffer"
    :description "Switch to a specific buffer in Emacs."
    :inputSchema {:type "object"
                  :properties {"buffer_name" {:type "string"
                                              :description "Name of the buffer to switch to"}}
                  :required ["buffer_name"]}
    :handler handle-switch-to-buffer}

   {:name "find_file"
    :description "Open a file in Emacs. Creates a new buffer visiting that file."
    :inputSchema {:type "object"
                  :properties {"file_path" {:type "string"
                                            :description "Absolute path to the file to open"}}
                  :required ["file_path"]}
    :handler handle-find-file}

   {:name "save_buffer"
    :description "Save the current buffer to its associated file."
    :inputSchema {:type "object" :properties {}}
    :handler handle-save-buffer}

   {:name "goto_line"
    :description "Move cursor to a specific line number in the current buffer."
    :inputSchema {:type "object"
                  :properties {"line" {:type "integer"
                                       :description "Line number to go to (1-indexed)"}}
                  :required ["line"]}
    :handler handle-goto-line}

   {:name "insert_text"
    :description "Insert text at the current cursor position in Emacs."
    :inputSchema {:type "object"
                  :properties {"text" {:type "string"
                                       :description "Text to insert"}}
                  :required ["text"]}
    :handler handle-insert-text}

   {:name "project_root"
    :description "Get the root directory of the current project (detected by project.el)."
    :inputSchema {:type "object" :properties {}}
    :handler handle-project-root}

   {:name "recent_files"
    :description "Get list of recently opened files from recentf."
    :inputSchema {:type "object" :properties {}}
    :handler handle-recent-files}

   ;; emacs-mcp.el Integration Tools
   {:name "mcp_capabilities"
    :description "Check if emacs-mcp.el is loaded and get available capabilities. Use this first to verify the enhanced features are available."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-capabilities}

   {:name "mcp_get_context"
    :description "Get full context from Emacs including current buffer, region, project info, git status, and project memory. Requires emacs-mcp.el."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-get-context}

   {:name "mcp_memory_add"
    :description "Add an entry to project memory. Types: note, snippet, convention, decision. Requires emacs-mcp.el."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum ["note" "snippet" "convention" "decision"]
                                       :description "Type of memory entry"}
                               "content" {:type "string"
                                          :description "Content of the memory entry"}
                               "tags" {:type "array"
                                       :items {:type "string"}
                                       :description "Optional tags for categorization"}}
                  :required ["type" "content"]}
    :handler handle-mcp-memory-add}

   {:name "mcp_memory_query"
    :description "Query project memory by type. Returns stored notes, snippets, conventions, or decisions. Requires emacs-mcp.el."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum ["note" "snippet" "convention" "decision" "conversation"]
                                       :description "Type of memory entries to query"}
                               "tags" {:type "array"
                                       :items {:type "string"}
                                       :description "Optional tags to filter by"}
                               "limit" {:type "integer"
                                        :description "Maximum number of results (default: 20)"}}
                  :required ["type"]}
    :handler handle-mcp-memory-query}

   {:name "mcp_list_workflows"
    :description "List available user-defined workflows. Requires emacs-mcp.el."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-list-workflows}

   {:name "mcp_run_workflow"
    :description "Run a user-defined workflow by name. Workflows can automate multi-step tasks. Requires emacs-mcp.el."
    :inputSchema {:type "object"
                  :properties {"name" {:type "string"
                                       :description "Name of the workflow to run"}
                               "args" {:type "object"
                                       :description "Optional arguments to pass to the workflow"}}
                  :required ["name"]}
    :handler handle-mcp-run-workflow}

   {:name "mcp_notify"
    :description "Show a notification message to the user in Emacs."
    :inputSchema {:type "object"
                  :properties {"message" {:type "string"
                                          :description "Message to display"}
                               "type" {:type "string"
                                       :enum ["info" "warning" "error"]
                                       :description "Type of notification (default: info)"}}
                  :required ["message"]}
    :handler handle-mcp-notify}

   ;; Buffer Monitoring Tools
   {:name "mcp_watch_buffer"
    :description "Get recent content from a buffer for monitoring. Useful for watching *Messages*, *Warnings*, *Compile-Log*, etc. Returns the last N lines."
    :inputSchema {:type "object"
                  :properties {"buffer_name" {:type "string"
                                              :description "Name of the buffer to watch (e.g., \"*Messages*\")"}
                               "lines" {:type "integer"
                                        :description "Number of lines to retrieve from end (default: 50)"}}
                  :required ["buffer_name"]}
    :handler handle-mcp-watch-buffer}

   {:name "mcp_list_special_buffers"
    :description "List all special buffers (those starting with *) useful for monitoring. Returns buffer names like *Messages*, *scratch*, *Warnings*, etc."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-list-special-buffers}

   {:name "mcp_buffer_info"
    :description "Get detailed info about a buffer including size, line count, major mode, and modification status."
    :inputSchema {:type "object"
                  :properties {"buffer_name" {:type "string"
                                              :description "Name of the buffer to inspect"}}
                  :required ["buffer_name"]}
    :handler handle-mcp-buffer-info}

   ;; CIDER Integration Tools (requires emacs-mcp-cider addon)
   {:name "cider_status"
    :description "Get CIDER connection status including connected state, REPL buffer name, current namespace, and REPL type."
    :inputSchema {:type "object" :properties {}}
    :handler handle-cider-status}

   {:name "cider_eval_silent"
    :description "Evaluate Clojure code via CIDER silently. Fast evaluation without REPL buffer output. Use for routine/automated evals."
    :inputSchema {:type "object"
                  :properties {"code" {:type "string"
                                       :description "Clojure code to evaluate"}}
                  :required ["code"]}
    :handler handle-cider-eval-silent}

   {:name "cider_eval_explicit"
    :description "Evaluate Clojure code via CIDER interactively. Shows output in REPL buffer for collaborative debugging. Use when stuck or want user to see output."
    :inputSchema {:type "object"
                  :properties {"code" {:type "string"
                                       :description "Clojure code to evaluate"}}
                  :required ["code"]}
    :handler handle-cider-eval-explicit}

   ;; Kanban Integration Tools (requires emacs-mcp-org-kanban addon)
   {:name "mcp_kanban_status"
    :description "Get kanban status including tasks by status, progress percentage, backend info, and roadmap. Use at session start."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-kanban-status}

   {:name "mcp_kanban_list_tasks"
    :description "List kanban tasks, optionally filtered by status (todo, inprogress, inreview, done)."
    :inputSchema {:type "object"
                  :properties {"status" {:type "string"
                                         :enum ["todo" "inprogress" "inreview" "done"]
                                         :description "Filter by status (optional)"}}
                  :required []}
    :handler handle-mcp-kanban-list-tasks}

   {:name "mcp_kanban_create_task"
    :description "Create a new kanban task with title and optional description."
    :inputSchema {:type "object"
                  :properties {"title" {:type "string"
                                        :description "Task title"}
                               "description" {:type "string"
                                              :description "Task description (optional)"}}
                  :required ["title"]}
    :handler handle-mcp-kanban-create-task}

   {:name "mcp_kanban_update_task"
    :description "Update a kanban task's status or title."
    :inputSchema {:type "object"
                  :properties {"task_id" {:type "string"
                                          :description "Task ID to update"}
                               "status" {:type "string"
                                         :enum ["todo" "inprogress" "inreview" "done"]
                                         :description "New status (optional)"}
                               "title" {:type "string"
                                        :description "New title (optional)"}}
                  :required ["task_id"]}
    :handler handle-mcp-kanban-update-task}

   {:name "mcp_kanban_move_task"
    :description "Move a task to a new status column. Shorthand for update with status change."
    :inputSchema {:type "object"
                  :properties {"task_id" {:type "string"
                                          :description "Task ID to move"}
                               "new_status" {:type "string"
                                             :enum ["todo" "inprogress" "inreview" "done"]
                                             :description "Target status column"}}
                  :required ["task_id" "new_status"]}
    :handler handle-mcp-kanban-move-task}

   {:name "mcp_kanban_roadmap"
    :description "Get roadmap view with milestones, phases, and progress indicators."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-kanban-roadmap}

   {:name "mcp_kanban_my_tasks"
    :description "Get tasks assigned to or modified by the current agent session."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-kanban-my-tasks}

   {:name "mcp_kanban_sync"
    :description "Sync tasks between vibe-kanban cloud and standalone org-file backends."
    :inputSchema {:type "object" :properties {}}
    :handler handle-mcp-kanban-sync}])

(defn get-tool-by-name
  "Find a tool definition by name."
  [name]
  (first (filter #(= (:name %) name) tools)))
