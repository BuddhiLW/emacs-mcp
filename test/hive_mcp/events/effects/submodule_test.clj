(ns hive-mcp.events.effects.submodule-test
  "Tests for SRP-split effect submodules.

   Validates that each submodule registers its effects correctly
   and that the facade delegates properly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.events.effects :as effects]
            [hive-mcp.events.effects.coeffect :as cofx]
            [hive-mcp.events.effects.notification :as notif]
            [hive-mcp.events.effects.memory :as mem]
            [hive-mcp.events.effects.agent :as agent-fx]
            [hive-mcp.events.effects.dispatch :as dispatch-fx]
            [hive-mcp.events.effects.infrastructure :as infra]
            [hive-mcp.events.effects.drone-loop :as drone-loop-fx]
            [hive-mcp.events.core :as ev]))

;; =============================================================================
;; Test fixture: Reset registration before each test
;; =============================================================================

(defn reset-effects-fixture [f]
  (effects/reset-registration!)
  (f))

(use-fixtures :each reset-effects-fixture)

;; =============================================================================
;; Submodule registration tests
;; =============================================================================

(deftest coeffect-register-test
  (testing "Coeffect submodule registers all expected coeffects"
    (cofx/register-coeffects!)
    (is (fn? (ev/get-cofx-handler :now)) ":now registered")
    (is (fn? (ev/get-cofx-handler :agent-context)) ":agent-context registered")
    (is (fn? (ev/get-cofx-handler :db-snapshot)) ":db-snapshot registered")
    (is (fn? (ev/get-cofx-handler :waiting-lings)) ":waiting-lings registered")
    (is (fn? (ev/get-cofx-handler :request-ctx)) ":request-ctx registered")))

(deftest notification-effects-register-test
  (testing "Notification submodule registers all expected effects"
    (notif/register-notification-effects!)
    (is (fn? (ev/get-fx-handler :shout)) ":shout registered")
    (is (fn? (ev/get-fx-handler :targeted-shout)) ":targeted-shout registered")
    (is (fn? (ev/get-fx-handler :log)) ":log registered")
    (is (fn? (ev/get-fx-handler :channel-publish)) ":channel-publish registered")
    (is (fn? (ev/get-fx-handler :emit-system-error)) ":emit-system-error registered")
    (is (fn? (ev/get-fx-handler :olympus-broadcast)) ":olympus-broadcast registered")))

(deftest memory-effects-register-test
  (testing "Memory submodule registers all expected effects"
    (mem/register-memory-effects!)
    (is (fn? (ev/get-fx-handler :memory-write)) ":memory-write registered")
    (is (fn? (ev/get-fx-handler :wrap-notify)) ":wrap-notify registered")
    (is (fn? (ev/get-fx-handler :wrap-crystallize)) ":wrap-crystallize registered")))

(deftest agent-effects-register-test
  (testing "Agent submodule registers all expected effects"
    (agent-fx/register-agent-effects!)
    (is (fn? (ev/get-fx-handler :dispatch-task)) ":dispatch-task registered")
    (is (fn? (ev/get-fx-handler :swarm-send-prompt)) ":swarm-send-prompt registered")
    (is (fn? (ev/get-fx-handler :agora/continue)) ":agora/continue registered")
    (is (fn? (ev/get-fx-handler :agora/execute-drone)) ":agora/execute-drone registered")
    (is (fn? (ev/get-fx-handler :saa/run-workflow)) ":saa/run-workflow registered")))

(deftest dispatch-effects-register-test
  (testing "Dispatch submodule registers all expected effects"
    (dispatch-fx/register-dispatch-effects!)
    (is (fn? (ev/get-fx-handler :dispatch)) ":dispatch registered")
    (is (fn? (ev/get-fx-handler :dispatch-n)) ":dispatch-n registered")))

(deftest infrastructure-effects-register-test
  (testing "Infrastructure submodule registers all expected effects"
    (infra/register-infrastructure-effects!)
    (is (fn? (ev/get-fx-handler :ds-transact)) ":ds-transact registered")
    (is (fn? (ev/get-fx-handler :git-commit)) ":git-commit registered")
    (is (fn? (ev/get-fx-handler :kanban-sync)) ":kanban-sync registered")
    (is (fn? (ev/get-fx-handler :kanban-move-done)) ":kanban-move-done registered")
    (is (fn? (ev/get-fx-handler :report-metrics)) ":report-metrics registered")
    (is (fn? (ev/get-fx-handler :tool-registry-refresh)) ":tool-registry-refresh registered")))

(deftest drone-loop-effects-register-test
  (testing "Drone-loop submodule is a no-op seam in core"
    ;; The `:drone/*` effects are contributed by the drone addon. Core keeps a
    ;; stub so the facade can delegate unconditionally; registering it must be
    ;; harmless and must NOT fabricate handlers core cannot honour.
    (is (nil? (drone-loop-fx/register-drone-loop-effects!))
        "core's drone-loop registration is a no-op")
    (doseq [fx [:drone/seed-session :drone/emit :drone/record-obs :drone/record-reason]]
      (is (nil? (ev/get-fx-handler fx))
          (str fx " is addon-supplied, not core-registered")))))

;; =============================================================================
;; Facade delegation test
;; =============================================================================

(deftest facade-registers-all-submodules-test
  (testing "Facade register-effects! delegates to all submodules"
    (effects/register-effects!)
    ;; Spot-check one coeffect
    (is (fn? (ev/get-cofx-handler :now)) "coeffect registered via facade")
    ;; Spot-check one effect from each submodule core owns
    (is (fn? (ev/get-fx-handler :shout)) "notification registered via facade")
    (is (fn? (ev/get-fx-handler :memory-write)) "memory registered via facade")
    (is (fn? (ev/get-fx-handler :dispatch-task)) "agent registered via facade")
    (is (fn? (ev/get-fx-handler :dispatch)) "dispatch registered via facade")
    (is (fn? (ev/get-fx-handler :ds-transact)) "infrastructure registered via facade")
    (is (fn? (ev/get-fx-handler :kg-add-edge)) "kg registered via facade")
    ;; drone-loop is addon-supplied: the facade must delegate to the stub
    ;; without throwing, and without inventing a handler.
    (is (nil? (ev/get-fx-handler :drone/seed-session))
        "drone-loop stays absent until the drone addon registers it")))

;; =============================================================================
;; Re-export test
;; =============================================================================

(deftest facade-reexports-handler-setters-test
  (testing "Facade re-exports set-memory-write-handler! and set-wrap-crystallize-handler!"
    (is (fn? effects/set-memory-write-handler!) "set-memory-write-handler! exported")
    (is (fn? effects/set-wrap-crystallize-handler!) "set-wrap-crystallize-handler! exported")
    ;; Test that they actually delegate to the memory submodule
    (let [called (atom false)]
      (effects/set-memory-write-handler! (fn [_] (reset! called true)))
      (effects/register-effects!)
      (let [handler (ev/get-fx-handler :memory-write)]
        (handler {:type "note" :content "test"})
        (is @called "Facade setter wires to submodule handler")))))
