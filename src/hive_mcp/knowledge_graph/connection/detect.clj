(ns hive-mcp.knowledge-graph.connection.detect
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [hive-mcp.config.core :as config]
            [hive-mcp.knowledge-graph.scope :as scope]
            [taoensso.timbre :as log]))

(declare walk-hierarchy-for-kg-backend detect-backend detect-writer-config)

(defn walk-hierarchy-for-kg-backend
  "Walk up .hive-project.edn hierarchy to find :kg-backend.
   Parent is more authoritative than child — first match walking UP wins.
   Returns keyword or nil."
  []
  (r/rescue nil
            (let [cwd (System/getProperty "user.dir")
                  home (System/getProperty "user.home")]
              (loop [dir (io/file cwd)
               ;; Collect configs child→parent, then reverse for parent-first
                     configs []]
                (cond
                  (nil? dir) nil
                  (= (.getAbsolutePath dir) home)
            ;; Check home dir then stop
                  (let [all-configs (if-let [cfg (scope/read-direct-project-config (.getAbsolutePath dir))]
                                      (conj configs cfg)
                                      configs)
                  ;; Parent-first: last found = most ancestral = highest authority
                        parent-first (reverse all-configs)]
                    (some :kg-backend parent-first))

                  :else
                  (let [cfg (scope/read-direct-project-config (.getAbsolutePath dir))]
                    (recur (.getParentFile dir)
                           (if cfg (conj configs cfg) configs))))))))

(defn detect-backend
  "Detect the desired KG backend from configuration sources.

   Priority (highest → lowest):
   0. `hive.kg.backend` system property — explicit JVM override. The :test
      aliases set -Dhive.kg.backend=datascript so cold test JVMs use an
      ephemeral in-memory store and never open (and lock-contend) a prod
      file backend. See axiom 20260122235103-7151cc29.
   1. .hive-project.edn hierarchy (parent > child > grandchild)
   2. HIVE_KG_BACKEND env var (explicit override)
   3. config.edn :services.kg.backend (global default)
   4. Fallback: config/default-kg-backend"
  []
  (let [prop-backend (some-> (System/getProperty "hive.kg.backend") keyword)
        hierarchy-backend (walk-hierarchy-for-kg-backend)
        env-backend (some-> (System/getenv "HIVE_KG_BACKEND") keyword)
        config-backend (config/get-service-value :kg :backend :parse keyword)
        backend (or prop-backend hierarchy-backend env-backend config-backend
                    config/default-kg-backend)]
    (log/info "KG backend detection"
              {:property prop-backend
               :hierarchy hierarchy-backend
               :env env-backend
               :config config-backend
               :selected backend})
    backend))

(defn detect-writer-config
  "Detect the writer backend config from :services.kg.writer in config.edn.
   Returns nil for :self (local) or the writer map for remote backends.

   Example config.edn:
     {:services {:kg {:backend :datahike
                      :writer {:backend :datahike-server
                               :url \"http://localhost:4444\"
                               :token \"your-token\"}}}}

   Or for kabel:
     {:services {:kg {:backend :datahike
                      :writer {:backend :kabel
                               :peer-id #uuid \"aaaa...\"
                               :local-peer <peer-atom>}}}}"
  []
  (r/rescue nil
            (let [writer-cfg (config/get-service-value :kg :writer)]
              (when (and (map? writer-cfg)
                         (not= :self (:backend writer-cfg)))
                (log/info "KG writer config detected" {:writer writer-cfg})
                writer-cfg))))
