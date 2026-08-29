(ns hive-mcp.memory.type-registry-test
  "Tests for the OPEN, sanitized memory type registry.

   The registry is no longer a closed enum: any safe token is a valid type
   and unknown-but-safe types are auto-registered with sane defaults. Safety
   (charset + bounded length) is the security gate that replaced membership."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.memory.type-registry :as tr]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest sanitize-type-test
  (testing "normalizes string/keyword to a canonical lowercased token"
    (is (= "pattern" (tr/sanitize-type "  Pattern ")))
    (is (= "axiom" (tr/sanitize-type :axiom)))
    (is (= "my-type" (tr/sanitize-type "My-Type"))))
  (testing "non-coercible / blank input -> nil"
    (is (nil? (tr/sanitize-type 123)))
    (is (nil? (tr/sanitize-type "")))
    (is (nil? (tr/sanitize-type "   ")))
    (is (nil? (tr/sanitize-type nil)))))

(deftest safe-type?-test
  (testing "accepts safe tokens (letter-led, [a-z0-9_-], <= max length)"
    (is (tr/safe-type? "axiom"))
    (is (tr/safe-type? "insight"))
    (is (tr/safe-type? "my-custom-type"))
    (is (tr/safe-type? "adr_v2"))
    (is (tr/safe-type? "Pattern"))            ; lowercased then matched
    (is (tr/safe-type? :decision)))
  (testing "rejects unsafe / oversized / malformed tokens"
    (is (not (tr/safe-type? "bad type")))     ; whitespace
    (is (not (tr/safe-type? "type!")))        ; punctuation
    (is (not (tr/safe-type? "9lives")))       ; leading digit
    (is (not (tr/safe-type? "-foo")))         ; leading dash
    (is (not (tr/safe-type? "a;drop table"))) ; filter/sql injection shape
    (is (not (tr/safe-type? "a#=(eval)")))    ; edn reader-eval shape
    (is (not (tr/safe-type? (apply str (repeat (inc tr/max-type-length) "a")))))
    (is (not (tr/safe-type? "")))
    (is (not (tr/safe-type? nil)))
    (is (not (tr/safe-type? 123)))))

(deftest known-vs-valid-test
  (testing "known-type? is strict registry membership"
    (is (tr/known-type? "axiom"))
    (is (tr/known-type? :decision))
    (is (not (tr/known-type? "insight"))))
  (testing "valid-type? is permissive — any safe token is valid"
    (is (tr/valid-type? "axiom"))
    (is (tr/valid-type? "insight"))
    (is (not (tr/valid-type? "bad type!")))
    (is (not (tr/valid-type? nil)))))

(deftest type-def-test
  (testing "known type returns its registered definition"
    (is (= 4 (:abstraction (tr/type-def "axiom")))))
  (testing "safe-but-unknown type returns sane defaults"
    (let [d (tr/type-def "insight")]
      (is (= 2 (:abstraction d)))
      (is (true? (:auto-registered? d)))))
  (testing "unsafe type returns nil"
    (is (nil? (tr/type-def "bad type!")))))

(deftest ensure-type!-roundtrip-test
  ;; Fully isolate global state: a fresh extension atom + no-op persistence,
  ;; so the test neither pollutes the live registry nor writes config.
  (with-redefs [hive-mcp.memory.type-registry/registry-extensions (atom {})
                hive-mcp.memory.type-registry/config-set-value! (fn [_] nil)
                hive-mcp.memory.type-registry/persisted-loaded? (atom true)]
    (testing "a novel safe type canonicalizes, registers, becomes known"
      (is (= "qa-roundtrip" (tr/ensure-type! "QA-Roundtrip")))
      (is (tr/known-type? "qa-roundtrip"))
      (is (contains? (tr/all-types) :qa-roundtrip)))
    (testing "an already-known type is a no-op (returns canonical form)"
      (is (= "axiom" (tr/ensure-type! :axiom))))
    (testing "an unsafe type returns nil so the caller can reject it"
      (is (nil? (tr/ensure-type! "bad type!"))))
    (testing "the auto-type cap bounds keyword interning"
      (reset! @#'hive-mcp.memory.type-registry/registry-extensions
              (zipmap (map #(keyword (str "t" %)) (range tr/max-auto-types))
                      (repeat tr/default-type-def)))
      ;; at the cap, a new safe token is accepted (returns canonical) but NOT
      ;; auto-registered.
      (is (= "overflowtype" (tr/ensure-type! "overflowtype")))
      (is (not (tr/known-type? "overflowtype"))))))

;; =============================================================================
;; Write gate (human review queue)
;; =============================================================================

(deftest gate-declaration-test
  (testing ":axiom is gated; ordinary types are not"
    (is (tr/gated-type? "axiom"))
    (is (tr/gated-type? :axiom))
    (is (not (tr/gated-type? "principle")))
    (is (not (tr/gated-type? "note")))
    (is (not (tr/gated-type? "my-custom-type"))))
  (testing "the queue type is itself ungated — otherwise parking would recurse"
    (is (not (tr/gated-type? "axiom-candidate"))))
  (testing "the gate names where a request is parked"
    (is (= "axiom-candidate" (tr/queue-target "axiom")))
    (is (nil? (tr/queue-target "principle")))))

(deftest resolve-write-type-test
  (testing "a gated request is parked, and the original request is preserved"
    (let [r (tr/resolve-write-type "axiom")]
      (is (= "axiom-candidate" (:type r)))
      (is (= "axiom" (:requested r)))
      (is (:queued? r))
      (is (string? (:reason (:gate r))) "gate carries a reason to show the agent")))
  (testing "parking is insensitive to case and surrounding space"
    (is (= "axiom-candidate" (:type (tr/resolve-write-type "  AXIOM ")))))
  (testing "ungated types pass through untouched"
    (is (= {:type "principle" :requested "principle" :queued? false :gate nil}
           (tr/resolve-write-type "principle")))
    (is (= {:type "my-custom-type" :requested "my-custom-type" :queued? false :gate nil}
           (tr/resolve-write-type "my-custom-type"))))
  (testing "unsafe input returns nil so the caller rejects rather than parks"
    (is (nil? (tr/resolve-write-type "bad type!")))
    (is (nil? (tr/resolve-write-type nil)))
    (is (nil? (tr/resolve-write-type "   ")))))

(deftest gate-bypass-test
  (testing "the review path can land the gated type verbatim"
    (binding [tr/*gate-bypass?* true]
      (is (= {:type "axiom" :requested "axiom" :queued? false :gate nil}
             (tr/resolve-write-type "axiom")))))
  (testing "the bypass does not leak past its binding"
    (is (:queued? (tr/resolve-write-type "axiom")))))

(deftest queue-tag-algebra-test
  (let [gate (:gate (tr/resolve-write-type "axiom"))]
    (testing "parking stamps the queue tag and records what was requested"
      (is (= ["testing" "axiom-pending" "requested-type:axiom"]
             (tr/queued-tags ["testing"] gate "axiom"))))
    (testing "re-parking an already-parked entry duplicates nothing"
      (is (= ["testing" "axiom-pending" "requested-type:axiom"]
             (tr/queued-tags (tr/queued-tags ["testing"] gate "axiom") gate "axiom"))))
    (testing "works from no tags at all"
      (is (= ["axiom-pending" "requested-type:axiom"]
             (tr/queued-tags [] gate "axiom"))))
    (testing "resolving strips every trace of pendingness"
      (is (= ["testing" "scope:global"]
             (tr/strip-queue-tags
              ["testing" "axiom-pending" "requested-type:axiom" "scope:global"]))))
    (testing "stripping clean tags is a no-op"
      (is (= ["testing" "scope:global"]
             (tr/strip-queue-tags ["testing" "scope:global"]))))
    (testing "the requested type survives the round trip"
      (is (= "axiom" (tr/requested-type-of (tr/queued-tags [] gate "axiom"))))
      (is (nil? (tr/requested-type-of ["testing"]))))))

(deftest queue-type-catchup-wiring-test
  (testing "candidates are hidden from the MCP enum — they are never requested directly"
    (is (not (contains? (set (tr/mcp-types)) "axiom-candidate")))
    (is (contains? (set (tr/mcp-types)) "axiom")))
  (testing "candidates are a registered type with intent-level abstraction"
    (is (tr/known-type? "axiom-candidate"))
    (is (= 4 (tr/abstraction-level "axiom-candidate"))))
  (testing "the review queue leads the catchup order"
    (is (= "axiom-candidate" (:type-str (first (tr/catchup-categories))))))
  (testing "a nomination is NOT piggybacked — it is not law until approved"
    (is (not (contains? (tr/piggyback-types) :axiom-candidate)))
    (is (contains? (tr/piggyback-types) :axiom))))
