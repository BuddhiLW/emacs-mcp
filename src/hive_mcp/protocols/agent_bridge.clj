(ns hive-mcp.protocols.agent-bridge
  "Protocols for agent backend abstraction and session lifecycle."
  (:require [clojure.core.async :as async]
            [hive-mcp.protocols.saa :as psaa]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IAgentSession Protocol
;;; ============================================================================

(defprotocol IAgentSession
  "Protocol for agent session interaction."

  (session-id [this]
    "Return unique session identifier.")

  (query! [this prompt opts]
    "Send a prompt and return a core.async channel of response messages.")

  (interrupt! [this]
    "Interrupt the current query.")

  (receive-messages [this]
    "Get a core.async channel of streaming messages.")

  (receive-response [this]
    "Get a core.async channel yielding the final response."))

;;; ============================================================================
;;; IAgentBackend Protocol
;;; ============================================================================

(defprotocol IAgentBackend
  "Protocol for agent backend lifecycle management."

  (backend-id [this]
    "Return keyword identifier for this backend.")

  (available? [this]
    "Check if this backend is ready for use.")

  (capabilities [this]
    "Return set of keyword capabilities this backend supports.")

  (execute! [this task opts]
    "Execute a one-shot task without a persistent session.")

  (connect! [this opts]
    "Create and return an IAgentSession.")

  (disconnect! [this session]
    "Disconnect and clean up an agent session."))

;;; ============================================================================
;;; IAgentTools Protocol
;;; ============================================================================

(defprotocol IAgentTools
  "Protocol for custom tool registration with an agent backend."

  (register-tool! [this session tool-spec]
    "Register a custom tool with the agent session.")

  (register-mcp-server! [this session server-config]
    "Register an MCP server with the agent session.")

  (list-tools [this session]
    "List tools available to the agent session."))

;;; ============================================================================
;;; IAgentPermissions Protocol
;;; ============================================================================

(defprotocol IAgentPermissions
  "Protocol for agent permission configuration."

  (set-permission-mode! [this session mode]
    "Set the permission mode for the agent session.")

  (set-permission-handler! [this session handler-fn]
    "Set a custom permission handler for the agent session."))

;;; ============================================================================
;;; ISAAOrchestrator Protocol (canonical in hive-mcp.protocols.saa; re-exported)
;;; ============================================================================

(def ISAAOrchestrator psaa/ISAAOrchestrator)
(def run-silence! psaa/run-silence!)
(def run-abstract! psaa/run-abstract!)
(def run-act! psaa/run-act!)
(def run-full-saa! psaa/run-full-saa!)

;;; ============================================================================
;;; NoopAgentSession (No-Op Fallback)
;;; ============================================================================

(defrecord NoopAgentSession [id created-at]
  IAgentSession

  (session-id [_] id)

  (query! [_ _prompt _opts]
    (let [ch (async/chan 1)]
      (async/put! ch {:type :error
                      :message "NoopAgentSession: No agent backend configured. Set one via set-agent-backend!"})
      (async/close! ch)
      ch))

  (interrupt! [_]
    {:success? false
     :errors ["NoopAgentSession: No agent backend configured."]})

  (receive-messages [_]
    (let [ch (async/chan)]
      (async/close! ch)
      ch))

  (receive-response [_]
    (let [ch (async/chan)]
      (async/close! ch)
      ch)))

(defn ->noop-session
  "Create a NoopAgentSession."
  ([] (->noop-session (str "noop-agent-session-" (System/currentTimeMillis))))
  ([id] (->NoopAgentSession id (java.time.Instant/now))))

;;; ============================================================================
;;; NoopAgentBackend (No-Op Fallback)
;;; ============================================================================

(def ^:private noop-msg "NoopAgentBackend: No agent backend configured. Set one via set-agent-backend!")

(defrecord NoopAgentBackend []
  IAgentBackend

  (backend-id [_] :noop)

  (available? [_] false)

  (capabilities [_] #{})

  (execute! [_ _task _opts]
    (let [ch (async/chan 1)]
      (async/put! ch {:type :error :message noop-msg})
      (async/close! ch)
      ch))

  (connect! [_ _opts]
    {:success? false
     :session nil
     :errors [noop-msg]})

  (disconnect! [_ _session]
    {:success? true
     :errors []})

  IAgentTools

  (register-tool! [_ _session _tool-spec]
    {:success? false
     :errors [noop-msg]})

  (register-mcp-server! [_ _session _server-config]
    {:success? false
     :errors [noop-msg]})

  (list-tools [_ _session]
    [])

  IAgentPermissions

  (set-permission-mode! [_ _session _mode]
    {:success? false
     :errors [noop-msg]})

  (set-permission-handler! [_ _session _handler-fn]
    {:success? false
     :errors [noop-msg]})

  psaa/ISAAOrchestrator

  (run-silence! [_ _session _task _opts]
    (let [ch (async/chan 1)]
      (async/put! ch {:type :error :message noop-msg})
      (async/close! ch)
      ch))

  (run-abstract! [_ _session _observations _opts]
    (let [ch (async/chan 1)]
      (async/put! ch {:type :error :message noop-msg})
      (async/close! ch)
      ch))

  (run-act! [_ _session _plan _opts]
    (let [ch (async/chan 1)]
      (async/put! ch {:type :error :message noop-msg})
      (async/close! ch)
      ch))

  (run-full-saa! [_ _session _task _opts]
    (let [ch (async/chan 1)]
      (async/put! ch {:type :error :message noop-msg})
      (async/close! ch)
      ch)))

;;; ============================================================================
;;; Active Implementation Management
;;; ============================================================================

(defonce ^:private active-agent-backend (atom nil))

(defn set-agent-backend!
  "Set the active agent backend implementation."
  [impl]
  {:pre [(satisfies? IAgentBackend impl)]}
  (reset! active-agent-backend impl)
  impl)

(defn get-agent-backend
  "Get the active agent backend, or NoopAgentBackend if none set."
  []
  (or @active-agent-backend
      (->NoopAgentBackend)))

(defn agent-backend-set?
  "Check if an agent backend is configured."
  []
  (some? @active-agent-backend))

(defn clear-agent-backend!
  "Clear the active agent backend."
  []
  (reset! active-agent-backend nil)
  nil)

;;; ============================================================================
;;; Utility Functions
;;; ============================================================================

(defn agent-backend?
  "Check if object implements IAgentBackend protocol."
  [x]
  (satisfies? IAgentBackend x))

(defn agent-session?
  "Check if object implements IAgentSession protocol."
  [x]
  (satisfies? IAgentSession x))

(defn enhanced?
  "Check if a non-noop agent backend is active."
  []
  (and (agent-backend-set?)
       (not (instance? NoopAgentBackend @active-agent-backend))))

(defn backend-capabilities
  "Get a summary of available agent backend capabilities."
  []
  (let [backend (get-agent-backend)]
    {:backend-id (backend-id backend)
     :enhanced? (enhanced?)
     :available? (available? backend)
     :capabilities (capabilities backend)}))
