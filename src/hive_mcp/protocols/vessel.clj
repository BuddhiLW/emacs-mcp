(ns hive-mcp.protocols.vessel
  "Protocol definitions for host environment vessels.

   A vessel abstracts the headed environment (Emacs, tmux, VS Code, web UI)
   behind a formal protocol. Vessels provide terminals, editors, delivery
   channels, REPLs, and — critically — agent context resolution.

   Pattern: Registry (like delivery_channel.clj).
   Multiple vessels can be active simultaneously (Emacs + tmux, Emacs + Web UI).

   Key addition over ad-hoc context resolution: `resolve-context` gives each
   vessel ownership of the agent-to-context mapping, replacing implicit fallbacks
   in messaging.clj and routes.clj."
  (:require [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IVessel Protocol
;;; ============================================================================

(defprotocol IVessel
  "Host environment providing headed capabilities.
   A vessel is analogous to a window manager (i3, XMonad) — it provides
   terminals, editor, grid layout, delivery channels, and REPLs.

   Implementations:
   - EmacsVessel      (vessel/emacs.clj)
   - NoopVessel       (this file, fallback)
   - [future: TmuxVessel, WebUIVessel, VSCodeVessel]"

  (vessel-id [this]
    "Return keyword identifier. Examples: :emacs, :tmux, :web-ui, :vscode")

  (capabilities [this]
    "Return set of provided capabilities.
     #{:terminal :editor :grid :delivery :repl}")

  (resolve-context [this agent-id]
    "Return {:project-id :cwd :session-id} for the given agent.
     The vessel knows where its agents live — this replaces ad-hoc
     ctx/current-directory fallbacks. Returns nil for unknown agents.")

  (addon [this capability]
    "Return the concrete impl for a capability keyword.
     :terminal -> ITerminalAddon
     :editor   -> IEditor
     :delivery -> IDeliveryChannel
     :grid     -> IGridManager (future)
     :repl     -> ICiderSession (future)
     Returns nil when capability is not available.")

  (initialize! [this config]
    "Initialize the vessel with configuration map. Called during server init.")

  (shutdown! [this]
    "Shut down the vessel and release resources."))

;;; ============================================================================
;;; Vessel Registry (Multiple Active Vessels)
;;; ============================================================================

(defonce ^:private slot
  (reg/multi-slot {:validate #(satisfies? IVessel %)}))

(defn register-vessel!
  "Register a vessel. Replaces any existing vessel with same id."
  [vessel]
  {:pre [(satisfies? IVessel vessel)]}
  (reg/reg-put! slot (vessel-id vessel) vessel))

(defn unregister-vessel!
  "Unregister a vessel by id."
  [id]
  (reg/reg-remove! slot id))

(defn get-vessel
  "Get a specific vessel by id, or nil."
  [id]
  (reg/reg-get slot id))

(defn get-vessels
  "Get all registered vessels as a seq."
  []
  (vals (reg/reg-snapshot slot)))

(defn clear-vessels!
  "Clear all registered vessels."
  []
  (reg/reg-clear! slot))

(defn resolve-agent-context
  "Query all registered vessels for agent context.
   First vessel that returns non-nil wins.
   Returns {:project-id :cwd :session-id} or nil."
  [agent-id]
  (some #(resolve-context % agent-id) (get-vessels)))

;;; ============================================================================
;;; NoopVessel (Fallback)
;;; ============================================================================

(defrecord NoopVessel []
  IVessel
  (vessel-id [_] :noop)
  (capabilities [_] #{})
  (resolve-context [_ _] nil)
  (addon [_ _] nil)
  (initialize! [_ _] nil)
  (shutdown! [_] nil))

(defn noop-vessel
  "Create a no-op vessel fallback."
  []
  (->NoopVessel))
