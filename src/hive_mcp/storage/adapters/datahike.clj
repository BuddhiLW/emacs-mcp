;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.storage.adapters.datahike
  "Yggdrasil adapter for Datahike connections — STORAGE-4.2.

   Datahike has first-class versioning (datahike.versioning + .writing)
   plus konserve-backed branch refs. This adapter wraps a Datahike conn
   and exposes Snapshotable, Branchable, and Mergeable directly onto
   those native primitives — no shim layer needed.

   Reference: clones-ref/yggdrasil/src/yggdrasil/adapters/datahike.clj
   The ref impl additionally satisfies Graphable + GarbageCollectable +
   commit-hook integration; STORAGE-4.2 ships only the three protocols
   the kanban task requires. Extending the surface (history walking,
   commit-graph, GC roots, native d/listen hooks) is a follow-up.

   ## Mapping table

     Yggdrasil method              ←→  Datahike
     ─────────────────────────────────────────────────────────────
     SystemIdentity/system-id      → adapter-supplied (or auto-derived
                                     from the store config :id)
     SystemIdentity/system-type    → :datahike
     SystemIdentity/capabilities   → static set
     Snapshotable/snapshot-id      → str of (commit-id-of db)
     Snapshotable/parent-ids       → set of str (parent-ids-of db)
     Snapshotable/as-of            → dv/commit-as-db store uuid
     Snapshotable/snapshot-meta    → :timestamp / :parent-ids / :branch
     Branchable/branches           → konserve `:branches` key
     Branchable/current-branch     → (-> @conn :config :branch)
     Branchable/branch!            → dv/branch!
     Branchable/delete-branch!     → dv/delete-branch!
     Branchable/checkout           → d/connect on cloned config
     Mergeable/merge!              → dv/merge! with computed tx-data
     Mergeable/conflicts           → [] (Datahike merges are additive)
     Mergeable/diff                → compute-branch-diff both ways

   ## Caveats

   - `checkout` returns a NEW DatahikeSystem wrapping a freshly-opened
     connection on the named branch. The original system is unchanged.
     Callers are responsible for closing the new conn when done.
   - `merge!` defaults to a structural diff between source and target
     when `opts` doesn't carry `:tx-data`. The tx-meta is forwarded
     unchanged so callers can track provenance.
   - `diff` returns a plain map shape; the reference impl uses
     `yggdrasil.types/DatahikeDiff` records for protocol-level
     interchange. If the workspace coordinator expects records, swap
     the constructor here without changing the public surface."
  (:require [yggdrasil.protocols :as ygp]
            [konserve.core :as k]
            [datahike.api :as d]
            [datahike.versioning :as dv]))

(def ^:const datahike-capabilities
  "Static capability advertisement for SystemIdentity. Ships
   Snapshotable + Branchable + Mergeable; Graphable / Overlayable /
   Watchable / Committable / GarbageCollectable / Addressable are
   deferred per STORAGE-4.2 scope."
  #{:snapshotable :branchable :mergeable})

;; ---------------------------------------------------------------------------
;; Internal helpers — match the reference impl's accessors so the body of
;; the protocol implementations stays small + auditable.
;; ---------------------------------------------------------------------------

(defn- store-of  [conn] (:store @conn))
(defn- db-of    [conn] @conn)
(defn- branch-of [conn] (get-in @conn [:config :branch]))
(defn- commit-id-of [db] (get-in db [:meta :datahike/commit-id]))
(defn- parent-ids-of [db] (get-in db [:meta :datahike/parents]))

(defn- ->uuid
  "Coerce a snapshot-id (already a UUID, or a string) into a UUID. Returns
   nil for unparseable inputs."
  [snap-id]
  (cond
    (uuid? snap-id) snap-id
    :else (try (parse-uuid (str snap-id)) (catch Throwable _ nil))))

(defn- compute-branch-diff
  "Return tx-data adding the datoms in source-db that are absent from
   target-db. Mirrors the reference impl — the Datalog query excludes
   :db/txInstant so transaction timestamps don't pollute the diff."
  [source-db target-db]
  (let [diff (d/q '[:find ?e ?a ?v
                    :in $ $2
                    :where
                    [$ ?e ?a ?v]
                    [(not= :db/txInstant ?a)]
                    (not [$2 ?e ?a ?v])]
                  source-db target-db)]
    (mapv (fn [[e a v]] [:db/add e a v]) diff)))

;; ---------------------------------------------------------------------------
;; DatahikeSystem record. Carries the conn + an optional system-name; the
;; konserve store, branch, and commit metadata are read live from the
;; connection so checkout/merge mutations stay in sync with the underlying
;; Datahike state.
;; ---------------------------------------------------------------------------

(defrecord DatahikeSystem [conn system-name]
  ygp/SystemIdentity
  (system-id [_]
    (or system-name
        (str "datahike:" (get-in @conn [:config :store :id]))))
  (system-type [_] :datahike)
  (capabilities [_] datahike-capabilities)

  ygp/Snapshotable
  (snapshot-id [_]
    (str (commit-id-of (db-of conn))))
  (parent-ids [_]
    (set (map str (parent-ids-of (db-of conn)))))
  (as-of [this snap-id] (ygp/as-of this snap-id nil))
  (as-of [_ snap-id _opts]
    (when-let [uuid (->uuid snap-id)]
      (dv/commit-as-db (store-of conn) uuid)))
  (snapshot-meta [this snap-id] (ygp/snapshot-meta this snap-id nil))
  (snapshot-meta [_ snap-id _opts]
    (when-let [uuid (->uuid snap-id)]
      (when-let [db (dv/commit-as-db (store-of conn) uuid)]
        {:snapshot-id (str (commit-id-of db))
         :parent-ids  (set (map str (parent-ids-of db)))
         :timestamp   (get-in db [:meta :datahike/updated-at])
         :branch      (get-in db [:config :branch])})))

  ygp/Branchable
  (branches [this] (ygp/branches this nil))
  (branches [_ _opts]
    (or (k/get (store-of conn) :branches nil {:sync? true}) #{}))
  (current-branch [_]
    (branch-of conn))
  (branch! [this name]
    (dv/branch! conn (branch-of conn) name)
    this)
  (branch! [this name from] (ygp/branch! this name from nil))
  (branch! [this name from _opts]
    (dv/branch! conn from name)
    this)
  (delete-branch! [this name] (ygp/delete-branch! this name nil))
  (delete-branch! [_ name _opts]
    (dv/delete-branch! conn name))
  (checkout [this name] (ygp/checkout this name nil))
  (checkout [_ name _opts]
    (let [branch-cfg  (assoc (:config @conn) :branch name)
          branch-conn (d/connect branch-cfg)]
      (->DatahikeSystem branch-conn system-name)))

  ygp/Mergeable
  (merge! [this source] (ygp/merge! this source {}))
  (merge! [this source opts]
    (let [store         (store-of conn)
          source-branch (when (keyword? source) source)
          parents       (if source-branch
                          #{source-branch}
                          #{(or (->uuid source)
                                (throw (ex-info "merge! source must be branch keyword or snapshot id"
                                                {:source source})))})
          tx-data       (or (:tx-data opts)
                            (when source-branch
                              (compute-branch-diff
                               (dv/branch-as-db store source-branch)
                               (db-of conn)))
                            [])]
      (dv/merge! conn parents tx-data (:tx-meta opts))
      this))
  (conflicts [this a b] (ygp/conflicts this a b nil))
  (conflicts [_ _a _b _opts]
    ;; Datahike merges are additive (datoms either match or don't);
    ;; structural conflicts only arise via :db.unique/identity violations
    ;; which surface at transact time, not as a precomputed conflict set.
    [])
  (diff [this a b] (ygp/diff this a b nil))
  (diff [_ a b _opts]
    (let [store      (store-of conn)
          resolve-db (fn [x]
                       (cond
                         (keyword? x) (dv/branch-as-db store x)
                         :else        (when-let [u (->uuid x)]
                                        (dv/commit-as-db store u))))
          db-a (resolve-db a)
          db-b (resolve-db b)]
      (if (and db-a db-b)
        (let [added   (compute-branch-diff db-b db-a)
              removed (compute-branch-diff db-a db-b)]
          {:from a :to b
           :added added :removed removed
           :stats {:added-datoms    (count added)
                   :removed-datoms  (count removed)
                   :entities-touched (count (into (set (map second added))
                                                  (map second removed)))}})
        {:err :datahike/diff-unresolved
         :reason "Could not resolve one of the branch/snapshot refs"
         :a a :b b}))))

(defn create-system
  "Wrap an existing Datahike connection into a DatahikeSystem satisfying
   yggdrasil Snapshotable + Branchable + Mergeable.

   `:conn`        — Datahike connection atom (`d/connect` result).
   `:system-name` — string id used by SystemIdentity/system-id; when
                    omitted the adapter derives one from the store
                    config `:id` (e.g. \"datahike:<uuid>\")."
  [{:keys [conn system-name]}]
  (when-not conn
    (throw (ex-info "create-system requires :conn" {:provided #{}})))
  (when (and system-name (not (string? system-name)))
    (throw (ex-info ":system-name must be a string" {:provided system-name})))
  (->DatahikeSystem conn system-name))
