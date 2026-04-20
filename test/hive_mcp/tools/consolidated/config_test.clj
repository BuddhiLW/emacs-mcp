(ns hive-mcp.tools.consolidated.config-test
  "Tests for consolidated config CLI tool.

   Test Coverage:
   1. Handler map completeness
   2. CLI handler - help, unknown command
   3. Get handler - reads dotted key paths
   4. Set handler - updates and persists values
   5. List handler - returns full config
   6. Reload handler - re-reads from disk
   7. Tool definition structure
   8. Config path migration (legacy fallback)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [hive-mcp.tools.consolidated.config :as config-tool]
            [hive-mcp.config.core :as config]
            [hive-mcp.config.io :as config-io]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn parse-response
  "Parse JSON response from handler."
  [result]
  (when (and result (not (:isError result)) (:text result))
    (try
      (json/read-str (:text result) :key-fn keyword)
      (catch Exception _ nil))))

(def ^:private test-config-dir
  "Temp directory for test config files."
  (str (System/getProperty "java.io.tmpdir") "/hive-mcp-config-test"))

(def ^:private test-config-path
  (str test-config-dir "/config.edn"))

;; Fixture: reset config state + clean up temp files before/after each test
(defn clean-config-fixture [f]
  (config/reset-config!)
  ;; Clean up any leftover test files
  (let [dir (io/file test-config-dir)]
    (when (.exists dir)
      (doseq [f (.listFiles dir)]
        (.delete f))
      (.delete dir)))
  (f)
  (config/reset-config!)
  (let [dir (io/file test-config-dir)]
    (when (.exists dir)
      (doseq [f (.listFiles dir)]
        (.delete f))
      (.delete dir))))

(use-fixtures :each clean-config-fixture)

;; =============================================================================
;; Handler Map Tests
;; =============================================================================

(deftest test-handlers-map-completeness
  (testing "all handlers are registered"
    (is (contains? config-tool/handlers :get))
    (is (contains? config-tool/handlers :set))
    (is (contains? config-tool/handlers :list))
    (is (contains? config-tool/handlers :reload))))

(deftest test-handlers-are-functions
  (testing "all handlers are functions"
    (doseq [[k v] config-tool/handlers]
      (is (fn? v) (str "Handler " k " should be a function")))))

;; =============================================================================
;; CLI Handler Tests
;; =============================================================================

(deftest test-cli-handler-help-command
  (testing "help command returns help text with all commands"
    (let [result (config-tool/handle-config {:command "help"})]
      (is (not (:isError result)))
      (is (= "text" (:type result)))
      (is (str/includes? (:text result) "Available commands"))
      (is (str/includes? (:text result) "get"))
      (is (str/includes? (:text result) "set"))
      (is (str/includes? (:text result) "list"))
      (is (str/includes? (:text result) "reload")))))

(deftest test-cli-handler-unknown-command
  (testing "unknown command returns error"
    (let [result (config-tool/handle-config {:command "bogus"})]
      (is (:isError result))
      (is (str/includes? (:text result) "Unknown command")))))

;; =============================================================================
;; Get Handler Tests
;; =============================================================================

(deftest test-get-handler-reads-value
  (testing "get reads a dotted key path from defaults"
    ;; Load defaults by loading with a non-existent file
    (config/load-global-config! "/nonexistent/path/config.edn")
    (let [result (config-tool/handle-config {:command "get"
                                             :key "embeddings.ollama.host"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= "embeddings.ollama.host" (:key parsed)))
        (is (= "http://localhost:11434" (:value parsed)))))))

(deftest test-get-handler-nested-key
  (testing "get reads nested config value"
    (config/load-global-config! "/nonexistent/path/config.edn")
    (let [result (config-tool/handle-config {:command "get"
                                             :key "defaults.kg-backend"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (= "datahike" (:value parsed)))))))

(deftest test-get-handler-missing-key
  (testing "get returns nil for non-existent key"
    (config/load-global-config! "/nonexistent/path/config.edn")
    (let [result (config-tool/handle-config {:command "get"
                                             :key "nonexistent.key.path"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (nil? (:value parsed)))))))

(deftest test-get-handler-no-key-param
  (testing "get without key returns error"
    (let [result (config-tool/handle-config {:command "get"})]
      (is (:isError result))
      (is (str/includes? (:text result) "Missing required parameter")))))

;; =============================================================================
;; Set Handler Tests
;; =============================================================================

(deftest test-set-handler-updates-value
  (testing "set updates a value in the config without writing to real config"
    ;; Load defaults first (nonexistent path → pure defaults, no disk write)
    (config/load-global-config! "/nonexistent/path/config.edn")
    ;; Stub out disk writes so we never touch ~/.config/hive-mcp/config.edn
    (with-redefs [config-io/write-config! (fn [_config _path] nil)]
      (let [result (config-tool/handle-set {:key "embeddings.ollama.host"
                                            :value "http://new-host:11434"})]
        ;; Check atom was updated (in-memory only, no disk side-effect)
        (is (= "http://new-host:11434"
               (config/get-config-value "embeddings.ollama.host")))))))

(deftest test-set-handler-no-key-param
  (testing "set without key returns error"
    (let [result (config-tool/handle-config {:command "set" :value "foo"})]
      (is (:isError result))
      (is (str/includes? (:text result) "Missing required parameter")))))

;; =============================================================================
;; List Handler Tests
;; =============================================================================

(deftest test-list-handler-returns-config
  (testing "list returns full config map"
    (config/load-global-config! "/nonexistent/path/config.edn")
    (let [result (config-tool/handle-config {:command "list"})]
      (is (not (:isError result)))
      (let [parsed (parse-response result)]
        (is (map? (:config parsed)))
        (is (contains? (:config parsed) :embeddings))
        (is (contains? (:config parsed) :defaults))
        (is (contains? (:config parsed) :project-roots))))))

;; =============================================================================
;; Reload Handler Tests
;; =============================================================================

(deftest test-reload-handler-reloads-from-disk
  (testing "reload re-reads config from disk"
    ;; Create a test config file
    (let [dir (io/file test-config-dir)]
      (.mkdirs dir)
      (spit test-config-path (pr-str {:project-roots ["/test/path"]
                                      :custom-key "custom-value"})))
    ;; Load from test path first to set it up
    (config/load-global-config! test-config-path)
    ;; Verify custom key is loaded
    (is (= "custom-value" (config/get-config-value "custom-key")))
    ;; Reload handler uses default path, so we test with redefs
    ;; Capture original fn before with-redefs to avoid infinite recursion
    (let [orig-load! config/load-global-config!]
      (with-redefs [config/load-global-config!
                    (fn
                      ([] (orig-load! test-config-path))
                      ([path] (orig-load! path)))]
        (let [result (config-tool/handle-config {:command "reload"})]
          (is (not (:isError result)))
          (let [parsed (parse-response result)]
            (is (= "reloaded" (:status parsed)))))))))

;; =============================================================================
;; Config Key Path Parsing Tests
;; =============================================================================

(deftest test-parse-key-path
  (testing "dotted key paths are parsed correctly"
    (is (= [:embeddings :ollama :host]
           (config/parse-key-path "embeddings.ollama.host")))
    (is (= [:defaults]
           (config/parse-key-path "defaults")))
    (is (nil? (config/parse-key-path nil)))
    (is (nil? (config/parse-key-path "")))))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

(deftest test-tool-definition-structure
  (testing "tool-def has required fields"
    (is (= "config" (:name config-tool/tool-def)))
    (is (true? (:consolidated config-tool/tool-def)))
    (is (string? (:description config-tool/tool-def)))
    (is (map? (:inputSchema config-tool/tool-def)))
    (is (fn? (:handler config-tool/tool-def)))))

(deftest test-tool-definition-enum
  (testing "tool-def enum includes all commands"
    (let [enum (get-in config-tool/tool-def [:inputSchema :properties "command" :enum])]
      (is (some #(= "get" %) enum))
      (is (some #(= "set" %) enum))
      (is (some #(= "list" %) enum))
      (is (some #(= "reload" %) enum))
      (is (some #(= "help" %) enum)))))

(deftest test-tool-description-mentions-config
  (testing "tool description mentions config.edn"
    (is (str/includes? (:description config-tool/tool-def) "config.edn"))))

(deftest test-tools-vector
  (testing "tools vector contains tool-def"
    (is (= 1 (count config-tool/tools)))
    (is (= config-tool/tool-def (first config-tool/tools)))))

;; =============================================================================
;; Default Config Schema Tests
;; =============================================================================

(deftest test-default-config-has-embeddings
  (testing "default config includes :embeddings section"
    (config/load-global-config! "/nonexistent/path/config.edn")
    (let [cfg (config/get-global-config)]
      (is (map? (:embeddings cfg)))
      (is (map? (get-in cfg [:embeddings :ollama])))
      (is (= "http://localhost:11434" (get-in cfg [:embeddings :ollama :host])))
      (is (= "nomic-embed-text" (get-in cfg [:embeddings :ollama :model])))
      (is (map? (get-in cfg [:embeddings :openrouter])))
      (is (= "qwen/qwen3-embedding-8b" (get-in cfg [:embeddings :openrouter :model]))))))
