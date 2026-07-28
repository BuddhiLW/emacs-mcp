(ns hive-mcp.test.stub.vessel
  "IVessel stubs for tests.

   The vessel registry is empty in a cold JVM — the DataScript-backed vessel
   ships with an addon — so `vessel/resolve-agent-context` answers nil and any
   caller that relies on it falls through to its last resort.

   `with-datascript-vessel` registers a vessel that resolves an agent's context
   from its DataScript slave row, which is what the mounted vessel does.

   API:
     (->datascript-vessel)      vessel resolving from DataScript
     (->fixed-vessel id ctx-by-agent)  vessel answering from a literal map
     with-vessels               run f with VESSELS registered, restoring prior
     with-datascript-vessel     clojure.test :each fixture"
  (:require [hive-mcp.protocols.vessel :as vessel]
            [hive-mcp.swarm.datascript :as ds]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord DatascriptVessel []
  vessel/IVessel
  (vessel-id [_] :test/datascript)
  (capabilities [_] #{})
  (resolve-context [_ agent-id]
    (when-let [slave (ds/get-slave agent-id)]
      (let [project-id (:slave/project-id slave)
            cwd        (:slave/cwd slave)]
        (when (or project-id cwd)
          {:project-id project-id
           :cwd        cwd
           :session-id (:slave/session-id slave)}))))
  (addon [_ _] nil)
  (initialize! [_ _] nil)
  (shutdown! [_] nil))

(defrecord FixedVessel [id ctx-by-agent]
  vessel/IVessel
  (vessel-id [_] id)
  (capabilities [_] #{})
  (resolve-context [_ agent-id] (get ctx-by-agent agent-id))
  (addon [_ _] nil)
  (initialize! [_ _] nil)
  (shutdown! [_] nil))

(defn ->datascript-vessel
  "Vessel resolving agent context from the agent's DataScript slave row."
  []
  (->DatascriptVessel))

(defn ->fixed-vessel
  "Vessel answering `resolve-context` from CTX-BY-AGENT, a {agent-id ctx} map."
  [id ctx-by-agent]
  (->FixedVessel id ctx-by-agent))

(defn with-vessels
  "Run F with VESSELS registered, restoring the prior registry afterwards."
  [vessels f]
  (let [prior (vec (vessel/get-vessels))]
    (try
      (doseq [v vessels] (vessel/register-vessel! v))
      (f)
      (finally
        (vessel/clear-vessels!)
        (doseq [v prior] (vessel/register-vessel! v))))))

(defn with-datascript-vessel
  "clojure.test fixture: register the DataScript-backed vessel for the test."
  [f]
  (with-vessels [(->datascript-vessel)] f))
