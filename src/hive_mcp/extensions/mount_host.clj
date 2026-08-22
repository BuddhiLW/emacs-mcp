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
            [hive-mcp.addons.core :as addon-core]
            [hive-addon.protocol :as proto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(defn addon-registry-host
  "Construct an IMountHost backed by the hive-mcp addon registry. opts may
   override the default addons.core seams (:reg-fn :init-fn :shutdown-fn
   :unreg-fn :registered-fn) for isolated tests.

   register! has REPLACE semantics, which a remount depends on — see its body."
  ([] (addon-registry-host {}))
  ([{:keys [reg-fn init-fn shutdown-fn unreg-fn registered-fn]
     :or {reg-fn        addon-core/register-addon!
          init-fn       addon-core/init-addon!
          shutdown-fn   addon-core/shutdown-addon!
          unreg-fn      addon-core/unregister-addon!
          registered-fn (fn [id] (:addon (addon-core/get-addon-entry id)))}}]
   (reify port/IMountHost
     (register! [this addon]
       ;; addons.core/register-addon! REFUSES a duplicate id: it warns and returns
       ;; {:success? false}, leaving the incumbent instance in place. This port
       ;; returns the host either way, so that refusal is invisible to the caller
       ;; — and a remount would then keep the STALE object and merely re-initialize
       ;; it, while reporting success. Dropping the old entry first is what makes
       ;; re-registration actually take.
       ;;
       ;; Safe during a remount: teardown! has already run shutdown-addon!, which
       ;; moves the entry to :registered, so unregister-addon! sees a non-:active
       ;; entry and will not shut the addon down a second time.
       (let [id (proto/addon-id addon)]
         (when (some? (registered-fn id))
           (unreg-fn id))
         (reg-fn addon))
       this)
     (init! [_ addon-id config] (init-fn addon-id config))
     (shutdown! [_ addon-id] (shutdown-fn addon-id) nil)
     (registered [_ addon-id] (registered-fn addon-id)))))
