(ns hive-mcp.addons.protocol
  "IAddon protocol — re-exported from the standalone hive-addon leaf lib.

   The protocol itself moved to hive-addon.protocol so that any host — hive-mcp
   or an unrelated project — can DIP-load addons without those addons compile-
   depending on hive-mcp. This namespace keeps every historical qualified name
   (hive-mcp.addons.protocol/IAddon, /addon-id, /capabilities, …) resolving for
   the ~30 monorepo implementors and hive-mcp internals.

   The protocol and method vars below are plain `def` ALIASES of the hive-addon
   originals. Do NOT turn them back into `defprotocol` — a second `defprotocol`
   mints a DISTINCT protocol, and every record implementing the original then
   fails `satisfies?` silently.

   See also:
   - hive-mcp.addons.core       — Registry (register!, init!, shutdown!)
   - hive-mcp.addons.mcp-bridge — IMcpBridge companion protocol
   - hive-mcp.addons.manifest   — EDN manifest format + validation"
  (:require [hive-addon.protocol :as addon]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IAddon Protocol — re-export from hive-addon.protocol
;; =============================================================================

(def IAddon addon/IAddon)

(def addon-id          addon/addon-id)
(def addon-type        addon/addon-type)
(def capabilities      addon/capabilities)
(def initialize!       addon/initialize!)
(def shutdown!         addon/shutdown!)
(def tools             addon/tools)
(def schema-extensions addon/schema-extensions)
(def health            addon/health)
(def excluded-tools    addon/excluded-tools)
(def hooks             addon/hooks)

;; =============================================================================
;; Predicates + constants — re-export
;; =============================================================================

(def addon?               addon/addon?)
(def valid-addon-types    addon/valid-addon-types)
(def valid-addon-type?    addon/valid-addon-type?)
(def standard-capabilities addon/standard-capabilities)
(def health-statuses      addon/health-statuses)
(def healthy?             addon/healthy?)
(def degraded?            addon/degraded?)
(def down?                addon/down?)
