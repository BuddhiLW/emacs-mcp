(ns hive-mcp.emacs-ext.notify
  "Extension-registry façade for `:emacs/notify!`.

   Replaces the legacy `hive-mcp.emacs.notify` delegation shim. Resolves
   `:emacs/notify!` from the ext registry; no-op when hive-emacs is not
   loaded (desktop notifications are non-essential — silent miss is OK).

   Decisions: 20260429195812-0c5dfe8d, 20260429230453-7e7627cc (Phase 2)."
  (:require [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn notify!
  [m]
  (when-let [f (ext/get-extension :emacs/notify!)]
    (f m)))
