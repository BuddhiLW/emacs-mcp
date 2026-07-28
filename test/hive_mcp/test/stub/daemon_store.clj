(ns hive-mcp.test.stub.daemon-store
  "Daemon-store fixture for the multi-daemon suites.

   `daemon-ds/create-store` with no args mints its OWN DataScript conn, so a
   store built at namespace load writes to a database the test never reads —
   `(d/entity db [:emacs-daemon/id id])` against the swarm conn returns nil and
   the tx becomes {:db/id nil ...}. `create-store` takes `:connection` for
   exactly this reason; this fixture supplies the isolated swarm conn, per test,
   AFTER `iso/with-isolations :swarm-ds` has swapped it in.

   Usage:
     (def ^:private ^:dynamic store nil)
     (use-fixtures :each
       (iso/with-isolations :swarm-ds)
       (stub/daemon-store-fixture #'store))"
  (:require [hive-mcp.emacs.daemon-ds :as daemon-ds]
            [hive-mcp.swarm.datascript.connection :as conn]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn ->store
  "A daemon store backed by the currently-installed swarm DataScript conn."
  []
  (daemon-ds/create-store {:connection (conn/ensure-conn)}))

(defn daemon-store-fixture
  "clojure.test fixture that binds VAR to a conn-sharing daemon store.

   VAR must be a dynamic Var (pass it as #'store). Order it AFTER the
   :swarm-ds isolation fixture so it sees the isolated conn."
  [var]
  (fn [f]
    (with-bindings* {var (->store)} f)))
