(ns hive-mcp.agent.ling.headless-registry-property-test
  "Property-based tests for the headless registry.

   Tests algebraic properties:
   - resolve-headless-strategy returns nil for unregistered keywords
   - register + resolve round-trip produces ILingStrategy
   - deregister totality: safe for unregistered keywords
   - registered-headless reflects registrations accurately
   - capability consistency: registered backend caps match headless-capabilities

   Convention: 200 iterations per property (per hive-mcp testing convention)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [hive-mcp.agent.ling.headless-registry :as reg]
            [hive-mcp.addons.headless :as headless]
            [hive-mcp.agent.ling.strategy :as strategy]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (reg/clear-registry!)
    (try (f)
         (finally (reg/clear-registry!)))))

;; =============================================================================
;; Mock Backend Factory
;; =============================================================================

(defn- make-mock-backend [id caps]
  (reify
    headless/IHeadlessBackend
    (headless-id [_] id)
    (headless-spawn! [_ _ _] (name id))
    (headless-dispatch! [_ _ _] true)
    (headless-status [_ _ _] {:alive? true})
    (headless-kill! [_ _] {:killed? true})
    (headless-interrupt! [_ _] {:success? false})

    headless/IHeadlessCapabilities
    (declared-capabilities [_] caps)))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-headless-id
  "Generator for headless backend keyword identifiers."
  (gen/fmap (fn [s] (keyword "headless" s))
            (gen/elements ["alpha" "beta" "gamma" "delta" "epsilon"
                           "zeta" "eta" "theta" "iota" "kappa"
                           "lambda" "mu" "nu" "xi" "omicron"
                           "pi" "rho" "sigma" "tau" "upsilon"])))

(def gen-unregistered-keyword
  "Generator for keywords unlikely to be registered."
  (gen/elements [:unregistered/a :unregistered/b :unregistered/c
                 :unregistered/d :unregistered/e :unregistered/f
                 :unregistered/g :unregistered/h :unregistered/i
                 :unregistered/j :unregistered/k :unregistered/l]))

(def gen-capability-set
  "Generator for a random subset of capabilities."
  (gen/fmap set
            (gen/vector (gen/elements [:cap/hooks :cap/interrupts :cap/subagents
                                       :cap/checkpointing :cap/mcp-tools
                                       :cap/streaming :cap/multi-turn
                                       :cap/budget-guard :cap/saa]))))

;; =============================================================================
;; Property: resolve-headless-strategy returns nil for unregistered
;; =============================================================================

(defspec resolve-nil-for-unregistered 200
  (prop/for-all [kw gen-unregistered-keyword]
                (do (reg/clear-registry!)
                    (nil? (reg/resolve-headless-strategy kw)))))

;; =============================================================================
;; Property: register + resolve round-trip returns non-nil ILingStrategy
;; =============================================================================

(defspec register-resolve-round-trip 100
  (prop/for-all [id gen-headless-id
                 caps gen-capability-set]
                (do (reg/clear-registry!)
                    (let [backend (make-mock-backend id caps)
                          reg-result (reg/register-headless! id backend)
                          strategy (reg/resolve-headless-strategy id)]
                      (and (:registered? reg-result)
                           (some? strategy)
                           (satisfies? strategy/ILingStrategy strategy))))))

;; =============================================================================
;; Property: deregister totality - safe for unregistered keywords
;; =============================================================================

(defspec deregister-safe-for-unregistered 200
  (prop/for-all [kw gen-unregistered-keyword]
                (do (reg/clear-registry!)
                    (let [result (reg/deregister-headless! kw)]
                      (and (map? result)
                           (false? (:deregistered? result)))))))

;; =============================================================================
;; Property: registered-headless reflects registrations
;; =============================================================================

(defspec registered-reflects-registrations 100
  (prop/for-all [ids (gen/vector (gen/elements [:test/a :test/b :test/c
                                                :test/d :test/e :test/f])
                                 1 6)]
                (do (reg/clear-registry!)
                    (let [unique-ids (distinct ids)]
                      (doseq [id unique-ids]
                        (reg/register-headless! id (make-mock-backend id #{})))
                      (= (count unique-ids)
                         (count (reg/registered-headless)))))))

;; =============================================================================
;; Property: capability consistency
;; =============================================================================

(defspec capability-consistency 100
  (prop/for-all [id gen-headless-id
                 caps gen-capability-set]
                (do (reg/clear-registry!)
                    (let [backend (make-mock-backend id caps)]
                      (reg/register-headless! id backend)
                      (= caps (reg/headless-capabilities id))))))

;; =============================================================================
;; Deterministic unit tests
;; =============================================================================

(deftest best-headless-prefers-claude-sdk
  (testing "best-headless-for-provider prefers :claude-sdk over :claude-process and :hive-agent"
    (reg/clear-registry!)
    (reg/register-headless! :hive-agent
                            (make-mock-backend :hive-agent #{:cap/subagents}))
    (reg/register-headless! :claude-process
                            (make-mock-backend :claude-process #{:cap/streaming}))
    (reg/register-headless! :claude-sdk
                            (make-mock-backend :claude-sdk #{:cap/hooks :cap/interrupts}))
    (is (= :claude-sdk (reg/best-headless-for-provider :claude)))))

(deftest best-headless-hive-agent-does-not-override-process
  (testing "META-INF-loaded :hive-agent remains explicit when process backend exists"
    (reg/clear-registry!)
    (reg/register-headless! :hive-agent
                            (make-mock-backend :hive-agent #{:cap/subagents}))
    (reg/register-headless! :claude-process
                            (make-mock-backend :claude-process #{:cap/streaming}))
    (is (= :claude-process (reg/best-headless-for-provider :claude)))))

(deftest best-headless-falls-back-to-process
  (testing "best-headless-for-provider falls back to :claude-process when no SDK"
    (reg/clear-registry!)
    (reg/register-headless! :claude-process
                            (make-mock-backend :claude-process #{:cap/streaming}))
    (is (= :claude-process (reg/best-headless-for-provider :claude)))))

(deftest best-headless-nil-when-empty
  (testing "best-headless-for-provider returns nil when registry empty"
    (reg/clear-registry!)
    (is (nil? (reg/best-headless-for-provider :claude)))))

(deftest clear-registry-empties-everything
  (testing "clear-registry! empties the registry completely"
    (reg/register-headless! :test/x (make-mock-backend :test/x #{}))
    (reg/register-headless! :test/y (make-mock-backend :test/y #{}))
    (is (= 2 (count (reg/registered-headless))))
    (reg/clear-registry!)
    (is (empty? (reg/registered-headless)))))
