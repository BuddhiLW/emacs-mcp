(ns hive-mcp.tools.consolidated.addon
  "Consolidated addon diagnostics tool."
  (:require [hive-mcp.addons.doctor :as doctor]
            [hive-mcp.tools.cli :refer [make-cli-handler]]
            [hive-mcp.tools.result-bridge :as rb]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn- normalize-doctor-input
  [params]
  (let [addon-id (or (:addon-id params) (:addon_id params))
        features-present? (or (contains? params :emacs-features)
                              (contains? params :emacs_features))
        features (or (:emacs-features params) (:emacs_features params))
        timeout-ms (or (:timeout-ms params) (:timeout_ms params))]
    (cond-> {:addon-id addon-id}
      (:directory params) (assoc :directory (:directory params))
      features-present? (assoc :emacs-features (vec (or features [])))
      timeout-ms (assoc :timeout-ms timeout-ms))))

(defn handle-doctor
  "Run the read-only addon doctor and return its evidence report as JSON."
  [params]
  (rb/result->mcp
   (rb/try-result :addon-doctor/failed
                  #(doctor/run-doctor (normalize-doctor-input params)))))

(def handlers
  {:doctor handle-doctor})

(def handle-addon
  (make-cli-handler handlers))

(def tool-def
  {:name "addon"
   :consolidated true
   :description
   "Addon diagnostics. doctor discovers the manifest, resolves its constructor, scans dependency boundaries, checks live lifecycle/health and capability parity, verifies declared or requested Emacs features, and emits a versioned evidence report. The live addon is never initialized, stopped, or re-registered. Use command='help' to list commands."
   :inputSchema
   {:type "object"
    :properties
    {"command" {:type "string"
                :enum ["doctor" "help"]
                :description "Addon operation to perform"}
     "addon_id" {:type "string"
                 :description "Stable addon ID, e.g. hive.emacs"}
     "directory" {:type "string"
                  :description "Optional addon project root for deps.edn and src namespace boundary scans"}
     "emacs_features" {:type "array"
                       :items {:type "string"}
                       :description "Optional expected Emacs feature symbols; overrides manifest :addon/doctor hints"}
     "timeout_ms" {:type "integer"
                   :minimum 1
                   :maximum 30000
                   :description "Per-feature live Emacs probe timeout (default 3000)"}}
    :required ["command"]}
   :handler handle-addon})

(def tools [tool-def])

