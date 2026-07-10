(ns hive-mcp.emacs-ext.daemon-store
  "Extension-registry façade for `:emacs/daemon-store-*` keys.

   Replaces the legacy `hive-mcp.emacs.daemon-store` delegation shim.
   Used by `hive-mcp.swarm.sync` for ling↔daemon binding bookkeeping.
   When hive-emacs is not loaded, getters return nil and effecting fns
   are no-ops — swarm sync degrades gracefully (no daemon affinity).

   Decisions: 20260429195812-0c5dfe8d, 20260429230453-7e7627cc (Phase 2)."
  (:require [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn ensure-default-daemon! []
  (when-let [f (ext/get-extension :emacs/daemon-store-ensure-default!)] (f)))

(defn select-daemon-for-ling [ling-id]
  (when-let [f (ext/get-extension :emacs/daemon-store-select-for-ling)]
    (f ling-id)))

(defn bind-ling! [daemon-id slave-id]
  (when-let [f (ext/get-extension :emacs/daemon-store-bind-ling!)]
    (f daemon-id slave-id)))

(defn unbind-ling! [daemon-id slave-id]
  (when-let [f (ext/get-extension :emacs/daemon-store-unbind-ling!)]
    (f daemon-id slave-id)))

(defn get-daemon-for-ling [slave-id]
  (when-let [f (ext/get-extension :emacs/daemon-store-get-for-ling)]
    (f slave-id)))

(defn default-daemon-id []
  (when-let [f (ext/get-extension :emacs/daemon-store-default-id)] (f)))
