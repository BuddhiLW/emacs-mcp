;; Scratch verification for MQ-ADOPT safe-slice (not committed to prod paths).
;; Runs in a cold JVM with local.deps.edn override so hive-addon.mount.* is on
;; the classpath. Fakes only — no real addon init, no shared-store touch.
(ns mount-verify
  (:require [hive-mcp.extensions.mount-host :as mh]
            [hive-addon.mount.port :as port]
            [hive-addon.mount.compose :as compose]
            [hive-addon.mount.boundary :as boundary]
            [hive-addon.protocol :as proto]
            [hive-dsl.result :as r]))

(defn fake-addon [id]
  (reify proto/IAddon
    (addon-id [_] id)
    (addon-type [_] :native)
    (capabilities [_] #{:tools})
    (initialize! [_ _cfg] {:success? true :errors []})
    (shutdown! [_] nil)
    (tools [_] [])
    (excluded-tools [_] #{})
    (schema-extensions [_] {})
    (health [_] {:status :ok})
    (hooks [_] {})))

(defn ctor-knowledge [_cfg] (fake-addon "hive.knowledge"))
(defn ctor-carto [_cfg] (fake-addon "hive.carto"))

(defn fake-host [state]
  (mh/addon-registry-host
   {:reg-fn        (fn [a] (swap! state update :order conj [:register (proto/addon-id a)])
                     (swap! state assoc-in [:reg (proto/addon-id a)] a) {:success? true})
    :init-fn       (fn [id _cfg] (swap! state update :order conj [:init id]) {:success? true :errors []})
    :shutdown-fn   (fn [id] (swap! state update :order conj [:shutdown id]) {:success? true})
    :registered-fn (fn [id] (get-in @state [:reg id]))}))

;; 1) adapter delegation order
(def s1 (atom {:order [] :reg {}}))
(def h1 (fake-host s1))
(def a1 (fake-addon "x.one"))
(port/register! h1 a1)
(def reg1 (port/registered h1 "x.one"))
(def init1 (port/init! h1 "x.one" {}))
(port/shutdown! h1 "x.one")

;; 2) compose! drives adapter in topo order (carto depends on knowledge)
(def specs
  [{:addon/id "hive.carto" :addon/init-ns "mount-verify"
    :addon/init-fn "ctor-carto" :addon/dependencies #{"hive.knowledge"}}
   {:addon/id "hive.knowledge" :addon/init-ns "mount-verify" :addon/init-fn "ctor-knowledge"}])
(def s2 (atom {:order [] :reg {}}))
(def h2 (fake-host s2))
(def compose-res (compose/compose! specs [] h2 {}))
(def init-order (->> (:order @s2) (filter #(= :init (first %))) (mapv second)))

;; 3) real-manifest discovery + ordering (pure; no ctor calls)
(def disc (boundary/discover-specs))
(def real-ids (mapv :addon/id (:specs disc)))
(def plan-res (compose/compose-plan (:specs disc) []))
(def real-order (when (r/ok? plan-res) (mapv :addon/id (:ordered (:plan (:ok plan-res))))))
(defn idx [coll x] (.indexOf coll x))

(println "=== MQ-ADOPT safe-slice verification ===")
(println "1 adapter satisfies IMountHost:" (satisfies? port/IMountHost h1))
(println "1 register returns host:" (identical? h1 (port/register! h1 (fake-addon "x.two"))))
(println "1 registered returns addon:" (= "x.one" (proto/addon-id reg1)))
(println "1 init result:" init1)
(println "1 delegation order:" (:order @s1))
(println "2 compose! ok?:" (r/ok? compose-res))
(println "2 init topo order:" init-order)
(println "2 knowledge-before-carto:" (= ["hive.knowledge" "hive.carto"] init-order))
(println "3 discovered real ids:" real-ids)
(println "3 real solved order:" real-order)
(println "3 real knowledge-before-carto:"
         (boolean (and real-order
                       (contains? (set real-order) "hive.knowledge")
                       (contains? (set real-order) "hive.carto")
                       (< (idx real-order "hive.knowledge") (idx real-order "hive.carto")))))
(println "PROOF_OK:"
         (boolean (and (satisfies? port/IMountHost h1)
                       (= "x.one" (proto/addon-id reg1))
                       (r/ok? compose-res)
                       (= ["hive.knowledge" "hive.carto"] init-order))))
(shutdown-agents)
