(ns hive-mcp.tools.consolidated.workflow.readiness
  "Agent readiness checking for spawn workflows.

   Provides spawn-mode-dispatched readiness checks:
   - vterm: Emacs buffer ready for input
   - headless: subprocess alive (process running)
   - agent-sdk: session idle with event loop thread
   - openrouter: always ready (stateless API)

   Extracted from workflow.clj to reduce cyclomatic complexity."
  (:require [hive-mcp.emacs.client :as ec]
            [hive-mcp.agent.headless :as headless]
            [hive-mcp.swarm.datascript.queries :as queries]
            [hive-mcp.config.core :as config]
            [hive-mcp.dns.result :as result]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private default-ling-ready-timeout-ms
  "Default max wait time for ling readiness (ms). 60s allows Claude CLI startup
   which can take 10-30s. Configurable via {:services {:forge {:readiness-timeout-ms N}}}."
  60000)

(def ^:private vterm-ling-ready-timeout-ms
  "Readiness timeout for vterm/claude spawn modes (ms).
   Emacs buffer creation takes 3-5s, CLI init adds a few more."
  10000)

(def ^:private ling-ready-poll-ms 50)

(def ^:private readiness-retry-wait-ms
  "Extra wait before the single retry attempt after a readiness timeout.
   2s allows the ling process a final window to register / become CLI-ready."
  2000)

(defn- configured-timeout-ms
  "Return the configured readiness timeout, falling back to the default."
  []
  (config/get-service-value :forge :readiness-timeout-ms
                            :default default-ling-ready-timeout-ms))

(defn- timeout-ms-for-spawn-mode
  "Return the appropriate readiness timeout for the given spawn-mode.
   vterm/claude modes use a shorter timeout (10s) since Emacs is already live.
   All other modes (headless, agent-sdk, etc.) use the configured timeout (default 60s)."
  [spawn-mode]
  (case spawn-mode
    (:vterm :claude) vterm-ling-ready-timeout-ms
    (configured-timeout-ms)))

;; ── Per-Mode Readiness Checks ───────────────────────────────────────────────

(defn vterm-ready?
  "Check if a vterm ling's CLI is ready for input."
  [agent-id]
  (result/rescue false
                 (let [elisp (format "(if (hive-mcp-swarm-tasks--slave-ready-p \"%s\") \"t\" \"nil\")" agent-id)
                       result (ec/eval-elisp-with-timeout elisp 2000)]
                   (and (:success result)
                        (= "t" (:result result))))))

(defn headless-ready?
  "Check if a headless ling's process is alive.
   Relaxed from requiring stdout output — Claude CLI may take 10+ seconds
   to produce first stdout line, but the process is ready to receive stdin
   dispatch as soon as it's alive."
  [agent-id]
  (result/rescue false
                 (when-let [status (headless/headless-status agent-id)]
                   (:alive? status))))

(defn agent-sdk-ready?
  "Check if an agent-sdk ling's session is idle and event loop thread is alive."
  [agent-id]
  (result/rescue false
                 (when-let [get-session-fn (requiring-resolve 'hive-claude.sdk.session/get-session)]
                   (when-let [sess (get-session-fn agent-id)]
                     (and (= :idle (:phase sess))
                          (some? (:client-ref sess))
                          (let [safe-id (:py-safe-id sess)]
                            (if safe-id
                              (when-let [py-get-fn (requiring-resolve 'hive-claude.sdk.python/py-get-global)]
                                (let [thread-obj (py-get-fn (str "_hive_loop_thread_" safe-id))]
                                  (when thread-obj
                                    (when-let [py-call-fn (requiring-resolve 'hive-claude.sdk.python/py-call)]
                                      (boolean (py-call-fn thread-obj "is_alive"))))))
                              false)))))))

;; ── Mode Dispatch ───────────────────────────────────────────────────────────

(defn ling-cli-ready?
  "Mode-dispatch readiness check for a ling's CLI.
   Checks terminal modes via Emacs, headless via process/SDK status."
  [agent-id spawn-mode]
  (case spawn-mode
    :headless        (headless-ready? agent-id)
    :claude-process  (headless-ready? agent-id)
    :openrouter      true
    :agent-sdk       (agent-sdk-ready? agent-id)
    :claude-sdk      (agent-sdk-ready? agent-id)
    ;; default: claude / vterm (both Emacs-bound)
    (:claude :vterm) (vterm-ready? agent-id)
    (vterm-ready? agent-id)))

;; ── Polling Loop ────────────────────────────────────────────────────────────

(defn- poll-until-ready
  "Inner polling loop. Returns readiness map when ready or timed out.
   {:ready? bool :slave slave :attempts N :elapsed-ms N :phase kw}"
  [agent-id spawn-mode timeout-ms]
  (let [start-ms (System/currentTimeMillis)]
    (loop [attempt 1]
      (let [slave   (queries/get-slave agent-id)
            cli-ok? (when slave (ling-cli-ready? agent-id spawn-mode))
            elapsed (- (System/currentTimeMillis) start-ms)]
        (cond
          (and slave cli-ok?)
          (do
            (log/debug "SPARK: ling ready" {:agent-id   agent-id
                                            :attempts   attempt
                                            :elapsed-ms elapsed
                                            :spawn-mode spawn-mode})
            {:ready?     true
             :slave      slave
             :attempts   attempt
             :elapsed-ms elapsed
             :phase      :cli-ready})

          (>= elapsed timeout-ms)
          {:ready?     false
           :slave      slave
           :attempts   attempt
           :elapsed-ms elapsed
           :phase      (if slave :cli-timeout :ds-timeout)}

          :else
          (do
            (Thread/sleep ling-ready-poll-ms)
            (recur (inc attempt))))))))

(defn wait-for-ling-ready
  "Poll for ling readiness before dispatching (two-phase: DataScript + CLI).

   On timeout, waits an additional `readiness-retry-wait-ms` then retries once.
   This handles the common case where the Claude CLI process is running but
   hasn't registered in DataScript or produced stdout within the initial window.

   Timeout is spawn-mode aware:
   - vterm/claude: 10s (Emacs buffer creation ~3-5s + CLI init)
   - headless/agent-sdk and others: configurable via
     {:services {:forge {:readiness-timeout-ms N}}} in
     ~/.config/hive-mcp/config.edn (default: 60000 ms)."
  [agent-id spawn-mode]
  (let [timeout-ms (timeout-ms-for-spawn-mode spawn-mode)
        result     (poll-until-ready agent-id spawn-mode timeout-ms)]
    (if (:ready? result)
      result
      ;; First poll timed out — log clearly and attempt one retry after extra wait
      (let [{:keys [elapsed-ms phase attempts]} result]
        (log/warn "SPARK: ling readiness timeout on first poll — retrying after extra wait"
                  {:agent-id        agent-id
                   :elapsed-ms      elapsed-ms
                   :timeout-ms      timeout-ms
                   :phase           phase
                   :attempts        attempts
                   :spawn-mode      spawn-mode
                   :ds-found?       (some? (:slave result))
                   :retry-wait-ms   readiness-retry-wait-ms})
        (Thread/sleep readiness-retry-wait-ms)
        (let [retry   (poll-until-ready agent-id spawn-mode timeout-ms)
              total-elapsed (+ elapsed-ms readiness-retry-wait-ms (:elapsed-ms retry))]
          (if (:ready? retry)
            (do
              (log/info "SPARK: ling became ready on retry"
                        {:agent-id     agent-id
                         :total-elapsed-ms total-elapsed
                         :phase        (:phase retry)
                         :spawn-mode   spawn-mode})
              (assoc retry :elapsed-ms total-elapsed))
            (do
              (log/warn "SPARK: ling readiness timeout — dispatch permanently skipped"
                        {:agent-id         agent-id
                         :total-elapsed-ms total-elapsed
                         :timeout-ms       timeout-ms
                         :phase            (:phase retry)
                         :spawn-mode       spawn-mode
                         :ds-found?        (some? (:slave retry))})
              (assoc retry :elapsed-ms total-elapsed))))))))
