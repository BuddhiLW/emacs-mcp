(ns hive-mcp.test.stub.terminal-addon
  "Stub ITerminalAddon for driver-free tests.

   Spawn modes are contributed by addons: on a cold CI run no terminal is
   registered, so ling-lifecycle/resolve-strategy throws
   \"No strategy registered for mode: :claude\". The registry is the seam —
   this ns registers a recording stub in it rather than mocking the lookup.

   API:
     (->terminal id)          stub addon answering to spawn mode `id`
     with-terminal            :each fixture registering `:claude`
     (register-terminal! id)  register a stub, returns it
     (calls stub)             recorded [op & args] vectors, oldest first"
  (:require [hive-addon.protocol :as addon]
            [hive-mcp.addons.terminal :as terminal]
            [hive-mcp.agent.ling.terminal-registry :as treg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- record!
  "Log the call, then throw when FAULTS names OP."
  [log faults op args]
  (swap! log conj (into [op] args))
  (when-let [msg (get faults op)]
    (throw (ex-info msg {:op op :stub/fault true}))))

(defrecord StubTerminalAddon [id log faults]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :terminal)
  (capabilities [_] #{:terminal})
  (initialize! [_ _config] {:success? true})
  (shutdown! [_] {:success? true})
  (tools [_] [])
  (schema-extensions [_] {})
  (health [_] {:healthy? true})
  (excluded-tools [_] #{})
  (hooks [_] {})

  terminal/ITerminalAddon
  (terminal-id [_] id)

  (terminal-spawn! [_ ctx opts]
    (record! log faults :spawn! [ctx opts])
    (:id ctx))

  (terminal-dispatch! [_ ctx task-opts]
    (record! log faults :dispatch! [ctx task-opts])
    true)

  (terminal-status [_ ctx ds-status]
    (record! log faults :status [ctx ds-status])
    {:slave/id (:id ctx)
     :slave/status (or (:slave/status ds-status) :running)})

  (terminal-kill! [_ ctx]
    (record! log faults :kill! [ctx])
    {:killed? true :id (:id ctx)})

  (terminal-interrupt! [_ ctx]
    (record! log faults :interrupt! [ctx])
    {:success? true :ling-id (:id ctx)}))

(defn ->terminal
  "A recording stub terminal addon answering to spawn mode ID (default :claude).

   FAULTS is {op-keyword message} over :spawn! :dispatch! :status :kill!
   :interrupt! — a faulted op throws ex-info instead of succeeding, which is
   how a test drives the host's failure path without naming a transport."
  ([] (->terminal :claude {}))
  ([id] (->terminal id {}))
  ([id faults] (->StubTerminalAddon id (atom []) (or faults {}))))

(defn calls
  "Recorded [op & args] vectors for a stub terminal, oldest first."
  [stub]
  @(:log stub))

(defn calls-of
  "Recorded arg vectors for OP only."
  [stub op]
  (into [] (comp (filter #(= op (first %))) (map #(vec (rest %)))) (calls stub)))

(defn register-terminal!
  "Register a fresh stub terminal under ID. Returns the stub."
  ([] (register-terminal! :claude {}))
  ([id] (register-terminal! id {}))
  ([id faults]
   (let [stub (->terminal id faults)]
     (treg/register-terminal! id stub)
     stub)))

(defn with-terminal
  "clojure.test fixture: register a stub `:claude` terminal for the test and
   deregister it afterwards, leaving any other registered terminal alone."
  [f]
  (let [had? (contains? (treg/registered-terminals) :claude)
        prior (treg/get-terminal-addon :claude)]
    (try
      (register-terminal! :claude)
      (f)
      (finally
        (if had?
          (treg/register-terminal! :claude prior)
          (treg/deregister-terminal! :claude))))))
