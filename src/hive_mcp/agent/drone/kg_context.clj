(ns hive-mcp.agent.drone.kg-context
  "KG-first context building for drones, consulting the Knowledge Graph before file reads."
  (:require [hive-mcp.knowledge-graph.disc :as disc]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.agent.drone.sandbox :as sandbox]
            [hive-dsl.result :as r]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- format-disc-info
  "Format disc entity info for injection."
  [disc-info file-path]
  (let [{:keys [disc staleness-score read-count]} disc-info
        file-name (last (str/split file-path #"/"))]
    (str "- **" file-name "** (KG-known, staleness: "
         (format "%.1f" (float (or staleness-score 0)))
         ", reads: " (or read-count 0)
         (when-let [analyzed (:disc/analyzed-at disc)]
           (str ", analyzed: " analyzed))
         ")")))

(defn- get-related-knowledge
  "Query for knowledge entries related to a file path."
  [file-path]
  (let [result (r/guard Exception []
                        (when (mem-proto/store-set?)
                          (->> (mem-proto/query-entries (mem-proto/get-store) {:limit 20})
                               (filter #(= file-path (get-in % [:metadata :source-file])))
                               (take 5)
                               vec)))]
    (when-let [err (::r/error (meta result))]
      (log/debug "Could not query related knowledge:" (:message err)))
    result))

(defn- get-kg-edges
  "Get KG edges where this file's entities are involved."
  [file-path]
  (let [result (r/guard Exception []
                        (let [file-name (last (str/split file-path #"/"))
                              source-edges (when (resolve 'hive-mcp.knowledge-graph.edges/query-by-source)
                                             ((resolve 'hive-mcp.knowledge-graph.edges/query-by-source)
                                              {:source-pattern file-name
                                               :limit 5}))]
                          (vec (or source-edges []))))]
    (when-let [err (::r/error (meta result))]
      (log/debug "Could not query KG edges:" (:message err)))
    result))

(defn build-kg-summary
  "Build a summary string from KG knowledge for known files."
  [kg-known]
  (when (seq kg-known)
    (let [entries (for [[path info] kg-known]
                    (let [disc-str (format-disc-info info path)
                          related (get-related-knowledge path)
                          edges (get-kg-edges path)
                          knowledge-str (when (seq related)
                                          (str "\n  Knowledge entries:\n"
                                               (str/join "\n"
                                                         (map (fn [e]
                                                                (str "    - [" (or (:type e) "note") "] "
                                                                     (subs (str (:content e))
                                                                           0 (min 80 (count (str (:content e)))))))
                                                              related))))
                          edges-str (when (seq edges)
                                      (str "\n  KG relationships:\n"
                                           (str/join "\n"
                                                     (map (fn [e]
                                                            (str "    - " (:source e) " --["
                                                                 (:relation e) "]--> " (:target e)))
                                                          edges))))]
                      (str disc-str knowledge-str edges-str)))]
      (str "## KG-Known Files (cached knowledge)\n"
           "These files have fresh KG data - content may be summarized.\n\n"
           (str/join "\n\n" entries)
           "\n"))))

(defn build-staleness-warnings
  "Build staleness warnings for stale files."
  [stale-paths]
  (when (seq stale-paths)
    (let [warnings (disc/staleness-warnings stale-paths)
          formatted (disc/format-staleness-warnings warnings)]
      {:warnings warnings
       :formatted (or formatted "")})))

(defn- read-file-content
  "Read file content with path validation."
  [path project-root]
  (let [fallback {:error "Unknown error" :path path}
        result (r/guard Exception fallback
                        (let [validation (sandbox/validate-path-containment path project-root)]
                          (if (:valid? validation)
                            {:content (slurp (:canonical-path validation))
                             :path (:canonical-path validation)}
                            {:error (:error validation)
                             :path path})))]
    (if-let [err (::r/error (meta result))]
      {:error (:message err) :path path}
      result)))

(defn- format-file-content-block
  "Format a single file's content as a markdown block."
  [path content]
  (str "### " (last (str/split path #"/")) "\n```\n" content "```\n"))

(defn- format-kg-known-file
  "Format a KG-known file's summary instead of full content."
  [path kg-info]
  (let [file-name (last (str/split path #"/"))
        {:keys [disc staleness-score read-count]} kg-info]
    (str "### " file-name " (KG-cached)\n"
         "**Note:** This file has fresh KG data (staleness: "
         (format "%.1f" (float (or staleness-score 0)))
         "). Full content skipped to save tokens.\n"
         "If you need exact content, request a file read.\n\n"
         "**KG metadata:**\n"
         "- Read count: " (or read-count 0) "\n"
         (when-let [analyzed (:disc/analyzed-at disc)]
           (str "- Last analyzed: " analyzed "\n"))
         (when-let [hash (:disc/content-hash disc)]
           (str "- Content hash: " (subs hash 0 (min 16 (count hash))) "...\n"))
         "\n")))

(defn format-files-with-kg-context
  "Format file contents using KG-first approach."
  [files & [{:keys [project-root]}]]
  (let [effective-root (or project-root "")
        {:keys [kg-known needs-read stale]} (disc/kg-first-context files)

        files-to-read (concat needs-read stale)

        {:keys [warnings formatted]} (build-staleness-warnings stale)

        read-results (for [f files-to-read]
                       (merge {:path f} (read-file-content f effective-root)))

        kg-summaries (for [[path info] kg-known]
                       (format-kg-known-file path info))

        read-contents (for [{:keys [path content error]} read-results]
                        (if error
                          (str "### " (last (str/split path #"/")) "\n(ERROR: " error ")\n")
                          (format-file-content-block path content)))

        full-context (str
                      (when (seq formatted)
                        (str formatted "\n"))

                      (when (seq kg-summaries)
                        (str "## Files with KG Knowledge (content cached)\n"
                             (str/join "\n" kg-summaries)
                             "\n"))

                      (when (seq read-contents)
                        (str "## Current File Contents\n"
                             "IMPORTANT: Use this EXACT content as old_content in propose_diff.\n"
                             "Do NOT guess or assume file content - use what is provided below.\n\n"
                             (str/join "\n" read-contents))))]

    {:context full-context
     :files-read (vec (keep #(when-not (:error %) (:path %)) read-results))
     :kg-skipped (vec (keys kg-known))
     :warnings (or warnings [])
     :summary {:total (count files)
               :kg-known (count kg-known)
               :needs-read (count needs-read)
               :stale (count stale)
               :actually-read (count files-to-read)}}))

(defn augment-task-with-kg
  "Augment a drone task using KG-first file context."
  [task files & [{:keys [project-root]}]]
  (let [{:keys [context files-read kg-skipped summary]}
        (format-files-with-kg-context files {:project-root project-root})]

    (when (seq kg-skipped)
      (log/info "KG-first context saved file reads"
                {:kg-skipped (count kg-skipped)
                 :files-read (count files-read)
                 :summary summary}))

    {:augmented-task (str "## Task\n" task
                          (when (seq files)
                            (str "\n\n## Files to modify\n"
                                 (str/join "\n" (map #(str "- " %) files))))
                          "\n\n" context)
     :files-read files-read
     :kg-skipped kg-skipped
     :summary summary}))
