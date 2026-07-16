(ns hive-mcp.extensions.deftool-test
  "deftool projects an MCP tool from ONE registered malli schema: the advertised
   :inputSchema AND the runtime coerce+validate guard both come from the schema."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.schema.registry :as sreg]
            [hive-mcp.extensions.registry :as ereg]
            [hive-mcp.extensions.deftool :as dt]))

(sreg/register! ::greet [:map [:name :string] [:times {:optional true} :int]])

(defn greet-handler [{:keys [name times]}]
  {:greeting (apply str (repeat (or times 1) (str "hi " name " ")))})

(deftest schema->tool-projects-and-guards
  (let [t (dt/schema->tool "greet" "greets" ::greet greet-handler)]
    (testing "one schema-key yields the MCP :inputSchema (malli -> JSON Schema)"
      (is (= "greet" (:name t)))
      (is (= "greets" (:description t)))
      (is (= "object" (get-in t [:inputSchema :type])))
      (is (= #{:name :times} (set (keys (get-in t [:inputSchema :properties])))))
      (is (= [:name] (get-in t [:inputSchema :required]))))
    (testing "the handler coerces+validates params against the SAME schema"
      (is (= {:greeting "hi bob "} ((:handler t) {:name "bob"})))
      (is (= {:greeting "hi al hi al "} ((:handler t) {:name "al" :times 2}))))
    (testing "an invalid call is refused by the schema BEFORE the body runs"
      (is (= :schema/invalid
             (try ((:handler t) {}) :no-throw
                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))

(deftest deftool-registers-a-schema-projected-tool
  (ereg/deregister-tool! "greet-reg")
  (dt/deftool "greet-reg" {:description "greets" :schema ::greet :handler greet-handler})
  (let [t (first (filter #(= "greet-reg" (:name %)) (ereg/get-registered-tools)))]
    (testing "the macro projects + registers the tool through register-tool!"
      (is (some? t))
      (is (= "object" (get-in t [:inputSchema :type])))
      (is (= {:greeting "hi al "} ((:handler t) {:name "al"}))))))
