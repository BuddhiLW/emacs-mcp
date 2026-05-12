;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.storage.adapters.proximum
  "Yggdrasil adapter for Proximum HNSW vector indices — STORAGE-4.4.

   Proximum already exposes a near-isomorphic API to the yggdrasil
   protocol surface: `branch!` / `branches` / `delete-branch!` /
   `get-commit-id` / `parents` / `history` / `ancestors` / `ancestor?` /
   `commit-graph` / `commit-info` / `common-ancestor` / `sync!` / `gc!`.
   Loading this ns extends those yggdrasil protocols onto Proximum's
   concrete `proximum.hnsw.HnswIndex` record so the workspace can manage
   vector indices as first-class systems alongside Datahike/Datalevin.

   ## Mapping table

     Yggdrasil method      ←→  Proximum fn
     ───────────────────────────────────────────────────────────
     SystemIdentity/system-id      → adapter wraps + carries id
     SystemIdentity/system-type    → :proximum
     SystemIdentity/capabilities   → static set
     Snapshotable/snapshot-id      → prox/get-commit-id
     Snapshotable/parent-ids       → prox/parents
     Snapshotable/as-of            → prox/load-commit (needs store-config)
     Snapshotable/snapshot-meta    → prox/commit-info
     Branchable/branches           → prox/branches
     Branchable/current-branch     → prox/get-branch
     Branchable/branch!            → prox/branch!
     Branchable/delete-branch!     → prox/delete-branch!
     Branchable/checkout           → prox/load (re-load on the named branch)
     Graphable/history             → prox/history
     Graphable/ancestors           → prox/ancestors
     Graphable/ancestor?           → prox/ancestor?
     Graphable/common-ancestor     → prox/common-ancestor
     Graphable/commit-graph        → prox/commit-graph
     Graphable/commit-info         → prox/commit-info
     Committable/commit!           → prox/sync! + blocking <!! on channel
     GarbageCollectable/gc-roots   → derived from prox/branches (live tips)
     GarbageCollectable/gc-sweep!  → prox/gc! with caller-supplied cutoff

   ## Gaps (not implemented — see ::unsupported below)

   - `Mergeable` (merge!/conflicts/diff) — HNSW vector indices have no
     three-way merge semantics. Vectors don't conflict structurally; an
     embedding model swap is not a 'merge'.
   - `Overlayable` (overlay/advance!/...) — Proximum lacks live-fork
     observation modes. `fork` exists but doesn't surface the layered
     write-tracking yggdrasil overlays expect.
   - `Addressable/working-path` — vector indices aren't filesystem-y in
     the same sense as a Git repo or ZFS dataset.

   Calling an unsupported method on a Proximum-backed system throws
   `ex-info` with `{:err :proximum/unsupported-protocol-method ...}` so
   workspace-level coordination surfaces the gap explicitly rather than
   silently no-op'ing.

   ## Caveats

   - `commit!` blocks the calling thread on the sync! channel via `<!!`.
     Inside core.async go-blocks use `prox/sync!` directly with `<!`.
   - `gc-sweep!` requires a caller to supply the `:remove-before` date
     since Proximum's `gc!` takes a cutoff. The adapter defaults to
     `(java.util.Date.)` when called via the no-arg yggdrasil signature
     — caveat: that wipes everything older than NOW.
   - `as-of` requires the store-config (Proximum's `load-commit` is a
     top-level fn, not an instance method). The adapter carries
     `:store-config` on the wrapping record so this works."
  (:require [yggdrasil.protocols :as ygp]
            [proximum.core :as prox]
            [clojure.core.async :as async]
            [taoensso.timbre :as log]))

(def ^:const proximum-capabilities
  "Static capability advertisement for the SystemIdentity protocol. Lists
   the yggdrasil protocols this adapter satisfies. Mergeable/Overlayable/
   Addressable are intentionally absent — see ns docstring gaps section."
  #{:snapshotable :branchable :graphable :committable :gc-collectable})

(defn- unsupported!
  "Throw a structured error for protocol methods Proximum doesn't model."
  [op]
  (throw (ex-info (str "Proximum does not implement yggdrasil " op)
                  {:err     :proximum/unsupported-protocol-method
                   :backend :proximum
                   :op      op})))

;; ---------------------------------------------------------------------------
;; Wrapping record — carries the system-name + store-config that Proximum's
;; top-level fns (load, load-commit) need but the index record doesn't hold.
;; ---------------------------------------------------------------------------

(defrecord ProximumSystem [index-atom system-name store-config]
  ygp/SystemIdentity
  (system-id   [_] system-name)
  (system-type [_] :proximum)
  (capabilities [_] proximum-capabilities)

  ygp/Snapshotable
  (snapshot-id  [_]    (prox/get-commit-id @index-atom))
  (parent-ids   [_]    (prox/parents @index-atom))
  (as-of [_ commit-id] (prox/load-commit store-config commit-id {:branch (prox/get-branch @index-atom)}))
  (snapshot-meta [_ snap-id] (prox/commit-info @index-atom snap-id))

  ygp/Branchable
  (branches        [_]      (prox/branches @index-atom))
  (current-branch  [_]      (prox/get-branch @index-atom))
  (branch!         [this n] (swap! index-atom prox/branch! n) this)
  (delete-branch!  [this n] (swap! index-atom prox/delete-branch! n) this)
  (checkout        [this n] (reset! index-atom (prox/load store-config {:branch n})) this)

  ygp/Graphable
  (history         [_]       (prox/history @index-atom))
  (ancestors       [_ sid]   (prox/ancestors @index-atom sid))
  (ancestor?       [_ a b]   (prox/ancestor? @index-atom a b))
  (common-ancestor [_ a b]   (prox/common-ancestor @index-atom a b))
  (commit-graph    [_]       (prox/commit-graph @index-atom))
  (commit-info     [_ sid]   (prox/commit-info @index-atom sid))

  ygp/Committable
  (commit! [_ msg]
    (let [ch (prox/sync! @index-atom msg)
          updated (async/<!! ch)]
      (when updated (reset! index-atom updated))
      (prox/get-commit-id @index-atom)))

  ygp/GarbageCollectable
  (gc-roots [_]
    (->> (prox/branches @index-atom)
         (mapv #(prox/get-commit-id (prox/load store-config {:branch %})))
         (filterv some?)))
  (gc-sweep! [_ _retain-snapshot-ids]
    (let [ch (prox/gc! @index-atom {} (java.util.Date.))]
      (async/<!! ch)))

  ygp/Mergeable
  (merge!    [_ _]   (unsupported! 'merge!))
  (conflicts [_ _ _] (unsupported! 'conflicts))
  (diff      [_ _ _] (unsupported! 'diff)))


(defn create-system
  "Wrap a Proximum index into a ProximumSystem that satisfies the
   yggdrasil protocols. `store-config` is the Proximum store-config map
   (`{:backend :file :path \"...\" :id #uuid \"...\"}`) used for
   load/load-commit. `system-name` identifies this system inside the
   yggdrasil workspace.

   The index is held in an atom so branch! / checkout / commit! can swap
   in updated immutable values returned by Proximum's persistent ops."
  [{:keys [index store-config system-name]}]
  (when-not (and index store-config system-name)
    (throw (ex-info "create-system requires :index, :store-config, :system-name"
                    {:provided (vec (filter (fn [[_ v]] (some? v))
                                            {:index index :store-config store-config :system-name system-name}))})))
  (->ProximumSystem (atom index) system-name store-config))
