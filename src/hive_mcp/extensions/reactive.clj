(ns hive-mcp.extensions.reactive
  "Late addon contributions reach the advertised MCP surface without a restart.

   Composite tools are built once at boot from whatever had been contributed
   by then, and the advertised inputSchema is assembled when the server's
   tool table is (re)built. A contribution made AFTER boot — an addon mounted
   by `hot inject`, a remount from `hot reload`, a live `contribute-commands!`
   from a REPL — therefore dispatched immediately but stayed invisible: absent
   from its composite, absent from tools/list, its schema-extensions never
   re-read. This namespace subscribes to the registry's contribution events
   and does, for the one tool that changed, the three things a boot does."
  (:require [hive-addon.protocol :as proto]
            [hive-mcp.addons.core :as addon-core]
            [hive-mcp.dns.result :refer [rescue]]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.tools.composite :as composite]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def composite-descriptions
  "Composite tools built from addon contributions, name -> description prefix.
   A whitelist: a contribution to a name outside it folds into that tool's
   core definition through build-merged-handler instead."
  {"analysis" "Code analysis"
   "overarch" "Architecture model"})

(defn rebuild-composite!
  "Rebuild and re-register the composite tool `tool-name` from its current
   contributions, when it is whitelisted. Mirrors the boot one-shot: a name
   with NO contributions gets no composite (and loses the one it had, so a
   retraction that empties a tool does not leave a help-only shell behind).
   Returns the tool-def, or nil."
  [tool-name]
  (when-let [desc (get composite-descriptions tool-name)]
    (if (empty? (ext/get-contributed-commands tool-name))
      (do (ext/deregister-tool! tool-name) nil)
      (let [t (composite/build-composite-tool tool-name desc)]
        (ext/register-tool! t)
        (rescue nil
                (when-let [reg-fn (requiring-resolve 'hive-mcp.agent.registry/register!)]
                  (reg-fn [t])))
        t))))

(defn redrain-schema-extensions!
  "Re-read every ACTIVE addon's (schema-extensions) into the registry — the
   map-shaped idiom (tool-name -> params); the DataScript-attribute sequence
   is not a tool schema. Returns the tool names touched."
  []
  (into []
        (comp (filter #(= :active (:state %)))
              (keep (fn [{:keys [name]}] (:addon (addon-core/get-addon-entry name))))
              (mapcat (fn [addon]
                        (let [exts (rescue nil (proto/schema-extensions addon))]
                          (when (map? exts)
                            (doseq [[tool-name props] exts]
                              (ext/register-schema! tool-name props))
                            (keys exts))))))
        (addon-core/list-addons)))

(defn refresh-server-tools!
  "Rebuild the running server's tool table, when a server is running.
   Returns the tool count, or nil when there is no server context yet."
  []
  (rescue nil
          (when-let [ctx-atom (some-> (requiring-resolve 'hive-mcp.server.core/server-context-atom)
                                      deref)]
            (when @ctx-atom
              ((requiring-resolve 'hive-mcp.server.routes/refresh-tools!) ctx-atom)))))

(defn refresh-surface!
  "Bring the advertised surface up to date after a contribution to
   `tool-name` (nil: no composite to rebuild): rebuild its composite, re-drain
   schema-extensions, refresh the server's tool table. Each leg is rescued on
   its own; returns what each did."
  [tool-name]
  {:composite    (some? (rescue nil (rebuild-composite! tool-name)))
   :schema-tools (rescue [] (redrain-schema-extensions!))
   :server-tools (refresh-server-tools!)})

(defn install!
  "Subscribe to the registry's contribution events. Idempotent."
  []
  (ext/add-contribution-listener!
   :reactive-surface
   (fn [{:keys [type tool-name addon-id]}]
     (let [out (refresh-surface! tool-name)]
       (log/debug "Contribution reached the surface"
                  {:type type :tool tool-name :addon addon-id :refresh out})))))

(defn uninstall!
  "Unsubscribe. For tests."
  []
  (ext/remove-contribution-listener! :reactive-surface))
