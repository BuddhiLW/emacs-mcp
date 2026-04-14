(ns hive-mcp.agent.drone.backend
  "IDroneExecutionBackend protocol and resolve-backend multimethod.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol IDroneExecutionBackend
  "Strategy protocol for backend-specific drone execution."

  (execute-drone [this task-context]
    "Execute a single drone task using this backend's mechanism.")

  (supports-validation? [this]
    "Whether this backend supports post-execution validation.")

  (backend-type [this]
    "Return keyword identifying this backend type."))

(defmulti resolve-backend
  "Resolve an IDroneExecutionBackend implementation from a task context map."
  (fn [context] (:backend context)))

(defmethod resolve-backend :default [context]
  (let [k (:backend context)]
    (if (= k :openrouter)
      {:error :unknown-backend
       :key :openrouter
       :fix :use-openrouter-loop-or-omit
       :hint "Use :openrouter-loop explicitly or omit :backend to use best-backend() priority (fsm-agentic → sdk-drone → openrouter-loop)"}
      (throw (IllegalArgumentException.
              (str "No IDroneExecutionBackend registered for backend: "
                   (pr-str k)
                   ". Available backends are registered via (defmethod resolve-backend :key ...)"))))))
