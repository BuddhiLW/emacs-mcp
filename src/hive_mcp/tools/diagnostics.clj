(ns hive-mcp.tools.diagnostics
  "Error-diagnostic helpers for MCP tool responses: detect the Clojure
   protocol-class-poisoning failure mode and produce an operator recovery hint.")

(def ^:private protocol-poison-pattern
  "Regex for the Clojure protocol-class-poisoning failure mode: a
   protocol var was redefined (typically via :reload-all) so existing
   instances point to the old protocol's interface and dispatch can't
   find an impl for them.

   See convention 20260429195405-2425b388. The recovery is a server
   restart — `extend` cannot patch around it because the JVM-level
   `implements` slot is locked at defrecord time."
  #"No implementation of method: (\S+) of protocol: #'(\S+) found for class: (\S+)")

(def ^:private known-poisonable-protocols
  "Protocols we've actually seen poisoned in production hive-mcp sessions
   (lifecycle classes whose deftype/defrecord instances are long-lived).
   Used to lift the recovery hint from \"likely\" to \"almost certainly\"."
  #{"iapetos.collector/Collector"
    "milvus-clj.client/IMilvusCore"
    "hive-weave.pool/IBindingConveyor"})

(defn detect-protocol-poisoning
  "Inspect a Throwable or message string for the protocol-class-poisoning
   error pattern. Returns

     {:poisoned? true
      :protocol \"<ns>/<Proto>\"
      :method \":<m>\"
      :class \"<TypeImpl>\"
      :known-poisonable? true|false}

   when matched, else nil. Pure — no logging or side effects."
  [x]
  (let [msg (cond
              (string? x)        x
              (instance? Throwable x) (or (.getMessage ^Throwable x) "")
              :else              (str x))]
    (when-let [[_ method protocol kls] (re-find protocol-poison-pattern msg)]
      {:poisoned?         true
       :method            method
       :protocol          protocol
       :class             kls
       :known-poisonable? (contains? known-poisonable-protocols protocol)})))

(defn recovery-hint
  "Produce an actionable error message for a protocol-poisoned exception.

   Returns nil when the input doesn't match the poisoning pattern, so
   callers can use it as a soft enrichment:
     (or (recovery-hint e) (.getMessage e))"
  [x]
  (when-let [{:keys [protocol method class known-poisonable?]} (detect-protocol-poisoning x)]
    (str "Protocol dispatch poisoned (server restart required).\n"
         "  protocol: " protocol "\n"
         "  method:   " method "\n"
         "  class:    " class "\n"
         (if known-poisonable?
           "  This is a known-poisonable lifecycle protocol — :reload-all on a\n"
           "  Likely root cause: :reload-all was run on a ns whose dep tree\n")
         "  source ns rebinds defprotocol/deftype vars so existing instances\n"
         "  point to the old protocol's interface. JVM `implements` slot is\n"
         "  locked at defrecord time; `extend` cannot repair this in-process.\n"
         "  Recovery: restart the JVM (no in-process recovery exists).\n"
         "  Convention: memory id 20260429195405-2425b388.")))
