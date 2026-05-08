;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.storage.adapters.datalevin
  "Yggdrasil adapter for Datalevin LMDB stores — STORAGE-4.3.

   Datalevin lacks a native Datalog-level time-travel API (no `as-of`,
   no `since`), but its KV layer exposes LMDB-level snapshot rotation
   plus a WAL watermark surface:

     dl/create-snapshot!    rotate `current` / `previous` LMDB snapshots
                            + update WAL snapshot-floor bookkeeping
     dl/list-snapshots      enumerate snapshots with metadata
     dl/txlog-watermarks    return WAL LSN watermarks
     dl/open-tx-log         read WAL records from LSN range

   This adapter maps Yggdrasil's Snapshotable onto those KV-level
   primitives. Snapshots are addressed by the WAL LSN that floors them
   (durable, monotonically increasing) — `snapshot-id` returns the
   stringified LSN. `snapshot-meta` joins the LSN with `list-snapshots`
   metadata.

   Branchable is implemented as a labelled-snapshot registry held in an
   in-process atom: `branch!` records `{branch-kw → snapshot-id}`,
   `checkout` switches the current-branch ref. Datalevin does NOT
   support divergent writes on non-:main branches without a CoW LMDB
   layer — branches here are read-tip labels, not independent history
   forks. Mutations always land on `:main`; non-:main branches only
   enable point-in-time read views once `as-of` is wired up.

   ## Mapping table

     Yggdrasil method              ←→  Datalevin
     ─────────────────────────────────────────────────────────────────
     SystemIdentity/system-id      → adapter-supplied
     SystemIdentity/system-type    → :datalevin
     SystemIdentity/capabilities   → static (snapshotable + branchable)
     Snapshotable/snapshot-id      → str of WAL LSN watermark
     Snapshotable/parent-ids       → set of prior LSNs from list-snapshots
     Snapshotable/as-of            → ::unsupported (no Datalog as-of in
                                     Datalevin 0.10.x — KV snapshot
                                     re-open path is the follow-up)
     Snapshotable/snapshot-meta    → list-snapshots entry by LSN
     Branchable/branches           → keys of branches-atom
     Branchable/current-branch     → @current-branch-atom
     Branchable/branch!            → swap! branches-atom assoc
     Branchable/delete-branch!     → swap! branches-atom dissoc
     Branchable/checkout           → reset! current-branch-atom

   ## Gaps (not implemented — see ::unsupported below)

   - `Snapshotable/as-of` — Datalevin lacks a Datalog `as-of` op. The
     KV `create-snapshot!` rotates physical LMDB snapshots that could
     be re-opened at a separate path, but exposing that as a value-
     semantic read-view requires a path-aware open-conn dance owned
     by the workspace, not the adapter. Tracked for follow-up.
   - `Mergeable` (merge!/conflicts/diff) — needs a Datalog-level
     three-way diff engine. Out of scope for 4.3.
   - `Overlayable` (overlay/advance!/...) — Datalevin has no live-fork
     observer mode at the LMDB layer.
   - `Graphable/Committable/GarbageCollectable/Addressable` —
     deferrable; the workspace coordinator can opt-in per call site.

   Calling an unsupported method throws `ex-info` with
   `{:err :datalevin/unsupported-protocol-method ...}` so workspace
   coordination surfaces the gap instead of silently no-op'ing.

   ## Caveats

   - `branches-atom` is **not durable** across JVM restarts; the
     workspace coordinator owns durable branch persistence (konserve-
     backed registry). Tests that exercise restart semantics need a
     workspace-level fixture, not this adapter alone.
   - `snapshot-id` is a stringified LSN — opaque to callers, but
     comparable lexicographically when zero-padded. Adapter does NOT
     pad; consumers who sort must coerce to long.
   - `create-snapshot!` is a writer-side op; calling it concurrently
     with active transactions is documented Datalevin behaviour
     (rotation is atomic at the WAL boundary)."
  (:require [yggdrasil.protocols :as ygp]
            [datalevin.core :as dl]
            [taoensso.timbre :as log]))

(def ^:const datalevin-capabilities
  "Static capability advertisement for SystemIdentity. Lists the
   yggdrasil protocols this adapter satisfies. Mergeable / Overlayable /
   Graphable / Committable / GarbageCollectable / Addressable are absent
   — see ns docstring gaps section."
  #{:snapshotable :branchable})

(defn- unsupported!
  "Throw a structured error for protocol methods Datalevin doesn't model
   (or that this adapter intentionally defers)."
  [op]
  (throw (ex-info (str "Datalevin adapter does not implement yggdrasil " op)
                  {:err     :datalevin/unsupported-protocol-method
                   :backend :datalevin
                   :op      op})))

(defn- kv-handle
  "Extract the KV store from a handle. Accepts:
     - a raw KV store (open-kv result) — passed straight through
     - a Datalog connection atom — pulls `:store` from `(dl/db conn)`
   The KV-level snapshot/WAL ops (create-snapshot!, list-snapshots,
   txlog-watermarks) all require the underlying KV handle, not the
   Datalog conn."
  [handle]
  (or (when (instance? clojure.lang.IDeref handle)
        (some-> handle dl/db :store))
      handle))

(defn- current-lsn
  "Read the current WAL committed-LSN watermark from the KV handle.
   Returns nil if the store wasn't opened with `:wal? true` (Datalevin
   guards txlog-watermarks behind that flag)."
  [handle]
  (try
    (some-> handle kv-handle dl/txlog-watermarks :committed-lsn)
    (catch Throwable t
      (log/debug t "txlog-watermarks unavailable — store likely opened without :wal?")
      nil)))

(defn- snapshot-entries
  "Return Datalevin's `list-snapshots` output coerced to a vector. nil-
   safe so callers don't have to guard."
  [handle]
  (or (try (vec (dl/list-snapshots (kv-handle handle))) (catch Throwable _ nil))
      []))

(defn- find-snapshot
  "Look up a snapshot entry whose `:lsn` matches `snap-id` (stringified
   long). Returns nil when not found."
  [handle snap-id]
  (let [target (try (Long/parseLong (str snap-id)) (catch Throwable _ nil))]
    (when target
      (some (fn [{:keys [lsn] :as e}]
              (when (= lsn target) e))
            (snapshot-entries handle)))))

;; ---------------------------------------------------------------------------
;; DatalevinSystem record — wraps either a raw KV store or a Datalog conn.
;; Branch-registry + current-branch are in-process atoms; the workspace
;; coordinator owns durable persistence (see ns docstring caveats).
;; ---------------------------------------------------------------------------

(defrecord DatalevinSystem [handle system-name branches-atom current-branch-atom]
  ygp/SystemIdentity
  (system-id    [_] system-name)
  (system-type  [_] :datalevin)
  (capabilities [_] datalevin-capabilities)

  ygp/Snapshotable
  (snapshot-id [_]
    (some-> (current-lsn handle) str))
  (parent-ids [_]
    ;; Snapshots in `list-snapshots` are LSN-ordered; "parents" of the
    ;; current state are all prior snapshot LSNs strictly < current LSN.
    (let [now (current-lsn handle)
          entries (snapshot-entries handle)]
      (->> entries
           (keep :lsn)
           (filter (fn [lsn] (and now (< lsn now))))
           (map str)
           set)))
  (as-of [_ _snap-id] (unsupported! 'as-of))
  (as-of [_ _snap-id _opts] (unsupported! 'as-of))
  (snapshot-meta [_ snap-id]
    (find-snapshot handle snap-id))
  (snapshot-meta [_ snap-id _opts]
    (find-snapshot handle snap-id))

  ygp/Branchable
  (branches [_]
    (-> @branches-atom keys set))
  (branches [_ _opts]
    (-> @branches-atom keys set))
  (current-branch [_]
    @current-branch-atom)
  (branch! [this name]
    (let [snap (some-> (current-lsn handle) str)]
      (swap! branches-atom assoc name snap)
      this))
  (branch! [this name from]
    (let [snap (cond
                 (keyword? from) (get @branches-atom from)
                 :else           (str from))]
      (swap! branches-atom assoc name snap)
      this))
  (branch! [this name from _opts]
    (ygp/branch! this name from))
  (delete-branch! [this name]
    (swap! branches-atom dissoc name)
    this)
  (delete-branch! [this name _opts]
    (ygp/delete-branch! this name))
  (checkout [this name]
    (when-not (contains? @branches-atom name)
      (throw (ex-info (str "Datalevin adapter: unknown branch " name)
                      {:err :datalevin/unknown-branch :branch name
                       :known (set (keys @branches-atom))})))
    (reset! current-branch-atom name)
    this)
  (checkout [this name _opts]
    (ygp/checkout this name))

  ;; --- Explicit unsupported surface (workspace coordinator MUST see ex-info,
  ;; not silent no-ops, when reaching for a protocol this backend can't honour)

  ygp/Mergeable
  (merge!    [_ _]   (unsupported! 'merge!))
  (conflicts [_ _ _] (unsupported! 'conflicts))
  (diff      [_ _ _] (unsupported! 'diff)))

(defn create-system
  "Wrap a Datalevin handle (raw KV store OR Datalog conn) into a
   DatalevinSystem satisfying yggdrasil Snapshotable + Branchable.

   `:handle`       — Datalevin KV store (open-kv result) or Datalog conn
                     atom (create-conn / get-conn result). The adapter
                     extracts the KV layer for snapshot/WAL ops.
   `:system-name`  — string id used by SystemIdentity/system-id.
   `:initial-branches` — optional map of {kw → snapshot-id}; defaults to
                     `{:main nil}` (snapshot-id resolves at first commit).

   Branch-registry + current-branch are in-process; durable persistence
   is the workspace coordinator's responsibility."
  [{:keys [handle system-name initial-branches]
    :or   {initial-branches {:main nil}}}]
  (when-not (and handle system-name)
    (throw (ex-info "create-system requires :handle and :system-name"
                    {:provided (cond-> #{}
                                 handle      (conj :handle)
                                 system-name (conj :system-name))})))
  (->DatalevinSystem handle
                     system-name
                     (atom initial-branches)
                     (atom (or (-> initial-branches keys first) :main))))
