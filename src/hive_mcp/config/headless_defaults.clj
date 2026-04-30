(ns hive-mcp.config.headless-defaults
  "Typed defaults for the :headless spawn-mode, resolved via hive-di.

   The :headless spawn-mode is the abstract Iinterface; concrete backends
   (e.g. :hive-agent, :openrouter, :claude-sdk) are contributed by addons
   that register themselves under their own keyword in
   `hive-mcp.agent.ling.headless-registry` during their initialize!
   lifecycle (typically via META-INF/hive-addons/*.edn auto-discovery).

   This namespace contains *no* literal reference to any concrete backend
   keyword — the operator names them as inert data in
   `~/.config/hive-mcp/config.edn` under `[:headless :default-backend]`.

   Three-tier resolution for :default-backend (highest precedence first):
     1. config.edn  [:headless :default-backend]   (operator decision)
     2. env var      HIVE_HEADLESS_DEFAULT_BACKEND  (deployment override)
     3. :auto                                       (sentinel literal)

   The :auto sentinel means \"fall through to
   `headless-registry/best-headless-for-provider` for the requested
   provider\". Concrete keyword values (e.g. `:hive-agent`) are looked up
   directly in the registry and used if registered, ignored otherwise."
  (:require [hive-di.core :refer [defconfig env]]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:const auto-sentinel
  "Sentinel value meaning \"resolve via best-headless-for-provider\"."
  :auto)

(defconfig HeadlessDefaultsConfig
  :default-backend (env "HIVE_HEADLESS_DEFAULT_BACKEND"
                        :default :auto
                        :type :keyword
                        :doc "Headless backend keyword. :auto = registry-driven preference per provider; concrete keyword = direct lookup."))

(defn- config-edn-value
  "Read a value at `path` from ~/.config/hive-mcp/config.edn via
   hive-mcp.config.core/get-in-config. Returns nil if hive-mcp config
   is not loaded or the path is absent. Never throws."
  [path]
  (try
    (when-let [getter (requiring-resolve 'hive-mcp.config.core/get-in-config)]
      (getter path))
    (catch Throwable _ nil)))

(defn resolve!
  "Resolve HeadlessDefaultsConfig (env + literal) with optional overrides.
   Returns the resolved map directly. Throws on resolution failure."
  ([] (resolve! {}))
  ([overrides]
   (let [result (resolve-HeadlessDefaultsConfig overrides)]
     (if (r/ok? result)
       (:ok result)
       (throw (ex-info "HeadlessDefaultsConfig resolution failed"
                       {:result result :overrides overrides}))))))

(defn default-backend
  "Resolved default backend keyword via three-tier precedence:
     config.edn [:headless :default-backend] > env > defconfig literal.

   May return the :auto sentinel — callers should distinguish via `auto?`
   and delegate to registry-driven preference when appropriate."
  []
  (let [edn-val (config-edn-value [:headless :default-backend])]
    (if (keyword? edn-val)
      edn-val
      (:default-backend (resolve!)))))

(defn auto?
  "True when configured default backend is the :auto sentinel."
  [backend-kw]
  (= auto-sentinel backend-kw))
