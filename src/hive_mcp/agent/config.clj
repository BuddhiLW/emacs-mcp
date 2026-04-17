(ns hive-mcp.agent.config
  "OpenRouter configuration for agent delegation. Manages task-type to model mappings and presets."
  (:require [hive-mcp.config.core :as config]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private hardcoded-task-models
  "Fallback task-models when config.edn has no :models.task-models key."
  {:coding     "moonshotai/kimi-k2.5"
   :coding-alt "minimax/minimax-m2.7"
   :arch       "qwen/qwen3.6-plus"
   :docs       "z-ai/glm-5.1"})

(defn- load-task-models
  "Load task-models from config.edn, falling back to hardcoded defaults."
  []
  (or (config/get-config-value "models.task-models")
      hardcoded-task-models))

(defonce task-models
  (atom (load-task-models)))

(defn list-models
  "List all configured OpenRouter task models."
  []
  @task-models)

(defn set-model!
  "Set the model for a task type."
  [task-type model]
  (swap! task-models assoc task-type model)
  @task-models)

(defn remove-model!
  "Remove a task type from the model mapping."
  [task-type]
  (swap! task-models dissoc task-type)
  @task-models)

(defn get-model
  "Get the model for a task type, falling back to :coding."
  [task-type]
  (or (get @task-models task-type)
      (get @task-models :coding)))

(defonce preset-task-types
  (atom {;; Implementation-focused
         "tdd" :coding
         "tester" :coding
         "fixer" :coding
         "refactorer" :coding
         "ling" :coding
         "ling-worker" :coding
         "ling-tdd" :coding
         "ling-fix" :coding
         "minimal" :coding
         ;; Alternative coding
         "ling-refactor" :coding-alt
         ;; Architecture/design-focused
         "reviewer" :arch
         "clarity" :arch
         "solid" :arch
         "ddd" :arch
         "researcher" :arch
         "task-coordinator" :arch
         "hivemind" :arch
         "hivemind-master" :arch
         "hive-master" :arch
         "mcp-first" :arch
         "ling-pattern" :arch
         ;; Documentation-focused
         "documenter" :docs
         "ling-docs" :docs}))

(defn preset->task-type
  "Get the task type for a preset name, defaults to :coding."
  [preset]
  (get @preset-task-types (name preset) :coding))

(defn list-preset-mappings
  "List all preset to task-type mappings."
  []
  @preset-task-types)

(defn set-preset-task-type!
  "Set the task type for a preset."
  [preset task-type]
  (swap! preset-task-types assoc (name preset) (keyword task-type))
  @preset-task-types)

(defn resolve-model
  "Resolve the model to use: explicit model > preset-derived > task-type > :coding default."
  [{:keys [model preset task-type]}]
  (let [resolved-task-type (or (when preset (preset->task-type preset))
                               (keyword task-type)
                               :coding)
        [resolved-model source] (cond
                                  model [model :explicit]
                                  (get-model resolved-task-type)
                                  [(get-model resolved-task-type) (keyword (str "task-type-" (name resolved-task-type)))]
                                  :else [(get-model :coding) :default-coding])]
    (log/debug "Model resolution:" {:preset preset
                                    :task-type task-type
                                    :resolved-task-type resolved-task-type
                                    :source source
                                    :model resolved-model})
    resolved-model))

(defn resolve-model-with-trace
  "Like resolve-model but returns the resolution path for debugging."
  [{:keys [model preset task-type] :as _opts}]
  (let [resolved-task-type (or (when preset (preset->task-type preset))
                               (keyword task-type)
                               :coding)
        [resolved-model source] (cond
                                  model [model :explicit]
                                  (get-model resolved-task-type)
                                  [(get-model resolved-task-type) (keyword (str "task-type-" (name resolved-task-type)))]
                                  :else [(get-model :coding) :default-coding])]
    {:model resolved-model
     :source source
     :preset preset
     :task-type task-type
     :resolved-task-type resolved-task-type}))
