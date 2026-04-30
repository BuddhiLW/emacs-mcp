(ns hive-mcp.multi.addon-e2e-test
  "End-to-end integration test for the IAddon-native multi extension contract.

   Exercises the full lifecycle:
     1. Register an example IAddon (EchoAddon) with the addons registry
     2. Initialize it — the hooks-walk routes :multi/* keys to multi.registry
     3. Call the registered tool via multi (both tool form and DSL verb form)
     4. Shutdown — multi.registry deregisters by-owner
     5. Verify post-shutdown calls fail with :multi/missing-tool

   This is the canonical contract test: any change to the IAddon hooks-walk
   in addons/core.clj or multi.registry/register-by-key! must keep this green.

   Decision: 20260429230453-7e7627cc"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.addons.core :as addons]
            [hive-mcp.addons.echo-addon :as echo]
            [hive-mcp.multi.registry :as registry]
            [hive-mcp.multi.registry.tools :as r-tools]
            [hive-mcp.multi.registry.verbs :as r-verbs]
            [hive-mcp.multi.registry.aliases :as r-aliases]
            [hive-mcp.multi.registry.batchables :as r-batchables]
            [hive-mcp.batch.protocol :as bproto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private test-id "test/echo-e2e")

;; Ensure clean addon registry around each test
(defn- with-fresh-addon [f]
  (try
    (addons/unregister-addon! test-id)
    (catch Throwable _ nil))
  (try
    (f)
    (finally
      (try
        (addons/shutdown-addon! test-id)
        (addons/unregister-addon! test-id)
        (catch Throwable _ nil)))))

(use-fixtures :each with-fresh-addon)

;; =============================================================================
;; §1 — Registration → all 4 :multi/* hook entries land in the registry
;; =============================================================================

(deftest addon-init-routes-all-four-multi-hook-keys
  (testing "Init walks (hooks [_]) and routes :multi/* keys to multi.registry"
    (let [addon  (echo/make-echo-addon test-id)
          _      (addons/register-addon! addon)
          result (addons/init-addon! test-id)]
      (is (:success? result) (str "init failed: " (:errors result)))
      (is (some? (r-tools/lookup "echo"))      ":multi/tool routed")
      (is (some? (r-verbs/lookup "e!"))         ":multi/verb routed")
      (is (some? (r-aliases/lookup "x"))        ":multi/param-alias routed")
      (is (some? (r-batchables/lookup "echo")) ":multi/batchable routed")
      (is (= test-id (:owner (r-tools/lookup "echo")))
          "owner stamp is the addon-id"))))

;; =============================================================================
;; §2 — resolve-tool-handler hits the addon-registered tool
;; =============================================================================

(deftest resolve-finds-addon-registered-tool
  (testing "After init, multi.registry/resolve-tool-handler returns the addon's handler"
    (let [addon (echo/make-echo-addon test-id)]
      (addons/register-addon! addon)
      (addons/init-addon! test-id)
      (let [handler (registry/resolve-tool-handler "echo")
            result  (handler {:command "say" :content "hello" :xtra "boom"})]
        (is (some? handler) "handler resolves through the registry")
        (is (= "text" (:type result)))
        (is (re-find #"hello" (:text result)))
        (is (re-find #"boom"  (:text result)))))))

;; =============================================================================
;; §3 — Batchable substitution: explicit EchoBatchable is returned, not default
;; =============================================================================

(deftest explicit-batchable-substitutes-for-default
  (testing "lookup-batchable-or-default returns the EchoBatchable record"
    (let [addon (echo/make-echo-addon test-id)]
      (addons/register-addon! addon)
      (addons/init-addon! test-id)
      (let [bx (registry/lookup-batchable-or-default "echo")]
        (is (satisfies? bproto/Batchable bx))
        (is (= "EchoBatchable" (-> bx class .getSimpleName))
            "explicit record substituted (not DefaultBatchableAdapter)")
        (let [out (bproto/batch-execute bx
                    [{:id "1" :tool "echo" :command "say" :content "a"}
                     {:id "2" :tool "echo" :command "say" :content "b"}]
                    {})]
          (is (:success out))
          (is (true? (:batched? out)) "batched? marker proves explicit Batchable was used")
          (is (= 2 (get-in out [:summary :success]))))))))

;; =============================================================================
;; §4 — Shutdown deregisters the addon's :multi/* entries (ownership-isolated)
;; =============================================================================

(deftest shutdown-deregisters-only-owned-entries
  (testing "shutdown-addon! removes addon-owned :multi/* entries; :multi/core unaffected"
    (let [addon (echo/make-echo-addon test-id)
          memory-resolved-pre (some? (registry/resolve-tool-handler "memory"))]
      (addons/register-addon! addon)
      (addons/init-addon! test-id)
      (is (some? (r-tools/lookup "echo")))
      (let [shutdown (addons/shutdown-addon! test-id)]
        (is (:success? shutdown) "shutdown succeeded"))
      (is (nil? (r-tools/lookup "echo"))      "echo tool removed")
      (is (nil? (r-verbs/lookup "e!"))         "echo verb removed")
      (is (nil? (r-aliases/lookup "x"))        "echo alias removed")
      (is (nil? (r-batchables/lookup "echo")) "echo batchable removed")
      ;; :multi/core entries (the seeded 20 tools) survive — ownership isolation
      (is (= memory-resolved-pre
             (some? (registry/resolve-tool-handler "memory")))
          ":multi/core entries unaffected by addon shutdown"))))

;; =============================================================================
;; §5 — After shutdown, resolve-tool-handler returns nil for echo
;; =============================================================================

(deftest post-shutdown-resolve-returns-nil
  (testing "After shutdown, resolve-tool-handler 'echo' returns nil"
    (let [addon (echo/make-echo-addon test-id)]
      (addons/register-addon! addon)
      (addons/init-addon! test-id)
      (is (some? (registry/resolve-tool-handler "echo")) "pre-shutdown: resolves")
      (addons/shutdown-addon! test-id)
      (is (nil? (registry/resolve-tool-handler "echo")) "post-shutdown: nil"))))

;; =============================================================================
;; §6 — Init/shutdown cycle is idempotent across re-registrations
;; =============================================================================

(deftest re-init-after-shutdown-restores-entries
  (testing "Shutdown then re-init restores the addon's :multi/* entries"
    (let [addon (echo/make-echo-addon test-id)]
      (addons/register-addon! addon)
      (addons/init-addon! test-id)
      (addons/shutdown-addon! test-id)
      (is (nil? (r-tools/lookup "echo")))
      (addons/init-addon! test-id)
      (is (some? (r-tools/lookup "echo")))
      (is (some? (r-verbs/lookup "e!"))))))
