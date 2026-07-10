(ns hive-mcp.saa.support
  "Shared doubles + fixtures for the SAA foundation contract suite (W0-W2).

   reset+seed! returns the four child registries to a pristine :saa/core
   seed before each test; MockSession replays a fixed vector of raw bridge
   messages so DefaultPhaseProvider.execute-phase! can be driven offline."
  (:require [clojure.core.async :as async]
            [hive-mcp.protocols.agent-bridge :as bridge]
            [hive-mcp.saa.registry :as registry]
            [hive-mcp.saa.core-seed :as core-seed]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn reset+seed!
  "Clear the four child SAA registries and re-seed the :saa/core owner."
  []
  (registry/reset-for-test!)
  (core-seed/install!))

(defn with-fresh-registry
  "use-fixtures :each fixture: pristine :saa/core seed per test."
  [f]
  (reset+seed!)
  (try (f) (finally (registry/reset-for-test!))))

(defrecord MockSession [id raws]
  bridge/IAgentSession
  (session-id [_] id)
  (query! [_ _prompt _opts]
    (let [ch (async/chan (max 1 (count raws)))]
      (doseq [r raws] (async/>!! ch r))
      (async/close! ch)
      ch))
  (interrupt! [_] nil)
  (receive-messages [_] (doto (async/chan) async/close!))
  (receive-response [_] (doto (async/chan) async/close!)))

(defn ->mock-session
  "Session whose query! replays `raws` (raw bridge messages) then closes."
  [raws]
  (->MockSession "mock-saa-session" raws))

(defn drain
  "Block-collect every value from `ch` into a vector until it closes."
  [ch]
  (loop [acc []]
    (if-let [v (async/<!! ch)] (recur (conj acc v)) acc)))
