(ns hive-mcp.tools.result-bridge
  "Shared Result->MCP bridge for consolidated tool handlers.

   Provides the canonical MCP tool handler pattern:
   1. Pure `*` functions returning Results ({:ok ...} / {:error ...})
   2. `try-result` for exception capture at the boundary
   3. `result->mcp` / `result->mcp-text` for final MCP formatting

   Also provides `keywordize-map` for normalizing string-keyed maps from MCP JSON."
  (:require [hive-mcp.tools.core :refer [mcp-success mcp-error mcp-json]]
            [hive-mcp.dns.result :as result]))

;; ── Exception Capture ─────────────────────────────────────────────────────────

(defn try-result
  "Execute f in try/catch, returning Result. f must return a Result ({:ok ...}).
   Exceptions -> (result/err category {:message ...})."
  [category f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (result/err category {:message (ex-message e) :data (ex-data e)}))
       (catch Exception e
         (result/err category {:message (or (ex-message e) (str (class e)))
                               :class   (str (class e))}))))

;; ── Result -> MCP Conversions ─────────────────────────────────────────────────

(defn result->mcp
  "Convert a Result to MCP JSON response: ok -> mcp-json, err -> mcp-error."
  [r]
  (if (result/ok? r)
    (mcp-json (:ok r))
    (mcp-error (or (:message r)
                   (str (:error r)
                        (when-let [c (:class r)] (str " (" c ")")))))))

(defn result->mcp-text
  "Convert a Result to MCP text response: ok -> mcp-success, err -> mcp-error."
  [r]
  (if (result/ok? r)
    (mcp-success (:ok r))
    (mcp-error (or (:message r)
                   (str (:error r)
                        (when-let [c (:class r)] (str " (" c ")")))))))

;; ── Map Normalization ─────────────────────────────────────────────────────────

(defn keywordize-map
  "Convert string-keyed map to keyword-keyed map. Idempotent on keyword maps.
   Used to normalize MCP JSON params which arrive with string keys."
  [m]
  (into {} (map (fn [[k v]] [(keyword k) v]) m)))
