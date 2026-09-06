(ns hive-mcp.tools.consolidated.magit
  "Consolidated Magit/Git CLI tool."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler make-batch-handler]]
            [hive-mcp.tools.core
 :refer
 [mcp-error emacs-timeout-ms-property git-files-property]]
            [hive-mcp.tools.magit :as magit-handlers]))

(defn- handle-batch-commit
  "Batch commit multiple operations. Injects :command \"commit\" into each op."
  [{:keys [operations] :as params}]
  (if (or (nil? operations) (empty? operations))
    (mcp-error "operations is required (array of commit parameter objects, each with :message)")
    (let [batch-fn (make-batch-handler {:commit magit-handlers/handle-magit-commit})
          ops-with-command (mapv #(assoc % :command "commit") operations)]
      (batch-fn (assoc params :operations ops-with-command)))))

(def handlers
  {:status   magit-handlers/handle-magit-status
   :stage    magit-handlers/handle-magit-stage
   :commit   magit-handlers/handle-magit-commit
   :push     magit-handlers/handle-magit-push
   :branches magit-handlers/handle-magit-branches
   :log      magit-handlers/handle-magit-log
   :diff     magit-handlers/handle-magit-diff
   :pull     magit-handlers/handle-magit-pull
   :fetch    magit-handlers/handle-magit-fetch
   :feature-branches magit-handlers/handle-magit-feature-branches
   :batch-commit handle-batch-commit})

(def handle-magit
  (make-cli-handler handlers))

(def tool-def
  {:name "magit"
   :consolidated true
   :description "Git operations via Magit: status (repo state), stage (add files), commit (create commit), push (to remote), branches (list all), log (recent commits), diff (view changes), pull/fetch (from remote), feature-branches (for /ship). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties (merge
                              {"command" {:type "string"
                                          :enum ["status" "stage" "commit" "push" "branches" "log" "diff" "pull" "fetch" "feature-branches" "batch-commit" "help"]
                                          :description "Git operation to perform"}
                               "directory" {:type "string"
                                            :description "IMPORTANT: Pass your working directory to target YOUR project"}
                               "message" {:type "string"
                                          :description "Commit message"}
                               "all" {:type "boolean"
                                      :description "Stage all changes before committing"}
                               "set_upstream" {:type "boolean"
                                               :description "Set upstream tracking for new branch"}
                               "count" {:type "integer"
                                        :description "Number of commits to return (default: 10)"}
                               "target" {:type "string"
                                         :enum ["staged" "unstaged" "all"]
                                         :description "What to diff (default: staged)"}
                               "remote" {:type "string"
                                         :description "Remote to act on: which remote `fetch` fetches from, and which remote `push` pushes the current branch to. Omitted or blank, git's own default remote is used"}
                               "operations" {:type "array"
                                             :items {:type "object"
                                                     :properties {"message" {:type "string"}
                                                                  "files" (get git-files-property "files")
                                                                  "all" {:type "boolean"}}
                                                     :required ["message"]}
                                             :description "Array of commit operations for batch-commit. Each: {message, files?, all?}. Each operation stages its own `files` and is refused when they do not stage anything."}
                               "parallel" {:type "boolean"
                                           :description "Run batch operations in parallel (default: false)"}}
                              git-files-property
                              emacs-timeout-ms-property)
                 :required ["command"]}
   :handler handle-magit})

(def tools [tool-def])
