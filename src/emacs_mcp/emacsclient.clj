(ns emacs-mcp.emacsclient
  "Shell wrapper for emacsclient communication with running Emacs."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:dynamic *emacsclient-path*
  "Path to emacsclient binary."
  (or (System/getenv "EMACSCLIENT") "emacsclient"))

(defn eval-elisp
  "Execute elisp code in running Emacs and return the result.
   Returns a map with :success, :result or :error keys.
   Includes timing information for observability."
  [code]
  (log/debug "Executing elisp:" code)
  (let [start (System/currentTimeMillis)]
    (try
      (let [{:keys [exit out err]} (sh *emacsclient-path* "--eval" code)
            duration (- (System/currentTimeMillis) start)]
        (if (zero? exit)
          (do
            (log/debug :emacsclient-success {:duration-ms duration
                                             :result-length (count out)})
            {:success true
             :result (str/trim out)
             :duration-ms duration})
          (do
            (log/warn :emacsclient-failure {:duration-ms duration
                                            :exit-code exit
                                            :error err})
            {:success false
             :error (str/trim err)
             :duration-ms duration})))
      (catch Exception e
        (let [duration (- (System/currentTimeMillis) start)]
          (log/error :emacsclient-exception {:duration-ms duration
                                             :exception (.getMessage e)}
                     e)
          {:success false
           :error (str "Failed to execute emacsclient: " (.getMessage e))
           :duration-ms duration})))))

(defn eval-elisp!
  "Execute elisp and return result string, or throw on error."
  [code]
  (let [{:keys [success result error]} (eval-elisp code)]
    (if success
      result
      (throw (ex-info "Elisp evaluation failed" {:error error :code code})))))

(defn emacs-running?
  "Check if Emacs server is running."
  []
  (:success (eval-elisp "t")))

;; Convenience functions for common operations

(defn buffer-list
  "Get list of buffer names."
  []
  (eval-elisp! "(mapcar #'buffer-name (buffer-list))"))

(defn current-buffer
  "Get current buffer name."
  []
  (eval-elisp! "(buffer-name)"))

(defn current-file
  "Get current file path, or nil if buffer is not visiting a file."
  []
  (let [result (eval-elisp! "(buffer-file-name)")]
    (when (not= result "nil")
      result)))

(defn buffer-content
  "Get content of a buffer by name."
  [buffer-name]
  (eval-elisp! (format "(with-current-buffer \"%s\" (buffer-string))" buffer-name)))

(defn switch-to-buffer
  "Switch to a buffer by name."
  [buffer-name]
  (eval-elisp! (format "(switch-to-buffer \"%s\")" buffer-name)))

(defn find-file
  "Open a file in Emacs."
  [file-path]
  (eval-elisp! (format "(find-file \"%s\")" file-path)))

(defn save-buffer
  "Save the current buffer."
  []
  (eval-elisp! "(save-buffer)"))

(defn goto-line
  "Go to a specific line number."
  [line-number]
  (eval-elisp! (format "(goto-line %d)" line-number)))

(defn insert-text
  "Insert text at point."
  [text]
  (eval-elisp! (format "(insert \"%s\")" (str/escape text {\" "\\\"" \\ "\\\\"}))))

(defn project-root
  "Get the current project root."
  []
  (let [result (eval-elisp! "(project-root (project-current))")]
    (when (not= result "nil")
      result)))

(defn recent-files
  "Get list of recent files."
  []
  (eval-elisp! "recentf-list"))

(comment
  ;; Test functions
  (emacs-running?)
  (buffer-list)
  (current-buffer)
  (current-file)
  (project-root))
