(ns hive-mcp.embeddings.service-routing-test
  "Per-memory-type routing tests — focused on the venice :type/plan flip
   in `service/configure-defaults!` and the matching `:venice` impl
   dispatch in `service/resolve-provider-for-type`.

   Isolated from service_test (which exercises per-collection routing) so
   the global-config mutations stay scoped to one fixture."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.config.core :as global-config]
            [hive-mcp.embeddings.registry :as registry]
            [hive-mcp.embeddings.service :as service]))

(defn- with-fresh-config
  "Fixture that resets global config + registry cache around each test.
   Required because configure-defaults! mutates :embedder routes."
  [f]
  (try
    (global-config/reset-config!)
    (registry/clear-cache!)
    (f)
    (finally
      (global-config/reset-config!)
      (registry/clear-cache!))))

(use-fixtures :each with-fresh-config)

(defn- plan-route []
  (-> (global-config/get-global-config) :embedder :routes :type/plan))

;; =============================================================================
;; configure-defaults! — :type/plan routing flip
;; =============================================================================

(deftest plan-route-stays-on-openrouter-when-venice-key-absent
  (testing ":type/plan keeps default :openrouter-qwen3 without VENICE_API_KEY"
    (with-redefs [global-config/get-secret (constantly nil)]
      (service/configure-defaults!))
    (is (= :openrouter-qwen3 (plan-route)))))

(deftest plan-route-flips-to-venice-when-key-present
  (testing ":type/plan flips to :venice-qwen3 when VENICE_API_KEY is set"
    (with-redefs [global-config/get-secret
                  (fn [k]
                    (case k
                      :venice-api-key     "fake-venice-key"
                      :openrouter-api-key "fake-openrouter-key"
                      nil))]
      (service/configure-defaults!))
    (is (= :venice-qwen3 (plan-route))
        "plan route should flip to :venice-qwen3 when key is present")))

(deftest other-type-routes-untouched-by-venice-flip
  (testing "Venice flip ONLY mutates :type/plan — other routes stay intact"
    (with-redefs [global-config/get-secret
                  (fn [k]
                    (case k
                      :venice-api-key "fake-venice-key"
                      nil))]
      (service/configure-defaults!))
    (let [routes (-> (global-config/get-global-config) :embedder :routes)]
      ;; All other types still route to their merge.clj defaults
      (is (= :openrouter-qwen3 (:type/decision routes)))
      (is (= :openrouter-qwen3 (:type/conversation-turn routes)))
      (is (= :ollama-nomic     (:type/note routes)))
      (is (= :ollama-nomic     (:type/snippet routes)))
      ;; Only :type/plan flipped
      (is (= :venice-qwen3 (:type/plan routes))))))

(deftest user-pinned-plan-route-survives-venice-key
  (testing "If user has set embedder.routes.type/plan via `hive config set`, the
            venice flip MUST NOT override it — even when VENICE_API_KEY is set"
    ;; Simulate a user config that pins :type/plan to ollama-nomic. This is
    ;; what `hive config set embedder.routes.type/plan :ollama-nomic` would
    ;; produce after deep-merge with defaults at config load.
    (global-config/update-in-config!
      [:embedder :routes] assoc :type/plan :ollama-nomic)
    (with-redefs [global-config/get-secret
                  (fn [k]
                    (case k
                      :venice-api-key "fake-venice-key"
                      nil))]
      (service/configure-defaults!))
    (is (= :ollama-nomic (plan-route))
        "user-pinned :type/plan must outrank the venice key heuristic")))

(deftest user-pinned-plan-route-to-venice-stays-venice
  (testing "User explicitly set :type/plan to :venice-qwen3 via config —
            also a non-default value, must be left intact"
    (global-config/update-in-config!
      [:embedder :routes] assoc :type/plan :venice-qwen3)
    (with-redefs [global-config/get-secret (constantly nil)]
      (service/configure-defaults!))
    (is (= :venice-qwen3 (plan-route))
        "explicit user-pinned :venice-qwen3 must NOT be reverted to default")))

;; =============================================================================
;; resolve-provider-for-type — :venice impl dispatch
;; =============================================================================

(deftest resolve-provider-for-type-plan-uses-venice-spec
  (testing "When :type/plan routes to :venice-qwen3, resolver returns 4096d/32k"
    ;; Skip actual provider construction by stubbing registry/get-provider
    (with-redefs [global-config/get-secret
                  (fn [k]
                    (case k
                      :venice-api-key "fake-venice-key"
                      nil))
                  registry/get-provider
                  (fn [emb-cfg]
                    ;; Return the config itself so we can introspect dispatch
                    {:emb-cfg emb-cfg})]
      (service/configure-defaults!)
      (let [resolved (service/resolve-provider-for-type "plan")]
        (is (= :venice-qwen3 (:provider-key resolved)))
        (is (= 4096 (:dimension resolved)))
        (is (= 32768 (:max-tokens resolved)))
        ;; collection-name uses dimension-based naming → 4096d
        (is (= "hive-mcp-memory-4096d" (:collection-name resolved)))
        ;; The EmbeddingConfig handed to registry/get-provider had impl :venice
        (let [emb-cfg (get-in resolved [:provider :emb-cfg])]
          (is (= :venice (:provider-type emb-cfg)))
          (is (= "text-embedding-qwen3-8b" (:model emb-cfg))))))))

(deftest resolve-provider-for-type-default-types-stay-on-ollama
  (testing "Untouched types (note, snippet) still resolve through :ollama impl"
    (with-redefs [global-config/get-secret (constantly nil)
                  registry/get-provider     (fn [emb-cfg] {:emb-cfg emb-cfg})]
      (service/configure-defaults!)
      (let [resolved (service/resolve-provider-for-type "note")]
        (is (= :ollama-nomic (:provider-key resolved)))
        (is (= 768 (:dimension resolved)))
        (is (= "hive-mcp-memory" (:collection-name resolved)))
        (let [emb-cfg (get-in resolved [:provider :emb-cfg])]
          (is (= :ollama (:provider-type emb-cfg))))))))
