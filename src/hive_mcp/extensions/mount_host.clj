(ns hive-mcp.extensions.mount-host
  "IMountHost adapter over the hive-mcp addon registry (addons.core).

   Lets the generic composer hive-addon.mount.compose drive hive-mcp's own
   addon lifecycle: register! -> addons.core/register-addon!, init! ->
   addons.core/init-addon! (which itself honors schema-extensions, tools and
   the declarative IAddon `hooks` seam), shutdown! -> addons.core/shutdown-addon!
   (no-nuke), registered -> the mounted instance for sibling injection.

   Seams are injectable (DIP) so the adapter is testable against a fake
   registry with no global state."
  (:require [hive-addon.mount.port :as port]
            [hive-mcp.addons.core :as addon-core]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn addon-registry-host
  "Construct an IMountHost backed by the hive-mcp addon registry. opts may
   override the default addons.core seams (:reg-fn :init-fn :shutdown-fn
   :registered-fn) for isolated tests."
  ([] (addon-registry-host {}))
  ([{:keys [reg-fn init-fn shutdown-fn registered-fn]
     :or {reg-fn        addon-core/register-addon!
          init-fn       addon-core/init-addon!
          shutdown-fn   addon-core/shutdown-addon!
          registered-fn (fn [id] (:addon (addon-core/get-addon-entry id)))}}]
   (reify port/IMountHost
     (register! [this addon] (reg-fn addon) this)
     (init! [_ addon-id config] (init-fn addon-id config))
     (shutdown! [_ addon-id] (shutdown-fn addon-id) nil)
     (registered [_ addon-id] (registered-fn addon-id)))))
