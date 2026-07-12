(ns hive-mcp.config.test-support
  "Config fixtures: bind an EDN-declared config so a test never reads the
   developer's ~/.config/hive-mcp.

   `with-config` takes the config a test wants to assert against. `with-edn-config`
   loads it from a resource. `with-no-config` binds the no-op source — any code
   that silently depends on ambient config fails visibly under it, instead of
   quietly passing on one machine and failing on another."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-mcp.config.core :as config]
            [hive-mcp.config.source :as src]))

(defn read-edn-resource
  "Load an EDN config fixture from the test classpath."
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))
    (throw (ex-info "config fixture not found on classpath"
                    {:resource resource-path}))))

(defn config-source
  "A mutable in-memory source seeded with `config`. Mutable because the code
   under test legitimately writes routes (configure-defaults! flips them) — the
   writes must land somewhere the test can observe, and nowhere else."
  [config]
  (src/atom-source (atom config) config))

(defn with-config*
  "Run `f` with `config` bound as the effective config."
  [config f]
  (binding [config/*config-source* (config-source config)]
    (f)))

(defmacro with-config
  "Bind `config` as the effective config for the body."
  [config & body]
  `(with-config* ~config (fn [] ~@body)))

(defmacro with-edn-config
  "Bind the EDN fixture at `resource-path` as the effective config."
  [resource-path & body]
  `(with-config* (read-edn-resource ~resource-path) (fn [] ~@body)))

(defmacro with-no-config
  "Bind the empty (no-op) source: every config lookup misses."
  [& body]
  `(binding [config/*config-source* (src/empty-source)]
     ~@body))

(defn edn-config-fixture
  "clojure.test fixture binding an EDN resource as the effective config.

     (use-fixtures :each (edn-config-fixture \"embedder_routing_fixture.edn\"))"
  [resource-path]
  (fn [f]
    (with-config* (read-edn-resource resource-path) f)))
