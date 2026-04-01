(ns hive-mcp.config.resolve
  "Pure config value resolution — mode-aware lookups, env fallback, secrets.
   Promote layer: takes a config map (or rules), returns resolved values."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :refer [rescue]]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Service Resolution
;; =============================================================================

(defn get-service-value
  "Get a field from service config with mode-aware resolution and env fallback.
   Takes config map explicitly — no global state access."
  [config service-key field-key & {:keys [env parse default]}]
  (let [svc-cfg (get-in config [:services service-key])
        mode (get svc-cfg :mode :local)
        config-val (get svc-cfg field-key)
        ;; When :mode is :local and requesting :host, prefer localhost defaults
        ;; When :mode is :remote, use the configured :host/:port as-is
        effective-val (if (and (= mode :local)
                               (#{:host :url} field-key)
                               (nil? config-val))
                        nil
                        config-val)
        env-val (when env
                  (when-let [raw (System/getenv env)]
                    (if parse (parse raw) raw)))]
    (or effective-val env-val default)))

;; =============================================================================
;; Secrets
;; =============================================================================

(defn- resolve-pass
  "Resolve a secret via `pass show <path>`. Returns trimmed stdout or nil."
  [pass-path]
  (rescue nil
    (let [{:keys [exit out]} (shell/sh "pass" "show" pass-path)]
      (when (zero? exit)
        (str/trim (first (str/split-lines (str out))))))))

(defn- resolve-secret-value
  "Resolve a secret value that may be a pass: reference or plain string.
   - \"pass:path/to/secret\"  → resolved via `pass show`
   - plain string             → returned as-is
   - nil/empty                → nil"
  [v]
  (cond
    (nil? v) nil
    (not (string? v)) v
    (str/blank? v) nil
    (str/starts-with? v "pass:") (resolve-pass (subs v 5))
    :else v))

(defn get-secret
  "Return a secret from config or env var fallback.
   Config values prefixed with \"pass:\" are resolved via password-store.
   Resolution order: config value → env var.
   Takes config map explicitly — no global state access."
  [config secret-key]
  (let [config-val (get-in config [:secrets secret-key])
        env-name   (-> (name secret-key)
                       (str/replace "-" "_")
                       (str/upper-case))
        resolved   (resolve-secret-value config-val)]
    (or resolved (System/getenv env-name))))

;; =============================================================================
;; Parent Rules
;; =============================================================================

(defn get-parent-for-path
  "Resolve parent-id for a directory path via parent-rules.
   Takes rules vector explicitly — no global state access."
  [parent-rules directory-path]
  (when directory-path
    (let [norm-path (if (.endsWith (str directory-path) "/")
                      (str directory-path)
                      (str directory-path "/"))]
      (->> parent-rules
           (filter (fn [{:keys [path-prefix]}]
                     (and path-prefix
                          (.startsWith norm-path path-prefix))))
           first
           :parent-id))))
