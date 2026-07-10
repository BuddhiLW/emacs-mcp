(ns hive-mcp.agent.cider
  "CIDER session backend for agent delegation via nREPL."
  (:require [hive-mcp.agent.protocol :as proto]
            [hive-mcp.agent.ollama :as ollama]
            [hive-mcp.agent.openrouter :as openrouter]
            [hive-mcp.emacs-ext.client :as ec]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defonce ^:private session-pool
  (atom {:sessions {}
         :available (ArrayBlockingQueue. 10)}))

(defn- spawn-session!
  "Spawn a new CIDER session via Emacs."
  [session-name]
  (let [elisp (format "(json-encode (hive-mcp-cider-spawn-session \"%s\" nil nil))"
                      session-name)
        {:keys [success result error]} (ec/eval-elisp elisp)]
    (if success
      (let [data (json/read-str result :key-fn keyword)]
        (log/info "Spawned CIDER session:" session-name "port:" (:port data))
        {:name session-name
         :port (:port data)
         :status :idle
         :spawned-at (System/currentTimeMillis)})
      (do
        (log/error "Failed to spawn CIDER session:" error)
        nil))))

(defn- kill-session!
  "Kill a CIDER session via Emacs."
  [session-name]
  (let [elisp (format "(hive-mcp-cider-kill-session \"%s\")" session-name)
        {:keys [success]} (ec/eval-elisp elisp)]
    (when success
      (log/info "Killed CIDER session:" session-name))
    success))

(defn init-pool!
  "Initialize session pool with n sessions."
  [n]
  (log/info "Initializing CIDER session pool with" n "sessions")
  (doseq [i (range n)]
    (let [name (str "agent-pool-" i)
          session (spawn-session! name)]
      (when session
        (swap! session-pool update :sessions assoc name session)
        (.offer (:available @session-pool) name))))
  (log/info "Pool initialized:" (count (:sessions @session-pool)) "sessions"))

(defn shutdown-pool!
  "Shutdown all pooled sessions."
  []
  (log/info "Shutting down CIDER session pool")
  (doseq [[name _] (:sessions @session-pool)]
    (kill-session! name))
  (reset! session-pool {:sessions {} :available (ArrayBlockingQueue. 10)}))

(defn acquire-session!
  "Acquire an available session from pool, blocking up to timeout-ms."
  [timeout-ms]
  (when-let [name (.poll (:available @session-pool) timeout-ms TimeUnit/MILLISECONDS)]
    (swap! session-pool assoc-in [:sessions name :status] :busy)
    (get-in @session-pool [:sessions name])))

(defn release-session!
  "Return a session to the pool."
  [session-name]
  (swap! session-pool assoc-in [:sessions session-name :status] :idle)
  (.offer (:available @session-pool) session-name))

(defn pool-status
  "Get current pool status."
  []
  {:total (count (:sessions @session-pool))
   :available (.size (:available @session-pool))
   :sessions (vals (:sessions @session-pool))})

(defn- nrepl-eval
  "Evaluate code on an nREPL port with timeout."
  [port code timeout-ms]
  (try
    (require 'nrepl.core)
    (let [connect (resolve 'nrepl.core/connect)
          client (resolve 'nrepl.core/client)
          message (resolve 'nrepl.core/message)]
      (with-open [conn (connect :port port)]
        (let [c (client conn timeout-ms)
              responses (message c {:op "eval" :code code})]
          (reduce (fn [acc r]
                    (cond
                      (:value r) (assoc acc :value (:value r))
                      (:err r) (update acc :err str (:err r))
                      (:out r) (update acc :out str (:out r))
                      :else acc))
                  {:value nil :err "" :out ""}
                  responses))))
    (catch Exception e
      {:value nil :err (ex-message e) :out ""})))

(defrecord CiderBackend [session-name port timeout-ms]
  proto/LLMBackend

  (chat [_ messages _tools]
    (let [user-msg (last (filter #(= "user" (:role %)) messages))
          code (:content user-msg)
          result (nrepl-eval port code timeout-ms)]
      (if (and (:value result) (empty? (:err result)))
        {:type :text :content (:value result)}
        {:type :text :content (str "Error: " (:err result) "\nOutput: " (:out result))})))

  (model-name [_]
    (str "cider:" session-name)))

(defn cider-backend
  "Create a CiderBackend using a pooled session."
  ([] (cider-backend {}))
  ([{:keys [timeout-ms session] :or {timeout-ms 60000}}]
   (if-let [sess (if session
                   (get-in @session-pool [:sessions session])
                   (acquire-session! 5000))]
     (->CiderBackend (:name sess) (:port sess) timeout-ms)
     (throw (ex-info "No CIDER session available" {:pool (pool-status)})))))

(defn hybrid-backend
  "Create a hybrid backend: Ollama for reasoning, CIDER for execution."
  [{:keys [ollama-model cider-session timeout-ms]
    :or {ollama-model "devstral-small-2:latest"
         timeout-ms 60000}}]
  {:ollama (ollama/->OllamaBackend "http://localhost:11434" ollama-model)
   :cider (cider-backend {:timeout-ms timeout-ms :session cider-session})})

(defn make-backend
  "Factory function for creating LLM backends (:ollama, :cider, :openrouter)."
  ([type] (make-backend type {}))
  ([type opts]
   (case type
     :ollama (ollama/->OllamaBackend
              (or (:host opts) "http://localhost:11434")
              (or (:model opts) "devstral-small:24b"))
     :cider (cider-backend opts)
     :openrouter (openrouter/openrouter-backend opts)
     :openai-compat (openrouter/openai-compat-backend opts)
     :auto (openrouter/auto-backend opts)
     (throw (ex-info "Unknown backend type"
                     {:type type :available [:ollama :cider :openrouter :openai-compat :auto]})))))
