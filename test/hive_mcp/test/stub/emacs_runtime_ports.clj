(ns hive-mcp.test.stub.emacs-runtime-ports
  "Configures `hive-emacs.runtime-ports` for the multi-daemon suites.

   hive-emacs is host-neutral: `daemon-redistribution`, `daemon-autoheal` and
   friends reach the host only through the port functions installed by
   `runtime-ports/configure!`. Nothing installs them in a test JVM, so
   `lookup-ling` returns nil and every migration reports :ling-not-found.

   The adapters here are the host side of that seam — hive-mcp's swarm
   DataScript queries, renamed :slave/* -> :ling/* to satisfy the
   :hive-emacs/ling schema.

   Usage:
     (use-fixtures :each
       (iso/with-isolations :swarm-ds)
       ports-stub/with-swarm-ports)

   `->ports` also accepts overrides so a test can inject a failing ping or a
   recording adapter without touching the rest of the set."
  (:require [hive-emacs.runtime-ports :as runtime-ports]
            [hive-mcp.swarm.datascript.lings :as lings]
            [hive-mcp.swarm.datascript.queries :as queries]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Adapters — :slave/* is hive-mcp's vocabulary, :ling/* is hive-emacs's
;; =============================================================================

(defn slave->ling
  "Project a hive-mcp slave map onto the :hive-emacs/ling shape."
  [slave]
  (when slave
    (cond-> {:ling/id (:slave/id slave)}
      (contains? slave :slave/status)     (assoc :ling/status (:slave/status slave))
      (contains? slave :slave/project-id) (assoc :ling/project-id (:slave/project-id slave)))))

(defn lookup-ling
  "Ling by id, or nil."
  [ling-id]
  (slave->ling (queries/get-slave ling-id)))

(defn update-ling!
  "Apply UPDATES (in :ling/* vocabulary) to the slave.

   `update-slave!` takes raw :slave/* attributes, unlike `add-slave!`."
  [ling-id updates]
  (lings/update-slave!
   ling-id
   (cond-> {}
     (contains? updates :ling/status)     (assoc :slave/status (:ling/status updates))
     (contains? updates :ling/project-id) (assoc :slave/project-id (:ling/project-id updates)))))

(defn release-claims!
  [ling-id]
  (lings/release-claims-for-slave! ling-id))

(defn tasks-for-ling
  "Tasks for LING-ID filtered by STATUS, in the :hive-emacs/task shape."
  [ling-id status]
  (vec (queries/get-tasks-for-slave ling-id status)))

(defn fail-task!
  "Fail TASK-ID with STATUS (:error or :timeout)."
  [task-id status]
  (lings/fail-task! task-id status)
  {:success true})

;; =============================================================================
;; Port set
;; =============================================================================

(defn ->ports
  "The swarm-backed port map. OVERRIDES is merged last."
  ([] (->ports {}))
  ([overrides]
   (merge {:ping-fn                  (fn [_daemon-id] {:success true})
           :emit-fn                  (fn [_event _payload] nil)
           :lookup-ling-fn           lookup-ling
           :tasks-for-ling-fn        tasks-for-ling
           :fail-task-fn             fail-task!
           :release-claims-fn        release-claims!
           :update-ling-fn           update-ling!
           :report-daemon-error-fn   (fn [_message _death-tag] nil)
           :terminal-dispatch-fn     (fn [& _] {:success true})
           :resolve-agent-context-fn (fn [_agent-id] nil)
           :capability-fn            (fn [_k] nil)}
          overrides)))

(defn install!
  "Configure the runtime ports from OVERRIDES merged over the swarm set."
  ([] (install! {}))
  ([overrides]
   (runtime-ports/configure! (->ports overrides))))

(defn ports-fixture
  "clojure.test fixture configuring the ports, restoring the prior set after.

   Order it AFTER the :swarm-ds isolation fixture so the adapters read the
   isolated conn."
  ([] (ports-fixture {}))
  ([overrides]
   (fn [f]
     (let [prior (runtime-ports/snapshot)]
       (try
         (install! overrides)
         (f)
         (finally (runtime-ports/configure! prior)))))))

(def with-swarm-ports
  "Fixture installing the default swarm-backed port set."
  (ports-fixture))
