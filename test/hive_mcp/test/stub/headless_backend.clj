(ns hive-mcp.test.stub.headless-backend
  "Stub IHeadlessBackend implementations for backend-free tests.

   Headless backends are contributed by addons: on a cold run the headless
   registry is empty, so ling-lifecycle/resolve-strategy throws
   \"No strategy registered for mode: :headless\". The registry is the seam —
   this ns registers a stub in it rather than mocking the lookup.

   Two stubs:
     ->backend          pure recorder; spawn/kill have no process effect
     ->process-backend  recorder that delegates to the in-tree subprocess
                        manager (hive-mcp.agent.headless), so a test can drive
                        a real ling kill end-to-end without an addon

   API:
     (->backend id)             recording stub answering to mode `id`
     (->process-backend id)     process-manager-backed stub
     (register-backend! id b)   register in the real registry, returns b
     (with-backend id b & body) run body with b registered, restore prior
     (without-backends & body)  run body with the registry emptied, restore
     (calls stub)               recorded [op & args] vectors, oldest first
     (calls-of stub op)         recorded arg vectors for one op"
  (:require [hive-spi.addon.headless :as spi]
            [hive-mcp.agent.headless :as proc]
            [hive-mcp.agent.ling.headless-registry :as hreg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- record!
  "Log the call, then throw when FAULTS names OP."
  [log faults op args]
  (swap! log conj (into [op] args))
  (when-let [msg (get faults op)]
    (throw (ex-info msg {:op op :stub/fault true}))))

(defrecord StubHeadlessBackend [id log faults]
  spi/IHeadlessBackend
  (headless-id [_] id)

  (headless-spawn! [_ ctx opts]
    (record! log faults :spawn! [ctx opts])
    (str "slave-" (:id ctx)))

  (headless-dispatch! [_ ctx task-opts]
    (record! log faults :dispatch! [ctx task-opts])
    true)

  (headless-status [_ ctx ds-status]
    (record! log faults :status [ctx ds-status])
    (merge {:slave/id (:id ctx) :alive? true} ds-status))

  (headless-kill! [_ ctx]
    (record! log faults :kill! [ctx])
    {:killed? true :id (:id ctx)})

  (headless-interrupt! [_ ctx]
    (record! log faults :interrupt! [ctx])
    {:success? true :ling-id (:id ctx)}))

(defrecord ProcessHeadlessBackend [id log faults]
  spi/IHeadlessBackend
  (headless-id [_] id)

  (headless-spawn! [_ ctx opts]
    (record! log faults :spawn! [ctx opts])
    (proc/spawn-headless! (:id ctx) (merge {:cwd (:cwd ctx)} opts))
    (:id ctx))

  (headless-dispatch! [_ ctx task-opts]
    (record! log faults :dispatch! [ctx task-opts])
    (proc/dispatch-via-stdin! (:id ctx) (:task task-opts)))

  (headless-status [_ ctx ds-status]
    (record! log faults :status [ctx ds-status])
    (merge ds-status (proc/headless-status (:id ctx))))

  (headless-kill! [_ ctx]
    (record! log faults :kill! [ctx])
    (proc/kill-headless! (:id ctx) {:force? true}))

  (headless-interrupt! [_ ctx]
    (record! log faults :interrupt! [ctx])
    {:success? false
     :ling-id (:id ctx)
     :errors ["headless subprocess has no interrupt channel"]}))

(defn ->backend
  "A recording stub headless backend answering to mode ID (default :test-headless).

   FAULTS is {op-keyword message} over :spawn! :dispatch! :status :kill!
   :interrupt! — a faulted op throws ex-info instead of succeeding, which is
   how a test drives the host's failure path without naming a transport."
  ([] (->backend :test-headless {}))
  ([id] (->backend id {}))
  ([id faults] (->StubHeadlessBackend id (atom []) (or faults {}))))

(defn ->process-backend
  "A recording stub headless backend that delegates to the in-tree subprocess
   manager `hive-mcp.agent.headless`. Use when the test's subject is the ling
   lifecycle driving a real process (spawn/kill/status), not the addon
   boundary itself."
  ([] (->process-backend :test-headless-process {}))
  ([id] (->process-backend id {}))
  ([id faults] (->ProcessHeadlessBackend id (atom []) (or faults {}))))

(defn calls
  "Recorded [op & args] vectors for a stub backend, oldest first."
  [stub]
  @(:log stub))

(defn calls-of
  "Recorded arg vectors for OP only."
  [stub op]
  (into [] (comp (filter #(= op (first %))) (map #(vec (rest %)))) (calls stub)))

(defn register-backend!
  "Register BACKEND under ID in the real headless registry. Returns BACKEND.
   Metadata priority is high so `resolve-default-backend` picks it over any
   other backend that happens to be registered in the same image."
  ([id] (register-backend! id (->backend id)))
  ([id backend]
   (hreg/register-headless! id backend {:priority 1000})
   backend))

(defn- snapshot
  "Current registry contents as [[id backend metadata] ...]."
  []
  (mapv (fn [id] [id (hreg/get-headless-backend id) (hreg/headless-metadata id)])
        (hreg/registered-headless)))

(defn- restore!
  "Reset the registry to SNAP exactly."
  [snap]
  (hreg/clear-registry!)
  (doseq [[id backend m] snap]
    (hreg/register-headless! id backend m)))

(defn with-backend*
  "Run F with BACKEND registered under ID, restoring the prior registry after."
  [id backend f]
  (let [snap (snapshot)]
    (try
      (register-backend! id backend)
      (f)
      (finally (restore! snap)))))

(defmacro with-backend
  "Run BODY with BACKEND registered under ID, restoring the prior registry."
  [id backend & body]
  `(with-backend* ~id ~backend (fn [] ~@body)))

(defn without-backends*
  "Run F with the headless registry emptied, restoring the prior contents after.
   Arranges ABSENCE explicitly so a test of the no-backend branch passes in a
   hot image too, instead of relying on a cold JVM's empty registry."
  [f]
  (let [snap (snapshot)]
    (try
      (hreg/clear-registry!)
      (f)
      (finally (restore! snap)))))

(defmacro without-backends
  "Run BODY with the headless registry emptied, restoring the prior contents."
  [& body]
  `(without-backends* (fn [] ~@body)))
