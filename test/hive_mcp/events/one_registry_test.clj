(ns hive-mcp.events.one-registry-test
  "An addon is published to maven; this host is not. If the host kept its own
   event-handler atom, an addon could only register a handler by reaching into
   hive-mcp, so the host became a load-time dependency of every addon that
   emits events. One registry removes that reason: a handler registered
   through the hive-events LIBRARY is dispatched by the host, and vice versa.
   A regression here is silent, the handler is simply never invoked."
  (:require [clojure.test :refer [deftest is testing]]
            [hive.events :as lib]
            [hive-mcp.events.core :as ev]))

(deftest handler-registered-through-the-library-is-dispatched-by-the-host-test
  (ev/with-clean-registry
    (let [seen (atom nil)]
      (lib/reg-event-fx ::from-addon
                        (fn [_cofx event] (reset! seen (second event)) {}))
      (testing "the host sees the library's registration"
        (is (true? (ev/handler-registered? ::from-addon)))
        (is (contains? (ev/registered-events) ::from-addon)))
      (ev/dispatch [::from-addon {:n 1}])
      (is (= {:n 1} @seen) "the host's dispatch invoked the addon's handler"))))

(deftest handler-registered-through-the-host-is-dispatched-by-the-library-test
  (ev/with-clean-registry
    (let [seen (atom nil)]
      (ev/reg-event ::from-host [] (fn [_cofx event] (reset! seen (second event)) {}))
      (is (true? (lib/event-registered? ::from-host)))
      (lib/dispatch-sync [::from-host {:n 2}])
      (is (= {:n 2} @seen) "the library's dispatch invoked the host's handler"))))

(deftest host-interceptors-decorate-the-shared-entry-test
  (ev/with-clean-registry
    (let [order (atom [])]
      (lib/reg-event-fx ::decorated
                        (fn [_cofx _event] (swap! order conj :handler) {}))
      (is (true? (ev/append-interceptor!
                  ::decorated
                  {:id :host-telemetry
                   :before (fn [ctx] (swap! order conj :interceptor) ctx)})))
      (testing "the appended interceptor is in the chain the library stores"
        (is (= [:host-telemetry] (mapv :id (:interceptors (lib/get-event ::decorated))))))
      (ev/dispatch [::decorated])
      (is (= [:interceptor :handler] @order)
          "the host's interceptor runs before the handler, exactly once"))))

(deftest deregistration-is-shared-test
  (ev/with-clean-registry
    (lib/reg-event-fx ::shared (fn [_ _] {}))
    (is (true? (ev/unreg-event ::shared)))
    (is (false? (lib/event-registered? ::shared))
        "the host's unreg removed the library's registration")))
