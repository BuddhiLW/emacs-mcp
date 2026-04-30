(ns hive-mcp.multi.registry-test
  "Property + unit tests for the multi.registry façade and its four child
   registries.

   Coverage:
     - register! conflict policy (ok / replaced / conflict)
     - deregister-by-owner! ownership isolation
     - resolve-tool-handler chain (registry → flat fallback)
     - lookup-batchable-or-default LSP fallback
     - snapshot version stability across no-op operations

   Decision: 20260429230453-7e7627cc"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.multi.registry :as registry]
            [hive-mcp.multi.registry.tools :as r-tools]
            [hive-mcp.multi.registry.verbs :as r-verbs]
            [hive-mcp.multi.registry.aliases :as r-aliases]
            [hive-mcp.multi.registry.batchables :as r-batchables]
            [hive-mcp.multi.batchable-adapter :as adapter]
            [hive-mcp.batch.protocol :as bproto]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn- snapshot-tools-state []
  (-> (r-tools/snapshot) :data))

(defn- with-fresh-registry
  "Save the current state, run f, restore. Avoids clobbering :multi/core seed."
  [f]
  (let [saved-tools      (-> (r-tools/snapshot) :data)
        saved-verbs      (-> (r-verbs/snapshot) :data)
        saved-aliases    (-> (r-aliases/snapshot) :data)
        saved-batchables (-> (r-batchables/snapshot) :data)]
    (try
      (f)
      (finally
        (r-tools/reset-for-test!)
        (r-verbs/reset-for-test!)
        (r-aliases/reset-for-test!)
        (r-batchables/reset-for-test!)
        ;; Restore for downstream tests
        (doseq [[name entry] (:by-name saved-tools)]
          (r-tools/register! (:owner entry) name (dissoc entry :owner :registered-at)))
        (doseq [[code entry] (:by-code saved-verbs)]
          (r-verbs/register! (:owner entry) code (dissoc entry :owner)))
        (doseq [[short entry] (:by-short saved-aliases)]
          (r-aliases/register! (:owner entry) short (dissoc entry :owner)))
        (doseq [[name entry] (:by-name saved-batchables)]
          (r-batchables/register! (:owner entry) name (dissoc entry :owner)))))))

;; =============================================================================
;; §1 — Conflict policy unit tests (one per child registry)
;; =============================================================================

(deftest tools-conflict-policy
  (testing "Tool registry: ok → replaced → conflict"
    (with-fresh-registry
      (fn []
        (r-tools/reset-for-test!)
        (let [r1  (r-tools/register! :test/a "echo" {:handler (constantly :v1)})
              r1b (r-tools/register! :test/a "echo" {:handler (constantly :v2)})
              r2  (r-tools/register! :test/b "echo" {:handler (constantly :nope)})
              hit (r-tools/lookup "echo")]
          (is (= :ok r1)        ":ok on first register")
          (is (= :replaced r1b) ":replaced on same-owner re-register")
          (is (= :conflict r2)  ":conflict on different-owner attempt")
          (is (= :test/a (:owner hit)) "first-write-wins: original owner retained")
          (is (= :v2 ((:handler hit))) "same-owner replace updated the handler"))))))

(deftest verbs-conflict-policy
  (testing "Verb registry: ok → replaced → conflict"
    (with-fresh-registry
      (fn []
        (r-verbs/reset-for-test!)
        (is (= :ok       (r-verbs/register! :test/a "z!" {:tool "x" :command "y"})))
        (is (= :replaced (r-verbs/register! :test/a "z!" {:tool "x2" :command "y2"})))
        (is (= :conflict (r-verbs/register! :test/b "z!" {:tool "z"  :command "y"})))
        (is (= "x2" (-> (r-verbs/lookup "z!") :tool)))))))

(deftest aliases-conflict-policy
  (testing "Alias registry: ok → replaced → conflict"
    (with-fresh-registry
      (fn []
        (r-aliases/reset-for-test!)
        (is (= :ok       (r-aliases/register! :test/a "z" {:full :zz})))
        (is (= :replaced (r-aliases/register! :test/a "z" {:full :zzz})))
        (is (= :conflict (r-aliases/register! :test/b "z" {:full :other})))
        (is (= :zzz (-> (r-aliases/lookup "z") :full)))))))

(deftest batchables-conflict-policy
  (testing "Batchable registry: ok → replaced → conflict"
    (with-fresh-registry
      (fn []
        (r-batchables/reset-for-test!)
        (is (= :ok       (r-batchables/register! :test/a "tool" {:record :rec1})))
        (is (= :replaced (r-batchables/register! :test/a "tool" {:record :rec2})))
        (is (= :conflict (r-batchables/register! :test/b "tool" {:record :rec3})))
        (is (= :rec2 (-> (r-batchables/lookup "tool") :record)))))))

;; =============================================================================
;; §2 — Ownership isolation property
;; =============================================================================

(def gen-owner-key
  (gen/elements [:owner/a :owner/b :owner/c :owner/d]))

(def gen-tool-name
  (gen/such-that not-empty
                 (gen/fmap str/lower-case gen/string-alphanumeric)
                 100))

(defspec deregister-by-owner-isolates 50
  (prop/for-all [owners (gen/vector gen-owner-key 4 12)]
    (with-fresh-registry
      (fn []
        (r-tools/reset-for-test!)
        ;; Register one unique tool name per owner-index
        (doseq [[i o] (map-indexed vector owners)]
          (r-tools/register! o (str "tool-" i) {:handler (constantly i)}))
        ;; Deregister exactly one owner
        (let [target  (first owners)
              before  (set (keys (-> (r-tools/snapshot) :data :by-name)))
              removed (r-tools/deregister-by-owner! target)
              after   (set (keys (-> (r-tools/snapshot) :data :by-name)))
              ;; Tools that should remain: those whose owner ≠ target
              expected-after
              (->> (map-indexed vector owners)
                   (remove #(= target (second %)))
                   (map #(str "tool-" (first %)))
                   set)]
          (and
           ;; Every removed name was indeed in `before` and is gone from `after`
           (every? before removed)
           (every? (complement after) removed)
           ;; Every expected-after name still resolves
           (= expected-after after)))))))

;; =============================================================================
;; §3 — Resolve chain
;; =============================================================================

(deftest resolve-chain-registry-first
  (testing "Registry hit shadows flat fallback"
    (with-fresh-registry
      (fn []
        (r-tools/register! :test/owner "memory_v2" {:handler (constantly ::registry-hit)})
        (let [resolved (registry/resolve-tool-handler "memory_v2")]
          (is (some? resolved) "Tool registered in registry resolves")
          (is (= ::registry-hit (resolved {})) "Returns the registry handler"))))))

(deftest resolve-chain-missing
  (testing "Tool absent from registry AND flat fallback returns nil"
    (with-fresh-registry
      (fn []
        (is (nil? (registry/resolve-tool-handler "nonexistent-tool-xyz-zzz")))))))

(deftest core-seed-resolves-existing-tools
  (testing "After core-seed runs, the 20 consolidated tools resolve through registry"
    (doseq [name ["memory" "kg" "kanban" "agent" "magit" "wave" "session"
                  "preset" "config" "hivemind"]]
      (is (some? (registry/resolve-tool-handler name))
          (str "core-seed registered " name)))))

;; =============================================================================
;; §4 — LSP fallback (Batchable substitutability)
;; =============================================================================

(deftest lookup-batchable-or-default-lsp
  (testing "Tool without explicit Batchable returns DefaultBatchableAdapter"
    (with-fresh-registry
      (fn []
        (r-batchables/reset-for-test!)
        (let [adapter (registry/lookup-batchable-or-default "memory")]
          (is (satisfies? bproto/Batchable adapter)
              "Default adapter satisfies Batchable")
          (is (= "DefaultBatchableAdapter" (-> adapter class .getSimpleName))
              "Returns the fallback record"))))))

(deftest lookup-batchable-or-default-explicit
  (testing "Explicit Batchable record substitutes for the default"
    ;; Use a tool name not seeded under :multi/core so there's no conflict.
    (with-fresh-registry
      (fn []
        (r-batchables/deregister-by-owner! :test/owner)
        (let [explicit (reify bproto/Batchable
                         (batch-execute [_ ops _opts]
                           {:success true :waves {} :summary {:total (count ops)}})
                         (batch-schema [_] {:type "object"}))]
          (r-batchables/register! :test/owner "lsp-test-tool-only"
                                  {:record explicit})
          (let [returned (registry/lookup-batchable-or-default "lsp-test-tool-only")]
            (is (identical? explicit returned)
                "Explicit record substitutes for the default")))))))

;; =============================================================================
;; §5 — Snapshot version stability
;; =============================================================================

(deftest snapshot-version-stable-across-noop
  (testing "Identical-state snapshots have identical :version hashes"
    (let [v1 (:version (registry/snapshot))
          v2 (:version (registry/snapshot))]
      (is (= v1 v2)))))

(deftest snapshot-version-changes-on-mutation
  (testing "Mutation changes the :version hash"
    (with-fresh-registry
      (fn []
        (let [v1 (:version (registry/snapshot))]
          (r-tools/register! :test/v "version-tester" {:handler (constantly nil)})
          (let [v2 (:version (registry/snapshot))]
            (is (not= v1 v2))))))))

;; =============================================================================
;; §6 — register-by-key! routing (the IAddon hooks-walk surface)
;; =============================================================================

(deftest register-by-key-routes-to-correct-child
  (testing ":multi/tool / :multi/verb / :multi/param-alias / :multi/batchable
            route to their respective child registry"
    (with-fresh-registry
      (fn []
        (registry/register-by-key! :test/addon :multi/tool
                                   [{:tool-name "rb-tool" :handler (constantly :ok)}])
        (registry/register-by-key! :test/addon :multi/verb
                                   [{:code "rb!" :tool "rb-tool" :command "do"}])
        (registry/register-by-key! :test/addon :multi/param-alias
                                   [{:short "rb" :full :rb-full}])
        (registry/register-by-key! :test/addon :multi/batchable
                                   [{:tool-name "rb-tool"
                                     :record (reify bproto/Batchable
                                               (batch-execute [_ _ _] {:success true})
                                               (batch-schema [_] {}))}])
        (is (some? (r-tools/lookup "rb-tool"))      "tool routed")
        (is (some? (r-verbs/lookup "rb!"))           "verb routed")
        (is (some? (r-aliases/lookup "rb"))          "alias routed")
        (is (some? (r-batchables/lookup "rb-tool")) "batchable routed")
        ;; Cleanup via owner — proves the IAddon shutdown path works
        (registry/deregister-by-owner! :test/addon)
        (is (nil? (r-tools/lookup "rb-tool")))
        (is (nil? (r-verbs/lookup "rb!")))
        (is (nil? (r-aliases/lookup "rb")))
        (is (nil? (r-batchables/lookup "rb-tool")))))))

