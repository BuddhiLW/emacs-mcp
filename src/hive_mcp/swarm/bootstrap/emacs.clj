(ns hive-mcp.swarm.bootstrap.emacs
  "EmacsBootstrap — legacy bootstrap source that asks a running Emacs daemon
   for the current slave roster via `hive-mcp-swarm-api-status`.

   Write-through is a NO-OP: Emacs owns its own slave state internally;
   our process is a downstream observer, not a writer.

   This record encapsulates the pre-existing behavior from
   `hive-mcp.swarm.sync/full-sync-from-emacs!` so that sync.clj can depend
   only on ISwarmBootstrap (DIP)."
  (:require [hive-mcp.swarm.bootstrap.protocol :as proto]
            [hive-mcp.emacs.client :as ec]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private load-elisp
  "(json-encode (hive-mcp-swarm-api-status))")

(defn- parse-slaves
  "Pure: parse the JSON payload from elisp into a seq of slave maps.
   Returns [] on any parse failure."
  [json-str]
  (try
    (let [status (json/read-str json-str :key-fn keyword)]
      (or (:slaves-detail status) []))
    (catch Exception e
      (log/error "EmacsBootstrap: failed to parse Emacs swarm status:" (.getMessage e))
      [])))

(defrecord EmacsBootstrap [timeout-ms]
  proto/ISwarmBootstrap
  (-load-slaves [_this]
    (log/info "EmacsBootstrap: requesting swarm status from Emacs daemon")
    (let [{:keys [success result error]} (ec/eval-elisp-with-timeout load-elisp timeout-ms)]
      (if success
        (parse-slaves result)
        (do
          (log/warn "EmacsBootstrap: could not fetch Emacs swarm status:" error)
          []))))

  (-snapshot-slave! [this _slave-id _slave-data]
    ;; Emacs is source of truth for its own state — no write-through from us.
    this)

  (-forget-slave! [this _slave-id]
    this)

  (-close! [_this] nil))

(defn make-emacs-bootstrap
  "Construct an EmacsBootstrap.
   opts: {:timeout-ms int} — elisp eval timeout (default 5000)"
  ([] (make-emacs-bootstrap {}))
  ([{:keys [timeout-ms] :or {timeout-ms 5000}}]
   (->EmacsBootstrap timeout-ms)))
