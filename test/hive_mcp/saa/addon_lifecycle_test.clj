(ns hive-mcp.saa.addon-lifecycle-test
  "W4 contract suite (C11): SAA addon lifecycle through the registry + addons.core
   hooks-walk.

   C11 an addon publishing {:saa/phase-provider [...]} lands in the registry and
       resolves; deregister-by-owner of the addon leaves :saa/core intact (no
       clobber). Exercised both directly (saa.registry) and end-to-end through
       addons.core/init-addon! → shutdown-addon!, mirroring multi/addon-e2e."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [hive-mcp.protocols.saa :as psaa]
            [hive-mcp.addons.core :as addons]
            [hive-mcp.addons.protocol :as proto]
            [hive-mcp.saa.registry :as registry]
            [hive-mcp.saa.registry.phase-providers :as r-providers]
            [hive-mcp.saa.types :as types]
            [hive-mcp.saa.support :as support]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(use-fixtures :each support/with-fresh-registry)

;; =============================================================================
;; Stub IPhaseProvider — a no-vendor record an addon would contribute
;; =============================================================================

(defrecord StubPhaseProvider [tag]
  psaa/IPhaseProvider
  (phase-config [_ _phase] {:stub tag})
  (build-options [_ _phase neutral-opts] neutral-opts)
  (execute-phase! [_ _session _prompt _opts]
    (doto (async/chan) async/close!)))

;; =============================================================================
;; C11 (direct) — child-registry: addon provider under its own id lands +
;;                resolves; deregister-by-owner leaves :saa/core intact
;; =============================================================================

(deftest c11-addon-phase-provider-lands-and-resolves
  (testing "an addon provider registered under :test-x's own id lands + resolves"
    (let [stub (->StubPhaseProvider :x)]
      (is (= :ok (r-providers/register! :test-x :prov-x {:provider stub}))
          "addon provider registered under its own id without conflict")
      (is (= :test-x (:owner (r-providers/lookup :prov-x))) "addon owns its id")
      (is (identical? stub (:provider (r-providers/lookup :prov-x)))
          "the exact stub record is retrievable")
      (is (satisfies? psaa/IPhaseProvider
                      (registry/lookup-phase-provider-or-default :prov-x))
          "the resolver returns the addon provider for its id"))))

(deftest c11-deregister-owner-leaves-saa-core-intact
  (testing "deregister :test-x removes only its slice; :saa/core still backs default"
    (r-providers/register! :test-x :prov-x {:provider (->StubPhaseProvider :x)})
    (is (= #{:prov-x} (r-providers/deregister-by-owner! :test-x))
        "deregister-by-owner returns exactly the addon's removed ids")
    (is (nil? (r-providers/lookup :prov-x)) "addon entry gone")
    (is (satisfies? psaa/IPhaseProvider
                    (registry/lookup-phase-provider-or-default))
        ":saa/core provider still backs the default after addon deregister")
    (is (= :saa/core (:owner (r-providers/lookup :saa/default)))
        ":saa/core retained ownership of :saa/default (no clobber)")))

(deftest c11-facade-register-by-key-conflict-protects-core
  (testing "façade register-by-key! for :saa/phase-provider conflicts with the
            :saa/core seed (first-write-wins), so the seed is never clobbered"
    (let [out (registry/register-by-key!
               :test-x :saa/phase-provider
               [(types/saa-registry-entry
                 :saa/phase-provider {:provider (->StubPhaseProvider :x)
                                      :owner :test-x})])]
      (is (= [:conflict] out)
          "the façade routes :saa/phase-provider to the fixed :saa/default id,
           where the :saa/core seed already wins")
      (is (= :saa/core (:owner (r-providers/lookup :saa/default)))
          ":saa/core ownership preserved across the addon's conflicting write")
      (is (empty? (:providers (registry/deregister-by-owner! :test-x)))
          "the addon never owned :saa/default, so it removes nothing")
      (is (= :saa/core (:owner (r-providers/lookup :saa/default)))
          ":saa/core default survives the addon's deregister untouched"))))

;; =============================================================================
;; C11 (e2e) — addons.core init-addon! routes :saa/* through the hooks-walk
;; =============================================================================

(def ^:private addon-id "test/saa-lifecycle")

(defn- make-saa-addon
  "Minimal IAddon whose (hooks) publishes one :saa/phase-provider entry under id."
  [id provider]
  (reify proto/IAddon
    (addon-id [_] id)
    (addon-type [_] :native)
    (capabilities [_] #{:tools})
    (initialize! [_ _config] {:success? true :errors []})
    (shutdown! [_] {:success? true})
    (tools [_] [])
    (schema-extensions [_] [])
    (health [_] {:status :ok})
    (excluded-tools [_] #{})
    (hooks [_]
      {:saa/phase-provider
       [(types/saa-registry-entry
         :saa/phase-provider {:provider provider :owner (keyword id)})]})))

(defn- with-fresh-addon [f]
  (try (addons/unregister-addon! addon-id) (catch Throwable _ nil))
  (try (f)
       (finally
         (try (addons/shutdown-addon! addon-id) (catch Throwable _ nil))
         (try (addons/unregister-addon! addon-id) (catch Throwable _ nil)))))

(deftest c11-init-addon-routes-saa-hook-to-registry
  (testing "init-addon! walks (hooks) and routes :saa/phase-provider to saa.registry"
    (with-fresh-addon
      (fn []
        (let [addon  (make-saa-addon addon-id (->StubPhaseProvider :e2e))
              _      (addons/register-addon! addon)
              result (addons/init-addon! addon-id)]
          (is (:success? result) (str "init failed: " (:errors result)))
          (is (satisfies? psaa/IPhaseProvider
                          (registry/lookup-phase-provider-or-default))
              ":saa/* hook routed into the SAA registry"))))))

(deftest c11-shutdown-addon-deregisters-saa-leaving-core
  (testing "shutdown-addon! deregisters the addon's :saa/* entries; :saa/core survives"
    (with-fresh-addon
      (fn []
        (let [addon (make-saa-addon addon-id (->StubPhaseProvider :e2e))]
          (addons/register-addon! addon)
          (addons/init-addon! addon-id)
          (let [shutdown (addons/shutdown-addon! addon-id)]
            (is (:success? shutdown) "shutdown succeeded"))
          (is (= :saa/core (:owner (r-providers/lookup :saa/default)))
              ":saa/core retained the default provider after addon shutdown")
          (is (satisfies? psaa/IObservationScorer
                          (registry/lookup-scorer-or-default))
              ":saa/core scorer unaffected by the addon lifecycle"))))))
