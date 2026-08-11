(ns hive-mcp.addons.headless
  "IHeadlessBackend / IHeadlessCapabilities — `def` aliases of
   hive-spi.addon.headless.

   The protocols themselves live in hive-spi so addon-contributed backends can
   implement them without compile-depending on hive-mcp. Every historical
   hive-mcp.addons.headless/* qualified name still resolves here.
   `satisfies?` must be called on the hive-spi vars, never on these aliases.

   Method arities mirror ILingStrategy, so headless-registry dispatches to an
   addon-contributed backend and an in-tree one identically.

   See also:
   - hive-addon.protocol                  -- IAddon base protocol
   - hive-mcp.addons.headless-caps        -- Optional capability protocols (ISP)
   - hive-mcp.agent.ling.strategy         -- ILingStrategy (arities mirrored)"
  (:require [hive-addon.protocol :as proto]
            [hive-spi.addon.headless :as headless]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(do
  (def IHeadlessBackend headless/IHeadlessBackend)
  (def headless-id headless/headless-id)
  (def headless-spawn! headless/headless-spawn!)
  (def headless-dispatch! headless/headless-dispatch!)
  (def headless-status headless/headless-status)
  (def headless-kill! headless/headless-kill!)
  (def headless-interrupt! headless/headless-interrupt!))

(do
  (def IHeadlessCapabilities headless/IHeadlessCapabilities)
  (def declared-capabilities headless/declared-capabilities))

(def headless-backend?
  "Check if object implements IHeadlessBackend.
   Does NOT require IAddon -- in-tree backends (OpenRouter) may implement
   IHeadlessBackend without being full addons."
  headless/headless-backend?)

(defn headless-addon?
  "Check if object implements both IAddon and IHeadlessBackend.
   Analogous to terminal-addon? in hive-mcp.addons.terminal."
  [x]
  (headless/headless-addon? proto/IAddon x))

(def capabilities
  "Get declared capabilities for a headless backend.
   Falls back to hive-spi.addon.headless/default-capabilities if
   IHeadlessCapabilities is not satisfied."
  headless/capabilities)
