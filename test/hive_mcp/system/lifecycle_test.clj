(ns hive-mcp.system.lifecycle-test
  "Integration smoke tests for the Integrant lifecycle system.

   T12: Verifies that ig/init + ig/halt! round-trips cleanly for each profile,
   config shapes are correct, and no dangling state remains after halt.

   These tests stub out side-effecting functions (server starts, network I/O)
   while preserving the real Integrant init-key/halt-key! multimethod dispatch.
   This validates the actual wiring — not just config loading.

   Profiles tested:
     :desktop      — full feature set (stdio + ws + all channels)
     :k8s-headless — no stdio, keepalive :promise mode
     :k8s-minimal  — bare minimum (A2A + NATS only)

   Golden files: test/golden/system/
   Run with UPDATE_GOLDEN=true to regenerate after intentional changes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [integrant.core :as ig]
            [meta-merge.core :refer [meta-merge]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-mcp.server.core :as core]
            [hive-mcp.server.guards :as guards]
            [hive-mcp.server.lifecycle :as lifecycle]
            [hive-mcp.server.init :as init]
            [hive-mcp.server.transport :as transport]
            [hive-mcp.server.routes :as routes]
            ;; Load all layer namespaces (registers init-key/halt-key! multimethods)
            [hive-mcp.system.layer1]
            [hive-mcp.system.layer2]
            [hive-mcp.system.layer3]
            [hive-mcp.system.layer4]
            [hive-mcp.system.layer5]
            [hive-mcp.system.keepalive]
            ;; Pre-load MCP SDK namespaces so with-redefs can intercept them
            ;; (layer5 :hive/mcp-stdio uses requiring-resolve on these)
            [io.modelcontext.clojure-sdk.stdio-server :as mcp-stdio-sdk]
            [io.modelcontext.clojure-sdk.server :as mcp-server-sdk]
            [jsonrpc4clj.server :as jsonrpc-server]))

;; =============================================================================
;; Stubs — replace side-effecting functions with noops/stubs
;;
;; Goal: let Integrant dispatch real init-key/halt-key! multimethods
;; but prevent actual server starts, network I/O, file watchers, etc.
;; =============================================================================

(defn- stub-noop [& _] nil)
(defn- stub-coordinator! [a] (reset! a "test-coordinator-id"))
(defn- stub-nrepl! [a] (reset! a :stub-nrepl) :stub-nrepl)
(defn- stub-ws-server! [& _] :stub-ws)
(defn- stub-embedding! [& _] :stub-embedding)
(defn- stub-delegation! [& _] 0)
(defn- stub-extensions! [& _] {:registered 0 :total 0 :sources []})
(defn- stub-server-spec [& _] {:tools [] :resources [] :prompts []})
(defn- stub-stdio-server [& _] :stub-stdio-server)
(defn- stub-create-ctx! [& _] {:server :stub-stdio-server})
(defn- stub-jsonrpc-start! [& _] (promise))
(defn- stub-ws-channel! [a] (reset! a :stub-monitor))
(defn- stub-olympus! [& _] :stub-olympus)
(defn- stub-project-config [& _] {})

(defmacro with-stubs
  "Execute body with all side-effecting fns stubbed out.
   Real Integrant init-key/halt-key! dispatch is preserved."
  [& body]
  `(with-redefs [;; ── Layer 1 ──────────────────────────────────────────────
                 guards/mark-coordinator-running!   stub-noop
                 guards/mark-coordinator-stopped!   stub-noop
                 guards/enable-guards!              stub-noop
                 guards/disable-guards!             stub-noop
                 lifecycle/init-hooks!              stub-noop
                 init/init-events!                  stub-noop
                 init/register-coordinator!         stub-coordinator!
                 ;; ── Layer 2 ──────────────────────────────────────────────
                 transport/start-embedded-nrepl!    stub-nrepl!
                 transport/start-websocket-server!  stub-ws-server!
                 init/init-nats!                    stub-noop
                 ;; ── Layer 3 ──────────────────────────────────────────────
                 init/init-embedding-provider!      stub-embedding!
                 init/warmup-embedding!             stub-noop
                 init/wire-memory-store!            stub-noop
                 routes/register-tools-for-delegation! stub-delegation!
                 init/register-forge-belt-defaults! stub-noop
                 init/load-extensions!              stub-extensions!
                 ;; ── Layer 4 ──────────────────────────────────────────────
                 transport/start-ws-channel-with-healing! stub-ws-channel!
                 transport/start-olympus-ws!        stub-olympus!
                 transport/start-a2a-gateway!       stub-noop
                 transport/start-legacy-channel!    stub-noop
                 init/init-channel-bridge!          stub-noop
                 init/start-swarm-sync!             stub-noop
                 init/init-workflow-engine!          stub-noop
                 ;; ── Layer 5 ──────────────────────────────────────────────
                 lifecycle/read-project-config      stub-project-config
                 init/init-hot-reload-watcher!      stub-noop
                 init/start-decay-scheduler!        stub-noop
                 init/stop-decay-scheduler!         stub-noop
                 init/start-housekeeping-scheduler!  stub-noop
                 init/stop-housekeeping-scheduler!   stub-noop
                 init/start-registry-sync!          stub-noop
                 ;; ── MCP SDK (used by :hive/mcp-stdio via requiring-resolve) ──
                 routes/build-server-spec            stub-server-spec
                 mcp-stdio-sdk/stdio-server          stub-stdio-server
                 mcp-server-sdk/create-context!      stub-create-ctx!
                 jsonrpc-server/start                stub-jsonrpc-start!]
     ~@body))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean-system-fixture
  "Ensures system atom is nil before/after each test."
  [f]
  (when @core/system
    (with-stubs (core/stop!)))
  (try
    (f)
    (finally
      (when @core/system
        (with-stubs (core/stop!))))))

(use-fixtures :each clean-system-fixture)

;; =============================================================================
;; Config Loading — Pure Tests (no Integrant init)
;; =============================================================================

(deftest config-loading-base
  (testing "Base system.edn loads with Integrant readers"
    (let [config (core/read-base-config)]
      (is (map? config))
      (is (contains? config :hive/guards))
      (is (contains? config :hive/keepalive))
      (is (contains? config :hive/mcp-stdio)))))

(deftest config-loading-profiles
  (testing "All three profiles load without error"
    (doseq [profile [:desktop :k8s-headless :k8s-minimal]]
      (testing (str "profile: " (name profile))
        (let [config (core/load-system-config profile)]
          (is (map? config))
          (is (pos? (count config))))))))

(deftest profile-resolution-precedence
  (testing "resolve-profile defaults to :desktop"
    (is (= :desktop (core/resolve-profile))))
  (testing "resolve-profile with explicit arg"
    (is (= :k8s-headless (core/resolve-profile "k8s-headless")))
    (is (= :k8s-minimal (core/resolve-profile "k8s-minimal")))))

;; =============================================================================
;; Golden: Config key sets per profile
;; =============================================================================

(deftest-golden desktop-config-keys
  "test/golden/system/desktop-config-keys.edn"
  (->> (core/load-system-config :desktop)
       keys
       sort
       vec))

(deftest-golden k8s-headless-config-keys
  "test/golden/system/k8s-headless-config-keys.edn"
  (->> (core/load-system-config :k8s-headless)
       keys
       sort
       vec))

(deftest-golden k8s-minimal-config-keys
  "test/golden/system/k8s-minimal-config-keys.edn"
  (->> (core/load-system-config :k8s-minimal)
       keys
       sort
       vec))

;; =============================================================================
;; Profile Key Assertions — structural invariants
;; =============================================================================

(def ^:private base-keys
  "All keys present in base system.edn."
  #{:hive/guards :hive/hooks :hive/events :hive/coordinator
    :hive/nrepl :hive/websocket-mcp :hive/nats
    :hive/embedding :hive/memory-store :hive/tool-delegation
    :hive/forge-belt :hive/config :hive/extensions
    :hive/ws-channel :hive/olympus :hive/a2a-gateway
    :hive/legacy-channel :hive/channel-bridge :hive/swarm-sync
    :hive/workflow-engine
    :hive/hot-reload :hive/registry-sync :hive/decay-scheduler
    :hive/housekeeping :hive/mcp-stdio :hive/keepalive})

(deftest desktop-profile-keys
  (let [config (core/load-system-config :desktop)
        ks     (set (keys config))]
    (testing "Desktop has all base keys (nothing removed)"
      (is (= base-keys ks)))
    (testing "Desktop has :hive/mcp-stdio"
      (is (contains? ks :hive/mcp-stdio)))
    (testing "Desktop keepalive mode is :stdio"
      (is (= :stdio (:mode (:hive/keepalive config)))))))

(deftest k8s-headless-profile-keys
  (let [config (core/load-system-config :k8s-headless)
        ks     (set (keys config))]
    (testing "K8s-headless removes :hive/mcp-stdio"
      (is (not (contains? ks :hive/mcp-stdio))))
    (testing "K8s-headless removes :hive/legacy-channel"
      (is (not (contains? ks :hive/legacy-channel))))
    (testing "K8s-headless enables A2A gateway"
      (is (true? (get-in config [:hive/a2a-gateway :enabled]))))
    (testing "K8s-headless enables WebSocket MCP"
      (is (true? (get-in config [:hive/websocket-mcp :enabled]))))
    (testing "K8s-headless enables NATS"
      (is (true? (get-in config [:hive/nats :enabled]))))
    (testing "K8s-headless keepalive uses SIGTERM signal"
      (is (= :sigterm (get-in config [:hive/keepalive :signal]))))))

(deftest k8s-minimal-profile-keys
  (let [config (core/load-system-config :k8s-minimal)
        ks     (set (keys config))]
    (testing "K8s-minimal removes stdio, legacy, ws-channel, olympus, hot-reload, registry-sync"
      (let [excluded #{:hive/mcp-stdio :hive/legacy-channel :hive/ws-channel
                       :hive/olympus :hive/hot-reload :hive/registry-sync}]
        (doseq [k excluded]
          (is (not (contains? ks k)) (str k " should be excluded in k8s-minimal")))))
    (testing "K8s-minimal enables A2A gateway"
      (is (true? (get-in config [:hive/a2a-gateway :enabled]))))
    (testing "K8s-minimal enables NATS"
      (is (true? (get-in config [:hive/nats :enabled]))))
    (testing "K8s-minimal keepalive uses SIGTERM signal"
      (is (= :sigterm (get-in config [:hive/keepalive :signal]))))))

(deftest profile-subset-invariant
  (testing "k8s-minimal keys are a strict subset of k8s-headless keys"
    (let [headless-ks (set (keys (core/load-system-config :k8s-headless)))
          minimal-ks  (set (keys (core/load-system-config :k8s-minimal)))]
      (is (set/subset? minimal-ks headless-ks))))
  (testing "k8s-headless keys are a strict subset of desktop keys"
    (let [desktop-ks  (set (keys (core/load-system-config :desktop)))
          headless-ks (set (keys (core/load-system-config :k8s-headless)))]
      (is (set/subset? headless-ks desktop-ks)))))

;; =============================================================================
;; Lifecycle Smoke: Desktop — init + halt round-trip
;; =============================================================================

(deftest ^:integration desktop-lifecycle-round-trip
  (with-stubs
    (testing "Desktop: ig/init produces system map with all expected keys"
      (let [config (core/load-system-config :desktop)
            sys    (ig/init config)]
        (try
          (is (map? sys))
          (is (= (set (keys config)) (set (keys sys)))
              "Every config key should have an init-key result")
          ;; Spot-check status maps
          (is (= :running (get-in sys [:hive/guards :status])))
          (is (= :running (get-in sys [:hive/hooks :status])))
          (is (= :running (get-in sys [:hive/events :status])))
          (is (= :running (get-in sys [:hive/coordinator :status])))
          (is (= :running (get-in sys [:hive/keepalive :status])))
          (is (= :stdio (get-in sys [:hive/keepalive :mode])))
          (finally
            (ig/halt! sys)))))))

(deftest ^:integration desktop-halt-reverses-cleanly
  (with-stubs
    (testing "Desktop: halt completes without exception, system atom is nil"
      (core/start! :profile :desktop)
      (is (some? @core/system))
      (let [result (core/stop!)]
        (is (= :halted result))
        (is (nil? @core/system))))))

;; =============================================================================
;; Lifecycle Smoke: K8s-Headless
;; =============================================================================

(deftest ^:integration k8s-headless-lifecycle-round-trip
  (with-stubs
    (testing "K8s-headless: init succeeds with no :hive/mcp-stdio key"
      (let [config (core/load-system-config :k8s-headless)
            sys    (ig/init config)]
        (try
          (is (map? sys))
          (is (not (contains? sys :hive/mcp-stdio))
              ":hive/mcp-stdio should NOT be in k8s-headless system")
          (is (= :running (get-in sys [:hive/keepalive :status])))
          ;; keepalive should have a shutdown-ch for blocking
          (is (some? (get-in sys [:hive/keepalive :shutdown-ch]))
              "keepalive should have a shutdown-ch for blocking")
          (finally
            (ig/halt! sys)))))))

(deftest ^:integration k8s-headless-no-stdio
  (with-stubs
    (testing "K8s-headless system has no :hive/mcp-stdio at runtime"
      (core/start! :profile :k8s-headless)
      (is (not (contains? @core/system :hive/mcp-stdio)))
      (core/stop!))))

;; =============================================================================
;; Lifecycle Smoke: K8s-Minimal
;; =============================================================================

(deftest ^:integration k8s-minimal-lifecycle-round-trip
  (with-stubs
    (testing "K8s-minimal: init with minimal key set"
      (let [config (core/load-system-config :k8s-minimal)
            sys    (ig/init config)]
        (try
          (is (map? sys))
          (is (= (set (keys config)) (set (keys sys)))
              "All config keys should init successfully")
          ;; Verify excluded keys are truly absent
          (is (not (contains? sys :hive/mcp-stdio)))
          (is (not (contains? sys :hive/ws-channel)))
          (is (not (contains? sys :hive/olympus)))
          (is (not (contains? sys :hive/hot-reload)))
          (is (not (contains? sys :hive/registry-sync)))
          (is (not (contains? sys :hive/legacy-channel)))
          ;; Verify present keys
          (is (contains? sys :hive/a2a-gateway))
          (is (contains? sys :hive/nats))
          (is (contains? sys :hive/keepalive))
          (finally
            (ig/halt! sys)))))))

(deftest ^:integration k8s-minimal-key-count
  (with-stubs
    (testing "K8s-minimal has significantly fewer keys than desktop"
      (let [desktop-sys (do (core/start! :profile :desktop) @core/system)
            _           (core/stop!)
            minimal-sys (do (core/start! :profile :k8s-minimal) @core/system)
            _           (core/stop!)]
        (is (< (count minimal-sys) (count desktop-sys)))
        ;; k8s-minimal excludes 6 keys from base
        (is (<= (- (count desktop-sys) (count minimal-sys)) 6))))))

;; =============================================================================
;; Reset! — full dev cycle (init -> halt -> init)
;; =============================================================================

(deftest ^:integration reset-lifecycle
  (with-stubs
    (testing "reset! cycles cleanly: start -> stop -> start"
      (core/start! :profile :desktop)
      (let [first-sys @core/system]
        (is (some? first-sys) "First system should be running")
        ;; reset! calls stop! then start! (with clj-reload skipped in test)
        (core/reset! :profile :desktop)
        (let [second-sys @core/system]
          (is (some? second-sys) "Second system should be running after reset")
          (is (not (identical? first-sys second-sys))
              "Reset should create a fresh system, not reuse the old one")
          (is (= (set (keys first-sys)) (set (keys second-sys)))
              "Same profile should produce same key set after reset"))
        (core/stop!)))))

(deftest ^:integration reset-cross-profile
  (with-stubs
    (testing "Stop desktop, start k8s-headless — key set changes"
      (core/start! :profile :desktop)
      (let [desktop-keys (set (keys @core/system))]
        (is (contains? desktop-keys :hive/mcp-stdio))
        (core/stop!)
        (core/start! :profile :k8s-headless)
        (let [headless-keys (set (keys @core/system))]
          (is (not (contains? headless-keys :hive/mcp-stdio)))
          (is (not= desktop-keys headless-keys)))
        (core/stop!)))))

;; =============================================================================
;; Dangling State — verify clean halt
;; =============================================================================

(deftest ^:integration no-dangling-state-after-halt
  (with-stubs
    (testing "System atom is nil after halt"
      (core/start! :profile :desktop)
      (is (some? @core/system))
      (core/stop!)
      (is (nil? @core/system)))
    (testing "Double-stop is safe (idempotent)"
      (core/start! :profile :desktop)
      (core/stop!)
      (is (nil? (core/stop!)) "Second stop! should return nil, not :halted"))))

(deftest ^:integration idempotent-start-guard
  (with-stubs
    (testing "start! throws if system already running"
      (core/start! :profile :desktop)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"System already running"
                            (core/start! :profile :desktop)))
      (core/stop!))))

(deftest ^:integration halt-enables-restart
  (with-stubs
    (testing "After halt, system can be started again cleanly"
      (core/start! :profile :desktop)
      (core/stop!)
      (is (nil? @core/system))
      (core/start! :profile :desktop)
      (is (some? @core/system))
      (core/stop!))))

;; =============================================================================
;; Meta-merge property: nil values exclude keys
;; =============================================================================

(deftest meta-merge-nil-exclusion
  (testing "meta-merge ignores nil — load-system-config must handle exclusion"
    ;; meta-merge keeps the base value when overlay is nil.
    ;; load-system-config handles this by dissoc-ing nil keys before merge.
    (let [base    {:a 1 :b 2 :c 3}
          overlay {:b nil :d 4}
          merged  (meta-merge base overlay)]
      ;; Raw meta-merge does NOT remove :b
      (is (= 2 (:b merged)) "meta-merge keeps base when overlay is nil")))
  (testing "load-system-config correctly excludes nil-keyed profiles"
    (let [config (core/load-system-config :k8s-headless)]
      (is (not (contains? config :hive/mcp-stdio))
          "load-system-config should dissoc keys that profile sets to nil"))))

;; =============================================================================
;; Integrant dependency ordering — verify ref resolution
;; =============================================================================

(deftest config-dependency-resolution
  (testing "ig/dependency-graph resolves cleanly for all profiles"
    (doseq [profile [:desktop :k8s-headless :k8s-minimal]]
      (testing (str "profile: " (name profile))
        (let [config (core/load-system-config profile)]
          ;; ig/dependency-graph validates all #ig/ref targets exist
          (is (some? (ig/dependency-graph config))
              (str "Dependency graph should resolve cleanly for " (name profile))))))))

;; =============================================================================
;; Keepalive mode per profile
;; =============================================================================

(deftest keepalive-mode-by-profile
  (testing "Desktop keepalive defaults to :stdio mode"
    (let [config (core/load-system-config :desktop)]
      (is (= :stdio (get-in config [:hive/keepalive :mode])))))
  (testing "K8s-headless keepalive has :sigterm signal"
    (let [config (core/load-system-config :k8s-headless)]
      (is (= :sigterm (get-in config [:hive/keepalive :signal])))))
  (testing "K8s-minimal keepalive has :sigterm signal"
    (let [config (core/load-system-config :k8s-minimal)]
      (is (= :sigterm (get-in config [:hive/keepalive :signal]))))))
