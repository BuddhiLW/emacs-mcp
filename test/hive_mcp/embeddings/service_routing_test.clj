(ns hive-mcp.embeddings.service-routing-test
  "Per-memory-type routing — the venice :type/plan flip in
   `service/configure-defaults!` and the matching impl dispatch in
   `service/resolve-provider-for-type`.

   Config comes from `embedder_routing_fixture.edn`, NOT from the developer's
   ~/.config/hive-mcp. These tests previously called `reset-config!`, which
   reloads that file from disk — so they asserted merge.clj's defaults while
   reading whatever routing the machine happened to be tuned to, and rotted the
   moment the machine moved to qwen3/1024d. A unit test may not depend on a live,
   shared, user-owned system.

   The two collaborators the resolver reaches for are stubbed:
     config    -> an EDN-declared source (hive-mcp.config.test-support)
     registry  -> `get-provider` returns its argument, so dispatch is
                  introspectable without constructing a real HTTP embedder."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.config.test-support :as cfg-test]
            [hive-mcp.embeddings.registry :as registry]
            [hive-mcp.embeddings.service :as service]))

(def ^:private fixture-path "embedder_routing_fixture.edn")

;; Every test runs against the EDN fixture, in memory. Nothing reads or writes
;; the real config file; the registry cache is cleared so a stubbed provider from
;; one test cannot leak into the next.
(use-fixtures :each
  (fn [f]
    (registry/clear-cache!)
    (try
      ((cfg-test/edn-config-fixture fixture-path) f)
      (finally (registry/clear-cache!)))))

(defn- plan-route []
  (-> (global-config/get-global-config) :embedder :routes :type/plan))

(defn- stub-registry
  "registry/get-provider returns the EmbeddingConfig it was handed, so a test can
   assert WHICH provider spec was dispatched without building a live client."
  [emb-cfg]
  {:emb-cfg emb-cfg})

(defn- no-secrets [_] nil)

(defn- venice-key-present [k]
  (case k :venice-api-key "fake-venice-key" nil))

(defn- all-keys-present [k]
  (case k
    :venice-api-key     "fake-venice-key"
    :openrouter-api-key "fake-openrouter-key"
    nil))

;; =============================================================================
;; configure-defaults! — :type/plan routing flip
;; =============================================================================

(deftest plan-route-stays-on-openrouter-when-venice-key-absent
  (testing ":type/plan keeps its configured :openrouter-qwen3 without VENICE_API_KEY"
    (with-redefs [global-config/get-secret no-secrets]
      (service/configure-defaults!))
    (is (= :openrouter-qwen3 (plan-route)))))

(deftest plan-route-flips-to-venice-when-key-present
  (testing ":type/plan flips to :venice-qwen3 when VENICE_API_KEY is set"
    (with-redefs [global-config/get-secret all-keys-present]
      (service/configure-defaults!))
    (is (= :venice-qwen3 (plan-route))
        "plan route should flip to :venice-qwen3 when key is present")))

(deftest other-type-routes-untouched-by-venice-flip
  (testing "the venice flip mutates ONLY :type/plan"
    (with-redefs [global-config/get-secret venice-key-present]
      (service/configure-defaults!))
    (let [routes (-> (global-config/get-global-config) :embedder :routes)]
      ;; Asserted against the fixture, so retuning the real machine cannot rot these.
      (is (= :openrouter-qwen3 (:type/decision routes)))
      (is (= :openrouter-qwen3 (:type/conversation-turn routes)))
      (is (= :ollama-nomic     (:type/note routes)))
      (is (= :ollama-nomic     (:type/snippet routes)))
      (is (= :venice-qwen3     (:type/plan routes)) "only :type/plan flipped"))))

(deftest user-pinned-plan-route-survives-venice-key
  (testing "a route the user pinned must outrank the venice-key heuristic"
    (global-config/update-in-config! [:embedder :routes] assoc :type/plan :ollama-nomic)
    (with-redefs [global-config/get-secret venice-key-present]
      (service/configure-defaults!))
    (is (= :ollama-nomic (plan-route))
        "user-pinned :type/plan must outrank the venice key heuristic")))

(deftest user-pinned-plan-route-to-venice-stays-venice
  (testing "an explicit non-default pin is left intact"
    (global-config/update-in-config! [:embedder :routes] assoc :type/plan :venice-qwen3)
    (with-redefs [global-config/get-secret no-secrets]
      (service/configure-defaults!))
    (is (= :venice-qwen3 (plan-route))
        "explicit user-pinned :venice-qwen3 must NOT be reverted to default")))

;; =============================================================================
;; resolve-provider-for-type — impl dispatch
;; =============================================================================

(deftest resolve-provider-for-type-plan-uses-venice-spec
  (testing "when :type/plan routes to :venice-qwen3, the resolver returns 4096d/32k"
    (with-redefs [global-config/get-secret venice-key-present
                  registry/get-provider    stub-registry]
      (service/configure-defaults!)
      (let [resolved (service/resolve-provider-for-type "plan")]
        (is (= :venice-qwen3 (:provider-key resolved)))
        (is (= 4096 (:dimension resolved)))
        (is (= 32768 (:max-tokens resolved)))
        (is (= "hive-mcp-memory-4096d" (:collection-name resolved))
            "collection name is derived from the dimension")
        (let [emb-cfg (get-in resolved [:provider :emb-cfg])]
          (is (= :venice (:provider-type emb-cfg)))
          (is (= "text-embedding-qwen3-8b" (:model emb-cfg))))))))

(deftest resolve-provider-for-type-honours-the-configured-route
  (testing "a type routed to ollama in config resolves through the :ollama impl"
    (with-redefs [global-config/get-secret no-secrets
                  registry/get-provider    stub-registry]
      (service/configure-defaults!)
      (let [resolved (service/resolve-provider-for-type "note")]
        ;; The fixture routes note -> :ollama-nomic. The assertion tracks the
        ;; fixture, not the machine: change the route above and this changes with it.
        (is (= :ollama-nomic (:provider-key resolved)))
        (is (= 768 (:dimension resolved)))
        (is (= "hive-mcp-memory" (:collection-name resolved)))
        (is (= :ollama (get-in resolved [:provider :emb-cfg :provider-type])))))))

;; =============================================================================
;; The seam itself — a test must not be able to read live config by accident
;; =============================================================================

(deftest fixture-config-shadows-the-real-config-file
  (testing "the bound source, not ~/.config/hive-mcp, is what the code sees"
    (is (= :openrouter-qwen3
           (-> (global-config/get-global-config) :embedder :routes :type/convention))
        "fixture says openrouter; the live machine routes convention elsewhere —
         if this ever reads the machine, the two disagree and this test fails")))

(deftest writes-under-a-bound-source-do-not-touch-disk
  (testing "config writes land in the fixture's atom, never in the user's file"
    (global-config/set-config-value! "embedder.default" :sentinel-value)
    (is (= :sentinel-value (-> (global-config/get-global-config) :embedder :default))
        "the write is visible through the bound source")))

(deftest a-read-only-source-refuses-writes-loudly
  (testing "writing through the no-op source throws rather than silently landing elsewhere"
    (cfg-test/with-no-config
      (is (thrown? clojure.lang.ExceptionInfo
                   (global-config/update-in-config! [:embedder :routes] assoc :type/note :x))))))
