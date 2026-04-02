(ns hive-mcp.schema.cider
  "Malli schemas for CIDER tool parameters.

   Single source of truth for CLJ↔CLJEL contracts.
   Used at runtime (m/validate in handlers) and compile-time
   (schema/elisp.clj generates elisp validators from these)."
  (:require [hive-mcp.schema.tools :as t]
            [hive-mcp.schema.elisp :as elisp]
            [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Common CIDER Types
;; =============================================================================

(def ReplType
  "REPL type: Clojure, ClojureScript, or ClojureElisp."
  [:enum "clj" "cljs" "cljel"])

(def Port
  "Valid TCP port number."
  [:int {:min 1 :max 65535}])

;; =============================================================================
;; Command Parameter Schemas
;; =============================================================================

(def ConnectSessionParams
  "Parameters for cider connect command."
  [:map
   [:name t/NonEmptyString]
   [:host {:optional true :default "localhost"} :string]
   [:port Port]
   [:repl_type {:optional true :default "clj"} [:maybe ReplType]]
   [:agent_id {:optional true} t/OptionalString]])

(def EvalParams
  "Parameters for cider eval command."
  [:map
   [:code t/NonEmptyString]
   [:session_name {:optional true} t/OptionalString]
   [:mode {:optional true} [:enum "silent" "explicit"]]
   [:timeout {:optional true} t/PositiveInt]])

(def SpawnSessionParams
  "Parameters for cider spawn command."
  [:map
   [:name t/NonEmptyString]
   [:project_dir {:optional true} t/OptionalString]
   [:repl_type {:optional true :default "clj"} [:maybe ReplType]]
   [:agent_id {:optional true} t/OptionalString]])

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn validate
  "Validate params against schema. Returns nil on success, throws ex-info on failure."
  [schema params]
  (when-not (m/validate schema params)
    (throw (ex-info "Invalid params"
                    {:type :validation
                     :errors (m/explain schema params)}))))

(defn validate-connect-params
  "Validate connect-session parameters."
  [params]
  (validate ConnectSessionParams params))

(defn validate-spawn-params
  "Validate spawn-session parameters."
  [params]
  (validate SpawnSessionParams params))

(defn validate-eval-params
  "Validate eval parameters."
  [params]
  (validate EvalParams params))

;; =============================================================================
;; Elisp Validator Generation (compile-time)
;; =============================================================================

(def ^:private elisp-opts
  {:connect {:name "hive-mcp-cider-connect-params"}
   :spawn   {:name "hive-mcp-cider-spawn-params"}
   :eval    {:name "hive-mcp-cider-eval-params"}})

(defn emit-connect-validator
  "Generate elisp -valid-p predicate for ConnectSessionParams."
  []
  (elisp/emit-validator ConnectSessionParams (:connect elisp-opts)))

(defn emit-connect-struct
  "Generate elisp cl-defstruct for ConnectSessionParams."
  []
  (elisp/emit-struct ConnectSessionParams (:connect elisp-opts)))

(defn emit-connect-from-plist
  "Generate elisp -from-plist for ConnectSessionParams."
  []
  (elisp/emit-from-plist ConnectSessionParams (:connect elisp-opts)))

(defn emit-all
  "Generate all elisp validators, structs, and converters."
  []
  (str
   ";; Generated from hive-mcp.schema.cider — DO NOT EDIT\n\n"
   (elisp/emit-structs [[ConnectSessionParams (:connect elisp-opts)]
                         [SpawnSessionParams (:spawn elisp-opts)]
                         [EvalParams (:eval elisp-opts)]])
   "\n\n"
   (elisp/emit-from-plist-all [[ConnectSessionParams (:connect elisp-opts)]
                                [SpawnSessionParams (:spawn elisp-opts)]
                                [EvalParams (:eval elisp-opts)]])
   "\n\n"
   (elisp/emit-validators [[ConnectSessionParams (:connect elisp-opts)]
                            [SpawnSessionParams (:spawn elisp-opts)]
                            [EvalParams (:eval elisp-opts)]])))

(comment
  ;; Generate elisp validators
  (println (emit-all))

  ;; Validate good params
  (m/validate ConnectSessionParams
              {:name "sisf-web" :port 7902 :repl_type "cljs"})
  ;; => true

  ;; Validate bad params
  (m/explain ConnectSessionParams
             {:name "" :port 0})
  ;; => {:errors [...]}

  ;; Port range
  (m/validate ConnectSessionParams {:name "test" :port 70000})
  ;; => false
  )
