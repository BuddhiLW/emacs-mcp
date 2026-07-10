(ns hive-mcp.saa.adapters
  "DefaultPhaseProvider — the LSP-clean IPhaseProvider backing every phase
   lookup. Provider-neutral: build-options emits a neutral provider-options
   map (capabilities + permission-intent + neutral prompt) with NO vendor
   translation, and execute-phase! lifts every raw bridge message through
   raw-msg->phase-message so the stream carries only :pm/* variants (FIX#6)."
  (:require [clojure.core.async :as async :refer [go-loop chan >! <! close!]]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.protocols.agent-bridge :as bridge]
            [hive-mcp.saa.model :as model]
            [hive-mcp.saa.types :as types]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defrecord DefaultPhaseProvider [backend-or-session]
  psaa/IPhaseProvider

  (phase-config [_ phase]
    (model/phase-descriptor phase))

  (build-options [_ phase neutral-opts]
    (let [{:keys [tool-intent permission-intent goal-prompt-fragment]}
          (model/phase-descriptor phase)]
      (merge {:phase phase
              :capabilities tool-intent
              :permission-intent permission-intent
              :prompt goal-prompt-fragment}
             neutral-opts)))

  (execute-phase! [_ session prompt provider-options]
    (let [phase (:phase provider-options)
          raw-ch (bridge/query! session prompt provider-options)
          out-ch (chan 1024)]
      (go-loop []
        (if-let [raw (<! raw-ch)]
          (do (>! out-ch (types/raw-msg->phase-message raw phase))
              (recur))
          (close! out-ch)))
      out-ch)))

(defn ->default-phase-provider
  "Create a DefaultPhaseProvider bound to a backend or session."
  ([] (->default-phase-provider nil))
  ([backend-or-session]
   (->DefaultPhaseProvider backend-or-session)))
