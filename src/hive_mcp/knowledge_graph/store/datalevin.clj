(ns hive-mcp.knowledge-graph.store.datalevin
  "Datalevin implementation of IKGStore protocol."
  (:require [datalevin.core :as dtlv]
            [hive-mcp.protocols.kg :as kg]
            [hive-mcp.knowledge-graph.schema :as schema]
            [hive-mcp.knowledge-graph.store.datalevin-config :as dlc]
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
   :disc/read-count   :db.type/long})

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

(defrecord DatalevinStore [conn-atom db-path extra-schema]
  kg/IKGStore

  (ensure-conn! [_this]
    (when (nil? @conn-atom)
      (log/info "Initializing Datalevin KG store" {:path db-path})
      (validate-db-path! db-path)
      (let [base-schema (datalevin-schema)
            merged-schema (if extra-schema
                            (merge base-schema (translate-schema extra-schema))
                            base-schema)]
        (log/debug "Datalevin schema translated"
                   {:attributes (count merged-schema)
                    :extra-attributes (when extra-schema (count extra-schema))})
        (reset! conn-atom (dtlv/get-conn db-path merged-schema))))
    @conn-atom)

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
    (when-let [c @conn-atom]
      (rescue nil (dtlv/close c)))
    (reset! conn-atom nil)
    (kg/ensure-conn! this))

  (close! [_this]
    (when-let [c @conn-atom]
      (log/info "Closing Datalevin KG store" {:path db-path})
      (rescue nil (dtlv/close c))
      (reset! conn-atom nil)))

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
    (when-let [c @conn-atom]
      (rescue nil (dtlv/close c)))
    (let [dir (io/file db-path)]
      (when (.exists dir)
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))
    (reset! conn-atom nil)
    (log/error "[storage/destruction-completed] Datalevin directory deleted"
               {:backend :datalevin :db-path db-path})
    nil))

(def ^:private fallback-db-path
  "Final fallback only — DatalevinKGConfig owns env/config.edn resolution.
   Mirrors datahike.clj's bare-default safety net."
  dlc/default-db-path)

(defn- resolve-typed-config
  "Resolve DatalevinKGConfig via hive-di. Returns map with :db-path resolved
   across env > config.edn > XDG default. Falls back to bare default if
   resolution itself errors (defensive — should not happen)."
  []
  (let [result (dlc/resolve-DatalevinKGConfig)]
    (if (r/ok? result)
      (:ok result)
      (do (log/warn "DatalevinKGConfig resolution failed; using bare default"
                    {:errors (:errors result)})
          {:db-path fallback-db-path}))))

(defn create-store
  "Create a new Datalevin-backed graph store.
   Resolution order for :db-path:
     1. Explicit caller arg
     2. DatalevinKGConfig (env > config.edn > XDG default)"
  [& [{:keys [db-path extra-schema]}]]
  (rescue nil
          (let [resolved-path (or db-path (:db-path (resolve-typed-config)))]
            (log/info "Creating Datalevin graph store" {:path resolved-path
                                                        :extra-schema? (some? extra-schema)})
            (->DatalevinStore (atom nil) resolved-path extra-schema))))
