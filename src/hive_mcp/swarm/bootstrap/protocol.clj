(ns hive-mcp.swarm.bootstrap.protocol
  "ISwarmBootstrap — abstraction over the source-of-truth used to rehydrate
   the in-memory swarm registry at startup and to persist slave identity
   across process restarts.

   Design (SOLID/DDD/FP):
   - ISP: 4 methods, single concern (bootstrap + durable slave identity).
   - DIP: hive-mcp.swarm.sync depends on THIS, not on emacsclient or datahike.
   - OCP: new backends added as records; no edits to consumers.
   - DDD: 'bootstrap' is the Repository boundary for the Slave aggregate's
     persistent projection — NOT a general-purpose CRUD store. Transient
     state (tasks, claims) stays in the in-memory Datascript registry.
   - FP: methods return data; side effects confined to records; -close!
     runs in halt order.")
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defprotocol ISwarmBootstrap
  "Durable projection of the Slave aggregate.

   Semantics:
   - -load-slaves is called ONCE at sync startup to rehydrate the in-memory
     registry. Returns a (possibly empty) seq of slave maps. Never throws;
     backends log and return [] on failure.
   - -snapshot-slave! is called write-through whenever the in-memory registry
     gains or updates a slave. Idempotent (upsert).
   - -forget-slave! is called write-through on slave removal. Idempotent.
   - -close! releases any resources (connections, file handles).

   Slave map shape (minimum):
     {:slave-id string
      :name string
      :status keyword   ; :idle :working :error ...
      :depth int
      :cwd string       ; optional
      :project-id string ; optional, derivable from cwd
      :parent-id string} ; optional"

  (-load-slaves [this]
    "Return seq of slave maps to register at startup. Never throws.")

  (-snapshot-slave! [this slave-id slave-data]
    "Persist (upsert) a slave's durable identity. Write-through on spawn/update.
     slave-data: map with keys listed above. Returns this for threading.")

  (-forget-slave! [this slave-id]
    "Remove a slave from the durable projection. Write-through on termination.
     Returns this for threading.")

  (-close! [this]
    "Release resources. Idempotent."))
