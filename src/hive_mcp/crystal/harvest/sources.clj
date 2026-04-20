(ns hive-mcp.crystal.harvest.sources
  "IHarvestSource implementations for the crystal pipeline.

   Four sources, each reifying IHarvestSource:
   - memory-source  — progress notes + completed tasks from Chroma/DataScript
   - hivemind-source — shouts from piggyback history
   - kanban-source   — completed tasks from DataScript registry
   - git-source      — commits from JVM subprocess

   Each source is total (never throws) via result/rescue.
   Effectful fns resolved at call site, pure where possible.

   Part of CPPB Collect layer (Wave 1, T1)."
  (:require [hive-mcp.crystal.harvest.protocol :as proto
             :refer [harvest-ok harvest-empty harvest-error]]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.dns.result :as result]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- resolve-project-id
  "Derive project-id from opts or current directory."
  [{:keys [directory project-id]}]
  (or project-id
      (when-let [dir (or directory (ctx/current-directory))]
        (scope/get-current-project-id dir))))

(defn- resolve-session-start
  "Resolve session start instant from agent-id fallback chain."
  [{:keys [agent-id]}]
  (let [effective (or agent-id (ctx/current-agent-id))]
    (or (crystal/get-session-start effective)
        (crystal/get-session-start "_global")
        (crystal/get-session-start nil))))

(defn- timed
  "Execute body-fn, return [result elapsed-ms]."
  [body-fn]
  (let [t0 (System/currentTimeMillis)
        r  (body-fn)
        ms (- (System/currentTimeMillis) t0)]
    [r ms]))

;; =============================================================================
;; memory-source — Progress notes from Chroma
;; =============================================================================

(defn memory-source
  "IHarvestSource: progress notes from Chroma (ephemeral duration).
   Data shape: {:notes [...] :count int :project-id string?}"
  []
  (reify proto/IHarvestSource
    (source-id [_] :memory)
    (available? [_]
      (boolean (try (require 'hive-mcp.chroma.core) true
                    (catch Exception _ false))))
    (harvest [_ opts]
      (result/rescue (harvest-error :memory {:type :rescue-caught})
        (let [project-id (resolve-project-id opts)
              [data ms]
              (timed
               (fn []
                 (let [chroma (requiring-resolve 'hive-mcp.chroma.core/query-entries)
                       raw    (chroma :type "note"
                                      :project-id (or project-id "global")
                                      :limit 100)
                       notes  (->> raw
                                   (filter #(= "ephemeral" (:duration %)))
                                   (take 50)
                                   vec)]
                   {:notes notes
                    :count (count notes)
                    :project-id project-id})))]
          (log/info "memory-source:" (:count data) "notes in" ms "ms")
          (if (pos? (:count data))
            (harvest-ok :memory data ms)
            (harvest-empty :memory "no ephemeral notes found")))))))

;; =============================================================================
;; hivemind-source — Shouts from piggyback history
;; =============================================================================

(defn hivemind-source
  "IHarvestSource: hivemind messages from piggyback history.
   Data shape: {:messages [...] :count int :project-id string?}"
  []
  (reify proto/IHarvestSource
    (source-id [_] :hivemind)
    (available? [_]
      (boolean (try (require 'hive-mcp.channel.piggyback) true
                    (catch Exception _ false))))
    (harvest [_ opts]
      (result/rescue (harvest-error :hivemind {:type :rescue-caught})
        (let [project-id (resolve-project-id opts)
              start      (resolve-session-start opts)
              since-ms   (if start (.toEpochMilli start) 0)
              [data ms]
              (timed
               (fn []
                 (let [fetch (requiring-resolve 'hive-mcp.channel.piggyback/fetch-history)
                       msgs  (fetch :since since-ms
                                    :limit 100
                                    :project-id project-id)]
                   {:messages (vec msgs)
                    :count    (count msgs)
                    :project-id project-id})))]
          (log/info "hivemind-source:" (:count data) "shouts in" ms "ms"
                    "since" (or (some-> start .toString) "epoch"))
          (if (pos? (:count data))
            (harvest-ok :hivemind data ms)
            (harvest-empty :hivemind "no hivemind messages since session start")))))))

;; =============================================================================
;; kanban-source — Completed tasks from DataScript
;; =============================================================================

(defn kanban-source
  "IHarvestSource: kanban completed tasks from DataScript registry.
   Data shape: {:tasks-completed [...] :completed-count int :project-id string?}"
  []
  (reify proto/IHarvestSource
    (source-id [_] :kanban)
    (available? [_]
      (boolean (try (require 'hive-mcp.swarm.datascript) true
                    (catch Exception _ false))))
    (harvest [_ opts]
      (result/rescue (harvest-error :kanban {:type :rescue-caught})
        (let [project-id (resolve-project-id opts)
              [data ms]
              (timed
               (fn []
                 (let [ds-get (requiring-resolve 'hive-mcp.swarm.datascript/get-completed-tasks-this-session)
                       raw    (ds-get :project-id project-id)
                       tasks  (->> raw
                                   (mapv (fn [t]
                                           {:id           (or (:completed-task/id t) (:id t))
                                            :title        (or (:completed-task/title t) (:title t))
                                            :completed-at (or (:completed-task/completed-at t)
                                                              (:completed-at t))
                                            :agent-id     (or (:completed-task/agent-id t)
                                                              (:agent-id t))})))]
                   {:tasks-completed  tasks
                    :completed-count  (count tasks)
                    :project-id       project-id})))]
          (log/info "kanban-source:" (:completed-count data) "completed tasks in" ms "ms")
          (if (pos? (:completed-count data))
            (harvest-ok :kanban data ms)
            (harvest-empty :kanban "no completed tasks this session")))))))

;; =============================================================================
;; git-source — Commits via JVM subprocess
;; =============================================================================

(defn git-source
  "IHarvestSource: git commits since session start via clojure.java.shell.
   Data shape: {:commits [...] :count int :directory string?}"
  []
  (reify proto/IHarvestSource
    (source-id [_] :git)
    (available? [_] true)
    (harvest [_ opts]
      (result/rescue (harvest-error :git {:type :rescue-caught})
        (let [dir   (or (:directory opts) (ctx/current-directory) ".")
              start (resolve-session-start opts)
              since (if start (.toString start) "midnight")
              [data ms]
              (timed
               (fn []
                 (let [{:keys [exit out err]}
                       (sh "git" "log"
                           (str "--since=" since)
                           "--oneline"
                           :dir dir)]
                   (if (zero? exit)
                     (let [commits (when (and out (not (str/blank? out)))
                                    (str/split-lines (str/trim out)))]
                       {:commits   (or commits [])
                        :count     (count (or commits []))
                        :directory dir})
                     {:commits []
                      :count   0
                      :error   {:type :git-failed :exit exit :err err}}))))]
          (log/info "git-source:" (:count data) "commits in" ms "ms")
          (if (:error data)
            (harvest-error :git (:error data))
            (if (pos? (:count data))
              (harvest-ok :git data ms)
              (harvest-empty :git "no commits since session start"))))))))

;; =============================================================================
;; Registry — all default sources
;; =============================================================================

(defn default-sources
  "Return a vector of all default IHarvestSource instances."
  []
  [(memory-source) (hivemind-source) (kanban-source) (git-source)])
