(ns hive-mcp.addons.terminal
  "ITerminalAddon protocol, re-exported from the standalone hive-addon leaf lib.

   The protocol moved to hive-addon.terminal so that a vessel addon can
   compile against the contract without compile-depending on hive-mcp, the
   same move IAddon made into hive-addon.protocol. This namespace keeps every
   historical qualified name (hive-mcp.addons.terminal/ITerminalAddon,
   /terminal-spawn!, ...) resolving for downstream callers.

   Host internals must require hive-addon.terminal directly.

   The protocol and method vars below are plain `def` ALIASES of the
   hive-addon originals. Do NOT turn them back into `defprotocol`: a second
   `defprotocol` mints a DISTINCT protocol, and every vessel implementing the
   original then fails `satisfies?` silently.

   ITerminalAddon is a companion protocol to IAddon (same pattern as
   IMcpBridge in hive-mcp.addons.mcp-bridge). Concrete terminal backends
   implement BOTH on the same reify. Method signatures mirror ILingStrategy
   exactly, so the terminal-registry can dispatch to addon-contributed
   backends transparently via the TerminalAddonStrategy adapter.

   See also:
   - hive-addon.terminal                         -- the protocol itself
   - hive-addon.protocol                         -- IAddon base protocol
   - hive-mcp.addons.mcp-bridge                  -- IMcpBridge companion
   - hive-mcp.agent.ling.strategy                -- ILingStrategy (mirrored)
   - hive-mcp.agent.ling.terminal-addon-strategy -- Adapter (Layer 2 bridge)
   - hive-mcp.agent.ling.terminal-registry       -- Registry (dispatch lookup)"
  (:require [hive-addon.terminal :as term]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; ITerminalAddon Protocol -- re-export from hive-addon.terminal
;; =============================================================================

(def ITerminalAddon term/ITerminalAddon)

(def terminal-id        term/terminal-id)
(def terminal-spawn!    term/terminal-spawn!)
(def terminal-dispatch! term/terminal-dispatch!)
(def terminal-status    term/terminal-status)
(def terminal-kill!     term/terminal-kill!)
(def terminal-interrupt! term/terminal-interrupt!)

;; =============================================================================
;; Predicates -- re-export
;; =============================================================================

(def terminal-addon? term/terminal-addon?)
