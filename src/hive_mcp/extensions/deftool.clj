(ns hive-mcp.extensions.deftool
  "Schema-driven MCP tool definition — the hive-mcp leg of the malli macro layer.

   `deftool` projects an MCP tool from ONE registered malli schema-key: its
   :inputSchema is the malli -> JSON-Schema projection
   (hive-spi.schema.derive/input-schema, already MCP-ready) and its handler is
   wrapped to coerce + validate incoming params against the SAME schema before the
   body runs. Declare the schema once; the tool's advertised contract and its
   runtime guard are both derived from it — no hand-written JSON-Schema, no
   hand-written arg checking. Registration flows through
   hive-mcp.extensions.registry/register-tool!, so the sink (server.routes) is
   unchanged."
  (:require [malli.core :as m]
            [hive-spi.schema.derive :as derive]
            [hive-mcp.extensions.registry :as reg]))

(defn schema->tool
  "MCP tool-def map projected from a registered malli `schema-key`. :inputSchema
   is the malli -> JSON-Schema projection; :handler wraps `handler` to
   coerce+validate its params against `schema-key` first — an invalid call throws
   ex-info {:error :schema/invalid ...} (the coercer's own signal) before the body
   runs. Pure: builds the map, registers nothing."
  [tool-name description schema-key handler]
  (let [{:keys [coerce input-schema]} (derive/compile-op schema-key)]
    {:name        tool-name
     :description description
     :inputSchema input-schema
     :handler     (fn [params] (handler (coerce params)))}))

(defmacro deftool
  "Define + register an MCP tool from ONE registered malli schema-key.

     (deftool \"my-tool\"
       {:description \"...\"
        :schema      :my/tool-args      ; registered in hive-spi.schema.registry
        :handler     (fn [params] ...)}) ; params arrive coerced + validated

   :inputSchema is projected from the schema and the handler is wrapped to
   coerce+validate against it, so the tool's contract and its runtime guard come
   from the SAME schema. Emits a load-time register-tool! call; returns the name."
  [tool-name {:keys [description schema handler]}]
  `(reg/register-tool! (schema->tool ~tool-name ~description ~schema ~handler)))

(m/=> schema->tool [:=> [:cat :string [:maybe :string] :any ifn?] :map])
