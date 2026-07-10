(ns hive-mcp.extensions.registry-test
  "Tests for the opaque extension registry.

   Covers:
   - Function extension CRUD (register, get, deregister)
   - Batch registration (register-many!)
   - Schema extension registration and merging
   - Thread safety (concurrent writes)
   - clear-all! resets all registries
   - Precondition enforcement"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-registry-fixture [f]
  (ext/clear-all!)
  (f)
  (ext/clear-all!))

(use-fixtures :each clean-registry-fixture)

;; =============================================================================
;; Function Extension Registry
;; =============================================================================

(deftest register-and-get-extension
  (testing "register returns the key"
    (is (= :test/foo (ext/register! :test/foo identity))))

  (testing "get-extension returns the registered fn"
    (is (= identity (ext/get-extension :test/foo))))

  (testing "get-extension returns nil for unregistered key"
    (is (nil? (ext/get-extension :test/nonexistent))))

  (testing "get-extension returns custom default"
    (is (= :fallback (ext/get-extension :test/nonexistent :fallback)))))

(deftest register-replaces-silently
  (ext/register! :test/k inc)
  (ext/register! :test/k dec)
  (testing "last write wins"
    (is (= dec (ext/get-extension :test/k)))))

(deftest extension-available?-check
  (testing "false before registration"
    (is (false? (ext/extension-available? :test/x))))

  (ext/register! :test/x str)

  (testing "true after registration"
    (is (true? (ext/extension-available? :test/x)))))

(deftest registered-keys-returns-set
  (ext/register! :a/one identity)
  (ext/register! :b/two identity)
  (testing "returns set of registered keys"
    (let [ks (ext/registered-keys)]
      (is (set? ks))
      (is (contains? ks :a/one))
      (is (contains? ks :b/two)))))

(deftest deregister-removes-extension
  (ext/register! :test/remove identity)
  (is (true? (ext/extension-available? :test/remove)))

  (testing "deregister returns the key"
    (is (= :test/remove (ext/deregister! :test/remove))))

  (testing "extension is gone after deregister"
    (is (false? (ext/extension-available? :test/remove)))
    (is (nil? (ext/get-extension :test/remove)))))

(deftest deregister-nonexistent-is-noop
  (testing "deregistering nonexistent key doesn't throw"
    (is (= :test/ghost (ext/deregister! :test/ghost)))))

;; =============================================================================
;; Batch Registration
;; =============================================================================

(deftest register-many-registers-all
  (let [result (ext/register-many! {:a/one inc :a/two dec :a/three str})]
    (testing "returns registered keys"
      (is (= #{:a/one :a/two :a/three} (set result))))

    (testing "all extensions retrievable"
      (is (= inc (ext/get-extension :a/one)))
      (is (= dec (ext/get-extension :a/two)))
      (is (= str (ext/get-extension :a/three))))))

(deftest register-many-is-atomic
  (ext/register! :pre/existing identity)
  (ext/register-many! {:batch/a inc :batch/b dec})

  (testing "previous registrations preserved"
    (is (= identity (ext/get-extension :pre/existing))))

  (testing "batch registrations added"
    (is (= inc (ext/get-extension :batch/a)))))

;; =============================================================================
;; Schema Extension Registry
;; =============================================================================

(deftest register-schema-and-get
  (ext/register-schema! "workflow" {"max_slots" {:type "integer" :description "Max slots"}})

  (testing "get-schema-extensions returns registered properties"
    (let [ext-map (ext/get-schema-extensions "workflow")]
      (is (map? ext-map))
      (is (= "integer" (get-in ext-map ["max_slots" :type]))))))

(deftest register-schema-merges
  (ext/register-schema! "agent" {"name" {:type "string"}})
  (ext/register-schema! "agent" {"model" {:type "string"}})

  (testing "second registration merges with first"
    (let [ext-map (ext/get-schema-extensions "agent")]
      (is (contains? ext-map "name"))
      (is (contains? ext-map "model")))))

(deftest register-schema-overwrites-same-key
  (ext/register-schema! "tool" {"param" {:type "string" :description "old"}})
  (ext/register-schema! "tool" {"param" {:type "integer" :description "new"}})

  (testing "last write wins for same param"
    (is (= "integer" (get-in (ext/get-schema-extensions "tool") ["param" :type])))))

(deftest get-schema-extensions-returns-nil-for-unregistered
  (testing "nil for unknown tool name"
    (is (nil? (ext/get-schema-extensions "nonexistent")))))

(deftest clear-all-schemas-resets-only-schemas
  (ext/register! :fn/test identity)
  (ext/register-schema! "tool" {"p" {:type "string"}})

  (ext/clear-all-schemas!)

  (testing "schema registry cleared"
    (is (nil? (ext/get-schema-extensions "tool"))))

  (testing "fn registry untouched"
    (is (= identity (ext/get-extension :fn/test)))))

;; =============================================================================
;; clear-all! resets everything
;; =============================================================================

(deftest clear-all-resets-both-registries
  (ext/register! :fn/a identity)
  (ext/register-schema! "tool" {"p" {:type "string"}})

  (ext/clear-all!)

  (testing "fn registry cleared"
    (is (nil? (ext/get-extension :fn/a)))
    (is (empty? (ext/registered-keys))))

  (testing "schema registry cleared"
    (is (nil? (ext/get-schema-extensions "tool")))))

;; =============================================================================
;; Tool Registry (dynamic MCP tool definitions)
;; =============================================================================

(defn- tool-def [name] {:name name :handler (fn [_] :ok)})

(deftest register-tool-and-list
  (testing "register-tool! returns the tool name"
    (is (= "alpha" (ext/register-tool! (tool-def "alpha")))))

  (testing "get-registered-tools includes the registered def"
    (let [names (set (map :name (ext/get-registered-tools)))]
      (is (contains? names "alpha")))))

(deftest register-tool-replaces-by-name
  (ext/register-tool! (assoc (tool-def "dup") :tag :first))
  (ext/register-tool! (assoc (tool-def "dup") :tag :second))
  (testing "last write wins by tool name; single entry"
    (let [matches (filter #(= "dup" (:name %)) (ext/get-registered-tools))]
      (is (= 1 (count matches)))
      (is (= :second (:tag (first matches)))))))

(deftest deregister-tool-removes
  (ext/register-tool! (tool-def "gone"))
  (testing "deregister-tool! returns the name and removes it"
    (is (= "gone" (ext/deregister-tool! "gone")))
    (is (not (contains? (set (map :name (ext/get-registered-tools))) "gone")))))

(deftest clear-all-tools-resets-only-tools
  (ext/register! :fn/keep identity)
  (ext/register-tool! (tool-def "temp"))
  (ext/clear-all-tools!)
  (testing "tool registry cleared"
    (is (empty? (ext/get-registered-tools))))
  (testing "fn registry untouched"
    (is (= identity (ext/get-extension :fn/keep)))))

(deftest clear-all-resets-tool-registry
  (ext/register-tool! (tool-def "wiped"))
  (ext/clear-all!)
  (testing "clear-all! also clears the tool registry"
    (is (empty? (ext/get-registered-tools)))))

(deftest register-tool-rejects-non-string-name
  (testing "keyword :name throws AssertionError"
    (is (thrown? AssertionError (ext/register-tool! {:name :bad :handler (fn [_] :ok)})))))

(deftest register-tool-rejects-non-ifn-handler
  (testing "non-callable :handler throws AssertionError"
    (is (thrown? AssertionError (ext/register-tool! {:name "x" :handler "not-a-fn"})))))

;; =============================================================================
;; Precondition Enforcement
;; =============================================================================

(deftest register-rejects-non-keyword-key
  (testing "string key throws AssertionError"
    (is (thrown? AssertionError (ext/register! "bad-key" identity)))))

(deftest register-rejects-non-ifn-value
  (testing "non-callable value throws AssertionError"
    (is (thrown? AssertionError (ext/register! :test/k "not-a-fn")))))

(deftest register-many-rejects-non-map
  (testing "non-map throws AssertionError"
    (is (thrown? AssertionError (ext/register-many! [:a :b])))))

(deftest register-schema-rejects-non-string-name
  (testing "keyword tool name throws AssertionError"
    (is (thrown? AssertionError (ext/register-schema! :bad-name {})))))

(deftest register-schema-rejects-non-map-properties
  (testing "non-map properties throws AssertionError"
    (is (thrown? AssertionError (ext/register-schema! "tool" "bad")))))

;; =============================================================================
;; Thread Safety (concurrent writes)
;; =============================================================================

(deftest concurrent-registrations-no-data-loss
  (let [n 100
        futures (mapv (fn [i]
                        (future
                          (ext/register! (keyword "concurrent" (str i)) identity)))
                      (range n))]
    (doseq [f futures] @f)

    (testing "all concurrent registrations visible"
      (is (= n (count (filter #(= "concurrent" (namespace %))
                              (ext/registered-keys))))))))

(deftest concurrent-schema-registrations-merge-correctly
  (let [n 50
        futures (mapv (fn [i]
                        (future
                          (ext/register-schema! "concurrent-tool"
                                                {(str "param_" i) {:type "string"}})))
                      (range n))]
    (doseq [f futures] @f)

    (testing "all schema properties present"
      (is (= n (count (ext/get-schema-extensions "concurrent-tool")))))))
