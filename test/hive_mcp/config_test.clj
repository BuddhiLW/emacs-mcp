(ns hive-mcp.config-test
  "Unit tests for global config loader (hive-mcp.config.core).

   TEST ISOLATION GUARANTEES (kanban 20260403150842-665be79c):

   The production `global-config` is a `defonce` atom shared with a running
   MCP server. A test that naively `reset!`s it to nil silently kills server
   state (see convention 20260122235103-7151cc29). The real config file at
   `~/.config/hive-mcp/config.edn` is the user's live, irreplaceable config —
   tests must NEVER touch it.

   This namespace enforces a trifecta of safety nets:

   1. snapshot+restore (snapshot-config-fixture): captures `@global-config`
      before each test and restores bit-identical after, regardless of test
      outcome. Production atom is guaranteed pristine post-suite.

   2. write-guard (guard-real-config-fixture): redefines
      `config-io/write-config!` to throw if any test ever targets the real
      `config-path` or `legacy-config-path`. Writes to temp paths are allowed
      but also tracked for assertion.

   3. file-mtime snapshot (mtime-guard-fixture, :once): records mtime + sha256
      of the real config file before the suite and re-asserts after. Any
      byte-level mutation fails the suite.

   Tests cover:
   - Loading config from file with defaults merge
   - Missing file handling (returns defaults)
   - Malformed file handling (returns defaults)
   - Accessor functions (project-roots, defaults, overrides)
   - Parent rule resolution via path prefix matching
   - Reset/reload behavior
   - Roundtrip property: forall config-shape, isolated path read/write returns
     same data and real path is never touched.
   - Integration: full read/write cycle through public API on temp dir."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-mcp.config.core :as config]
            [hive-mcp.config.io :as config-io]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Safety-net helpers — hash/mtime of real config file
;; =============================================================================

(defn- sha256-hex
  "SHA-256 hex digest of a file, or nil if file does not exist."
  [^String path]
  (let [f (io/file path)]
    (when (.exists f)
      (let [md (java.security.MessageDigest/getInstance "SHA-256")
            bytes (with-open [in (io/input-stream f)]
                    (let [baos (java.io.ByteArrayOutputStream.)]
                      (io/copy in baos)
                      (.toByteArray baos)))
            digest (.digest md bytes)]
        (apply str (map #(format "%02x" %) digest))))))

(defn- file-fingerprint
  "Return {:exists?, :mtime, :size, :sha256} for the given path."
  [^String path]
  (let [f (io/file path)]
    (if (.exists f)
      {:exists? true
       :mtime   (.lastModified f)
       :size    (.length f)
       :sha256  (sha256-hex path)}
      {:exists? false})))

(defn- real-config-paths
  "The set of production config paths that must never be written during tests."
  []
  #{config-io/config-path config-io/legacy-config-path})

;; =============================================================================
;; Fixture 1 (:once): real-config mtime+sha256 snapshot
;; =============================================================================

(def ^:private real-config-fingerprint-pre (atom nil))

(defn- mtime-guard-fixture
  "Snapshot the real config file fingerprint before the suite, verify
   bit-exact identity after. Fails the suite if any test mutated the real
   config file."
  [f]
  (let [pre (into {}
                  (map (juxt identity file-fingerprint))
                  (real-config-paths))]
    (reset! real-config-fingerprint-pre pre)
    (try
      (f)
      (finally
        (doseq [p (real-config-paths)]
          (let [before (get pre p)
                after  (file-fingerprint p)]
            (when (not= before after)
              (throw (ex-info
                      (str "TEST ISOLATION VIOLATION: real config file "
                           "was mutated during test suite: " p)
                      {:path p :before before :after after})))))))))

;; =============================================================================
;; Fixture 2 (:each): snapshot + restore production atom; install write-guard
;; =============================================================================

(def ^:private forbidden-write-occurrences
  "Collected write attempts to the real config path during a test. Must stay
   empty; checked after each test."
  (atom []))

(defn- snapshot-and-guard-fixture
  "For each test:
     - snapshot @global-config,
     - redef `config-io/write-config!` so it throws if path is production,
     - run test,
     - restore atom bit-identical,
     - assert no forbidden writes were attempted."
  [f]
  (let [global-atom @#'config/global-config
        snapshot    @global-atom
        real-write  config-io/write-config!
        forbidden   (real-config-paths)]
    (reset! forbidden-write-occurrences [])
    (with-redefs [config-io/write-config!
                  (fn guarded-write!
                    ([cfg] (guarded-write! cfg config-io/config-path))
                    ([cfg path]
                     (if (contains? forbidden path)
                       (do
                         (swap! forbidden-write-occurrences conj path)
                         (throw (ex-info
                                 (str "TEST ISOLATION VIOLATION: attempted "
                                      "write to real config path " path)
                                 {:path path})))
                       (real-write cfg path))))]
      (try
        (f)
        (finally
          (reset! global-atom snapshot)
          (is (empty? @forbidden-write-occurrences)
              (str "Test attempted forbidden writes to: "
                   (pr-str @forbidden-write-occurrences))))))))

(use-fixtures :once mtime-guard-fixture)
(use-fixtures :each snapshot-and-guard-fixture)

;; =============================================================================
;; Helper: Create temp config files (isolated from real config dir)
;; =============================================================================

(defn- write-temp-config!
  "Write an EDN map to a temp file, return the file path."
  [config-map]
  (let [f (java.io.File/createTempFile "hive-config-test" ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str config-map))
    (.getAbsolutePath f)))

(defn- temp-path
  "Generate a temp-only path for negative/nonexistent-file tests.
   Guaranteed NOT to equal production `config-path`/`legacy-config-path`."
  [suffix]
  (str (System/getProperty "java.io.tmpdir")
       "/hive-mcp-config-test-"
       (System/currentTimeMillis) "-"
       (rand-int 1000000) "-"
       suffix))

;; =============================================================================
;; Test: load-global-config!
;; =============================================================================

(deftest test-load-missing-file
  (testing "Loading from non-existent file returns defaults"
    (let [result (config/load-global-config! (temp-path "missing.edn"))]
      (is (map? result))
      (is (= [] (:project-roots result)))
      (is (= :datahike (get-in result [:defaults :kg-backend])))
      (is (= false (get-in result [:defaults :hot-reload])))
      (is (= {} (:project-overrides result)))
      (is (= [] (:parent-rules result))))))

(deftest test-load-valid-config
  (testing "Loading valid config merges with defaults"
    (let [user-config {:project-roots ["/home/user/PP" "/home/user/PP/hive"]
                       :defaults {:kg-backend :datascript :hot-reload true}
                       :project-overrides {"hive-mcp" {:hot-reload true
                                                       :watch-dirs ["src" "hive-hot/src"]}}
                       :parent-rules [{:path-prefix "/home/user/PP/hive/"
                                       :parent-id "hive-mcp"}]}
          path (write-temp-config! user-config)
          result (config/load-global-config! path)]
      (is (= ["/home/user/PP" "/home/user/PP/hive"] (:project-roots result)))
      (is (= :datascript (get-in result [:defaults :kg-backend])))
      (is (= true (get-in result [:defaults :hot-reload])))
      (is (= {"hive-mcp" {:hot-reload true :watch-dirs ["src" "hive-hot/src"]}}
             (:project-overrides result)))
      (is (= [{:path-prefix "/home/user/PP/hive/" :parent-id "hive-mcp"}]
             (:parent-rules result))))))

(deftest test-load-partial-config-merges-defaults
  (testing "Partial config gets missing keys from defaults"
    (let [user-config {:project-roots ["/home/user/projects"]}
          path (write-temp-config! user-config)
          result (config/load-global-config! path)]
      (is (= ["/home/user/projects"] (:project-roots result)))
      (is (= :datahike (get-in result [:defaults :kg-backend])))
      (is (= {} (:project-overrides result)))
      (is (= [] (:parent-rules result))))))

(deftest test-load-nested-defaults-merge
  (testing "Nested :defaults map is merged, not replaced"
    (let [user-config {:defaults {:hot-reload true}}
          path (write-temp-config! user-config)
          result (config/load-global-config! path)]
      (is (= true (get-in result [:defaults :hot-reload])))
      (is (= :datahike (get-in result [:defaults :kg-backend]))))))

(deftest test-load-malformed-file
  (testing "Malformed EDN file returns defaults"
    (let [f (java.io.File/createTempFile "hive-config-bad" ".edn")]
      (.deleteOnExit f)
      (spit f "this is not valid edn {{{")
      (let [result (config/load-global-config! (.getAbsolutePath f))]
        (is (map? result))
        (is (= [] (:project-roots result)))))))

(deftest test-load-non-map-file
  (testing "EDN file that parses to non-map returns defaults"
    (let [f (java.io.File/createTempFile "hive-config-vec" ".edn")]
      (.deleteOnExit f)
      (spit f "[1 2 3]")
      (let [result (config/load-global-config! (.getAbsolutePath f))]
        (is (map? result))
        (is (= [] (:project-roots result)))))))

;; =============================================================================
;; Test: get-global-config (before and after load)
;; =============================================================================

(deftest test-get-global-config-before-load
  (testing "get-global-config returns defaults when not loaded"
    ;; Local reset is fine — the fixture will restore afterwards.
    (config/reset-config!)
    (let [result (config/get-global-config)]
      (is (map? result))
      (is (= [] (:project-roots result)))
      (is (= :datahike (get-in result [:defaults :kg-backend]))))))

(deftest test-get-global-config-after-load
  (testing "get-global-config returns loaded config"
    (let [user-config {:project-roots ["/test/path"]}
          path (write-temp-config! user-config)]
      (config/load-global-config! path)
      (is (= ["/test/path"] (:project-roots (config/get-global-config)))))))

;; =============================================================================
;; Test: get-project-roots
;; =============================================================================

(deftest test-get-project-roots-default
  (testing "Project roots default to empty vector"
    (config/reset-config!)
    (is (= [] (config/get-project-roots)))))

(deftest test-get-project-roots-loaded
  (testing "Project roots reflect loaded config"
    (let [path (write-temp-config! {:project-roots ["/a" "/b"]})]
      (config/load-global-config! path)
      (is (= ["/a" "/b"] (config/get-project-roots))))))

;; =============================================================================
;; Test: get-defaults
;; =============================================================================

(deftest test-get-defaults
  (testing "get-defaults returns defaults map"
    (let [path (write-temp-config! {:defaults {:kg-backend :datascript}})]
      (config/load-global-config! path)
      (let [defaults (config/get-defaults)]
        (is (= :datascript (:kg-backend defaults)))
        (is (= false (:hot-reload defaults)))))))

;; =============================================================================
;; Test: get-project-overrides
;; =============================================================================

(deftest test-get-project-overrides-existing
  (testing "Returns overrides for known project"
    (let [path (write-temp-config!
                {:project-overrides {"my-proj" {:hot-reload true}}})]
      (config/load-global-config! path)
      (is (= {:hot-reload true} (config/get-project-overrides "my-proj"))))))

(deftest test-get-project-overrides-missing
  (testing "Returns nil for unknown project"
    (let [path (write-temp-config!
                {:project-overrides {"my-proj" {:hot-reload true}}})]
      (config/load-global-config! path)
      (is (nil? (config/get-project-overrides "unknown-proj"))))))

;; =============================================================================
;; Test: get-project-config (merged defaults + overrides)
;; =============================================================================

(deftest test-get-project-config-with-overrides
  (testing "Project config merges defaults with overrides"
    (let [path (write-temp-config!
                {:defaults {:kg-backend :datahike :hot-reload false}
                 :project-overrides {"hive-mcp" {:hot-reload true
                                                 :watch-dirs ["src"]}}})]
      (config/load-global-config! path)
      (let [cfg (config/get-project-config "hive-mcp")]
        (is (= true (:hot-reload cfg)))
        (is (= :datahike (:kg-backend cfg)))
        (is (= ["src"] (:watch-dirs cfg)))))))

(deftest test-get-project-config-without-overrides
  (testing "Project config returns just defaults when no overrides"
    (let [path (write-temp-config!
                {:defaults {:kg-backend :datascript}})]
      (config/load-global-config! path)
      (let [cfg (config/get-project-config "unknown")]
        (is (= :datascript (:kg-backend cfg)))
        (is (= false (:hot-reload cfg)))))))

;; =============================================================================
;; Test: get-parent-for-path
;; =============================================================================

(deftest test-get-parent-for-path-matching
  (testing "Path matching first matching parent-rule"
    (let [path (write-temp-config!
                {:parent-rules [{:path-prefix "/home/user/PP/hive/"
                                 :parent-id "hive-mcp"}
                                {:path-prefix "/home/user/PP/"
                                 :parent-id "root-project"}]})]
      (config/load-global-config! path)
      (is (= "hive-mcp"
             (config/get-parent-for-path "/home/user/PP/hive/hive-hot"))))))

(deftest test-get-parent-for-path-fallback
  (testing "Path matching falls through to less specific rule"
    (let [path (write-temp-config!
                {:parent-rules [{:path-prefix "/home/user/PP/hive/"
                                 :parent-id "hive-mcp"}
                                {:path-prefix "/home/user/PP/"
                                 :parent-id "root-project"}]})]
      (config/load-global-config! path)
      (is (= "root-project"
             (config/get-parent-for-path "/home/user/PP/funeraria"))))))

(deftest test-get-parent-for-path-no-match
  (testing "No matching rule returns nil"
    (let [path (write-temp-config!
                {:parent-rules [{:path-prefix "/home/user/PP/"
                                 :parent-id "root"}]})]
      (config/load-global-config! path)
      (is (nil? (config/get-parent-for-path "/completely/different/path"))))))

(deftest test-get-parent-for-path-nil
  (testing "nil directory returns nil"
    (is (nil? (config/get-parent-for-path nil)))))

;; =============================================================================
;; Test: reset-config!
;; =============================================================================

(deftest test-reset-config
  (testing "reset-config! clears cached config"
    (let [path (write-temp-config! {:project-roots ["/test"]})]
      (config/load-global-config! path)
      (is (= ["/test"] (config/get-project-roots)))
      (config/reset-config!)
      (is (= [] (config/get-project-roots))))))

(deftest test-reload-after-reset
  (testing "Can reload config after reset"
    (let [path1 (write-temp-config! {:project-roots ["/v1"]})
          path2 (write-temp-config! {:project-roots ["/v2"]})]
      (config/load-global-config! path1)
      (is (= ["/v1"] (config/get-project-roots)))
      (config/reset-config!)
      (config/load-global-config! path2)
      (is (= ["/v2"] (config/get-project-roots))))))

;; =============================================================================
;; Test: default-drone-model / default-drone-backend
;; =============================================================================

(deftest test-default-drone-model-from-config
  (testing "default-drone-model reads from :services :drone :default-model"
    (let [path (write-temp-config!
                {:services {:drone {:mode :local
                                    :default-model "custom/test-model"
                                    :default-backend :openrouter}}})]
      (config/load-global-config! path)
      (is (= "custom/test-model" (config/default-drone-model))))))

(deftest test-default-drone-model-fallback
  (testing "default-drone-model falls back to hardcoded default when :drone missing"
    (let [path (write-temp-config! {:services {}})]
      (config/load-global-config! path)
      (is (= "devstral-small:24b" (config/default-drone-model))))))

(deftest test-default-drone-model-returns-string
  (testing "default-drone-model always returns a string"
    (is (string? (config/default-drone-model)))))

(deftest test-default-drone-backend-from-config
  (testing "default-drone-backend reads from :services :drone :default-backend"
    (let [path (write-temp-config!
                {:services {:drone {:mode :local
                                    :default-model "model"
                                    :default-backend :custom-backend}}})]
      (config/load-global-config! path)
      (is (= :custom-backend (config/default-drone-backend))))))

(deftest test-default-drone-backend-fallback
  (testing "default-drone-backend falls back to :agentic-loop when :drone missing"
    (let [path (write-temp-config! {:services {}})]
      (config/load-global-config! path)
      (is (= :agentic-loop (config/default-drone-backend))))))

(deftest test-default-drone-model-config-override
  (testing "User config.edn :drone :default-model overrides defaults"
    (let [path (write-temp-config!
                {:services {:drone {:default-model "my-org/my-model:latest"}}})]
      (config/load-global-config! path)
      (is (= "my-org/my-model:latest" (config/default-drone-model))))))

;; =============================================================================
;; Meta-test: prove the safety net itself works
;; =============================================================================

(deftest test-real-config-path-is-guarded
  (testing "write-guard rejects writes to the real config path"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"TEST ISOLATION VIOLATION"
         (config-io/write-config! {:dummy true} config-io/config-path)))
    ;; The guard records the attempt; clear it so the :each post-check passes.
    (reset! forbidden-write-occurrences [])))

(deftest test-real-config-fingerprint-unchanged-during-suite
  (testing "The real config file is not mutated by any test"
    (doseq [p (real-config-paths)]
      (let [before (get @real-config-fingerprint-pre p)
            now    (file-fingerprint p)]
        (is (= before now)
            (str "Real config file " p " was mutated during suite; "
                 "before=" (pr-str before) " now=" (pr-str now)))))))

;; =============================================================================
;; Property tests: roundtrip on isolated paths leaves real config untouched
;; =============================================================================

(def ^:private project-id-gen
  (gen/such-that #(not (clojure.string/blank? %))
                 (gen/fmap clojure.string/trim gen/string-alphanumeric)))

(def ^:private config-shape-gen
  "Generator for plausible config-map shapes (small, defaults-compatible)."
  (gen/let [roots   (gen/vector (gen/fmap #(str "/tmp/fake/" %) project-id-gen) 0 3)
            kgb     (gen/elements [:datahike :datascript :datalevin])
            hot     gen/boolean
            ov-keys (gen/vector project-id-gen 0 3)
            ov-vals (gen/vector gen/boolean 0 3)]
    {:project-roots roots
     :defaults {:kg-backend kgb :hot-reload hot :presets-path nil}
     :project-overrides (zipmap ov-keys
                                (map (fn [b] {:hot-reload b}) ov-vals))
     :parent-rules []}))

(defspec property-roundtrip-isolated 20
  (prop/for-all [shape config-shape-gen]
    (let [pre-fp      (file-fingerprint config-io/config-path)
          pre-legacy  (file-fingerprint config-io/legacy-config-path)
          path        (write-temp-config! shape)
          loaded      (config/load-global-config! path)
          post-fp     (file-fingerprint config-io/config-path)
          post-legacy (file-fingerprint config-io/legacy-config-path)]
      (and
       ;; Shape survived the roundtrip
       (= (:project-roots shape) (:project-roots loaded))
       (= (get-in shape [:defaults :kg-backend])
          (get-in loaded [:defaults :kg-backend]))
       (= (get-in shape [:defaults :hot-reload])
          (get-in loaded [:defaults :hot-reload]))
       (= (:project-overrides shape) (:project-overrides loaded))
       ;; Real config files were NOT mutated
       (= pre-fp post-fp)
       (= pre-legacy post-legacy)))))

;; =============================================================================
;; Integration: public-API read/write cycle on a temp dir
;; =============================================================================

(deftest test-integration-full-cycle-on-temp-dir
  (testing "Full read/write cycle through public API: temp mutated, real untouched"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "hive-mcp-config-integration-"
                                (System/currentTimeMillis)))
          _ (.mkdirs tmp-dir)
          tmp-path (str (.getAbsolutePath tmp-dir) "/config.edn")
          real-pre (file-fingerprint config-io/config-path)
          legacy-pre (file-fingerprint config-io/legacy-config-path)]
      (try
        ;; 1. Write initial config via raw spit (public API does not expose
        ;;    unguarded write; load! will persist only when path = config-path).
        (spit tmp-path (pr-str {:project-roots ["/int/one"]
                                :defaults {:kg-backend :datalevin}}))
        ;; 2. Load via public API, pointed at the temp path.
        (config/load-global-config! tmp-path)
        (is (= ["/int/one"] (config/get-project-roots)))
        (is (= :datalevin (get-in (config/get-defaults) [:kg-backend])))
        ;; 3. Mutate on disk, reset, reload — verify we see new content.
        (spit tmp-path (pr-str {:project-roots ["/int/two"]}))
        (config/reset-config!)
        (config/load-global-config! tmp-path)
        (is (= ["/int/two"] (config/get-project-roots)))
        ;; 4. Temp file mutated (content just written).
        (is (.exists (io/file tmp-path)))
        ;; 5. Real config untouched.
        (is (= real-pre   (file-fingerprint config-io/config-path)))
        (is (= legacy-pre (file-fingerprint config-io/legacy-config-path)))
        (finally
          (doseq [f (.listFiles tmp-dir)] (.delete f))
          (.delete tmp-dir))))))
