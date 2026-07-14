(ns hive-mcp.agent.context
  "Thread-local execution context for agent tool calls. Minimal dependencies to avoid cycles."
  (:require [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:dynamic *request-ctx*
  "Dynamic var holding request context during tool execution."
  nil)

(def ^:dynamic *request-cache*
  "Per-request memoization cache. Bound to a fresh atom per MCP request.
   Automatically cleared when the binding unwinds (request completes).
   Use `request-memoize` to cache expensive computations within a request.

   Lifecycle:
   - Created by `wrap-handler-context` (outermost middleware layer)
   - Preserved by `with-request-context` if already bound
   - Shared across all middleware wrappers + handler in the same request
   - GC'd when the dynamic binding scope exits

   What to cache:
   - extract-project-id (require + resolve + .hive-project.edn read, called 4x/request)
   - extract-caller-id (called 3x/request, cheap but free to cache)
   - Any expensive handler-internal computation that repeats within one request

   What NOT to cache:
   - Side-effectful drains (piggyback, memory, async) — these consume on read"
  nil)

(def ^:dynamic ^:deprecated *current-agent-id*
  "DEPRECATED: Use *request-ctx* instead."
  nil)

(defmacro with-request-context
  "Execute body with the given request context bound.
   Preserves *request-cache* if already bound (e.g. by wrap-handler-context),
   otherwise creates a fresh cache atom for this scope."
  [ctx & body]
  #_{:clj-kondo/ignore [:deprecated-var]}
  `(let [cache# (or *request-cache* (atom {}))]
     (binding [*request-ctx* ~ctx
               *current-agent-id* (:agent-id ~ctx)
               *request-cache* cache#]
       ~@body)))

(defn current-agent-id
  "Get the current agent-id from execution context."
  []
  (or (:agent-id *request-ctx*)
      #_{:clj-kondo/ignore [:deprecated-var]}
      *current-agent-id*))

(defn current-project-id
  "Get the current project-id from execution context."
  []
  (:project-id *request-ctx*))

(defn current-directory
  "Get the current working directory from execution context."
  []
  (:directory *request-ctx*))

(defn resolve-caller-directory
  "HCR directory resolution chain used by project-scope-aware handlers.

   Priority:
   1. Explicit :directory arg (user override)
   2. :_caller_cwd (injected by bb-mcp — per-session cwd = user cwd)
   3. Request-ctx :directory (wrap-handler-context)
   4. Server's user.dir (last-resort fallback — shared JVM; may be wrong)

   Returns nil only if every source is blank. Centralizes the fallback
   chain so all scope-sensitive handlers (catchup, wrap, spawn, project-id
   derivation) stay DRY."
  ([] (resolve-caller-directory nil))
  ([args]
   (let [blank? (fn [s] (or (nil? s) (and (string? s) (.isBlank ^String s))))
         pick   (fn [v] (when-not (blank? v) v))]
     (or (pick (:directory args))
         (pick (:_caller_cwd args))
         (pick (current-directory))
         (pick (System/getProperty "user.dir"))))))

(defn caller-directory-source
  "Diagnostic: which slot in the HCR chain supplied the directory.
   Useful for log lines so ops can see whether scope is coming from
   explicit arg, bb-mcp injection, request ctx, or server fallback."
  [args]
  (cond
    (and (:directory args)    (not (.isBlank ^String (:directory args))))    :explicit
    (and (:_caller_cwd args)  (not (.isBlank ^String (:_caller_cwd args))))  :caller-cwd
    (current-directory)                                                       :request-ctx
    :else                                                                     :server-cwd))

(defn current-session-id
  "Get the current session-id from execution context."
  []
  (:session-id *request-ctx*))

(defn current-timestamp
  "Get the request timestamp from execution context."
  []
  (:timestamp *request-ctx*))

(defn current-depth
  "Get the current nesting depth from execution context."
  []
  (:depth *request-ctx*))

(defn request-ctx
  "Get the full request context map."
  []
  *request-ctx*)

(defn make-request-ctx
  "Create a new request context map from the given options."
  [{:keys [agent-id project-id directory session-id timestamp depth]
    :or {timestamp (java.util.Date.)
         depth 1}}]
  {:agent-id   agent-id
   :project-id project-id
   :directory  directory
   :session-id session-id
   :timestamp  timestamp
   :depth      depth})

(defn increment-depth
  "Return a new context with depth incremented."
  [ctx]
  (update ctx :depth (fnil inc 0)))

;; ── Request-Level Memoization ────────────────────────────────────

(defn request-memoize
  "Memoize a computation within the current request scope.

   When *request-cache* is bound (inside a tool request), returns cached value
   for cache-key or computes via compute-fn, stores, and returns it.

   When *request-cache* is nil (outside request context, e.g. in tests or REPL),
   falls through to compute-fn without caching.

   cache-key:  any hashable key — use a vector for composite keys,
               e.g. [:project-id \"/home/user/project\"]
   compute-fn: zero-arg function producing the value

   Thread-safety: Each request gets its own atom, so no cross-request contention.
   The atom uses compare-and-swap internally, which is safe for concurrent reads
   within a single request (e.g. async piggyback futures)."
  [cache-key compute-fn]
  (if-let [cache *request-cache*]
    (let [sentinel ::not-found
          cached (get @cache cache-key sentinel)]
      (if (identical? cached sentinel)
        (let [v (compute-fn)]
          (swap! cache assoc cache-key v)
          v)
        cached))
    (compute-fn)))

(defn request-cache-stats
  "Return stats about the current request cache for debugging.
   Returns nil when outside request context."
  []
  (when-let [cache *request-cache*]
    (let [entries @cache]
      {:entry-count (count entries)
       :keys (keys entries)})))

(m/=> current-directory [:=> [:cat] [:maybe :string]])