(ns hive-mcp.emacs-ext.olympus
  "Extension-registry façade for `:emacs/olympus-*` keys.

   Replaces the legacy `hive-mcp.emacs.olympus` delegation shim. Pure
   layout calculations — when hive-emacs is not loaded, fns return nil
   and the consumer (tools/olympus.clj) falls back to no-ops.

   Decisions: 20260429195812-0c5dfe8d, 20260429230453-7e7627cc (Phase 2)."
  (:require [hive-mcp.extensions.registry :as ext]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn calculate-layout [n]
  (when-let [f (ext/get-extension :emacs/olympus-calculate-layout)] (f n)))

(defn assign-positions [lings layout]
  (when-let [f (ext/get-extension :emacs/olympus-assign-positions)]
    (f lings layout)))

(defn position-for-cell [positions row col tab]
  (when-let [f (ext/get-extension :emacs/olympus-position-for-cell)]
    (f positions row col tab)))
