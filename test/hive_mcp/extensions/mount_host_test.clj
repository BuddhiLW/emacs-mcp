(ns hive-mcp.extensions.mount-host-test
  "Isolated tests for the IMountHost adapter over the hive-mcp addon registry.
   Injected fake seams + fake IAddon ctors — no global registry, no real addon
   init, no shared-store touch."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.extensions.mount-host :as mh]
            [hive-addon.mount.port :as port]
            [hive-addon.mount.compose :as compose]
            [hive-addon.protocol :as proto]
            [hive-dsl.result :as r]))

(defn- fake-addon [id]
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

;; Public ctors — compose resolves these via requiring-resolve (init-ns/init-fn).
(defn ctor-knowledge [_cfg] (fake-addon "hive.knowledge"))
(defn ctor-carto [_cfg] (fake-addon "hive.carto"))

(defn- fake-host
  "IMountHost over an atom-backed fake registry that records call order."
  [state]
  (mh/addon-registry-host
   {:reg-fn        (fn [a]
                     (swap! state update :order conj [:register (proto/addon-id a)])
                     (swap! state assoc-in [:reg (proto/addon-id a)] a)
                     {:success? true})
    :init-fn       (fn [id _cfg]
                     (swap! state update :order conj [:init id])
                     {:success? true :errors []})
    :shutdown-fn   (fn [id]
                     (swap! state update :order conj [:shutdown id])
                     {:success? true})
    :registered-fn (fn [id] (get-in @state [:reg id]))}))

(deftest adapter-satisfies-imounthost
  (is (satisfies? port/IMountHost (mh/addon-registry-host))))

(deftest adapter-delegates-register-init-shutdown
  (let [state (atom {:order [] :reg {}})
        host  (fake-host state)
        a     (fake-addon "x.one")]
    (is (identical? host (port/register! host a)) "register! returns host")
    (is (= "x.one" (proto/addon-id (port/registered host "x.one"))))
    (is (= {:success? true :errors []} (port/init! host "x.one" {})))
    (is (nil? (port/shutdown! host "x.one")) "shutdown! returns nil")
    (is (= [[:register "x.one"] [:init "x.one"] [:shutdown "x.one"]]
           (:order @state)))))

(deftest compose-drives-adapter-in-topo-order
  (testing "carto depends on knowledge -> knowledge registers+inits first"
    (let [specs  [{:addon/id "hive.carto"
                   :addon/init-ns "hive-mcp.extensions.mount-host-test"
                   :addon/init-fn "ctor-carto"
                   :addon/dependencies #{"hive.knowledge"}}
                  {:addon/id "hive.knowledge"
                   :addon/init-ns "hive-mcp.extensions.mount-host-test"
                   :addon/init-fn "ctor-knowledge"}]
          state  (atom {:order [] :reg {}})
          host   (fake-host state)
          result (compose/compose! specs [] host {})]
      (is (r/ok? result))
      (is (= ["hive.knowledge" "hive.carto"]
             (->> (:order @state) (filter #(= :init (first %))) (mapv second)))))))
