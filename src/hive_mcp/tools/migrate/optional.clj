(ns hive-mcp.tools.migrate.optional
  "Late binding for the optional backend namespaces the kanban migrators use.

   Requiring hive-qdrant / clj-qdrant / hive-milvus at namespace load makes
   hive-mcp core compile-depend on its own backends, while those backends
   depend on hive-mcp for their addon surface. That mutual edge is a release
   cycle: no ordering exists, so a cascade cannot release any of the three,
   nor the five projects sitting behind them.

   Resolving at CALL time keeps the coupling to the moment a migration
   actually runs. hive-mcp then compiles and boots with no backend on the
   classpath, and a migration invoked without one fails with a directed
   message instead of a load-time ClassNotFoundException.")

(defn backend-var
  "The Var NS-STR/SYM, resolved on first use.

   Callable directly for functions — a Var implements IFn — and deref it for
   values. Throws when the optional backend is absent, naming what to add."
  [ns-str sym]
  (or (requiring-resolve (symbol ns-str (name sym)))
      (throw (ex-info (str ns-str " is not on the classpath")
                      {:missing (symbol ns-str (name sym))
                       :hint (str "this migration needs an optional backend; add its "
                                  "coordinate via local.deps.edn or a deps alias")}))))
