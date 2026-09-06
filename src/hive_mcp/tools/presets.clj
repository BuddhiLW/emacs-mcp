(ns hive-mcp.tools.presets
  "MCP tool handlers for swarm preset operations.

   Provides semantic search and management of swarm presets stored in Chroma.
   Presets can be queried by natural language (e.g., 'find testing-focused preset')."
  (:require [hive-mcp.presets.core :as presets]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.config.core :as config]
            [hive-mcp.tools.swarm.prompt :as prompt]
            [hive-mcp.tools.result-bridge :as rb]
            [hive-mcp.dns.result :as result]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;;; ============================================================
;;; Pure Result-returning functions
;;; ============================================================

(defn- file-fallback-preset
  "Try to get a preset from file-based fallback. The presets ship under
   resources/presets, so a checkout and the unpacked image agree on the
   default; `presets.dir` in config or HIVE_MCP_PRESETS_DIR overrides it."
  [name]
  (let [preset-dir (or (config/get-service-value :presets :dir :env "HIVE_MCP_PRESETS_DIR")
                       (str (System/getProperty "user.dir") "/resources/presets"))]
    (presets/get-preset-from-file preset-dir name)))

(defn- search* [{:keys [query limit category]}]
  (if-not (chroma/embedding-configured?)
    (result/err :preset/chroma-not-configured
                {:message "Chroma not configured. Semantic search requires Chroma with embedding provider."})
    (let [results (presets/search-presets query :limit (or limit 5) :category category)]
      (result/ok {:results results :count (count results) :query query}))))

(defn- get* [{:keys [name]}]
  (if-not (chroma/embedding-configured?)
    (if-let [preset (file-fallback-preset name)]
      (result/ok {:preset preset :source "file-fallback"})
      (result/err :preset/not-found {:message (str "Preset not found: " name)}))
    (if-let [preset (presets/get-preset name)]
      (result/ok {:preset preset})
      (if-let [preset (file-fallback-preset name)]
        (result/ok {:preset preset :source "file-fallback"})
        (result/err :preset/not-found
                    {:message (str "Preset not found: " name ". Try preset_list to see available presets")})))))

(defn- list-presets* [_]
  (if-not (chroma/embedding-configured?)
    (result/ok {:error "Chroma not configured"
                :message "Use file-based presets via swarm_list_presets"})
    (let [presets (presets/list-presets)]
      (result/ok {:presets presets :count (count presets)}))))

(defn- list-presets-slim* [_]
  (let [presets (presets/list-presets)]
    (result/ok {:presets (mapv #(select-keys % [:name :category]) presets)
                :count (count presets)})))

(defn- core* [{:keys [name]}]
  (if-not (chroma/embedding-configured?)
    (if-let [preset (file-fallback-preset name)]
      (result/ok {:core (presets/extract-preset-core preset)
                  :name name :source "file-fallback"})
      (result/err :preset/not-found {:message (str "Preset not found: " name)}))
    (if-let [preset (presets/get-preset name)]
      (result/ok {:core (presets/extract-preset-core preset)
                  :name name :source "chroma"})
      (if-let [preset (file-fallback-preset name)]
        (result/ok {:core (presets/extract-preset-core preset)
                    :name name :source "file-fallback"})
        (result/err :preset/not-found
                    {:message (str "Preset not found: " name ". Try preset_list to see available presets")})))))

(defn- header* [{:keys [presets lazy]}]
  (let [lazy? (if (nil? lazy) true lazy)
        preset-names (cond
                       (nil? presets) []
                       (sequential? presets) (filterv some? presets)
                       :else [presets])]
    (when (empty? preset-names)
      (log/warn "preset header called with no presets"))
    (if lazy?
      (result/ok {:header (prompt/build-lazy-preset-header preset-names)
                  :mode "lazy"
                  :preset-count (count preset-names)
                  :approx-tokens 300})
      (let [preset-contents (for [pname preset-names]
                              (if-let [preset (or (presets/get-preset pname)
                                                  (file-fallback-preset pname))]
                                {:name pname :content (:content preset)}
                                {:name pname :error "not found"}))
            full-content (->> preset-contents
                              (filter :content)
                              (map :content)
                              (str/join "\n\n---\n\n"))
            missing (->> preset-contents
                         (filter :error)
                         (map :name))]
        (result/ok {:header full-content
                    :mode "full"
                    :preset-count (count preset-names)
                    :approx-tokens (* 1500 (count preset-names))
                    :missing (when (seq missing) missing)})))))

(defn- migrate* [{:keys [directory]}]
  (if-not (chroma/embedding-configured?)
    (result/err :preset/chroma-not-configured
                {:message "Chroma not configured. Configure Chroma with embedding provider before migration."})
    (result/ok (presets/migrate-presets-from-dir! directory))))

(defn- status* [_]
  (result/ok (presets/status)))

(defn- add* [{:keys [name content category tags]}]
  (if-not (chroma/embedding-configured?)
    (result/err :preset/chroma-not-configured {:message "Chroma not configured"})
    (let [preset {:id name :name name :title name
                  :content content
                  :category (or category "custom")
                  :tags (if (coll? tags) (str/join "," tags) (or tags name))
                  :source "custom"}
          id (presets/index-preset! preset)]
      (result/ok {:success true :id id :message (str "Added preset: " name)}))))

(defn- delete* [{:keys [name]}]
  (if-not (chroma/embedding-configured?)
    (result/err :preset/chroma-not-configured {:message "Chroma not configured"})
    (do (presets/delete-preset! name)
        (result/ok {:success true :message (str "Deleted preset: " name)}))))

;;; ============================================================
;;; Handlers (MCP boundary)
;;; ============================================================

(defn handle-preset-search
  "Search presets using semantic similarity."
  [params]
  (log/info "preset-search:" (:query params))
  (rb/result->mcp (rb/try-result :preset/search #(search* params))))

(defn handle-preset-get
  "Get full content of a specific preset by name."
  [params]
  (log/info "preset-get:" (:name params))
  (rb/result->mcp (rb/try-result :preset/get #(get* params))))

(defn handle-preset-list
  "List all available presets."
  [params]
  (log/info "preset-list")
  (rb/result->mcp (rb/try-result :preset/list #(list-presets* params))))

(defn handle-preset-list-slim
  "List presets with minimal info (name + category only)."
  [params]
  (log/info "preset list_slim")
  (rb/result->mcp (rb/try-result :preset/list-slim #(list-presets-slim* params))))

(defn handle-preset-core
  "Get minimal summary of a preset for lazy loading."
  [params]
  (log/info "preset core:" (:name params))
  (rb/result->mcp (rb/try-result :preset/core #(core* params))))

(defn handle-preset-header
  "Generate preset header for system prompt."
  [params]
  (log/info "preset header:" (:presets params) "lazy:" (:lazy params))
  (rb/result->mcp (rb/try-result :preset/header #(header* params))))

(defn handle-preset-migrate
  "Migrate presets from .md files to Chroma."
  [params]
  (log/info "preset-migrate:" (:directory params))
  (rb/result->mcp (rb/try-result :preset/migrate #(migrate* params))))

(defn handle-preset-status
  "Get presets integration status."
  [params]
  (log/info "preset-status")
  (rb/result->mcp (rb/try-result :preset/status #(status* params))))

(defn handle-preset-add
  "Add a custom preset to Chroma."
  [params]
  (log/info "preset-add:" (:name params))
  (rb/result->mcp (rb/try-result :preset/add #(add* params))))

(defn handle-preset-delete
  "Delete a preset from Chroma."
  [params]
  (log/info "preset-delete:" (:name params))
  (rb/result->mcp (rb/try-result :preset/delete #(delete* params))))

;;; ============================================================
;;; Tool Definitions
;;; ============================================================

(def tools
  "REMOVED: Flat preset tools no longer exposed. Use consolidated `preset` tool."
  [])
