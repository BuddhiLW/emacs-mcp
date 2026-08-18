(ns hive-mcp.addons.terminal
  "ITerminalAddon protocol for addon-contributed terminal backends.

   Terminal addons extend the IAddon lifecycle with terminal-specific operations:
   - Spawn terminal processes (vterm buffers, headless subprocesses, etc.)
   - Dispatch tasks to running terminals
   - Query terminal status/liveness
   - Kill/interrupt running terminals

   Architecture:
     ITerminalAddon is a *companion* protocol to IAddon (same pattern as
     IMcpBridge in hive-mcp.addons.mcp-bridge). Concrete terminal backends
     implement BOTH IAddon and ITerminalAddon on the same reify.

     Method signatures mirror ILingStrategy exactly -- same arities and argument
     semantics -- so the terminal-registry can dispatch to addon-contributed
     backends transparently via the TerminalAddonStrategy adapter.

   See also:
   - hive-addon.protocol            -- IAddon base protocol
   - hive-mcp.addons.mcp-bridge     -- IMcpBridge companion (analogous pattern)
   - hive-mcp.agent.ling.strategy   -- ILingStrategy (method signatures mirrored)
   - hive-mcp.agent.ling.terminal-addon-strategy -- Adapter (Layer 2 bridge)
   - hive-mcp.agent.ling.terminal-registry       -- Registry (dispatch lookup)"
  (:require [hive-addon.protocol :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ITerminalAddon Protocol
;; =============================================================================

(defprotocol ITerminalAddon
  "Protocol for addon-contributed terminal backends.

   Implementors must also satisfy IAddon for addon lifecycle integration.
   The terminal backend is initialized during IAddon/initialize! and torn
   down during IAddon/shutdown!.

   Method signatures mirror ILingStrategy exactly -- same arities and argument
   semantics -- enabling the terminal-registry to dispatch to addon-contributed
   backends transparently."

  (terminal-id [this]
    "Return the keyword identifier for this terminal backend.
     Examples: :vterm, :opencode, :crush, :kitty, :tmux
     Used by the terminal-registry for backend selection and by ling spawn
     logic for mode resolution. Must be stable -- used as dispatch key.")

  (terminal-spawn! [this ctx opts]
    "Spawn a terminal/process using this backend's mechanism.
     Arguments:
       ctx  - Ling context map {:keys [id cwd presets project-id]}
       opts - Spawn options map {:keys [task kanban-task-id terminal presets]}
     Returns: String slave-id on success.
     Throws: ex-info on spawn failure with {:id str :error str}
     Mirrors: ILingStrategy/strategy-spawn! [this ling-ctx opts]")

  (terminal-dispatch! [this ctx task-opts]
    "Dispatch a task to a running terminal.
     Arguments:
       ctx       - Ling context map {:keys [id]}
       task-opts - Task options {:keys [task timeout-ms] :or {timeout-ms 60000}}
     Returns: true on successful dispatch.
     Throws: ex-info on dispatch failure with {:ling-id str :error str}
     Mirrors: ILingStrategy/strategy-dispatch! [this ling-ctx task-opts]")

  (terminal-status [this ctx ds-status]
    "Get terminal-specific liveness and status information.
     Arguments:
       ctx       - Ling context map {:keys [id]}
       ds-status - DataScript status map, may be nil or {:slave/status kw}
     Returns: Status map {:slave/id str :slave/status kw} or nil.
     May include backend-specific keys (e.g. :elisp-alive? for vterm).
     Mirrors: ILingStrategy/strategy-status [this ling-ctx ds-status]")

  (terminal-kill! [this ctx]
    "Terminate the terminal/process using this backend's mechanism.
     Arguments: ctx - Ling context map {:keys [id]}
     Returns: {:killed? true :id str} or {:killed? false :id str :reason kw}
     Mirrors: ILingStrategy/strategy-kill! [this ling-ctx]")

  (terminal-interrupt! [this ctx]
    "Interrupt the current query/task of a running terminal.
     Arguments: ctx - Ling context map {:keys [id]}
     Returns: {:success? true :ling-id str} or {:success? false :ling-id str :errors [str]}
     Mirrors: ILingStrategy/strategy-interrupt! [this ling-ctx]"))

;; =============================================================================
;; Predicates
;; =============================================================================

(defn terminal-addon?
  "Check if object implements both IAddon and ITerminalAddon.
   Analogous to bridge-addon? in hive-mcp.addons.mcp-bridge."
  [x]
  (and (satisfies? proto/IAddon x)
       (satisfies? ITerminalAddon x)))
