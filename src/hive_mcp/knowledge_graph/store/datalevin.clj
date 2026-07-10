(ns hive-mcp.knowledge-graph.store.datalevin
  "Datalevin implementation of IKGStore protocol."
  (:require [datalevin.core :as dtlv]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.datalevin-config :as dlc]
            [hive-mcp.knowledge-graph.conn-init :as ci]
            [hive-mcp.storage.recovery :as rec]
            [hive-mcp.dns.result :as r :refer [rescue]]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private value-type-map
  "Maps DataScript attribute names to Datalevin :db/valueType."
  {;; KG Edge attributes
   :kg-edge/id            :db.type/string
   :kg-edge/from          :db.type/string
   :kg-edge/to            :db.type/string
   :kg-edge/relation      :db.type/keyword
   :kg-edge/scope         :db.type/string
   :kg-edge/confidence    :db.type/double
   :kg-edge/created-by    :db.type/string
   :kg-edge/created-at    :db.type/instant
   :kg-edge/last-verified :db.type/instant
   :kg-edge/source-type   :db.type/keyword

   ;; Knowledge abstraction attributes
   :knowledge/abstraction-level :db.type/long
   :knowledge/grounded-at       :db.type/instant
   :knowledge/grounded-from     :db.type/string
   :knowledge/gaps              :db.type/keyword
   :knowledge/source-hash       :db.type/string
   :knowledge/source-type       :db.type/keyword

   ;; Disc (file state) attributes
   :disc/path         :db.type/string
   :disc/content-hash :db.type/string
   :disc/analyzed-at  :db.type/instant
   :disc/git-commit   :db.type/string
   :disc/project-id   :db.type/string
   :disc/last-read-at :db.type/instant
   :disc/read-count   :db.type/long
   :disc/certainty-alpha  :db.type/double
   :disc/certainty-beta   :db.type/double
   :disc/volatility-class :db.type/keyword
   :disc/last-observation :db.type/instant})

(defn translate-schema
  "Translate DataScript schema to Datalevin schema."
  [ds-schema]
  (reduce-kv
   (fn [acc attr props]
     (let [clean-props (dissoc props :db/doc)
           typed-props (if-let [vt (get value-type-map attr)]
                         (assoc clean-props :db/valueType vt)
                         clean-props)]
       (assoc acc attr typed-props)))
   {}
   ds-schema))

(defn datalevin-schema
  "Get the full Datalevin-compatible KG schema."
  []
  (translate-schema (schema/full-schema)))

(defn- validate-db-path!
  "Validate and ensure the database directory path exists."
  [db-path]
  (when (or (nil? db-path) (empty? db-path))
    (throw (ex-info "Datalevin db-path cannot be nil or empty"
                    {:db-path db-path})))
  (let [dir (io/file db-path)]
    (when-not (.exists (.getParentFile dir))
      (log/info "Creating Datalevin parent directory" {:path (.getParent dir)})
      (.mkdirs (.getParentFile dir))))
  db-path)

(defrecord DatalevinStore [conn-init db-path extra-schema recovery-policy cache-limit]
  kg/IKGStore

  (ensure-conn! [_this]
    ;; Single-init via IConnInit (ENGINE-L1.2a). Concurrent callers
    ;; block on the first open and observe the cached conn after,
    ;; eliminating the LMDB file-lock race that produced
    ;; `Resource temporarily unavailable`.
    (ci/open-once!
     conn-init
     (fn []
       (log/info "Initializing Datalevin KG store"
                 {:path db-path
                  :recovery-strategy (:strategy recovery-policy)})
       (validate-db-path! db-path)
       (let [base-schema (datalevin-schema)
             merged-schema (if extra-schema
                             (merge base-schema (translate-schema extra-schema))
                             base-schema)
             ;; HEAP-DL-CACHELIMIT — bound the Datalog index-cache LRU
             ;; (empty map == pre-fix 2-arity, so nil cache-limit is inert).
             conn-opts (cond-> {}
                         (some? cache-limit) (assoc :cache-limit cache-limit))]
         (log/debug "Datalevin schema translated"
                    {:attributes (count merged-schema)
                     :extra-attributes (when extra-schema (count extra-schema))
                     :cache-limit cache-limit})
         ;; ENGINE-L1.2 — wrap the open in heal-and-open!. Policy default
         ;; is `:throw` (preserves pre-L1.2 semantics); operators opt
         ;; into `:quarantine` for boot-time WAL self-heal.
         (rec/heal-and-open!
          {:policy recovery-policy :db-path db-path}
          #(dtlv/get-conn db-path merged-schema conn-opts))))))

  (transact! [this tx-data]
    (dtlv/transact! (kg/ensure-conn! this) tx-data))

  (query [this q]
    (dtlv/q q (dtlv/db (kg/ensure-conn! this))))

  (query [this q inputs]
    (apply dtlv/q q (dtlv/db (kg/ensure-conn! this)) inputs))

  (entity [this eid]
    (dtlv/entity (dtlv/db (kg/ensure-conn! this)) eid))

  (entid [this lookup-ref]
    (dtlv/entid (dtlv/db (kg/ensure-conn! this)) lookup-ref))

  (pull-entity [this pattern eid]
    (dtlv/pull (dtlv/db (kg/ensure-conn! this)) pattern eid))

  (eids-by-attr [this attr]
    ;; Datalevin exposes two Datalog indexes, :eav and :ave. For enumerating
    ;; all entities that have a given attribute, :ave (attribute, value, entity)
    ;; is the right one — it iterates attribute-first over LMDB and returns a
    ;; seq of datoms without loading values into memory eagerly.
    ;; `dtlv/datoms` returns a sequence over the index (LMDB cursor under the
    ;; hood); consuming it lazily is what keeps memory bounded.
    (map :e (dtlv/datoms (dtlv/db (kg/ensure-conn! this)) :ave attr)))

  (db-snapshot [this]
    (dtlv/db (kg/ensure-conn! this)))

  (reset-conn! [this]
    ;; NON-DESTRUCTIVE — close conn and reopen the SAME on-disk DB.
    ;; See AXIOM "Never NUKE Data". Destructive wipe lives on
    ;; IPersistentKGStore/delete-database!.
    (log/info "Reopening Datalevin KG store (non-destructive)" {:path db-path})
    (when-let [c (ci/snapshot conn-init)]
      (rescue nil (dtlv/close c)))
    (ci/clear! conn-init)
    (kg/ensure-conn! this))

  (close! [_this]
    (when-let [c (ci/snapshot conn-init)]
      (log/info "Closing Datalevin KG store" {:path db-path})
      (rescue nil (dtlv/close c))
      (ci/clear! conn-init)))

  kg/IPersistentKGStore

  (delete-database! [_this confirm]
    ;; DESTRUCTIVE — guard required.
    (when-not (= confirm :i-mean-it)
      (throw (ex-info "delete-database! requires confirm=:i-mean-it"
                      {:passed-confirm confirm
                       :hint "This call deletes the database directory from disk. Pass :i-mean-it explicitly to proceed."
                       :backend :datalevin
                       :db-path db-path})))
    (log/error "[storage/destruction-fired] Datalevin delete-database! invoked"
               {:backend :datalevin
                :db-path db-path
                :stacktrace (mapv str (.getStackTrace (Throwable.)))})
    (when-let [c (ci/snapshot conn-init)]
      (rescue nil (dtlv/close c)))
    (let [dir (io/file db-path)]
      (when (.exists dir)
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))
    (ci/clear! conn-init)
    (log/error "[storage/destruction-completed] Datalevin directory deleted"
               {:backend :datalevin :db-path db-path})
    nil))

(def ^:private fallback-db-path
  "Final fallback only — DatalevinKGConfig owns env/config.edn resolution.
   Mirrors datahike.clj's bare-default safety net."
  dlc/default-db-path)

(defn- resolve-typed-config
  "Resolve DatalevinKGConfig via hive-di. Returns map with :db-path and
   :cache-limit resolved across env > config.edn > default. Falls back to
   bare defaults if resolution itself errors (defensive — should not happen)."
  []
  (let [result (dlc/resolve-DatalevinKGConfig)]
    (if (r/ok? result)
      (:ok result)
      (do (log/warn "DatalevinKGConfig resolution failed; using bare defaults"
                    {:errors (:errors result)})
          {:db-path fallback-db-path
           :cache-limit dlc/default-cache-limit}))))

(defn create-store
  "Create a new Datalevin-backed graph store.
   Resolution order for :db-path and :cache-limit (each independently):
     1. Explicit caller arg
     2. DatalevinKGConfig (env > config.edn > default)

   `:cache-limit` (HEAP-DL-CACHELIMIT) bounds the Datalog index-cache LRU
   forwarded to `get-conn`; nil leaves upstream behaviour untouched.

   `recovery-policy` (ENGINE-L1.2) is forwarded to `heal-and-open!`:
     :strategy     :throw (default) | :audit | :quarantine
     :max-attempts pos-int (default 2)"
  [& [{:keys [db-path extra-schema recovery-policy cache-limit]}]]
  (rescue nil
          (let [typed         (resolve-typed-config)
                resolved-path (or db-path (:db-path typed))
                resolved-cl   (or cache-limit (:cache-limit typed))]
            (log/info "Creating Datalevin graph store"
                      {:path resolved-path
                       :extra-schema? (some? extra-schema)
                       :cache-limit resolved-cl
                       :recovery-strategy (:strategy recovery-policy)})
            (->DatalevinStore (ci/atom-conn-init) resolved-path extra-schema
                              recovery-policy resolved-cl))))
