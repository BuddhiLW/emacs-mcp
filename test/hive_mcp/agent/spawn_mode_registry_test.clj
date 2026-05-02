(ns hive-mcp.agent.spawn-mode-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.spawn-mode-registry :as smr]))

;; Tests that touch the addon-modes atom use try/finally to deregister
;; their own contributions; no global fixture needed.

;; =============================================================================
;; Registry structure
;; =============================================================================

(deftest registry-is-array-map
  (testing "Registry preserves insertion order (array-map)"
    (is (instance? clojure.lang.PersistentArrayMap (smr/registry)))))

(deftest registry-has-expected-core-variants
  (testing "All core abstract/legacy spawn modes present"
    (is (contains? smr/all-modes :claude))
    (is (contains? smr/all-modes :vterm))
    (is (contains? smr/all-modes :headless))
    (is (contains? smr/all-modes :agent-sdk)))
  (testing "Exactly 4 core variants (provider keywords like :openrouter are NOT spawn modes)"
    (is (= 4 (count smr/all-modes))))
  (testing "Concrete addon modes are NOT in core-modes"
    (is (not (contains? smr/all-modes :hive-agent))
        ":hive-agent is contributed by the hive-agent addon, not core")
    (is (not (contains? smr/all-modes :openrouter))
        ":openrouter is a provider, not a spawn-mode")))

(deftest every-variant-has-required-keys
  (testing "Each variant has all required metadata keys"
    (doseq [[mode-kw meta-map] (smr/registry)]
      (testing (str "mode " mode-kw)
        (is (string? (:description meta-map))
            (str mode-kw " missing :description"))
        (is (boolean? (:requires-emacs? meta-map))
            (str mode-kw " missing :requires-emacs?"))
        (is (#{:buffer :stdin-stdout :api} (:io-model meta-map))
            (str mode-kw " invalid :io-model"))
        (is (set? (:capabilities meta-map))
            (str mode-kw " missing :capabilities"))))))

;; =============================================================================
;; Derived views
;; =============================================================================

(deftest all-modes-consistent
  (testing "all-modes (core only) matches core-mode keys"
    ;; all-modes is the static core-modes set; addon modes show up in (registry).
    (is (= smr/all-modes #{:claude :vterm :headless :agent-sdk}))))

(deftest all-mode-strings-consistent
  (testing "all-mode-strings has string versions of all-modes"
    (is (= smr/all-mode-strings (set (map name smr/all-modes))))))

(deftest mcp-modes-only-mcp-visible
  (testing "MCP modes are exactly those with :mcp? true"
    (let [expected (->> (smr/registry)
                        (filter (fn [[_k v]] (:mcp? v)))
                        (mapv (comp name key)))]
      ;; mcp-modes is core-only; expected may include addon-contributed modes
      ;; if any are loaded, but the static var only reflects core.
      (is (= ["claude" "vterm" "headless"] smr/mcp-modes)
          "Core MCP enum is claude, vterm, headless"))))

(deftest emacs-modes-correct
  (testing "Claude and vterm require Emacs"
    (is (= #{:claude :vterm} smr/emacs-modes))))

(deftest headless-modes-correct
  (testing "Core non-emacs modes are headless and agent-sdk"
    (is (= #{:headless :agent-sdk} smr/headless-modes))))

(deftest alias-map-empty-after-cleanup
  (testing ":headless no longer has a static alias (resolved dynamically)"
    (is (= {} smr/alias-map))))

(deftest slot-limits-correct
  (testing "claude has slot limit 6"
    (is (= 6 (:claude smr/mode->slot-limit))))
  (testing "vterm has slot limit 6"
    (is (= 6 (:vterm smr/mode->slot-limit))))
  (testing "Headless and agent-sdk have no slot limit"
    (is (nil? (:headless smr/mode->slot-limit)))
    (is (nil? (:agent-sdk smr/mode->slot-limit)))))

;; =============================================================================
;; Functions
;; =============================================================================

(deftest valid-mode?-test
  (testing "Valid core keywords"
    (is (true? (smr/valid-mode? :claude)))
    (is (true? (smr/valid-mode? :vterm)))
    (is (true? (smr/valid-mode? :headless)))
    (is (true? (smr/valid-mode? :agent-sdk))))
  (testing "Valid strings"
    (is (true? (smr/valid-mode? "claude")))
    (is (true? (smr/valid-mode? "vterm")))
    (is (true? (smr/valid-mode? "headless"))))
  (testing "Invalid inputs"
    (is (false? (smr/valid-mode? :bogus)))
    (is (false? (smr/valid-mode? "invalid")))
    (is (false? (smr/valid-mode? :openrouter))
        ":openrouter is no longer a valid spawn-mode (it's a provider)")))

(deftest resolve-alias-test
  (testing ":headless has no static alias post-cleanup"
    (is (= :headless (smr/resolve-alias :headless))))
  (testing "Non-aliases resolve to themselves"
    (is (= :vterm (smr/resolve-alias :vterm)))
    (is (= :agent-sdk (smr/resolve-alias :agent-sdk)))))

(deftest requires-emacs?-test
  (testing "claude requires Emacs"
    (is (true? (smr/requires-emacs? :claude))))
  (testing "vterm requires Emacs"
    (is (true? (smr/requires-emacs? :vterm))))
  (testing "Others do not"
    (is (false? (smr/requires-emacs? :headless)))
    (is (false? (smr/requires-emacs? :agent-sdk)))))

(deftest slot-limit-test
  (testing "claude capped at 6"
    (is (= 6 (smr/slot-limit :claude))))
  (testing "vterm capped at 6"
    (is (= 6 (smr/slot-limit :vterm))))
  (testing "Others unlimited"
    (is (nil? (smr/slot-limit :agent-sdk)))))

(deftest io-model-test
  (testing "claude uses buffer I/O"
    (is (= :buffer (smr/io-model :claude))))
  (testing "vterm uses buffer I/O"
    (is (= :buffer (smr/io-model :vterm))))
  (testing "agent-sdk uses stdin-stdout"
    (is (= :stdin-stdout (smr/io-model :agent-sdk))))
  (testing "headless uses stdin-stdout"
    (is (= :stdin-stdout (smr/io-model :headless)))))

(deftest capabilities-test
  (testing "vterm has interactive capability"
    (is (contains? (smr/capabilities :vterm) :interactive)))
  (testing "agent-sdk has subagents capability"
    (is (contains? (smr/capabilities :agent-sdk) :subagents)))
  (testing "All core modes have dispatch and kill"
    (doseq [mode smr/all-modes]
      (is (smr/has-capability? mode :dispatch)
          (str mode " missing :dispatch"))
      (is (smr/has-capability? mode :kill)
          (str mode " missing :kill")))))

(deftest has-capability?-test
  (testing "Positive check"
    (is (true? (smr/has-capability? :agent-sdk :subagents))))
  (testing "Negative check"
    (is (false? (smr/has-capability? :vterm :subagents)))))

(deftest mcp-enum-test
  (testing "Core MCP enum is claude, vterm, headless"
    (is (= ["claude" "vterm" "headless"] smr/mcp-modes)))
  (testing "mcp-enum() includes addon-contributed mcp? modes"
    (smr/register-mode! :test-addon
                        {:description     "Test addon mode"
                         :requires-emacs? false
                         :io-model        :api
                         :slot-limit      nil
                         :mcp?            true
                         :alias-of        nil
                         :capabilities    #{:dispatch :kill}})
    (try
      (is (some #{"test-addon"} (smr/mcp-enum))
          "Addon-contributed mcp? modes appear in mcp-enum()")
      (finally
        (smr/deregister-mode! :test-addon)))))

;; =============================================================================
;; Addon contribution
;; =============================================================================

(deftest addon-mode-registration-roundtrip
  (testing "register-mode! makes the keyword visible via valid-mode? and registry"
    (smr/register-mode! :hive-agent
                        {:description     "Provider-agnostic agentic loop"
                         :requires-emacs? false
                         :io-model        :api
                         :slot-limit      nil
                         :mcp?            true
                         :alias-of        nil
                         :capabilities    #{:dispatch :kill :tool-use}})
    (try
      (is (true? (smr/valid-mode? :hive-agent)))
      (is (= :api (smr/io-model :hive-agent)))
      (is (smr/has-capability? :hive-agent :tool-use))
      (finally
        (smr/deregister-mode! :hive-agent)))
    (is (false? (smr/valid-mode? :hive-agent))
        "deregister-mode! removes the keyword cleanly")))
