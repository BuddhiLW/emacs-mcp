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

;; -----------------------------------------------------------------------------
;; Expectations are READ FROM THE FIXTURE, never restated as literals. Retune the
;; EDN and these tests retune with it; the only thing hardcoded here is the naming
;; rule itself (dimension -> collection), which is the contract under test.
;; -----------------------------------------------------------------------------

(def ^:private fixture (delay (cfg-test/read-edn-resource fixture-path)))

(defn- fixture-providers [] (-> @fixture :embedder :providers))

(defn- fixture-route [type-kw] (-> @fixture :embedder :routes type-kw))

(defn- fixture-dimension [provider-key] (:dimension (get (fixture-providers) provider-key)))

(defn- fixture-max-tokens [provider-key] (:max-tokens (get (fixture-providers) provider-key)))

(defn- fixture-dimensions [] (distinct (keep :dimension (vals (fixture-providers)))))

(defn- collection-for-dimension
  "The naming rule the service is contracted to follow: 768 keeps the legacy
   base name, everything else is suffixed."
  [dimension]
  (if (= dimension 768)
    "hive-mcp-memory"
    (str "hive-mcp-memory-" dimension "d")))

(defn- fixture-expected-collections []
  (into #{"hive-mcp-memory"} (map collection-for-dimension (fixture-dimensions))))

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
;; num_ctx — the context window an ollama provider is loaded with
;; =============================================================================

(deftest an-ollama-provider-declares-its-context-window-from-config
  (testing ":max-tokens reaches the provider as :num-ctx"
    (with-redefs [global-config/get-secret no-secrets
                  registry/get-provider    stub-registry]
      (let [resolved (service/resolve-provider-for-type "note")]
        (is (= (fixture-max-tokens (fixture-route :type/note))
               (get-in resolved [:provider :emb-cfg :options :num-ctx]))
            "the number comes from the fixture, so retuning it retunes the test")))))

(deftest a-qwen3-model-is-not-loaded-at-32k-just-for-being-a-qwen3
  (testing "the context window follows config, not a regex on the model name"
    ;; THE BUG: num_ctx came from a hardcoded table — qwen3-embedding:8b was
    ;; special-cased to 8192 and every OTHER qwen3 fell through to 32768. The 4b
    ;; was therefore loaded with a 32k context: 11 GB, spilling 34% onto the CPU
    ;; of an 8 GB card, and embeds went from ~0.1s to ~4s.
    (global-config/update-in-config! [:embedder :routes] assoc :type/note :ollama-qwen3-4b)
    (with-redefs [global-config/get-secret no-secrets
                  registry/get-provider    stub-registry]
      (let [resolved (service/resolve-provider-for-type "note")
            declared (fixture-max-tokens :ollama-qwen3-4b)]
        (is (= :ollama-qwen3-4b (:provider-key resolved)))
        (is (= declared (get-in resolved [:provider :emb-cfg :options :num-ctx]))
            "what the provider declares — not what a regex on the name guesses")
        (is (not= 32768 (get-in resolved [:provider :emb-cfg :options :num-ctx]))
            "32768 is the value that spilled the model onto the CPU")))))

;; =============================================================================
;; type->collection-names — which collections an unscoped read searches
;; =============================================================================

(deftest unscoped-search-covers-every-configured-dimension
  (testing "the searched collections are DERIVED from the configured providers"
    ;; Expectation is computed from the fixture: add a provider with a new
    ;; dimension there and this test demands the collection, with no edit here.
    (is (= (fixture-expected-collections)
           (set (service/type->collection-names nil))))))

(deftest a-configured-dimension-is-searchable-without-editing-a-list
  (testing "every dimension some provider declares is searched"
    ;; THE BUG: this list used to be hardcoded [base, -1024d, -4096d]. Entries
    ;; were written to hive-mcp-memory-2560d and then never found again — every
    ;; unscoped read (get-by-id, tag query, semantic search, dedup) searched a
    ;; list that did not contain the collection the writer had just used.
    (let [searched (set (service/type->collection-names nil))]
      (doseq [dim (fixture-dimensions)]
        (is (contains? searched (collection-for-dimension dim))
            (str "dimension " dim " is configured but not searched — a write-only hole"))))))

(deftest a-retired-provider-drops-out-of-the-search-list
  (testing "removing a provider from config removes its collection from the search"
    (let [victim   :ollama-qwen3-4b
          orphaned (collection-for-dimension (fixture-dimension victim))]
      (global-config/update-in-config! [:embedder :providers] dissoc victim)
      (is (not (contains? (set (service/type->collection-names nil)) orphaned))
          "the list tracks config — so a collection may not be retired while it still holds rows"))))

(deftest a-typed-read-searches-exactly-one-collection
  (testing "a scoped read goes straight to the type's own collection"
    (with-redefs [global-config/get-secret no-secrets
                  registry/get-provider    stub-registry]
      (is (= [(collection-for-dimension (fixture-dimension (fixture-route :type/note)))]
             (service/type->collection-names "note"))))))

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
