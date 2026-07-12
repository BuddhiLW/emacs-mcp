(ns hive-mcp.server.routes
  "MCP server route definitions and tool dispatch.

   Composes identity (routes.identity) and middleware (routes.middleware)
   into tool definitions and server specs.

   This namespace is the public API — callers require only this ns."
  (:require [hive-mcp.server.routes.identity :as id]
            [hive-mcp.server.routes.middleware :as mw]
            [hive-mcp.tools.registry :as tools]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.server.registration]              ; side-effect: tools/list defmethod
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.addons.core :as addons]
            [taoensso.timbre :as log]
            [clojure.spec.alpha :as s]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later


;; =============================================================================
;; Re-exports for backward compatibility
;; =============================================================================

(def normalize-content              id/normalize-content)
(def find-last-text-idx             id/find-last-text-idx)
(def wrap-delimited-block           id/wrap-delimited-block)
(def wrap-piggyback                 id/wrap-piggyback)
(def wrap-memory-piggyback-content  id/wrap-memory-piggyback-content)
(def extract-agent-id               id/extract-agent-id)
(def extract-caller-id              id/extract-caller-id)
(def extract-project-id             id/extract-project-id)
(def extract-caller-identity        id/extract-caller-identity)
(def extract-project-scope          id/extract-project-scope)

(def wrap-handler-retry             mw/wrap-handler-retry)
(def wrap-handler-nats-notify       mw/wrap-handler-nats-notify)
(def wrap-handler-context           mw/wrap-handler-context)
(def wrap-handler-normalize         mw/wrap-handler-normalize)
(def wrap-handler-compress          mw/wrap-handler-compress)
(def wrap-handler-response          mw/wrap-handler-response)
(def wrap-handler-default-async-for-commands mw/wrap-handler-default-async-for-commands)
(def wrap-handler-async             mw/wrap-handler-async)
(def wrap-handler-piggybacks        mw/wrap-handler-piggybacks)
(def build-middleware-chain         mw/build-middleware-chain)


;; =============================================================================
;; Specs for Tool Definitions
;; =============================================================================

(s/def ::tool-def
  (s/keys :req-un [::name ::description ::inputSchema ::handler]))

(s/def ::name string?)
(s/def ::description string?)
(s/def ::inputSchema map?)
(s/def ::handler fn?)

(s/def ::tool-response
  (s/keys :req-un [::content]))

(s/def ::content (s/coll-of map?))


;; =============================================================================
;; Tool Definition Conversion
;; =============================================================================

(s/fdef make-tool
  :args (s/cat :tool-def ::tool-def)
  :ret ::tool-response)

(defn make-tool
  "Convert a tool definition with :handler to SDK format.
   Wraps handler with the standard middleware chain."
  [{:keys [name description inputSchema handler deprecated default-async-commands]}]
  (let [schema-ext (ext/get-schema-extensions name)
        merged-schema (if schema-ext
                        (update inputSchema :properties merge schema-ext)
                        inputSchema)]
    (cond-> {:name name
             :description description
             :inputSchema merged-schema
             :handler (mw/build-middleware-chain handler name default-async-commands)}
      deprecated (assoc :deprecated true))))

(defn- select-base-tools-for-role
  "Role-appropriate base tool set: the restricted set for a child ling,
   otherwise the full coordinator set including deprecated shims."
  []
  (if (guards/child-ling?)
    (tools/get-child-ling-tools)
    (tools/get-all-tools :include-deprecated? true)))

(defn- gated-tool-set
  "Fold dynamic (extension) + addon tools onto the base set and apply the
   visibility gate, yielding the <=10-root surface with old names still callable."
  [base-tools dynamic-tools addon-tools]
  (tools/apply-visibility-gate
   (concat base-tools dynamic-tools addon-tools)))


;; =============================================================================
;; Server Spec Building
;; =============================================================================

(defn build-server-spec
  "Build MCP server spec with role-based and capability-based tool filtering.

   ROLE BRANCHING (Self-Call Prevention):
   - Coordinator (default): Full tool set including deprecated shims
   - Child ling (HIVE_MCP_ROLE=child-ling): Restricted tool set"
  []
  (let [dynamic-tools (ext/get-registered-tools)
        addon-tools   (addons/active-addon-tools)
        base          (select-base-tools-for-role)
        gated         (gated-tool-set base dynamic-tools addon-tools)]
    (if (guards/child-ling?)
      (let [role (guards/get-role)
            depth (guards/ling-depth)]
        (log/info "Building CHILD LING server spec with" (count gated) "tools"
                  "(role:" role "depth:" depth
                  "excluded:" (count tools/child-excluded-tool-names) "tool categories)"
                  "dynamic:" (count dynamic-tools) "addon:" (count addon-tools))
        {:name "hive-mcp"
         :version "0.1.0"
         :tools (mapv make-tool gated)})
      (let [deprecated-count (count (filter :deprecated gated))
            visible-count (- (count gated) deprecated-count)]
        (log/info "Building server spec with" (count gated) "tools"
                  "(" visible-count "visible," deprecated-count "deprecated/gated)"
                  "dynamic:" (count dynamic-tools) "addon:" (count addon-tools))
        {:name "hive-mcp"
         :version "0.1.0"
         :tools (mapv make-tool gated)}))))


;; =============================================================================
;; Hot Reload & Debug
;; =============================================================================

(defn refresh-tools!
  "Hot-reload all tools in the running server."
  [server-context-atom]
  (when-let [context @server-context-atom]
    (let [tools-atom (:tools context)
          child? (guards/child-ling?)
          base-tools (select-base-tools-for-role)
          selected-tools (gated-tool-set base-tools
                                         (ext/get-registered-tools)
                                         (addons/active-addon-tools))
          new-tools (mapv make-tool selected-tools)
          deprecated-count (if child?
                             0
                             (count (filter :deprecated selected-tools)))]
      (reset! tools-atom {})
      (doseq [tool new-tools]
        (swap! tools-atom assoc (:name tool) {:tool (dissoc tool :handler)
                                              :handler (:handler tool)}))
      (if child?
        (log/info "Hot-reloaded" (count new-tools) "tools (child-ling restricted)")
        (log/info "Hot-reloaded" (count new-tools) "tools"
                  "(including" deprecated-count "deprecated shims for backward compat)"))
      (count new-tools))))

(defn debug-tool-handler
  "Get info about a registered tool handler (for debugging)."
  [server-context-atom tool-name]
  (when-let [context @server-context-atom]
    (let [tools-atom (:tools context)
          tool-entry (get @tools-atom tool-name)]
      (when tool-entry
        {:name tool-name
         :handler-class (str (type (:handler tool-entry)))
         :tool-keys (keys (:tool tool-entry))}))))

(defn register-tools-for-delegation!
  "Register tools for agent delegation with role-based filtering."
  []
  (require 'hive-mcp.agent.core)
  (let [register-tools! (resolve 'hive-mcp.agent.core/register-tools!)
        child? (guards/child-ling?)
        selected-tools (select-base-tools-for-role)
        deprecated-count (if child?
                           0
                           (count (filter :deprecated selected-tools)))]
    (register-tools! selected-tools)
    (if child?
      (log/info "Registered" (count selected-tools) "tools for child-ling delegation (restricted)")
      (log/info "Registered" (count selected-tools) "tools for agent delegation"
                "(including" deprecated-count "deprecated shims)"))
    (count selected-tools)))