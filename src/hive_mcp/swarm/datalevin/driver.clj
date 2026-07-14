;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.swarm.datalevin.driver
  "Late-bound facade over datalevin.core for the swarm coordination store.

   Each fn resolves its datalevin.core target at call time so the swarm
   namespaces compile and load with NO datalevin on the classpath. The
   driver enters only when a swarm store operation actually runs; absent
   the jar, the first call throws at this boundary rather than failing the
   build.")

(defn get-conn  [& args] (apply (requiring-resolve 'datalevin.core/get-conn) args))
(defn close     [& args] (apply (requiring-resolve 'datalevin.core/close) args))
(defn transact! [& args] (apply (requiring-resolve 'datalevin.core/transact!) args))
(defn q         [& args] (apply (requiring-resolve 'datalevin.core/q) args))
(defn db        [& args] (apply (requiring-resolve 'datalevin.core/db) args))
(defn pull      [& args] (apply (requiring-resolve 'datalevin.core/pull) args))
