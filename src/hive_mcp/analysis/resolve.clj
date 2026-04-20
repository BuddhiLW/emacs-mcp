(ns hive-mcp.analysis.resolve
  "Lazy resolution of analysis addon functions.

   Single source of truth: clj-kondo-mcp.core/run-analysis.
   Internal consumers use this resolver for graceful degradation
   when the kondo addon is absent from the classpath."
  (:require [hive-dsl.result :refer [rescue]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn resolve-kondo-analysis
  "Resolve clj-kondo-mcp.core/run-analysis. Returns fn or nil."
  []
  (rescue nil (requiring-resolve 'clj-kondo-mcp.core/run-analysis)))
