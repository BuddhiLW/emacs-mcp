;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(ns hive-mcp.scripts.kg-datahike-rebuild
  "One-off rebuild of the KG datahike store, keeping only durable edges.

   Carto structural edges (both endpoints a UUID) live in the :carto datalevin
   slot and are regenerable by `codebase-map scan`; carto reads already come
   from :carto. They are dropped here. Retraction is infeasible in-heap
   (see memory 20260521153840-1e8a1b05), so we export the keep-set and
   import it into a fresh store.

   Run order (deliberate):
     1. (export-keep-edges! \"/tmp/kg-keep.edn\")   ; against the current store
     2. stop hive-mcp
     3. mv ~/.local/share/hive-mcp/datahike ~/.local/share/hive-mcp/datahike.bloated-bak
     4. start hive-mcp                              ; fresh empty datahike auto-created
     5. (import-keep-edges! \"/tmp/kg-keep.edn\")
     6. (verify)                                    ; snapshot count + carto smoke"
  (:require [hive-mcp.knowledge-graph.connection :as conn]
            [hive-mcp.knowledge-graph.protocol :as proto]
            [hive-mcp.events.core :as events]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]))

(def ^:private uuid-re
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn- uuid-id? [s]
  (and (string? s) (boolean (re-matches uuid-re s))))

(defn structural-edge?
  "True when both endpoints are UUIDs (carto-derived, regenerable)."
  [e]
  (and (uuid-id? (:kg-edge/from e)) (uuid-id? (:kg-edge/to e))))

(def ^:private edge-pull
  '[:kg-edge/id :kg-edge/from :kg-edge/to :kg-edge/relation
    :kg-edge/confidence :kg-edge/scope :kg-edge/source-type
    :kg-edge/created-at :kg-edge/last-verified :kg-edge/weight
    :kg-edge/verified])

(defn- heap-pct []
  (let [h (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))]
    (int (* 100.0 (/ (.getUsed h) (double (.getMax h)))))))

(defn export-keep-edges!
  "Stream durable (non-structural) edges to `out-path`, one EDN map per line.
   Application heap stays bounded: lazy scan + line-by-line write, holding one
   edge at a time. Returns {:scanned :kept :dropped :max-heap-pct}."
  [out-path]
  (with-open [w (io/writer out-path)]
    (let [kept (atom 0) dropped (atom 0) n (atom 0) maxh (atom 0)]
      (doseq [eid (conn/eids-by-attr :kg-edge/id)]
        (let [e (conn/pull-entity edge-pull eid)]
          (swap! n inc)
          (if (structural-edge? e)
            (swap! dropped inc)
            (do (.write w (pr-str (dissoc e :db/id)))
                (.write w "\n")
                (swap! kept inc))))
        (when (zero? (mod @n 100000))
          (.flush w)
          (swap! maxh max (heap-pct))
          (log/info "kg-rebuild export progress"
                    {:scanned @n :kept @kept :dropped @dropped :heap-pct (heap-pct)})))
      {:scanned @n :kept @kept :dropped @dropped :max-heap-pct @maxh})))

(defn import-keep-edges!
  "Chunked import of EDN edges (one map per line) into the CURRENT KG store.
   Run only against a FRESH store. Returns {:imported}."
  [in-path & {:keys [chunk] :or {chunk 2000}}]
  (let [imported (atom 0)]
    (with-open [r (io/reader in-path)]
      (doseq [batch (partition-all chunk (line-seq r))]
        (conn/transact-sync! (mapv edn/read-string batch))
        (swap! imported + (count batch))
        (when (zero? (mod @imported 50000))
          (log/info "kg-rebuild import progress" {:imported @imported}))))
    {:imported @imported}))

(defn verify
  "Post-import sanity: total edge count + structural leakage check."
  []
  (let [total (count (conn/eids-by-attr :kg-edge/id))
        leaked (->> (conn/eids-by-attr :kg-edge/id)
                    (map #(conn/pull-entity '[:kg-edge/from :kg-edge/to] %))
                    (filter structural-edge?)
                    (take 1)
                    count)]
    {:total-edges total :structural-leaked? (pos? leaked)}))

(defn- actual-store-path
  "Ground-truth on-disk path of the currently-open datahike store, read from
   its own resolved cfg (not a separate config lookup, which can disagree)."
  []
  (get-in (:cfg (proto/get-store)) [:store :path]))

(defn- silence-kg-events!
  "Register no-op handlers for KG lifecycle events. The server wires these via
   edges.stats-events at startup; this standalone JVM does not, and dispatch
   throws on an unregistered event. No-ops also skip the on-store-ready stats
   warm-up (a redundant full-table scan of the store we are about to replace)."
  []
  (let [noop (fn [_ _] {})]
    (doseq [e [:kg.store/ready :kg.edges/added :kg.edges/removed :kg.edges/scope-migrated]]
      (events/reg-event e [] noop))))

(defn rebuild!
  "exec-fn for `clj -X:kg-rebuild`. Run STANDALONE with hive-mcp STOPPED and a
   large heap. exec-args:
     :edn-path  scratch EDN file (default /tmp/kg-keep.edn)
     :confirm?  false (default) = export-only (inspect first);
                true = also move the bloated dir to a timestamped backup,
                recreate a fresh store, import the keep-set, verify.
   The original store is moved (never deleted) to <path>.bloated-bak-<ts>."
  [{:keys [edn-path confirm?] :or {edn-path "/tmp/kg-keep.edn"}}]
  (silence-kg-events!)
  (log/info "kg-rebuild: exporting keep-set" {:edn edn-path})
  (let [exp  (export-keep-edges! edn-path)   ; opens the store as a side effect
        path (actual-store-path)]
    (log/info "kg-rebuild: export done" (assoc exp :store-path path))
    (println "EXPORT:" exp "STORE-PATH:" path)
    (cond
      (not confirm?)
      (do (println "Dry-run (export only). Inspect" edn-path
                   "then re-run with :confirm? true to swap + import.")
          (assoc exp :phase :export-only :store-path path))

      (or (nil? path) (= "" path))
      (throw (ex-info "could not resolve open store path — refusing destructive swap"
                      {:store (some-> (proto/get-store) class .getName)}))

      :else
      (let [bak (str path ".bloated-bak-" (System/currentTimeMillis))]
        (log/info "kg-rebuild: closing store + moving dir" {:path path :bak bak})
        (conn/close!)
        (when-not (.renameTo (io/file path) (io/file bak))
          (throw (ex-info "datahike dir move failed" {:path path :bak bak})))
        (proto/clear-store!)
        (conn/set-backend! :datahike)
        (log/info "kg-rebuild: importing keep-set")
        (let [imp (import-keep-edges! edn-path)
              v   (verify)]
          (conn/close!)
          (shutdown-agents)
          (println "IMPORT:" imp "VERIFY:" v "BACKUP:" bak)
          {:phase :complete :export exp :import imp :verify v :backup bak})))))
