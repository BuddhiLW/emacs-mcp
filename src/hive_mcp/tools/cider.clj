(ns hive-mcp.tools.cider
  "CIDER integration handlers for MCP.

   Provides Clojure REPL operations via CIDER:
   - Status checking and connection info
   - Silent and explicit code evaluation
   - Multi-session support for parallel agent work
   - Auto-connect fallback when CIDER is not connected

   Result DSL: Internal logic returns Result maps ({:ok val} or {:error category}).
   Single try-result boundary at each handler level. Zero nested try-catch."
  (:require [hive-mcp.dns.result :as result]
            [hive-mcp.tools.core :refer [mcp-success mcp-error]]
            [hive-mcp.emacs-ext.client :as ec]
            [hive-mcp.emacs-ext.elisp :as el]
            [hive-mcp.telemetry.core :as telemetry]
            [hive-mcp.dns.validation :as v]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.schema.cider :as cider-schema]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; =============================================================================
;;; Result DSL Helpers (boundary pattern)
;;; =============================================================================

(defn- elisp->result
  "Execute elisp and convert response to Result.
   {:success true :result r} -> (ok r), {:success false :error e} -> (err ...)
   Optional timeout-ms overrides the default emacsclient timeout."
  ([elisp] (elisp->result elisp nil))
  ([elisp timeout-ms]
   (let [{:keys [success result error]}
         (if timeout-ms
           (ec/eval-elisp-with-timeout elisp timeout-ms)
           (ec/eval-elisp elisp))]
     (if success
       (result/ok result)
       (result/err :cider/elisp-failed {:message (str error)})))))

(defn- try-result
  "Execute thunk f returning Result; catch unexpected exceptions as error Result.
   Unlike try-effect, expects f to return a Result map directly."
  [category f]
  (try
    (f)
    (catch Exception e
      (log/error e (str (name category) " failed"))
      (result/err category {:message (.getMessage e)}))))

(defn- result->mcp
  "Convert Result to MCP response.
   {:ok data} -> (mcp-success data), {:error ...} -> (mcp-error message)."
  [r]
  (if (result/ok? r)
    (mcp-success (:ok r))
    (mcp-error (str "Error: " (or (:message r) (:error r))))))

(defn- handle-elisp
  "Common handler: execute elisp via try-result boundary, return MCP response.
   DRYs the repeated pattern: eval-elisp -> if success -> mcp-success/mcp-error."
  [category elisp]
  (result->mcp (try-result category #(elisp->result elisp))))

;;; =============================================================================
;;; Auto-Connect Fallback Helpers (Result-returning)
;;; =============================================================================

(defn- cider-not-connected-error?
  "Check if error indicates CIDER is not connected."
  [error]
  (and (string? error)
       (str/includes? (str/lower-case error) "cider not connected")))

(defn- list-sessions*
  "List CIDER sessions. Returns Result with session vector."
  []
  (result/let-ok [raw (elisp->result (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-list-sessions))]
                 (result/try-effect* :cider/parse-sessions
                                     (let [parsed (json/read-str raw :key-fn keyword)]
                                       (if (vector? parsed) parsed (vec parsed))))))

(defn- find-connected-session
  "Find a connected session. Returns session name or nil.
   When project-dir is supplied, only matches sessions whose :project-dir
   equals it (after trailing-slash normalization). Without project-dir,
   falls back to first-connected behavior."
  ([sessions] (find-connected-session sessions nil))
  ([sessions project-dir]
   (let [norm (fn [d] (some-> d str (str/replace #"/+$" "")))
         target (norm project-dir)]
     (some (fn [s]
             (when (and (= "connected" (:status s))
                        (or (nil? target)
                            (= target (norm (:project-dir s)))))
               (:name s)))
           sessions))))

(defn- spawn-session-internal
  "Internal call to spawn a new CIDER session. Returns true on success.
   Optionally accepts project-dir to ensure session connects to correct nREPL."
  ([session-name] (spawn-session-internal session-name nil))
  ([session-name project-dir]
   (let [elisp (el/require-and-call-plist-json
                 'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                 {:name session-name :project-dir project-dir})
         {:keys [success]} (ec/eval-elisp elisp)]
     success)))

(defn- wait-for-session-ready
  "Wait briefly for the named session to become connected. Returns true if
   ready, false on timeout. Filters by session-name so prior unrelated
   connected sessions don't trick us into returning early."
  [session-name max-attempts]
  (loop [attempt 0]
    (if (>= attempt max-attempts)
      false
      (let [r (list-sessions*)]
        (if (and (result/ok? r)
                 (some (fn [s]
                         (and (= session-name (:name s))
                              (= "connected" (:status s))))
                       (:ok r)))
          true
          (do (Thread/sleep 500)
              (recur (inc attempt))))))))

(defn- spawn-and-wait*
  "Spawn a session and wait for readiness. Returns Result with session name."
  [session-name project-dir]
  (if (spawn-session-internal session-name project-dir)
    (if (wait-for-session-ready session-name 5)
      (result/ok session-name)
      (result/err :cider/session-timeout
                  {:message (str "Spawned session '" session-name "' but it didn't become ready in time")}))
    (result/err :cider/spawn-failed
                {:message (str "Failed to spawn session '" session-name "'")})))

(defn- ensure-connected*
  "Ensure CIDER is connected, auto-spawning a session if needed.
   Returns Result with session name.

   project-dir is the caller's working directory (from MCP transport).
   When supplied, session reuse is project-scoped — only sessions whose
   :project-dir matches will be reused, and a new session named
   auto-<hash> is spawned if none matches. Per-project naming prevents
   collision across simultaneous projects sharing one daemon.

   When project-dir is nil, falls back to legacy behavior:
   reuse first connected session OR spawn 'auto' rooted at the Emacs
   daemon's current project (ec/project-root). Legacy fallback keeps
   tests + non-MCP callers working but leaks the daemon's project."
  ([] (ensure-connected* nil))
  ([project-dir]
   (log/debug "ensure-cider-connected: checking sessions" {:project-dir project-dir})
   (result/let-ok [sessions (list-sessions*)]
                  (if-let [session (find-connected-session sessions project-dir)]
                    (do (log/info "ensure-cider-connected: using existing session" session)
                        (result/ok session))
                    (if project-dir
                      (let [auto-name (str "auto-" (Integer/toHexString
                                                    (.hashCode ^String project-dir)))]
                        (log/info "ensure-cider-connected: spawning session"
                                  {:name auto-name :project-dir project-dir})
                        (spawn-and-wait* auto-name project-dir))
                      (let [fallback-dir (rescue nil (ec/project-root))]
                        (log/info "ensure-cider-connected: spawning 'auto' (legacy)"
                                  {:project-dir fallback-dir})
                        (spawn-and-wait* "auto" fallback-dir)))))))

(defn- with-auto-connect*
  "Execute eval-thunk with auto-connect retry on 'not connected' errors.
   eval-thunk should be a zero-arg fn that returns a Result.
   project-dir scopes session reuse and auto-spawn (see ensure-connected*).
   Returns the Result from eval-thunk or retry."
  ([eval-thunk] (with-auto-connect* eval-thunk nil))
  ([eval-thunk project-dir]
   (let [r (eval-thunk)]
     (if (result/ok? r)
       r
       (if (cider-not-connected-error? (:message r))
         (result/let-ok [session (ensure-connected* project-dir)]
                        (log/info "with-auto-connect: reconnected via session" session)
                        (eval-thunk))
         r)))))

;;; =============================================================================
;;; Eval Handlers (with validation + telemetry + auto-connect)
;;; =============================================================================

(defn- handle-cider-eval-common
  "Common eval handler with validation, telemetry, and auto-connect retry.
   DRYs handle-cider-eval-silent and handle-cider-eval-explicit.
   elisp-fn: (fn [code] elisp-expression-string)
   timeout-ms: optional emacsclient timeout override (default 5000)

   Target directory precedence: explicit :directory, then :project_dir,
   then the request-context caller cwd (bound by wrap-handler-context).
   When resolved, eval routes through the project-scoped session (via
   eval-in-session) so the call lands on the right nREPL. nil (no request
   context — tests / non-MCP callers) keeps the legacy default-connection path."
  [params telemetry-key elisp-fn]
  (try
    (v/validate-cider-eval-request params)
    (let [{:keys [code timeout]} params
          directory (or (:directory params)
                        (:project_dir params)
                        (ctx/current-directory))
          ;; Convert CIDER timeout (seconds) to emacsclient timeout (ms).
          ;; Add 2s buffer so emacsclient doesn't race the nREPL timeout.
          ;; Default: 62s (CIDER default nREPL timeout is 60s).
          nrepl-timeout (or timeout 60)
          ec-timeout-ms (+ (* nrepl-timeout 1000) 2000)]
      (telemetry/with-eval-telemetry telemetry-key code nil
        (result->mcp
         (try-result :cider/eval-failed
                     (fn []
                       (if directory
                         ;; Project-scoped: ensure session exists + route eval through it.
                         (result/let-ok [session (ensure-connected* directory)]
                                        (elisp->result
                                          (el/require-and-call-text
                                            'hive-mcp-cider 'hive-mcp-cider-eval-in-session
                                            session code (or timeout nil))
                                          ec-timeout-ms))
                         ;; No directory: legacy default-connection path with auto-retry.
                         (with-auto-connect*
                           (fn [] (elisp->result (elisp-fn code) ec-timeout-ms))
                           nil)))))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

(defn handle-cider-eval-silent
  "Evaluate Clojure code via CIDER silently with telemetry.
   Auto-connects to CIDER if not connected (spawns 'auto' session if needed).
   Supports optional :timeout param (seconds)."
  [params]
  (let [timeout (:timeout params)]
    (handle-cider-eval-common params :cider-silent
                              (fn [code] (el/require-and-call-text 'hive-mcp-cider 'hive-mcp-cider-eval-silent
                                                                    code (or timeout nil))))))

(defn handle-cider-eval-explicit
  "Evaluate Clojure code via CIDER interactively (shows in REPL) with telemetry.
   Auto-connects to CIDER if not connected (spawns 'auto' session if needed)."
  [params]
  (handle-cider-eval-common params :cider-explicit
                            (fn [code] (el/require-and-call-text 'hive-mcp-cider 'hive-mcp-cider-eval-explicit code))))

;;; =============================================================================
;;; Simple Handlers (thin wrappers over handle-elisp)
;;; =============================================================================

(defn handle-cider-status
  "Get CIDER connection status."
  [_]
  (log/info "cider-status")
  (handle-elisp :cider/status-failed
                (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-status)))

(defn handle-cider-doc
  "Get documentation for a Clojure symbol via CIDER."
  [{:keys [symbol]}]
  (log/info "cider-doc" {:symbol symbol})
  (handle-elisp :cider/doc-failed
                (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-doc symbol)))

(defn handle-cider-apropos
  "Search for symbols matching a pattern via CIDER."
  [{:keys [pattern search_docs]}]
  (log/info "cider-apropos" {:pattern pattern :search_docs search_docs})
  (handle-elisp :cider/apropos-failed
                (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-apropos
                                          pattern (boolean search_docs))))

(defn handle-cider-info
  "Get full semantic info for a symbol via CIDER."
  [{:keys [symbol]}]
  (log/info "cider-info" {:symbol symbol})
  (handle-elisp :cider/info-failed
                (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-info symbol)))

(defn handle-cider-complete
  "Get completions for a prefix via CIDER."
  [{:keys [prefix]}]
  (log/info "cider-complete" {:prefix prefix})
  (handle-elisp :cider/complete-failed
                (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-complete prefix)))

;;; =============================================================================
;;; Multi-Session Handlers
;;; =============================================================================

(defn handle-cider-spawn-session
  "Spawn a new named CIDER session with its own nREPL server.
   Useful for parallel agent work where each agent needs isolated REPL.
   Uses plist-based boundary crossing (ADT) to prevent positional arg bugs."
  [{:keys [name project_dir agent_id repl_type port]}]
  (log/info "cider-spawn-session" {:name name :repl_type repl_type :agent_id agent_id :port port})
  (let [port (cond-> port (string? port) parse-long)]
    (handle-elisp :cider/spawn-failed
                  (el/require-and-call-plist-json
                    'hive-mcp-cider 'hive-mcp-cider-spawn-session-from-plist
                    {:name      name
                     :repl-type (when repl_type (symbol repl_type))
                     :port      port
                     :project-dir project_dir
                     :agent-id  agent_id}))))

(defn handle-cider-connect-session
  "Connect to an existing nREPL server as a named session.
   Useful for connecting to shadow-cljs, lein, or other external nREPL servers.
   Dispatches to the correct CIDER connect function based on repl_type.
   Uses extended timeout (20s) because deferred connections poll in Emacs.
   Coerces port from string to int (MCP JSON boundary).
   project_dir (optional) labels the REPL buffer with the target project so
   cross-project sessions don't collide under the daemon's current buffer."
  [{:keys [name host port repl_type agent_id project_dir] :as params}]
  (try
    (let [port (cond-> port (string? port) parse-long)
          params (assoc params :port port)
          elisp (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-connect-session
                                          name (or host "localhost") port
                                          (or repl_type "clj") agent_id project_dir)]
      (cider-schema/validate-connect-params params)
      (log/info "cider-connect-session" {:name name :host host :port port
                                         :repl_type repl_type :project_dir project_dir})
      (result->mcp
       (try-result :cider/connect-failed
                   (fn []
                     (let [{:keys [success result error]} (ec/eval-elisp-with-timeout elisp 20000)]
                       (if success
                         (result/ok result)
                         (result/err :cider/elisp-failed {:message (str error)})))))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :validation (:type (ex-data e)))
        (v/wrap-validation-error e)
        (throw e)))))

(defn handle-cider-list-sessions
  "List all active CIDER sessions with their status and ports."
  [_]
  (log/info "cider-list-sessions")
  (handle-elisp :cider/list-sessions-failed
                (el/require-and-call-json 'hive-mcp-cider 'hive-mcp-cider-list-sessions)))

(defn handle-cider-eval-session
  "Evaluate Clojure code in a specific named CIDER session.
   Supports optional :timeout param (seconds)."
  [{:keys [session_name code timeout]}]
  (log/info "cider-eval-session" {:session session_name :code-length (count code) :timeout timeout})
  (handle-elisp :cider/eval-session-failed
                (el/require-and-call-text 'hive-mcp-cider 'hive-mcp-cider-eval-in-session
                                          session_name code (or timeout nil))))

(defn- kill-session*
  "Kill a named session. Returns Result with confirmation message."
  [{:keys [session_name]}]
  (result/let-ok [_ (elisp->result (el/require-and-call 'hive-mcp-cider 'hive-mcp-cider-kill-session session_name))]
                 (result/ok (format "Session '%s' killed" session_name))))

(defn handle-cider-kill-session
  "Kill a specific named CIDER session."
  [{:keys [session_name] :as params}]
  (log/info "cider-kill-session" {:session session_name})
  (result->mcp (try-result :cider/kill-session-failed #(kill-session* params))))

(defn- kill-all-sessions*
  "Kill all sessions. Returns Result with confirmation message."
  [_]
  (result/let-ok [_ (elisp->result (el/require-and-call 'hive-mcp-cider 'hive-mcp-cider-kill-all-sessions))]
                 (result/ok "All CIDER sessions killed")))

(defn handle-cider-kill-all-sessions
  "Kill all CIDER sessions."
  [params]
  (log/info "cider-kill-all-sessions")
  (result->mcp (try-result :cider/kill-all-failed #(kill-all-sessions* params))))

;;; =============================================================================
;;; Unified Eval Router
;;; =============================================================================

(defn handle-cider-eval
  "Unified eval handler. Routes based on mode and session_name params.
   - session_name provided → eval in named session
   - mode=\"explicit\" → explicit eval (shows in REPL buffer)
   - default (mode=\"silent\") → silent eval
   Backward compatible: eval-explicit and eval-session commands still work as aliases."
  [params]
  (let [{:keys [session_name mode] :or {mode "silent"}} params]
    (cond
      ;; Session eval takes priority when session_name provided
      session_name
      (handle-cider-eval-session params)

      ;; Explicit mode: show in REPL buffer
      (= mode "explicit")
      (handle-cider-eval-explicit params)

      ;; Default: silent eval
      :else
      (handle-cider-eval-silent params))))

;;; =============================================================================
;;; Tool Definitions
;;; =============================================================================

(def tools
  "REMOVED: Flat cider tools no longer exposed. Use consolidated `cider` tool."
  [])
