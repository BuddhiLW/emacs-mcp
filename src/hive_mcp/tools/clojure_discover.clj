(ns hive-mcp.tools.clojure-discover
  "Inline nREPL port discovery for the `clojure` MCP tool.

   basic-tools-mcp.core/discover-ports requires `clojure-mcp-light`, which
   is a babashka-only dep and absent from the JVM classpath. That made
   `mcp__hive__clojure command=discover` fail with
   `{:error :ns/dependency-unavailable
     :symbol clojure-mcp-light.nrepl-eval/discover-nrepl-ports}`.

   This namespace provides a light-weight JVM-native replacement that
   scans `.nrepl-port` files in conventional locations (cwd + a few
   ancestors, `$BB_MCP_PROJECT_DIR`, `$HIVE_ROOT` and its immediate
   children, user.home) and wraps basic-tools-mcp.tools/handle-clojure
   to intercept the `discover` subcommand while delegating every other
   subcommand unchanged.

   Register via `install!` — idempotently replaces the `clojure` entry
   in the extensions tool-registry and, when present, the live
   server-context handler so running stdio/http MCP servers see the
   patched tool without a restart.

   Kanban: 20260423132055-27af713a"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-mcp.extensions.registry :as ext]
            [hive-dsl.result :refer [rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Candidate Directory Resolution
;; =============================================================================

(defn- ancestors-of
  "Return a lazy seq of the directory `d` and each of its ancestors up to
   the filesystem root. Nil-safe; returns [] when `d` is nil/blank."
  [d]
  (when (and d (not (str/blank? (str d))))
    (let [f (io/file d)]
      (take-while some? (iterate #(some-> ^java.io.File % .getParentFile) f)))))

(defn- hive-root-children
  "Return the immediate subdirectories of $HIVE_ROOT (or ~/PP/hive if
   unset), capped at 64 entries to avoid unbounded fs scans."
  []
  (let [root (or (System/getenv "HIVE_ROOT")
                 (str (System/getProperty "user.home") "/PP/hive"))
        dir  (io/file root)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory ^java.io.File %))
           (take 64)
           vec))))

(defn candidate-dirs
  "Return a vector of directories to probe for `.nrepl-port` files, in
   priority order, deduplicated by absolute path. Pure — respects the
   process env; does no IO other than listing $HIVE_ROOT children."
  []
  (let [cwd  (System/getProperty "user.dir")
        home (System/getProperty "user.home")
        bb   (System/getenv "BB_MCP_PROJECT_DIR")
        raw  (concat
              ;; cwd and up to 5 ancestors
              (take 6 (ancestors-of cwd))
              ;; babashka-hinted project dir
              (when bb [(io/file bb)])
              ;; user home (some repls drop .nrepl-port there)
              (when home [(io/file home)])
              ;; hive-root children (monorepo convention)
              (hive-root-children))
        seen (atom #{})]
    (vec (for [^java.io.File f raw
               :let [canonical (try (.getCanonicalPath f) (catch Exception _ nil))]
               :when (and canonical
                          (.isDirectory f)
                          (not (contains? @seen canonical)))
               :let [_ (swap! seen conj canonical)]]
           f))))

;; =============================================================================
;; Port File Parsing
;; =============================================================================

(defn- parse-port
  "Parse a `.nrepl-port` file. Returns an int or nil on malformed input."
  [^java.io.File f]
  (rescue nil
          (let [s (str/trim (slurp f))]
            (when (seq s)
              (Long/parseLong s)))))

(defn- probe-dir
  "Probe a single directory for `.nrepl-port`. Returns {:path :port :dir}
   or nil when absent/unparsable."
  [^java.io.File dir]
  (let [f (io/file dir ".nrepl-port")]
    (when (.isFile f)
      (when-let [port (parse-port f)]
        {:path (.getAbsolutePath f)
         :port port
         :dir  (.getAbsolutePath dir)}))))

;; =============================================================================
;; Public Discover API
;; =============================================================================

(defn discover-ports
  "Scan conventional locations for `.nrepl-port` files and return a
   vector of {:host \"localhost\" :port int :path str :dir str
             :source :nrepl-port-file} maps, deduplicated by port.

   Intentionally minimal — no socket probing, no session validation.
   Callers needing liveness should connect to each port themselves."
  []
  (let [hits (into [] (keep probe-dir) (candidate-dirs))
        by-port (reduce (fn [acc h]
                          (if (contains? acc (:port h)) acc
                              (assoc acc (:port h) h)))
                        {}
                        hits)]
    (mapv (fn [[_ h]]
            (assoc h :host "localhost" :source :nrepl-port-file))
          (sort-by key by-port))))

;; =============================================================================
;; Wrapped MCP Handler
;; =============================================================================

(defn- discover-mcp-response
  "Build the MCP response payload for the `discover` subcommand."
  []
  (try
    (let [ports (discover-ports)]
      {:content [{:type "text"
                  :text (pr-str {:ports ports
                                 :count (count ports)})}]})
    (catch Exception e
      (log/warn "clojure discover failed:" (ex-message e))
      {:content [{:type "text"
                  :text (pr-str {:error   :nrepl/discover-failed
                                 :message (ex-message e)})}]
       :isError true})))

(defn wrap-handle-clojure
  "Return a wrapped handle-clojure that intercepts :command \"discover\"
   with our inline implementation and delegates every other command to
   the upstream basic-tools-mcp handler (if resolvable) or reports an
   error if that fails."
  []
  (let [inner (rescue nil (requiring-resolve 'basic-tools-mcp.tools/handle-clojure))]
    (fn wrapped-handle-clojure [{:keys [command] :as params}]
      (cond
        (= "discover" command)
        (discover-mcp-response)

        inner
        (inner params)

        :else
        {:content [{:type "text"
                    :text (pr-str {:error   :clojure/handler-missing
                                   :command command
                                   :hint    "basic-tools-mcp.tools/handle-clojure not on classpath"})}]
         :isError true}))))

;; =============================================================================
;; Installation
;; =============================================================================

(defn- update-server-context!
  "When the stdio/http server-context has been populated, re-wrap the live
   handler for the \"clojure\" tool so running sessions see the patched
   discover command. No-op when server-context hasn't been initialised
   (e.g. pure REPL mode)."
  [wrapped-tool-def]
  (rescue nil
          (when-let [ctx-atom-var (resolve 'hive-mcp.server.core/server-context-atom)]
            (when-let [ctx-atom @ctx-atom-var]
              (when-let [ctx @ctx-atom]
                (when-let [tools-atom (:tools ctx)]
                  (when-let [make-tool (rescue nil
                                               (requiring-resolve
                                                'hive-mcp.server.routes/make-tool))]
                    (let [wrapped (make-tool wrapped-tool-def)]
                      (swap! tools-atom assoc (:name wrapped-tool-def)
                             {:tool    (dissoc wrapped-tool-def :handler)
                              :handler (:handler wrapped)})
                      (log/info "clojure tool handler refreshed in live server-context")
                      true))))))))

(defn install!
  "Replace the `clojure` tool-registry entry (and any live server-context
   handler) with a wrapper that implements `discover` inline. Idempotent.
   Must run AFTER basic-tools-mcp registers its addon so the upstream
   tool-def shape is available for reuse."
  []
  (let [inner-def    (rescue nil (when-let [f (requiring-resolve
                                               'basic-tools-mcp.tools/tool-def)]
                                   (f)))
        tool-def     (assoc (or inner-def
                                {:name "clojure"
                                 :description "Clojure dev tools (minimal JVM fallback)"
                                 :inputSchema {:type "object" :required ["command"]}})
                            :handler (wrap-handle-clojure))]
    (ext/register-tool! tool-def)
    (update-server-context! tool-def)
    (log/info "Installed clojure discover fallback (JVM-native .nrepl-port scan)")))
