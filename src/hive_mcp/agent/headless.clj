(ns hive-mcp.agent.headless
  "Headless ling process management - subprocess-based Claude Code instances."
  (:require [clojure.string :as str]
            [hive-mcp.agent.ring-buffer :as rb]
            [hive-dsl.result :as result]
            [hive-mcp.server.guards :as guards]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder]
           [java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter]
           [java.util.concurrent ConcurrentHashMap Executors ScheduledExecutorService TimeUnit]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(declare dispatch-via-stdin!)
(declare stop-watchdog!)
(declare start-watchdog!)
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ===========================================================================
;; Ring Buffer (delegates to hive-mcp.agent.ring-buffer)
;; ===========================================================================

(def ^:const default-buffer-capacity rb/default-buffer-capacity)
(def create-ring-buffer rb/create-ring-buffer)
(def ring-buffer-append! rb/ring-buffer-append!)
(def ring-buffer-contents rb/ring-buffer-contents)
(def ring-buffer-contents-since rb/ring-buffer-contents-since)
(def ring-buffer-stats rb/ring-buffer-stats)

;; ===========================================================================
;; Process Registry
;; ===========================================================================

(defonce ^:private process-registry
  (ConcurrentHashMap.))

(defonce ^:private shutdown-hook-registered?
  (atom false))

(defonce ^:private watchdog-state
  (atom {:running? false
         :sweep-count 0
         :last-sweep-at nil
         :last-cleaned []}))

;; Holds the ScheduledFuture returned by scheduleAtFixedRate.
(defonce ^:private watchdog-handle
  (atom nil))

(defonce ^:private watchdog-executor
  (delay
    (Executors/newSingleThreadScheduledExecutor
     (reify java.util.concurrent.ThreadFactory
       (newThread [_ r]
         (doto (Thread. r "hive-headless-watchdog")
           (.setDaemon true)))))))

(def ^:private ^:const watchdog-interval-s
  "Watchdog sweep interval in seconds."
  30)

(def ^:private ^:const drain-timeout-ms
  "Max time (ms) to drain reader output after process death."
  60000)

;; ===========================================================================
;; Result DSL Helpers
;; ===========================================================================

(defn- unwrap!
  "Convert a Result to its :ok value, or throw ex-info on :error.
   Maintains backward-compatible exception API for public functions.
   Uses if-let (scc-free) for CC optimization."
  [r]
  (if-let [v (when (contains? r :ok) (:ok r))]
    v
    (when-not (contains? r :ok)
      (let [msg (if-let [m (:message r)] m (name (:error r)))
            data (dissoc r :error :message :cause)]
        (if-let [cause (:cause r)]
          (throw (ex-info msg data cause))
          (throw (ex-info msg data)))))))

(defn- require-entry
  "Get registry entry for ling-id, returning Result."
  [ling-id]
  (if-let [entry (.get process-registry ling-id)]
    (result/ok entry)
    (result/err :headless/not-found
                {:ling-id ling-id
                 :message "Headless ling not found in registry"})))

(defn- require-not-registered
  "Ensure ling-id is NOT already in registry, returning Result."
  [ling-id]
  (if-let [_ (.get process-registry ling-id)]
    (result/err :headless/duplicate-id
                {:ling-id ling-id
                 :message "Headless ling already exists with this ID"})
    (result/ok ling-id)))

(defn- require-alive
  "Check that process in entry is alive, returning Result with the process."
  [ling-id entry]
  (let [^Process process (:process entry)]
    (if-let [_ (when-not (.isAlive process) :dead)]
      (result/err :headless/not-alive
                  {:ling-id ling-id
                   :pid (.pid process)
                   :message "Headless ling process is not alive"})
      (result/ok process))))

;; ===========================================================================
;; Internal Helpers
;; ===========================================================================

(defn- register-shutdown-hook!
  "Register a JVM shutdown hook to stop watchdog and kill all headless processes."
  []
  (when (compare-and-set! shutdown-hook-registered? false true)
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (stop-watchdog!)
        (log/info "JVM shutdown hook: killing" (.size process-registry) "headless processes")
        (doseq [[ling-id entry] process-registry]
          (let [res (result/guard Exception nil
                                  (when-let [^Process process (:process entry)]
                                    (when (.isAlive process)
                                      (log/info "Killing headless process" {:ling-id ling-id :pid (.pid process)})
                                      (.destroyForcibly process))))]
            (when-let [err (::result/error (meta res))]
              (log/warn "Failed to kill headless process on shutdown"
                        {:ling-id ling-id :error (:message err)})))))
      "hive-headless-shutdown-hook"))
    (log/info "Registered JVM shutdown hook for headless processes")))

(defn- start-stream-reader!
  "Start a daemon thread reading lines from a stream into a ring buffer.
   Includes drain timeout: after process death, reads remaining output
   for up to `drain-timeout-ms` before exiting."
  [stream buffer label ling-id ^Process process]
  (let [reader (BufferedReader. (InputStreamReader. stream))
        thread (Thread.
                (fn []
                  (try
                    (loop [deadline nil]
                      (let [dead? (not (.isAlive process))
                            deadline (cond
                                       deadline deadline
                                       dead? (do (log/debug (str "Headless " label " process died, draining")
                                                            {:ling-id ling-id :drain-ms drain-timeout-ms})
                                                 (+ (System/currentTimeMillis) drain-timeout-ms))
                                       :else nil)]
                        (cond
                          ;; Drain deadline exceeded — stop reading
                          (and deadline (> (System/currentTimeMillis) deadline))
                          (log/info (str "Headless " label " drain timeout reached")
                                    {:ling-id ling-id})

                          ;; Process dead, no data ready — poll with sleep
                          (and dead? (not (.ready reader)))
                          (do (Thread/sleep 1000)
                              (recur deadline))

                          ;; Normal read (or drain read with data available)
                          :else
                          (when-let [line (.readLine reader)]
                            (ring-buffer-append! buffer line)
                            (when (str/includes? line "error")
                              (log/debug (str "Headless " label " [" ling-id "]: " line)))
                            (recur deadline)))))
                    (log/debug (str "Headless " label " reader exited") {:ling-id ling-id})
                    (catch java.io.IOException _
                      (log/debug (str "Headless " label " stream closed") {:ling-id ling-id}))
                    (catch InterruptedException _
                      (log/debug (str "Headless " label " reader interrupted") {:ling-id ling-id}))
                    (catch Exception e
                      (log/warn (str "Headless " label " reader error")
                                {:ling-id ling-id :error (.getMessage e)}))))
                (str "hive-headless-" label "-" ling-id))]
    (.setDaemon thread true)
    (.start thread)
    thread))

(defn- build-command-parts
  "Build command parts for spawning a headless ling."
  [claude-cmd model task system-prompt]
  (let [claude-like? (str/includes? (or claude-cmd "claude") "claude")
        openrouter-model? (and model
                               (not= model "claude")
                               claude-like?)]
    (cond-> [(or claude-cmd "claude")]
      claude-like? (conj "-p")
      (and task claude-like?) (conj task)
      claude-like? (conj "--output-format" "stream-json" "--verbose"
                        "--permission-mode" "acceptEdits")
      openrouter-model? (conj "--model" model)
      (and system-prompt claude-like?) (conj "--append-system-prompt" system-prompt)
      (and task (not claude-like?)) (conj task))))

;; ===========================================================================
;; Spawn Helpers (decomposed from spawn-headless!)
;; ===========================================================================

(def ^:private forwarded-env-vars
  "Parent env vars to explicitly forward to child ProcessBuilder.
   These are critical for child ling operation and would be lost
   if the child's env were ever constructed fresh (not inherited)."
  ["ANTHROPIC_API_KEY"
   "OPENROUTER_API_KEY"
   "BB_MCP_NREPL_PORT"
   "OLLAMA_HOST"
   "HOME"
   "PATH"
   "TERM"])

(defn build-child-env
  "Build environment variable map for a child ling process (pure calculation).

   Merges (in priority order, later wins):
   1. Forwarded parent env vars (ANTHROPIC_API_KEY, OPENROUTER_API_KEY, etc.)
   2. Child-ling guard vars (HIVE_MCP_ROLE, HIVE_LING_DEPTH)
   3. Project dir (BB_MCP_PROJECT_DIR from cwd)
   4. Agent identity (CLAUDE_SWARM_SLAVE_ID)
   5. Model override (OPENROUTER_MODEL when non-claude)
   6. Caller-provided env-extra (highest priority override)"
  [ling-id {:keys [cwd env-extra model]}]
  (let [parent-vars (reduce (fn [m var-name]
                              (if-let [v (System/getenv var-name)]
                                (assoc m var-name v)
                                m))
                            {}
                            forwarded-env-vars)
        guard-vars (guards/child-ling-env)]
    (cond-> (merge parent-vars
                   guard-vars
                   {"CLAUDE_SWARM_SLAVE_ID" ling-id})
      cwd (assoc "BB_MCP_PROJECT_DIR" cwd)
      (and model (not= model "claude")) (assoc "OPENROUTER_MODEL" model)
      env-extra (as-> m (reduce-kv (fn [acc k v] (assoc acc (name k) (str v)))
                                   m env-extra)))))

(defn- configure-process-env!
  "Set up ProcessBuilder environment variables.

   Uses build-child-env (pure calculation) to compute the env map,
   then applies it to the ProcessBuilder's mutable environment."
  [^ProcessBuilder pb ling-id opts]
  (let [env (.environment pb)
        child-env (build-child-env ling-id opts)]
    (doseq [[k v] child-env]
      (.put env k v))))

(defn- create-process-builder
  "Create and configure a ProcessBuilder for headless ling."
  [cmd-parts cwd]
  (doto (ProcessBuilder. ^java.util.List (vec cmd-parts))
    (.directory (java.io.File. cwd))
    (.redirectErrorStream false)))

(defn- start-process!
  "Start the subprocess, returning Result."
  [^ProcessBuilder pb ling-id cwd model cmd-parts]
  (try
    (result/ok (.start pb))
    (catch Exception e
      (result/err :headless/spawn-failed
                  {:ling-id ling-id
                   :cwd cwd
                   :model model
                   :cmd cmd-parts
                   :message (str "Failed to start headless ling process: " (.getMessage e))
                   :cause e}))))

(defn- wire-streams!
  "Set up stream readers, buffers, and stdin writer for a process."
  [^Process process ling-id buffer-capacity]
  (let [stdout-buf (create-ring-buffer buffer-capacity)
        stderr-buf (create-ring-buffer buffer-capacity)]
    {:stdout-buffer stdout-buf
     :stderr-buffer stderr-buf
     :stdin-writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream process)))
     :stdout-reader-thread (start-stream-reader! (.getInputStream process) stdout-buf "stdout" ling-id process)
     :stderr-reader-thread (start-stream-reader! (.getErrorStream process) stderr-buf "stderr" ling-id process)}))

(defn- build-registry-entry
  "Build the registry entry map for a spawned process."
  [^Process process streams {:keys [cwd model]}]
  (merge streams
         {:process process
          :pid (.pid process)
          :cwd cwd
          :model model
          :started-at (System/currentTimeMillis)}))

(defn- build-spawn-result
  "Build the public spawn result map."
  [ling-id ^Process process streams model]
  {:ling-id ling-id
   :pid (.pid process)
   :process process
   :stdout-buf (:stdout-buffer streams)
   :stderr-buf (:stderr-buffer streams)
   :model model})

;; ===========================================================================
;; Kill Helpers (decomposed from kill-headless!)
;; ===========================================================================

(defn- close-writer-quietly!
  "Close a writer, ignoring any exceptions."
  [^BufferedWriter writer]
  (result/rescue nil (.close writer)))

(defn- terminate-process!
  "Terminate process gracefully or forcibly."
  [^Process process {:keys [force? timeout-ms ling-id pid]
                     :or {timeout-ms 5000}}]
  (if force?
    (.destroyForcibly process)
    (do
      (.destroy process)
      (when-not (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (log/warn "Headless ling didn't exit gracefully, force-killing"
                  {:ling-id ling-id :pid pid})
        (.destroyForcibly process)))))

(defn- await-exit-code
  "Wait for process exit and return exit code (-1 on timeout)."
  [^Process process]
  (result/guard Exception -1
                (.waitFor process 5000 java.util.concurrent.TimeUnit/MILLISECONDS)
                (.exitValue process)))

;; ===========================================================================
;; Internal Logic (returns Result)
;; ===========================================================================

(defn- spawn-headless!*
  "Internal spawn logic returning Result."
  [ling-id {:keys [cwd task model env-extra claude-cmd buffer-capacity system-prompt]
            :or {claude-cmd "claude"
                 buffer-capacity default-buffer-capacity}}]
  (result/let-ok [_ (require-not-registered ling-id)]
                 (register-shutdown-hook!)
                 (start-watchdog!)
                 (let [cmd-parts (build-command-parts claude-cmd model task system-prompt)
                       pb (create-process-builder cmd-parts cwd)]
                   (configure-process-env! pb ling-id {:cwd cwd :env-extra env-extra :model model})
                   (log/info "Spawning headless ling" {:ling-id ling-id
                                                       :cwd cwd
                                                       :model (or model "claude")
                                                       :cmd (str/join " " cmd-parts)})
                   (result/let-ok [^Process process (start-process! pb ling-id cwd model cmd-parts)]
                                  (let [streams (wire-streams! process ling-id buffer-capacity)
                                        entry (build-registry-entry process streams {:cwd cwd :model model})]
                                    (.put process-registry ling-id entry)
                                    (log/info "Headless ling spawned" {:ling-id ling-id :pid (.pid process) :cwd cwd
                                                                       :model (or model "claude")})
                                    (result/ok (build-spawn-result ling-id process streams model)))))))

(defn- dispatch-stdin!*
  "Internal dispatch logic returning Result."
  [ling-id message]
  (result/let-ok [entry (require-entry ling-id)
                  _ (require-alive ling-id entry)]
                 (let [^BufferedWriter writer (:stdin-writer entry)]
                   (try
                     (.write writer ^String message)
                     (.newLine writer)
                     (.flush writer)
                     (log/debug "Dispatched to headless ling via stdin"
                                {:ling-id ling-id :message-length (count message)})
                     (result/ok true)
                     (catch java.io.IOException e
                       (result/err :headless/stdin-write-failed
                                   {:ling-id ling-id
                                    :message (str "Failed to write to headless ling stdin: " (.getMessage e))
                                    :cause e}))))))

(defn- kill-headless!*
  "Internal kill logic returning Result."
  [ling-id {:keys [force? timeout-ms] :or {timeout-ms 5000}}]
  (result/let-ok [entry (require-entry ling-id)]
                 (let [^Process process (:process entry)
                       pid (:pid entry)
                       ^BufferedWriter writer (:stdin-writer entry)]
                   (log/info "Killing headless ling" {:ling-id ling-id :pid pid :force? force?})
                   (close-writer-quietly! writer)
                   (terminate-process! process {:force? force? :timeout-ms timeout-ms
                                                :ling-id ling-id :pid pid})
                   (let [exit-code (await-exit-code process)]
                     (.remove process-registry ling-id)
                     (log/info "Headless ling killed" {:ling-id ling-id :pid pid :exit-code exit-code})
                     (result/ok {:killed? true
                                 :ling-id ling-id
                                 :pid pid
                                 :exit-code exit-code})))))

;; ===========================================================================
;; Watchdog — periodic dead-process cleanup
;; ===========================================================================

(defn- watchdog-sweep!
  "Scan process-registry for dead processes and clean them up.
   Called periodically by the ScheduledExecutorService."
  []
  (try
    (let [dead-ids (reduce-kv
                    (fn [acc ling-id entry]
                      (let [^Process process (:process entry)]
                        (if (and process (not (.isAlive process)))
                          (conj acc ling-id)
                          acc)))
                    []
                    process-registry)]
      (doseq [ling-id dead-ids]
        (log/warn "Watchdog: dead headless process detected, cleaning up"
                  {:ling-id ling-id
                   :pid (:pid (.get process-registry ling-id))})
        (result/guard Exception nil
                      (kill-headless!* ling-id {:force? true})))
      (swap! watchdog-state
             (fn [s]
               (-> s
                   (update :sweep-count inc)
                   (assoc :last-sweep-at (System/currentTimeMillis))
                   (assoc :last-cleaned (vec dead-ids))))))
    (catch Exception e
      (log/error "Watchdog sweep error" {:error (.getMessage e)}))))

(defn start-watchdog!
  "Start the periodic watchdog sweep. Idempotent — no-op if already running."
  []
  (when-not (:running? @watchdog-state)
    (let [^ScheduledExecutorService exec @watchdog-executor
          handle (.scheduleAtFixedRate
                  exec
                  ^Runnable watchdog-sweep!
                  (long watchdog-interval-s)
                  (long watchdog-interval-s)
                  TimeUnit/SECONDS)]
      (reset! watchdog-handle handle)
      (swap! watchdog-state assoc :running? true)
      (log/info "Headless watchdog started" {:interval-s watchdog-interval-s}))))

(defn stop-watchdog!
  "Stop the periodic watchdog sweep."
  []
  (when-let [^java.util.concurrent.ScheduledFuture handle @watchdog-handle]
    (.cancel handle false)
    (reset! watchdog-handle nil)
    (swap! watchdog-state assoc :running? false)
    (log/info "Headless watchdog stopped")))

;; ===========================================================================
;; Public API
;; ===========================================================================

(defn spawn-headless!
  "Spawn a headless ling subprocess via ProcessBuilder."
  [ling-id {:keys [cwd] :as opts}]
  {:pre [(string? ling-id)
         (string? cwd)]}
  (unwrap! (spawn-headless!* ling-id opts)))

(defn dispatch-via-stdin!
  "Send a task to a headless ling via its stdin pipe."
  [ling-id message]
  {:pre [(string? ling-id)
         (string? message)]}
  (unwrap! (dispatch-stdin!* ling-id message)))

(defn kill-headless!
  "Terminate a headless ling process."
  ([ling-id] (kill-headless! ling-id {}))
  ([ling-id opts]
   (unwrap! (kill-headless!* ling-id opts))))

(defn headless-status
  "Get the status of a headless ling."
  [ling-id]
  (when-let [entry (.get process-registry ling-id)]
    (let [^Process process (:process entry)
          alive? (.isAlive process)]
      {:ling-id ling-id
       :pid (:pid entry)
       :alive? alive?
       :exit-code (when-not alive?
                    (result/guard Exception nil (.exitValue process)))
       :cwd (:cwd entry)
       :model (:model entry)
       :started-at (:started-at entry)
       :uptime-ms (- (System/currentTimeMillis) (:started-at entry))
       :stdout (ring-buffer-stats (:stdout-buffer entry))
       :stderr (ring-buffer-stats (:stderr-buffer entry))})))

(defn get-stdout
  "Get stdout contents of a headless ling."
  ([ling-id] (get-stdout ling-id {}))
  ([ling-id opts]
   (when-let [entry (.get process-registry ling-id)]
     (ring-buffer-contents (:stdout-buffer entry) opts))))

(defn get-stderr
  "Get stderr contents of a headless ling."
  ([ling-id] (get-stderr ling-id {}))
  ([ling-id opts]
   (when-let [entry (.get process-registry ling-id)]
     (ring-buffer-contents (:stderr-buffer entry) opts))))

(defn get-stdout-since
  "Get stdout lines appended after a given timestamp."
  [ling-id since]
  (when-let [entry (.get process-registry ling-id)]
    (ring-buffer-contents-since (:stdout-buffer entry) since)))

(defn list-headless
  "List all active headless ling processes."
  []
  (->> process-registry
       (.keySet)
       (map headless-status)
       (remove nil?)
       vec))

(defn headless-count
  "Get count of registered headless processes (alive + dead)."
  []
  (.size process-registry))

(defn active-process-count
  "Get breakdown of alive vs dead headless processes in the registry."
  []
  (let [total (.size process-registry)
        alive (reduce-kv
               (fn [n _id entry]
                 (if (.isAlive ^Process (:process entry)) (inc n) n))
               0
               process-registry)]
    {:active alive :total total :dead (- total alive)}))

(defn watchdog-status
  "Get current watchdog state."
  []
  @watchdog-state)

(defn headless?
  "Check if a ling-id corresponds to a headless process."
  [ling-id]
  (.containsKey process-registry ling-id))

(defn get-process
  "Get the raw Process handle for a headless ling."
  [ling-id]
  (when-let [entry (.get process-registry ling-id)]
    (:process entry)))

(defn get-stdout-buffer
  "Get the raw stdout ring buffer atom for a headless ling."
  [ling-id]
  (when-let [entry (.get process-registry ling-id)]
    (:stdout-buffer entry)))

(defn get-stderr-buffer
  "Get the raw stderr ring buffer atom for a headless ling."
  [ling-id]
  (when-let [entry (.get process-registry ling-id)]
    (:stderr-buffer entry)))

(defn kill-all-headless!
  "Kill all active headless processes."
  []
  (let [ids (vec (.keySet process-registry))
        results (for [id ids]
                  (let [res (result/guard Exception nil
                                          (kill-headless! id {:force? true}))]
                    (if-let [err (::result/error (meta res))]
                      {:success false :id id :error (:message err)}
                      {:success true :id id})))]
    {:killed (count (filter :success results))
     :errors (count (remove :success results))}))

(comment)
  ;; (spawn-headless! "test-ling-1" {:cwd "/home/user/project"})
  ;; (headless-status "test-ling-1")
  ;; (get-stdout "test-ling-1" {:last-n 50})
  ;; (dispatch-via-stdin! "test-ling-1" "Find all test files and list them")
  ;; (kill-headless! "test-ling-1")
  ;; (list-headless)

