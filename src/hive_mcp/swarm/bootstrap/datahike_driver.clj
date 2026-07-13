;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.swarm.bootstrap.datahike-driver
  "Late-bound facade over datahike.api for the swarm bootstrap store.

   Each fn resolves its datahike.api target at call time so
   hive-mcp.swarm.bootstrap.datahike compiles and loads with NO datahike on
   the classpath. The driver enters only when the swarm bootstrap store runs
   a store op; absent the jar, the first call throws at this boundary.")

(defn create-database  [& args] (apply (requiring-resolve 'datahike.api/create-database) args))
(defn connect          [& args] (apply (requiring-resolve 'datahike.api/connect) args))
(defn transact         [& args] (apply (requiring-resolve 'datahike.api/transact) args))
(defn q                [& args] (apply (requiring-resolve 'datahike.api/q) args))
(defn pull             [& args] (apply (requiring-resolve 'datahike.api/pull) args))
(defn db               [& args] (apply (requiring-resolve 'datahike.api/db) args))
(defn release          [& args] (apply (requiring-resolve 'datahike.api/release) args))
(defn database-exists? [& args] (apply (requiring-resolve 'datahike.api/database-exists?) args))
