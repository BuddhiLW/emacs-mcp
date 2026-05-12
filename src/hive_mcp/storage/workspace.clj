;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.storage.workspace
  "Yggdrasil workspace facade — STORAGE-4 scaffold.

   Wraps `yggdrasil.workspace` to coordinate snapshot/branch/merge across
   heterogeneous storage backends (Datahike, Datalevin, Proximum) with a
   shared Hybrid Logical Clock. See decision 20260507133442-017631c2 and
   the org.replikativ/yggdrasil library docs.

   Late-bound via `requiring-resolve` so this ns loads even before the
   yggdrasil dep is on the classpath at runtime — mirrors the proximum
   late-bind pattern in `slots/factory.clj`. Concrete adapters land in
   STORAGE-4.2 (Datahike), STORAGE-4.3 (Datalevin), STORAGE-4.4 (Proximum).

   Public API (placeholder — all ops degrade to nil + WARN if yggdrasil
   missing, never throw, never block boot):

   - `start-workspace!`    — open the persistent registry; idempotent
   - `stop-workspace!`     — close registry + cached system handles
   - `manage-system!`      — register a system (e.g. Datahike conn, Datalevin store)
   - `unmanage-system!`    — drop a system from the workspace
   - `coordinated-commit!` — snapshot ALL managed systems with shared HLC
   - `as-of-time`          — query the consistent state of all systems at a wall-clock instant
   - `workspace`           — return the current workspace handle (or nil if not started)

   Lifecycle: bucket 2 per axiom 20260424124618-1a104ec1 (in-process
   registry, hive-mcp owns the lifetime). Open at boot, close at shutdown."
  (:require [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

(defn- resolve-fn
  "Late-bound require — never throws."
  [sym]
  (r/rescue nil (requiring-resolve sym)))

(def ^:const default-registry-path
  "XDG-conformant default for the workspace's persistent registry."
  (str (System/getProperty "user.home") "/.local/share/hive-mcp/yggdrasil-registry"))

(def ^:const default-store-id
  "Stable UUID for the yggdrasil workspace's persistent registry. Pinned
   here so every JVM restart resolves to the SAME on-disk store. Changing
   this orphans the old registry — treat it like a Datahike :scope axiom
   (20260504191053-47106e10): fixed at deploy time, never auto-derived."
  #uuid "2c095999-1f4d-404f-9058-87057ad6c693")

(defonce ^:private workspace-state (atom nil))

(defn workspace
  "Return the active workspace handle, or nil if not started."
  []
  @workspace-state)

(defn started?
  []
  (some? @workspace-state))

(defn- create-workspace-fn []
  (resolve-fn 'yggdrasil.workspace/create-workspace))

(defn- close-workspace-fn []
  (resolve-fn 'yggdrasil.workspace/close!))

(defn- manage-fn []
  (resolve-fn 'yggdrasil.workspace/manage!))

(defn- unmanage-fn []
  (resolve-fn 'yggdrasil.workspace/unmanage!))

(defn- coordinated-commit-fn []
  (resolve-fn 'yggdrasil.workspace/coordinated-commit!))

(defn- as-of-time-fn []
  (resolve-fn 'yggdrasil.workspace/as-of-time))

(defn yggdrasil-available?
  "Report whether the yggdrasil library is on the classpath. Useful for
   degraded-mode signalling — every public op short-circuits + WARNs when
   this returns false."
  []
  (some? (create-workspace-fn)))

(defn start-workspace!
  "Open the workspace + persistent registry. Idempotent — returns the
   existing handle if already started. Returns nil + WARNs if yggdrasil
   isn't on the classpath (degraded mode — slot writes still work, but
   coordinated commits short-circuit to no-op).

   Default `:store-config` pins `:id` to `default-store-id` so restarts
   converge on the same registry. Tests pass an override map with their
   own (random-uuid) to keep state isolated."
  ([] (start-workspace! {:store-config {:backend :file
                                        :id     default-store-id
                                        :path   default-registry-path}}))
  ([opts]
   (or @workspace-state
       (if-let [create! (create-workspace-fn)]
         (try
           (let [ws (create! opts)]
             (reset! workspace-state ws)
             (log/info "yggdrasil workspace started"
                       {:registry-path (-> opts :store-config :path)
                        :store-id      (-> opts :store-config :id)})
             ws)
           (catch Throwable t
             (log/error t "yggdrasil workspace start! threw — degraded mode"
                        {:opts opts :ex-data (ex-data t)})
             nil))
         (do (log/warn "yggdrasil not on classpath — workspace remains in degraded mode")
             nil)))))

(defn stop-workspace!
  "Close the workspace + cached system handles. Idempotent. Non-destructive
   per axiom 20260428102603-3c7a5aff (close ≠ delete)."
  []
  (when-let [ws @workspace-state]
    (when-let [close! (close-workspace-fn)]
      (r/rescue nil (close! ws)))
    (reset! workspace-state nil)
    (log/info "yggdrasil workspace closed"))
  nil)

(defn manage-system!
  "Register `system` (a yggdrasil-protocol-satisfying record) under the
   workspace's coordination. Returns the system handle on success, nil on
   degraded mode."
  [system]
  (when-let [ws @workspace-state]
    (when-let [manage! (manage-fn)]
      (r/rescue nil (manage! ws system)))))

(defn unmanage-system!
  "Drop `system-name` from the workspace's managed set."
  [system-name]
  (when-let [ws @workspace-state]
    (when-let [unmanage! (unmanage-fn)]
      (r/rescue nil (unmanage! ws system-name)))))

(defn coordinated-commit!
  "Snapshot all managed systems with a shared HLC tick. `commit-fn-map` is
   {system-name (fn [system] snapshot-id)} per the yggdrasil.workspace
   API. Returns a coordinated-commit record, or nil if degraded."
  [commit-fn-map]
  (when-let [ws @workspace-state]
    (when-let [commit! (coordinated-commit-fn)]
      (r/rescue nil (commit! ws commit-fn-map)))))

(defn as-of-time
  "Query the consistent state of all managed systems at the given
   wall-clock millis. Returns a {[system-name branch-name] {:snapshot-id
   ...}} map or nil if degraded."
  [millis]
  (when-let [ws @workspace-state]
    (when-let [as-of! (as-of-time-fn)]
      (r/rescue nil (as-of! ws millis)))))