(ns hive-mcp.saa.planner
  "No-op plan synthesizer. Default IPlanSynthesizer that synthesizes nothing;
   real planning is supplied by an extension or downstream provider."
  (:require [hive-mcp.protocols.saa :as psaa]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord NoopPlanSynthesizer []
  psaa/IPlanSynthesizer

  (synthesize [_ _scored-observations _task]
    nil))

(defn ->noop-planner
  "Create a NoopPlanSynthesizer."
  []
  (->NoopPlanSynthesizer))
