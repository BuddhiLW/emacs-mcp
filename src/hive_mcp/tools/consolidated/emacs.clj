(ns hive-mcp.tools.consolidated.emacs
  "Consolidated Emacs CLI tool.

   Absorbs all buffer.clj and docs.clj functionality into a single
   consolidated tool. Docs are nested under a :docs subtree
   (e.g. emacs docs describe-function)."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.emacs-ext.elisp :as el]
            [hive-mcp.tools.buffer :as buffer]
            [hive-mcp.tools.docs :as docs]
            [taoensso.timbre :as log]))

(defn- elisp->result
  "Convert emacs client response {:keys [success result error]} to Result."
  [resp]
  (if (:success resp)
    (result/ok (:result resp))
    (result/err :emacs/eval-error {:message (str (:error resp))})))

;; ── logic* fns (pure Result-returning) ───────────────────────────────────────

(defn- eval* [{:keys [code timeout_ms]}]
  (elisp->result (ec/eval-elisp-with-timeout code (or timeout_ms ec/*default-timeout-ms*))))

(defn- buffers* [_]
  (let [elisp "(json-encode (mapcar (lambda (b) (list :name (buffer-name b) :file (buffer-file-name b))) (buffer-list)))"]
    (result/map-ok (elisp->result (ec/eval-elisp elisp))
                   (fn [r] {:buffers r}))))

(defn- notify* [{:keys [message level]}]
  (let [level-kw (or level "info")
        elisp (el/format-elisp "(message \"[%s] %s\")" level-kw message)]
    (result/map-ok (elisp->result (ec/eval-elisp elisp))
                   (constantly "Notification sent"))))

(defn- status* [_]
  (result/ok {:running (ec/emacs-running?)
              :current-buffer (ec/current-buffer)
              :current-file (ec/current-file)}))

(defn- switch-buffer* [{:keys [buffer]}]
  (let [elisp (el/format-elisp "(switch-to-buffer %s)" (pr-str buffer))]
    (result/map-ok (elisp->result (ec/eval-elisp elisp))
                   (constantly (str "Switched to " buffer)))))

(defn- find-file* [{:keys [file]}]
  (let [elisp (el/format-elisp "(find-file %s)" (pr-str file))]
    (result/map-ok (elisp->result (ec/eval-elisp elisp))
                   (constantly (str "Opened " file)))))

(defn- save* [{:keys [all]}]
  (let [elisp (if all "(save-some-buffers t)" "(save-buffer)")]
    (result/map-ok (elisp->result (ec/eval-elisp elisp))
                   (constantly (if all "All buffers saved" "Buffer saved")))))

(defn- current-buffer* [_]
  (let [elisp "(json-encode (list :name (buffer-name) :file (buffer-file-name) :modified (buffer-modified-p) :major-mode (symbol-name major-mode)))"]
    (result/map-ok (elisp->result (ec/eval-elisp elisp))
                   (fn [r] {:buffer r}))))

;; ── public handlers (MCP boundary) ──────────────────────────────────────────

(defn handle-eval
  "Evaluate Elisp code."
  [{:keys [code] :as params}]
  (log/info "emacs-eval" {:code-length (count code)})
  (rb/result->mcp-text (rb/try-result :emacs/eval-failed #(eval* params))))

(defn handle-buffers
  "List Emacs buffers."
  [params]
  (log/info "emacs-buffers")
  (rb/result->mcp (rb/try-result :emacs/buffers-failed #(buffers* params))))

(defn handle-notify
  "Send notification to Emacs."
  [{:keys [message level] :as params}]
  (log/info "emacs-notify" {:message message :level level})
  (rb/result->mcp-text (rb/try-result :emacs/notify-failed #(notify* params))))

(defn handle-status
  "Get Emacs connection status."
  [params]
  (log/info "emacs-status")
  (rb/result->mcp (rb/try-result :emacs/status-failed #(status* params))))

(defn handle-switch-buffer
  "Switch to a buffer."
  [{:keys [buffer] :as params}]
  (log/info "emacs-switch" {:buffer buffer})
  (rb/result->mcp-text (rb/try-result :emacs/switch-failed #(switch-buffer* params))))

(defn handle-find-file
  "Open a file in Emacs."
  [{:keys [file] :as params}]
  (log/info "emacs-find-file" {:file file})
  (rb/result->mcp-text (rb/try-result :emacs/find-file-failed #(find-file* params))))

(defn handle-save
  "Save current buffer or all buffers."
  [{:keys [all] :as params}]
  (log/info "emacs-save" {:all all})
  (rb/result->mcp-text (rb/try-result :emacs/save-failed #(save* params))))

(defn handle-current-buffer
  "Get current buffer info."
  [params]
  (log/info "emacs-current-buffer")
  (rb/result->mcp (rb/try-result :emacs/current-buffer-failed #(current-buffer* params))))

(def handlers
  {:eval            handle-eval
   :buffers         handle-buffers
   :notify          handle-notify
   :status          handle-status
   :switch          handle-switch-buffer
   :find            handle-find-file
   :save            handle-save
   :current         handle-current-buffer
   ;; Absorbed from buffer.clj
   :goto-line       buffer/handle-goto-line
   :insert          buffer/handle-insert-text
   :project-root    buffer/handle-project-root
   :recent          buffer/handle-recent-files
   :context         buffer/handle-mcp-get-context
   :capabilities    buffer/handle-mcp-capabilities
   :workflows       buffer/handle-mcp-list-workflows
   :special-buffers buffer/handle-mcp-list-special-buffers
   :buffer-info     buffer/handle-mcp-buffer-info
   ;; Absorbed from docs.clj (nested subtree)
   :docs {:describe-function  docs/handle-describe-function
          :describe-variable  docs/handle-describe-variable
          :apropos            docs/handle-apropos
          :package-functions  docs/handle-package-functions
          :find-keybindings   docs/handle-find-keybindings
          :package-commentary docs/handle-package-commentary
          :list-packages      docs/handle-list-packages}})

(def handle-emacs
  (make-cli-handler handlers))

(def tool-def
  {:name "emacs"
   :consolidated true
   :description (str "Emacs operations: eval (run elisp), buffers (list), notify (message), status (connection), "
                     "switch (change buffer), find (open file), save (save buffers), current (buffer info), "
                     "goto-line (move cursor), insert (text at point), project-root, recent (recent files), "
                     "context (full Emacs context), capabilities (hive-mcp.el status), workflows (list workflows), "
                     "special-buffers (list *-buffers), buffer-info (detailed buffer info), "
                     "docs <subcmd> (describe-function, describe-variable, apropos, package-functions, "
                     "find-keybindings, package-commentary, list-packages). "
                     "Use command='help' to list all.")
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["eval" "buffers" "notify" "status" "switch" "find" "save" "current"
                                                "goto-line" "insert" "project-root" "recent"
                                                "context" "capabilities" "workflows" "special-buffers" "buffer-info"
                                                "docs describe-function" "docs describe-variable" "docs apropos"
                                                "docs package-functions" "docs find-keybindings"
                                                "docs package-commentary" "docs list-packages"
                                                "help"]
                                         :description "Emacs operation to perform"}
                              "code" {:type "string"
                                      :description "Elisp code to evaluate"}
                              "timeout_ms" {:type "integer"
                                            :description "Timeout in milliseconds for eval (default: 5000, max: 30000)"}
                              "message" {:type "string"
                                         :description "Notification message"}
                              "level" {:type "string"
                                       :enum ["info" "warn" "error"]
                                       :description "Notification level"}
                              "buffer" {:type "string"
                                        :description "Buffer name (for switch or buffer-info)"}
                              "buffer_name" {:type "string"
                                             :description "Buffer name (for buffer-info)"}
                              "file" {:type "string"
                                      :description "File path to open"}
                              "all" {:type "boolean"
                                     :description "Save all buffers if true"}
                              "line" {:type "integer"
                                      :description "Line number for goto-line (1-indexed)"}
                              "text" {:type "string"
                                      :description "Text to insert at cursor"}
                              "type" {:type "string"
                                      :description "Notification type or apropos filter type"}
                              ;; Docs params
                              "function_name" {:type "string"
                                               :description "Function name for docs describe-function"}
                              "variable_name" {:type "string"
                                               :description "Variable name for docs describe-variable"}
                              "pattern" {:type "string"
                                         :description "Pattern for docs apropos search"}
                              "package_or_prefix" {:type "string"
                                                   :description "Package/prefix for docs package-functions"}
                              "package_name" {:type "string"
                                              :description "Package name for docs package-commentary"}}
                 :required ["command"]}
   :handler handle-emacs})

(def tools [tool-def])
