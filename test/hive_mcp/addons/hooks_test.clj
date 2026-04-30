(ns hive-mcp.addons.hooks-test
  "Tests for the IAddon `hooks` protocol method and its registry wiring.

   Covers:
   - Addon with `(hooks ...)` registers all entries on init.
   - Shutdown clears them.
   - Addon without override gets default `{}` and registers nothing.
   - Re-init is idempotent (no double-register, no spurious change).
   - Two addons don't clobber each other's hooks (per-addon ownership).

   See decision 20260429195812-0c5dfe8d for design rationale."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [hive-mcp.addons.protocol :as proto]
            [hive-mcp.addons.core :as addons]
            [hive-mcp.extensions.registry :as ext]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-everything-fixture
  "Reset addon registry AND extension registry around each test, so hook
   registrations leaking across tests are not possible."
  [f]
  (addons/reset-registry!)
  (ext/clear-all!)
  (try
    (f)
    (finally
      (addons/reset-registry!)
      (ext/clear-all!))))

(use-fixtures :each reset-everything-fixture)

;; =============================================================================
;; Test Addons
;; =============================================================================

;; LegacyAddon — does NOT implement hooks. Stand-in for addons that
;; existed before the protocol was extended (e.g. hive-milvus, hive-claude,
;; basic-tools-mcp, hive-ttracking, hive-qdrant). Verifies the rescue
;; default-of-{} path in init-addon!.
(defrecord LegacyAddon [id]
  proto/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _opts] {:success? true :errors []})
  (shutdown! [_] {:success? true :errors []})
  (tools [_] [])
  (schema-extensions [_] {})
  (health [_] {:status :ok :details {}}))

(defn ->legacy [id] (->LegacyAddon id))

;; HookedAddon — declares two hooks on init. Each call returns a unique
;; sentinel so we can assert which addon's fn won the registry slot.
(defrecord HookedAddon [id hook-keys init-counter]
  proto/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _opts]
    (swap! init-counter inc)
    {:success? true :errors []})
  (shutdown! [_] {:success? true :errors []})
  (tools [_] [])
  (schema-extensions [_] {})
  (health [_] {:status :ok :details {}})
  (hooks [_]
    (into {}
          (for [k hook-keys]
            [k (fn [& _] {:from id :hook k})]))))

(defn ->hooked
  ([id ks] (->HookedAddon id ks (atom 0))))

;; =============================================================================
;; Default-impl rescue (legacy addons)
;; =============================================================================

(deftest test-legacy-addon-no-hooks-registered
  (testing "Addon that does not implement `hooks` initializes cleanly and
            registers nothing into the extension registry."
    (let [addon (->legacy :legacy-1)]
      (addons/register-addon! addon)
      (let [pre-keys (ext/registered-keys)
            result (addons/init-addon! :legacy-1)
            post-keys (ext/registered-keys)]
        (is (true? (:success? result)))
        (is (= pre-keys post-keys)
            "Extension registry must be unchanged after init of a legacy addon.")
        (is (nil? (:hook-keys (addons/get-addon-entry :legacy-1)))
            "Registry entry has no :hook-keys for legacy addons.")))))

;; =============================================================================
;; Hook registration on init
;; =============================================================================

(deftest test-hooks-registered-on-init
  (testing "All entries returned by `(hooks addon)` are registered into
            the extension registry under their declared keys."
    (let [addon (->hooked :ax/main [:cu/a :catchup/wrap :gx/score])]
      (addons/register-addon! addon)
      (addons/init-addon! :ax/main)
      (is (ext/extension-available? :cu/a))
      (is (ext/extension-available? :catchup/wrap))
      (is (ext/extension-available? :gx/score))
      (testing "Registered fns are addon-owned (sentinel check)."
        (is (= {:from :ax/main :hook :cu/a}
               ((ext/get-extension :cu/a))))
        (is (= {:from :ax/main :hook :gx/score}
               ((ext/get-extension :gx/score)))))
      (testing "Per-addon ownership is tracked under entry :hook-keys."
        (is (= #{:cu/a :catchup/wrap :gx/score}
               (:hook-keys (addons/get-addon-entry :ax/main))))))))

(deftest test-hooks-empty-map-registers-nothing
  (testing "An addon whose `hooks` returns {} registers no extensions and
            sets no :hook-keys (cheap branch — no spurious tracking)."
    (let [addon (->hooked :ax/empty [])]
      (addons/register-addon! addon)
      (let [pre (ext/registered-keys)]
        (addons/init-addon! :ax/empty)
        (is (= pre (ext/registered-keys)))
        (is (nil? (:hook-keys (addons/get-addon-entry :ax/empty))))))))

;; =============================================================================
;; Shutdown clears hooks
;; =============================================================================

(deftest test-hooks-deregistered-on-shutdown
  (testing "shutdown-addon! removes every hook this addon registered, and
            the registry entry forgets its :hook-keys."
    (let [addon (->hooked :ax/temp [:tmp/a :tmp/b])]
      (addons/register-addon! addon)
      (addons/init-addon! :ax/temp)
      (is (ext/extension-available? :tmp/a))
      (is (ext/extension-available? :tmp/b))
      (addons/shutdown-addon! :ax/temp)
      (is (not (ext/extension-available? :tmp/a)))
      (is (not (ext/extension-available? :tmp/b)))
      (is (nil? (:hook-keys (addons/get-addon-entry :ax/temp)))))))

(deftest test-reset-registry-clears-hooks
  (testing "reset-registry! shuts down all active addons, clearing their hooks."
    (let [addon (->hooked :ax/r [:r/a :r/b])]
      (addons/register-addon! addon)
      (addons/init-addon! :ax/r)
      (is (ext/extension-available? :r/a))
      (addons/reset-registry!)
      (is (not (ext/extension-available? :r/a)))
      (is (not (ext/extension-available? :r/b))))))

;; =============================================================================
;; Idempotency
;; =============================================================================

(deftest test-init-idempotent-no-double-register
  (testing "Calling init-addon! twice does not call initialize! twice and
            does not double-register hooks (already-active short-circuits)."
    (let [addon (->hooked :ax/idem [:idem/a])]
      (addons/register-addon! addon)
      (addons/init-addon! :ax/idem)
      (let [first-fn (ext/get-extension :idem/a)
            entry-before (addons/get-addon-entry :ax/idem)
            init-count-before @(:init-counter addon)
            re-init (addons/init-addon! :ax/idem)
            entry-after (addons/get-addon-entry :ax/idem)
            second-fn (ext/get-extension :idem/a)]
        (is (true? (:success? re-init)))
        (is (true? (:already-active? re-init)))
        (is (= init-count-before @(:init-counter addon))
            "initialize! must not be called a second time.")
        (is (identical? first-fn second-fn)
            "Re-init must not replace the registered hook fn.")
        (is (= (:hook-keys entry-before) (:hook-keys entry-after)))))))

(deftest test-shutdown-then-reinit-restores-hooks
  (testing "After shutdown + init, hooks are registered again under the
            fresh init's fn (not the stale one)."
    (let [addon (->hooked :ax/cycle [:cyc/k])]
      (addons/register-addon! addon)
      (addons/init-addon! :ax/cycle)
      (let [old-fn (ext/get-extension :cyc/k)]
        (addons/shutdown-addon! :ax/cycle)
        (is (not (ext/extension-available? :cyc/k)))
        (addons/init-addon! :ax/cycle)
        (is (ext/extension-available? :cyc/k))
        (is (not (identical? old-fn (ext/get-extension :cyc/k)))
            "A fresh init produces a fresh fn closure (HookedAddon's hooks
             returns new fns each call).")))))

;; =============================================================================
;; Cross-addon ownership (no clobber on shutdown)
;; =============================================================================

(deftest test-two-addons-do-not-clobber-each-other
  (testing "Two addons registering disjoint hooks both succeed; shutting down
            one does not remove the other's hooks."
    (let [a (->hooked :ax/A [:a/k1 :a/k2])
          b (->hooked :ax/B [:b/k1 :b/k2])]
      (addons/register-addon! a)
      (addons/register-addon! b)
      (addons/init-addon! :ax/A)
      (addons/init-addon! :ax/B)
      (is (ext/extension-available? :a/k1))
      (is (ext/extension-available? :b/k1))
      ;; Shut down A — only A's keys must vanish.
      (addons/shutdown-addon! :ax/A)
      (is (not (ext/extension-available? :a/k1)))
      (is (not (ext/extension-available? :a/k2)))
      (is (ext/extension-available? :b/k1)
          "B's hooks survive A's shutdown.")
      (is (ext/extension-available? :b/k2)))))

(deftest test-overlapping-hook-key-last-write-wins-but-shutdown-isolates
  (testing "If two addons declare the SAME hook key, ext/register! is
            last-write-wins (per registry doc). Shutting down the loser
            still removes that key from the registry, because per-addon
            tracking treats every owner as registering its own copy.

            This documents the *current* contract: addons MUST NOT collide
            on hook keys in production. The decision says hook keys are
            'a documented namespaced surface owned by hive-mcp', so this
            is a defensive test against accidental key collisions."
    (let [a (->hooked :ax/first [:shared/k])
          b (->hooked :ax/second [:shared/k])]
      (addons/register-addon! a)
      (addons/register-addon! b)
      (addons/init-addon! :ax/first)
      (addons/init-addon! :ax/second)
      ;; Last-write-wins: B's fn currently occupies :shared/k.
      (is (= :ax/second (:from ((ext/get-extension :shared/k)))))
      ;; Shutting down A still removes :shared/k (A claims ownership).
      ;; This is acceptable — collisions are a configuration error,
      ;; and the alternative (skip-if-not-mine) would silently leak hooks.
      (addons/shutdown-addon! :ax/first)
      (is (not (ext/extension-available? :shared/k))
          "Last-write-wins under collision; documented edge case."))))
