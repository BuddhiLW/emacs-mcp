(ns hive-mcp.addons.runtime-ports
  "Composition-root adapters injected into IAddon config under :runtime/ports.

   This namespace is the only place where the generic addon port vocabulary is
   bound to hive-mcp implementations. Adapters resolve lazily to keep manifest
   parsing free of startup-order and require-cycle hazards.")

(defn- resolve!
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "Runtime port target unavailable: " sym)
                      {:error :addon/runtime-port-target-unavailable
                       :symbol sym}))))

(defn- call
  [sym & args]
  (apply (resolve! sym) args))

(defn runtime-ports
  "Return a fresh map of host-neutral function ports for addon injection."
  []
  {:memory/store
   (fn [slot]
     (get (call 'hive-mcp.protocols.memory/registered-stores) slot))

   :embedding/embed-batch
   (fn [memory-type texts]
     (when-let [provider (:provider
                          (call 'hive-mcp.embeddings.service/resolve-provider-for-type
                                memory-type))]
       (call 'hive-mcp.embeddings.protocol/embed-batch provider texts)))

   :embedding/provider
   (fn []
     (call 'hive-mcp.chroma.embeddings/get-embedding-provider))

   :embedding/configured?
   (fn []
     (call 'hive-mcp.chroma.embeddings/embedding-configured?))

   :kg/register-schema!
   (fn [schema]
     (call 'hive-mcp.knowledge-graph.schema/register-kg-schema! schema))

   :kg/infer-scope
   (fn [path]
     (call 'hive-mcp.knowledge-graph.scope/infer-scope-from-path path))

   :kg/resolve-project-id
   (fn [project-id]
     (call 'hive-mcp.knowledge-graph.scope/resolve-project-id project-id))

   :kg/query
   (fn
     ([slot query]
      (call 'hive-mcp.knowledge-graph.slots/query slot query))
     ([slot query inputs]
      (call 'hive-mcp.knowledge-graph.slots/query slot query inputs)))

   :extension/get
   (fn [k]
     (call 'hive-mcp.extensions.registry/get-extension k))

   :extension/keys
   (fn []
     (call 'hive-mcp.extensions.registry/registered-keys))

   :extension/register!
   (fn [k value]
     (call 'hive-mcp.extensions.registry/register! k value))

   :extension/contribute-commands!
   (fn [tool-name addon-id commands]
     (call 'hive-mcp.extensions.registry/contribute-commands!
           tool-name addon-id commands))

   :extension/retract-contributions!
   (fn [addon-id]
     (call 'hive-mcp.extensions.registry/retract-all-by-addon! addon-id))})
