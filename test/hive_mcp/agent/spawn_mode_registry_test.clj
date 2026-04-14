(ns hive-mcp.agent.spawn-mode-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.agent.spawn-mode-registry :as smr]))

;; =============================================================================
;; Registry structure
;; =============================================================================

(deftest registry-is-array-map
  (testing "Registry preserves insertion order (array-map)"
    (is (instance? clojure.lang.PersistentArrayMap (smr/registry)))))

(deftest registry-has-expected-variants
  (testing "All expected spawn modes present"
    (is (contains? smr/all-modes :claude))
    (is (contains? smr/all-modes :vterm))
    (is (contains? smr/all-modes :headless))
    (is (contains? smr/all-modes :agent-sdk))
    (is (contains? smr/all-modes :openrouter)))
  (testing "Exactly 5 variants"
    (is (= 5 (count smr/all-modes)))))

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
  (testing "all-modes matches registry keys"
    (is (= smr/all-modes (set (keys (smr/registry)))))))

(deftest all-mode-strings-consistent
  (testing "all-mode-strings has string versions of all-modes"
    (is (= smr/all-mode-strings (set (map name smr/all-modes))))))

(deftest mcp-modes-only-mcp-visible
  (testing "MCP modes are exactly those with :mcp? true"
    (let [expected (->> (smr/registry)
                        (filter (fn [[_k v]] (:mcp? v)))
                        (mapv (comp name key)))]
      (is (= expected smr/mcp-modes))))
  (testing "MCP enum is claude, vterm and headless"
    (is (= ["claude" "vterm" "headless"] smr/mcp-modes))))

(deftest emacs-modes-correct
  (testing "Claude and vterm require Emacs"
    (is (= #{:claude :vterm} smr/emacs-modes))))

(deftest headless-modes-correct
  (testing "Non-emacs modes are headless"
    (is (= #{:headless :agent-sdk :openrouter} smr/headless-modes))))

(deftest alias-map-correct
  (testing "headless aliases to agent-sdk"
    (is (= {:headless :agent-sdk} smr/alias-map))))

(deftest slot-limits-correct
  (testing "claude has slot limit 6"
    (is (= 6 (:claude smr/mode->slot-limit))))
  (testing "vterm has slot limit 6"
    (is (= 6 (:vterm smr/mode->slot-limit))))
  (testing "Headless modes have no slot limit"
    (is (nil? (:headless smr/mode->slot-limit)))
    (is (nil? (:agent-sdk smr/mode->slot-limit)))
    (is (nil? (:openrouter smr/mode->slot-limit)))))

;; =============================================================================
;; Functions
;; =============================================================================

(deftest valid-mode?-test
  (testing "Valid keywords"
    (is (true? (smr/valid-mode? :claude)))
    (is (true? (smr/valid-mode? :vterm)))
    (is (true? (smr/valid-mode? :headless)))
    (is (true? (smr/valid-mode? :agent-sdk)))
    (is (true? (smr/valid-mode? :openrouter))))
  (testing "Valid strings"
    (is (true? (smr/valid-mode? "claude")))
    (is (true? (smr/valid-mode? "vterm")))
    (is (true? (smr/valid-mode? "headless"))))
  (testing "Invalid inputs"
    (is (false? (smr/valid-mode? :bogus)))
    (is (false? (smr/valid-mode? "invalid")))))

(deftest resolve-alias-test
  (testing "Headless resolves to agent-sdk"
    (is (= :agent-sdk (smr/resolve-alias :headless))))
  (testing "Non-aliases resolve to themselves"
    (is (= :vterm (smr/resolve-alias :vterm)))
    (is (= :agent-sdk (smr/resolve-alias :agent-sdk)))
    (is (= :openrouter (smr/resolve-alias :openrouter)))))

(deftest requires-emacs?-test
  (testing "claude requires Emacs"
    (is (true? (smr/requires-emacs? :claude))))
  (testing "vterm requires Emacs"
    (is (true? (smr/requires-emacs? :vterm))))
  (testing "Others do not"
    (is (false? (smr/requires-emacs? :headless)))
    (is (false? (smr/requires-emacs? :agent-sdk)))
    (is (false? (smr/requires-emacs? :openrouter)))))

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
  (testing "openrouter uses API"
    (is (= :api (smr/io-model :openrouter)))))

(deftest capabilities-test
  (testing "vterm has interactive capability"
    (is (contains? (smr/capabilities :vterm) :interactive)))
  (testing "agent-sdk has subagents capability"
    (is (contains? (smr/capabilities :agent-sdk) :subagents)))
  (testing "openrouter has multi-model capability"
    (is (contains? (smr/capabilities :openrouter) :multi-model)))
  (testing "All modes have dispatch and kill"
    (doseq [mode (keys (smr/registry))]
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
  (testing "Returns MCP-visible mode strings"
    (is (= ["claude" "vterm" "headless"] (smr/mcp-enum)))))
