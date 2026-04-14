(ns hive-mcp.tools.consolidated.config
  "Consolidated Config CLI tool for managing hive-mcp configuration."
  (:require [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result]
            [hive-mcp.config.core :as config]
            [hive-mcp.config.schema :as schema]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; ── Pure Result-returning functions ───────────────────────────────────────────

(defn- get*
  [{:keys [key]}]
  (if (or (nil? key) (= "" key))
    (result/err :config/get {:message "Missing required parameter: key. Example: config get {\"key\": \"embeddings.ollama.host\"}"})
    (result/ok {:key key :value (config/get-config-value key)})))

(defn- set*
  [{:keys [key value]}]
  (if (or (nil? key) (= "" key))
    (result/err :config/set {:message "Missing required parameter: key. Example: config set {\"key\": \"embeddings.ollama.host\", \"value\": \"http://new:11434\"}"})
    (do
      (config/set-config-value! key value)
      (log/info "Config set:" key "=" value)
      (result/ok {:key key :value value :status "updated"}))))

(defn- list-config*
  [_params]
  (result/ok {:config (config/get-global-config)}))

(defn- reload*
  [_params]
  (let [loaded (config/load-global-config!)]
    (result/ok {:status "reloaded"
                :keys (vec (keys loaded))})))

(defn- path*
  "Return config file path info."
  [_params]
  (result/ok {:config-path (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn")
              :exists? (.exists (io/file
                                 (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn")))}))

(defn- validate*
  "Validate current config against schema. Optionally validate a section."
  [{:keys [key]}]
  (let [cfg (config/get-global-config)]
    (if (and key (not= key ""))
      ;; Validate specific section
      (let [section-kw (keyword key)]
        (try
          (let [value (get cfg section-kw)
                v (schema/validate-section section-kw value)]
            (result/ok (assoc v :section key)))
          (catch Exception e
            (result/err :config/validate {:message (ex-message e)}))))
      ;; Validate full config
      (result/ok (schema/validate-config cfg)))))

;; ── Public handlers (MCP boundary) ────────────────────────────────────────────

(defn handle-get
  "Read a config value by dotted key path."
  [params]
  (rb/result->mcp (rb/try-result :config/get-failed #(get* params))))

(defn handle-set
  "Set a config value at a dotted key path and persist to disk."
  [params]
  (rb/result->mcp (rb/try-result :config/set-failed #(set* params))))

(defn handle-list
  "List all configuration values."
  [params]
  (rb/result->mcp (rb/try-result :config/list-failed #(list-config* params))))

(defn handle-reload
  "Reload config from disk, merging with defaults."
  [params]
  (rb/result->mcp (rb/try-result :config/reload-failed #(reload* params))))

(defn handle-path
  "Show config file path."
  [params]
  (rb/result->mcp (rb/try-result :config/path-failed #(path* params))))

(defn handle-validate
  "Validate config against malli schema."
  [params]
  (rb/result->mcp (rb/try-result :config/validate-failed #(validate* params))))

(def handlers
  {:get      handle-get
   :set      handle-set
   :list     handle-list
   :reload   handle-reload
   :path     handle-path
   :validate handle-validate})

(def handle-config
  (make-cli-handler handlers))

(def tool-def
  {:name "config"
   :consolidated true
   :description "Manage ~/.config/hive-mcp/config.edn: get (read value at key path), set (write value at key path), list (show all config), reload (re-read from disk), path (show config file location), validate (check config against schema, optionally key=section). Use command='help' to list all."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string"
                                         :enum ["get" "set" "list" "reload" "path" "validate" "help"]
                                         :description "Config operation to perform"}
                              "key" {:type "string"
                                     :description "Dotted key path (e.g. \"embeddings.ollama.host\"). For validate: section name (e.g. \"memory\")"}
                              "value" {:description "Value to set (string, number, boolean, or object)"}}
                 :required ["command"]}
   :handler handle-config})

(def tools [tool-def])
