(ns hive-mcp.protocols.vessel
  "IVessel `def` aliases of hive-addon.vessel, plus the host's vessel registry.

   The protocol itself lives in hive-addon so a vessel can implement it without
   compile-depending on this host: a reify resolves its protocol symbol at
   compile time, so a vessel that named this namespace could not load without
   hive-mcp on the classpath. Every historical hive-mcp.protocols.vessel/*
   qualified name still resolves here.

   The REGISTRY stays here. The host owns the set of active vessels; the
   contract does not.

   A vessel abstracts the headed environment (Emacs, tmux, VS Code, web UI)
   behind a formal protocol. Vessels provide terminals, editors, delivery
   channels, REPLs, and agent context resolution: `resolve-context` gives each
   vessel ownership of the agent-to-context mapping, replacing implicit
   fallbacks in messaging.clj and routes.clj.

   Multiple vessels can be active simultaneously (Emacs + tmux, Emacs + Web UI).

   See also:
   - hive-addon.vessel   -- IVessel itself
   - hive-addon.terminal -- ITerminalAddon, returned by (addon v :terminal)"
  (:require [hive-addon.vessel :as vessel]
            [hive-mcp.protocols.registry :as reg]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================================
;;; IVessel Protocol
;;; ============================================================================

;; Implementations: EmacsVessel (vessel/emacs.clj), NoopVessel (below),
;; TmuxVessel (hive-tmux).
(do
  (def IVessel vessel/IVessel)
  (def vessel-id vessel/vessel-id)
  (def capabilities vessel/capabilities)
  (def resolve-context vessel/resolve-context)
  (def addon vessel/addon)
  (def initialize! vessel/initialize!)
  (def shutdown! vessel/shutdown!))

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
