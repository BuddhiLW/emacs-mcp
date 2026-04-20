(ns hive-mcp.tools.catchup.spawn
  "Spawn context injection for ling priming."
  (:require [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.catchup.scope :as catchup-scope]
            [hive-mcp.tools.catchup.git :as catchup-git]
            [hive-mcp.tools.catchup.format :as fmt]
            [hive-mcp.agent.hints :as hints]
            [hive-mcp.knowledge-graph.disc :as kg-disc]
            [hive-mcp.channel.context-store :as context-store]
            [hive-mcp.context.reconstruction :as reconstruction]
            [hive-mcp.dns.result :refer [rescue]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- lookup-context-refs
  "Query context-store for catchup-cached entries by project scope."
  [project-id]
  (rescue nil
          (let [entries (context-store/context-query :tags #{"catchup"} :limit 20)
                project-tag (or project-id "global")
                matching (filter (fn [entry]
                                   (contains? (:tags entry) project-tag))
                                 entries)
                category-tags #{"axioms" "priority-conventions" "sessions"
                                "decisions" "conventions" "snippets"}
                refs (reduce (fn [acc entry]
                               (let [cat-tag (first (filter category-tags (:tags entry)))]
                                 (if cat-tag
                                   (assoc acc (keyword cat-tag) (:id entry))
                                   acc)))
                             {}
                             matching)]
            (when (seq refs)
              refs))))

(defn- serialize-ref-context
  "Serialize context-store refs to a compact markdown block for spawn injection."
  [refs {:keys [project-name git-info]}]
  (let [sections (atom [])]
    (swap! sections conj "## Project Context (Ref Mode — Auto-Injected)")
    (swap! sections conj "")
    (when project-name
      (swap! sections conj (str "**Project**: " project-name)))
    (swap! sections conj "")
    (swap! sections conj "**Mode**: ref (context-store references, not full text)")
    (swap! sections conj "**Action**: Fetch these refs via `session context-get` to hydrate your context.")
    (swap! sections conj "")

    (swap! sections conj "### Context References")
    (swap! sections conj "")
    (swap! sections conj "| Category | Context ID |")
    (swap! sections conj "|----------|------------|")
    (doseq [[category ctx-id] (sort-by key refs)]
      (swap! sections conj (str "| " (name category) " | `" ctx-id "` |")))
    (swap! sections conj "")

    (swap! sections conj "### How to Hydrate")
    (swap! sections conj "")
    (swap! sections conj "Fetch each ref via the session tool:")
    (swap! sections conj "```")
    (doseq [[_category ctx-id] (sort-by key refs)]
      (swap! sections conj (str "session {\"command\": \"context-get\", \"ctx_id\": \"" ctx-id "\"}")))
    (swap! sections conj "```")
    (swap! sections conj "")
    (swap! sections conj (str "**TTL Warning**: These refs expire ~10 minutes after catchup. "
                              "If expired, run `/catchup` to refresh."))
    (swap! sections conj "")

    (when git-info
      (swap! sections conj "### Git Status")
      (swap! sections conj (str "- **Branch**: " (or (:branch git-info) "unknown")))
      (when (:uncommitted git-info)
        (swap! sections conj "- **Uncommitted changes**: yes"))
      (swap! sections conj (str "- **Last commit**: " (or (:last-commit git-info) "unknown")))
      (swap! sections conj ""))

    (str/join "\n" @sections)))

(defn spawn-context
  "Generate a compact context payload for ling spawn injection."
  ([directory] (spawn-context directory {}))
  ([directory {:keys [mode task task-id] :or {mode :full}}]
   (when (mem-proto/store-set?)
     (rescue nil
             (let [project-id (scope/get-current-project-id directory)
                   project-name (catchup-scope/get-current-project-name directory)]

               (case mode
                 :ref
                 (let [refs (lookup-context-refs project-id)]
                   (if refs
                     (let [git-info (catchup-git/gather-git-info directory)
                           kg-node-ids (when-let [dec-ref (:decisions refs)]
                                         (rescue []
                                                 (when-let [entry (context-store/context-get dec-ref)]
                                                   (->> (:data entry)
                                                        (take 5)
                                                        (keep :id)
                                                        vec))))
                           reconstructed (rescue nil
                                                 (reconstruction/reconstruct-context
                                                  refs
                                                  (or kg-node-ids [])
                                                  project-id))]
                       (log/info "spawn-context :ref mode, found" (count refs) "refs"
                                 {:categories (keys refs) :reconstructed? (some? reconstructed)})
                       (if reconstructed
                         (str reconstructed
                              (when git-info
                                (str "\n\n### Git Status\n"
                                     "- **Branch**: " (or (:branch git-info) "unknown") "\n"
                                     (when (:uncommitted git-info) "- **Uncommitted changes**: yes\n")
                                     "- **Last commit**: " (or (:last-commit git-info) "unknown") "\n")))
                         (serialize-ref-context refs
                                                {:project-name (or project-name project-id "global")
                                                 :git-info git-info})))
                     (do
                       (log/info "spawn-context :ref mode, no cached refs found, falling back to :full")
                       (spawn-context directory {:mode :full :task task :task-id task-id}))))

                 :hints
                 (let [hint-data (hints/generate-hints project-id {:task task})
                       task-hints (when task-id
                                    (rescue nil
                                            (hints/generate-task-hints {:task-id task-id :depth 2})))
                       enriched-hints (if task-hints
                                        (update hint-data :memory-hints
                                                (fn [mh]
                                                  (cond-> mh
                                                    (seq (:l1-ids task-hints))
                                                    (update :read-ids (fnil into []) (:l1-ids task-hints))
                                                    (seq (:l2-queries task-hints))
                                                    (update :queries (fnil into []) (:l2-queries task-hints))
                                                    (seq (:l3-seeds task-hints))
                                                    (assoc :kg-seeds (mapv :id (:l3-seeds task-hints))
                                                           :kg-depth (get (first (:l3-seeds task-hints)) :depth 2)))))
                                        hint-data)
                       git-info (catchup-git/gather-git-info directory)]
                   (hints/serialize-hints enriched-hints
                                          :project-name (or project-name project-id "global")
                                          :git-info git-info))

           ;; :full and default case
                 (let [axioms (catchup-scope/query-axioms project-id)
                       priority-conventions (catchup-scope/query-scoped-entries "convention" ["catchup-priority"]
                                                                                project-id 50)
                       decisions (catchup-scope/query-scoped-entries "decision" nil project-id 50)
                       git-info (catchup-git/gather-git-info directory)

                       stale-files (rescue []
                                           (kg-disc/top-stale-files :n 5 :project-id project-id))

                       axioms-meta (mapv fmt/entry->axiom-meta axioms)
                       priority-meta (mapv fmt/entry->priority-meta priority-conventions)
                       decisions-meta (mapv #(fmt/entry->catchup-meta % 80) decisions)

                       context-str (fmt/serialize-spawn-context
                                    {:axioms axioms-meta
                                     :priority-conventions priority-meta
                                     :decisions decisions-meta
                                     :stale-files stale-files
                                     :git-info git-info
                                     :project-name (or project-name project-id "global")})]

                   (if (> (count context-str) fmt/max-spawn-context-chars)
                     (do
                       (log/warn "spawn-context exceeds token budget:"
                                 (count context-str) "chars, truncating decisions + stale-files")
                       (fmt/serialize-spawn-context
                        {:axioms axioms-meta
                         :priority-conventions priority-meta
                         :decisions []
                         :stale-files []
                         :git-info git-info
                         :project-name (or project-name project-id "global")}))
                     context-str))))))))
