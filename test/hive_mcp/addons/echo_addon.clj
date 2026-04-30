(ns hive-mcp.addons.echo-addon
  "Test-only example IAddon proving the multi extension contract end-to-end.

   Demonstrates the four `:multi/*` hook keys in one declarative `(hooks [_])`:
   - :multi/tool         — registers an `\"echo\"` tool
   - :multi/verb         — registers an `\"e!\"` DSL verb code → echo/say
   - :multi/param-alias  — registers `\"x\"` → :xtra short alias
   - :multi/batchable    — registers an EchoBatchable record satisfying Batchable

   Used by hive-mcp.multi.addon-e2e-test. NOT loaded in production.

   Decision: 20260429230453-7e7627cc"
  (:require [hive-mcp.addons.protocol :as proto]
            [hive-mcp.batch.protocol :as bproto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Echo handler — what the registered :multi/tool dispatches to
;; =============================================================================

(defn handle-echo
  "Top-level handler for the echo tool. Returns its params verbatim wrapped
   in the MCP {:type :text} envelope so multi can format it."
  [params]
  {:type "text"
   :text (pr-str (select-keys params [:command :content :xtra :n]))})

;; =============================================================================
;; EchoBatchable — proves the single-call boundary contract
;;
;; In a real tool this would issue one store call across all ops. For the
;; example, we tag the result with :batched? true so the integration test
;; can verify the explicit Batchable was substituted (not the default).
;; =============================================================================

(defrecord EchoBatchable [tool-name]
  bproto/Batchable
  (batch-execute [_ ops _opts]
    {:success true
     :waves   {1 {:ops ops
                  :results (mapv (fn [op]
                                   {:id      (:id op)
                                    :success true
                                    :result  {:type "text"
                                              :text (pr-str (select-keys op [:command :content :xtra :n]))}
                                    :batched? true})
                                 ops)}}
     :summary {:total   (count ops)
               :success (count ops)
               :failed  0
               :waves   1}
     :batched? true})
  (batch-schema [_]
    {:type "object"
     :properties {:operations {:type "array"}}}))

;; =============================================================================
;; EchoAddon — IAddon impl with the canonical 4-key (hooks) contribution
;; =============================================================================

(defrecord EchoAddon [id]
  proto/IAddon
  (addon-id          [_] id)
  (addon-type        [_] :native)
  (capabilities      [_] #{:tools})
  (initialize!       [_ _opts] {:success? true :metadata {}})
  (shutdown!         [_] {:success? true})
  (tools             [_] [])
  (schema-extensions [_] [])
  (health            [_] {:status :ok})
  (excluded-tools    [_] #{})
  (hooks [_]
    {:multi/tool        [{:tool-name "echo" :handler handle-echo}]
     :multi/verb        [{:code "e!" :tool "echo" :command "say"}]
     :multi/param-alias [{:short "x" :full :xtra}]
     :multi/batchable   [{:tool-name "echo" :record (->EchoBatchable "echo")}]}))

(defn make-echo-addon
  ([] (->EchoAddon "test/echo"))
  ([id] (->EchoAddon id)))
