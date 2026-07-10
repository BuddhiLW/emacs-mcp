(ns hive-mcp.tools.migrate.kanban.state
  "Filesystem-backed IState adapter. Persists migration progress as EDN
   under `~/.local/share/hive-mcp/kanban-migration/state.edn` by default,
   atomically (tmp file + Files/move replace-existing) so a crash mid-write
   never leaves a half-EDN file.

   Schema (any extra keys passed through unchanged):
     {:phase         :ready | :ids-listed | :running | :done
      :all-ids       [string]
      :cursor        int        ; index into :all-ids
      :stats         {outcome-kw -> int}
      :failed        [{:id :reason}]
      :phase-a-summary  ; per-collection scan summary
      :started-at    iso-string
      :phase-a-at    iso-string
      :last-step-at  iso-string}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [hive-dsl.result :as r]
            [hive-mcp.tools.migrate.kanban.pure :as pure]
            [hive-mcp.tools.migrate.kanban.ports :as ports])
  (:import [java.nio.file Files StandardCopyOption]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def default-path
  (str (System/getProperty "user.home")
       "/.local/share/hive-mcp/kanban-migration/state.edn"))

(def initial-state
  "Identity element for state aggregation. Stats key set is closed and
   pinned to `pure/outcome-types` plus the `:written`/`:would-write`
   counters that only the use-case layer increments."
  {:phase     :ready
   :all-ids   []
   :cursor    0
   :stats     (merge pure/empty-tally
                     {:scanned 0 :written 0 :would-write 0 :failed 0})
   :failed    []
   :started-at nil
   :phase-a-at nil
   :last-step-at nil})

;; =============================================================================
;; Internals
;; =============================================================================

(defn- atomic-spit!
  "Write `body` to `path` atomically. Writes to <path>.tmp first, fsyncs
   via Files/move replace-existing-atomic-move."
  [path body]
  (let [target  (io/file path)
        _       (io/make-parents target)
        tmp     (io/file (str path ".tmp"))]
    (spit tmp body)
    (Files/move (.toPath tmp) (.toPath target)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))))

(defn- read-edn-file
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

;; =============================================================================
;; FileState — IState adapter
;; =============================================================================

(defrecord FileState [path]
  ports/IState
  (load-state [_]
    (try
      (r/ok (or (read-edn-file path) initial-state))
      (catch Throwable t
        (r/err :state/load-failed
               {:path path :message (.getMessage t)}))))

  (save-state! [_ state]
    (try
      (atomic-spit! path (with-out-str (pprint/pprint state)))
      (r/ok state)
      (catch Throwable t
        (r/err :state/save-failed
               {:path path :message (.getMessage t)}))))

  (reset-state! [this]
    (ports/save-state! this initial-state)))

(defn make
  "Construct a FileState. With no args defaults to `default-path`."
  ([] (make default-path))
  ([path] (->FileState path)))
