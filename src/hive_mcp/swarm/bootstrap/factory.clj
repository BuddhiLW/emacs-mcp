(ns hive-mcp.swarm.bootstrap.factory
  "Factory for ISwarmBootstrap implementations.

   OCP: adding a new bootstrap source means adding a new defmethod here
   and a new namespace under `hive-mcp.swarm.bootstrap.*`. No other code
   changes.

   Resolution priority for the bootstrap source keyword:
     1. explicit `:source` arg (e.g. from integrant config)
     2. `services.swarm-sync.source` in config.edn
     3. fallback default — `:emacs` (preserves legacy behavior)"
  (:require [hive-mcp.swarm.bootstrap.protocol :as proto]
            [hive-mcp.swarm.bootstrap.emacs :as emacs]
            [hive-mcp.swarm.bootstrap.datahike :as datahike]
            [hive-mcp.swarm.bootstrap.noop :as noop]
            [taoensso.timbre :as log] [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private legacy-default :emacs)

(defn- resolve-source
  "Pure: pick the source keyword from explicit arg → config → legacy default.
   Config values may arrive as strings (e.g. \"datahike\" from config.edn),
   so we keywordize the result to match multimethod dispatch keys."
  [source-arg config-fn]
  (or (some-> source-arg keyword)
      (rescue nil (some-> (config-fn :swarm-sync :source) keyword))
      legacy-default))

(defmulti ^:private build-bootstrap
  "Open extension point: dispatch on :source to construct the impl."
  (fn [source _opts] source))

(defmethod build-bootstrap :emacs
  [_ opts]
  (emacs/make-emacs-bootstrap opts))

(defmethod build-bootstrap :datahike
  [_ opts]
  (datahike/make-datahike-bootstrap opts))

(defmethod build-bootstrap :none
  [_ _opts]
  (noop/make-noop-bootstrap))

(defmethod build-bootstrap :default
  [source _opts]
  (log/warn "swarm.bootstrap.factory: unknown source" source "— falling back to NoopBootstrap")
  (noop/make-noop-bootstrap))

(defn make-bootstrap
  "Construct an ISwarmBootstrap from explicit args + config fallback.

   Args:
     opts — {:source :emacs|:datahike|:none ...backend-specific...}

   Config fallback (when :source absent): reads
     `services.swarm-sync.source` via the injected config-fn.

   The config-fn arg keeps this namespace pure of imports from
   hive-mcp.config.core (DIP); the caller (server/init.clj) wires it."
  [{:keys [source] :as opts} config-fn]
  (let [resolved (resolve-source source config-fn)
        impl (build-bootstrap resolved (dissoc opts :source))]
    (log/info "swarm.bootstrap.factory: built" resolved "bootstrap")
    impl))

;; Re-export protocol fns so callers can avoid a second require.
(def load-slaves     proto/-load-slaves)
(def snapshot-slave! proto/-snapshot-slave!)
(def forget-slave!   proto/-forget-slave!)
(def close!          proto/-close!)
