(ns hive-mcp.agent.drone.execution.env
  "Execution environment preparation: task augmentation, sandbox creation,
   backend resolution, and started-event emission.

   Extracted from execution.clj to reduce per-file cyclomatic complexity."
  (:require [hive-mcp.agent.drone.augment :as augment]
            [hive-mcp.agent.drone.backend :as backend]
            [hive-mcp.agent.drone.ext-router :as ext-router]
            [hive-mcp.agent.drone.sandbox :as sandbox]
            [hive-mcp.agent.drone.validation :as validation]
            [hive-mcp.dns.result :as result]
            [hive-mcp.hivemind.core :as hivemind]
            [hive-mcp.events.core :as ev]
            [hive-mcp.tools.diff :as diff]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- task-preview
  "First 80 chars of task string for log messages."
  [task]
  (let [s (str task)]
    (subs s 0 (min 80 (count s)))))

;;; ---------------------------------------------------------------------------
;;; Environment preparation
;;; ---------------------------------------------------------------------------

(defn prepare-execution-env
  "Prepare execution environment: augment task, create sandbox, security check."
  [ctx task-spec config]
  (let [{:keys [drone-id project-root]} ctx
        {:keys [task files options]} task-spec
        {:keys [step-budget]} config
        cwd (or (:cwd task-spec) project-root)

        augmented-task (augment/augment-task task files
                                             (cond-> {:project-root cwd}
                                               (seq (:seeds options))        (assoc :seeds (:seeds options))
                                               (seq (:ctx-refs options))     (assoc :ctx-refs (:ctx-refs options))
                                               (seq (:kg-node-ids options))  (assoc :kg-node-ids (:kg-node-ids options))))

        effective-root (or cwd (diff/get-project-root))
        drone-sandbox (sandbox/create-sandbox (or files []) effective-root)]

    (when (seq (:rejected-files drone-sandbox))
      (log/error {:event :drone/path-escape-blocked
                  :drone-id drone-id
                  :rejected-files (:rejected-files drone-sandbox)})
      (throw (ex-info "File paths escape project directory"
                      {:error-type :path-escape
                       :rejected-files (:rejected-files drone-sandbox)})))

    (log/info "Drone sandbox created"
              {:drone-id drone-id
               :allowed-files (count (:allowed-files drone-sandbox))
               :blocked-tools (count (:blocked-tools drone-sandbox))
               :max-turns step-budget
               :model (:model config)})

    {:augmented-task augmented-task
     :drone-sandbox drone-sandbox
     :cwd cwd}))

;;; ---------------------------------------------------------------------------
;;; Event emission
;;; ---------------------------------------------------------------------------

(defn emit-execution-started!
  "Emit :drone/started event and shout to parent ling."
  [ctx task-spec mode]
  (let [{:keys [drone-id task-id parent-id pre-validation]} ctx
        {:keys [task files]} task-spec
        mode-prefix (if (= mode :agentic) "Agentic drone" "Drone")]

    (ev/dispatch [:drone/started (cond-> {:drone-id drone-id
                                          :task-id task-id
                                          :parent-id parent-id
                                          :files files
                                          :task task
                                          :pre-validation (validation/summarize-validation (or pre-validation {}))}
                                   (= mode :agentic) (assoc :mode :agentic))])

    (when parent-id
      (hivemind/shout! parent-id :started
                       {:task (str mode-prefix ": " (task-preview task))
                        :message (format "%s %s working" mode-prefix drone-id)}))))

;;; ---------------------------------------------------------------------------
;;; Task context building
;;; ---------------------------------------------------------------------------

(defn build-task-context
  "Build the task-context map for IDroneExecutionBackend dispatch."
  [ctx task-spec config augmented-task drone-sandbox]
  (let [{:keys [drone-id]} ctx
        {:keys [tools preset model step-budget]} config
        cwd (or (:cwd task-spec) (:project-root ctx))]
    {:task       augmented-task
     :model      model
     :preset     preset
     :tools      tools
     :max-steps  (or step-budget 20)
     :drone-id   drone-id
     :cwd        cwd
     :files      (vec (or (:files task-spec) []))
     :sandbox    {:allowed-files    (:allowed-files drone-sandbox)
                  :allowed-dirs     (:allowed-dirs drone-sandbox)
                  :blocked-patterns (mapv str (:blocked-patterns drone-sandbox))
                  :blocked-tools    (:blocked-tools drone-sandbox)}}))

;;; ---------------------------------------------------------------------------
;;; Backend resolution
;;; ---------------------------------------------------------------------------

(defn resolve-backend
  "Resolve the IDroneExecutionBackend for execution."
  [task-context explicit-backend]
  (ext-router/load-available-backends!)
  (if explicit-backend
    (do
      (log/info {:event   :drone/explicit-backend-requested
                 :backend explicit-backend})
      (backend/resolve-backend (assoc task-context :backend explicit-backend)))
    (let [best (ext-router/best-backend)
          r (result/try-effect* :drone/backend-unavailable
                                (backend/resolve-backend (assoc task-context :backend best)))]
      (if (result/ok? r)
        (:ok r)
        (do (log/warn {:event   :drone/preferred-backend-unavailable
                       :preferred best
                       :error   (:message r)
                       :message "Falling back to :agentic-loop backend"})
            (backend/resolve-backend (assoc task-context :backend :agentic-loop)))))))
