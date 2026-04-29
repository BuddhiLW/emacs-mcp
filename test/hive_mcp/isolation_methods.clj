(ns hive-mcp.isolation-methods
  "Test-isolation methods specific to hive-mcp state.

   Registers via `defmethod hive-test.isolation/emit-isolation` so test
   files can compose isolations declaratively:

     (use-fixtures :each (iso/with-isolations :swarm-ds :events))

   Loading this namespace is a side-effect: it adds methods to the
   isolation multimethod. Tests should require it for its registrations:

     (:require [hive-test.isolation :as iso]
               hive-mcp.isolation-methods)

   Each registered type:

     :swarm-ds          — fresh DataScript swarm conn via ds-conn/*test-conn*
     :agent-registry    — clear hivemind agent-registry before+after
     :terminal-registry — clear ling terminal strategy registry
     :headless-registry — clear ling headless strategy registry
     :events            — reset event registration + handlers"
  (:require [hive-test.isolation :as iso]
            [hive-mcp.swarm.datascript.connection :as ds-conn]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; :swarm-ds — DataScript swarm conn override (per thread)
;; =============================================================================

(defmethod iso/emit-isolation :swarm-ds [_]
  (fn [f]
    (binding [ds-conn/*test-conn* (ds-conn/create-conn)]
      (f))))

;; =============================================================================
;; :agent-registry — hivemind agent registry clear-on-bracket
;; =============================================================================
;;
;; The agent-registry is a bounded-atom singleton. There is no binding-style
;; override, so we clear before and after each test. This still leaks across
;; the JVM (tests in this fixture race other code touching the registry),
;; but matches the pre-existing reset-all-registries behavior in
;; hivemind_spawn_registration_test.

(defmethod iso/emit-isolation :agent-registry [_]
  (fn [f]
    (let [clear! (fn []
                   (when-let [reg (some-> 'hive-mcp.hivemind.core/agent-registry
                                          requiring-resolve
                                          var-get)]
                     (when-let [bclear! (some-> 'hive-dsl.bounded-atom/bclear!
                                                requiring-resolve)]
                       (bclear! reg))))]
      (clear!)
      (try (f) (finally (clear!))))))

;; =============================================================================
;; :terminal-registry / :headless-registry — ling strategy backends
;; =============================================================================

(defn- clear-registry-via [ns-sym]
  (when-let [v (requiring-resolve (symbol (str ns-sym) "clear-registry!"))]
    (v)))

(defmethod iso/emit-isolation :terminal-registry [_]
  (fn [f]
    (clear-registry-via 'hive-mcp.agent.ling.terminal-registry)
    (try (f)
         (finally (clear-registry-via 'hive-mcp.agent.ling.terminal-registry)))))

(defmethod iso/emit-isolation :headless-registry [_]
  (fn [f]
    (clear-registry-via 'hive-mcp.agent.ling.headless-registry)
    (try (f)
         (finally (clear-registry-via 'hive-mcp.agent.ling.headless-registry)))))

;; =============================================================================
;; :events — event registration + handlers reset
;; =============================================================================

(defmethod iso/emit-isolation :events [_]
  (fn [f]
    (let [reset-reg!   (requiring-resolve 'hive-mcp.events.handlers/reset-registration!)
          reset-all!   (requiring-resolve 'hive-mcp.events.core/reset-all!)
          init!        (requiring-resolve 'hive-mcp.events.core/init!)
          register!    (requiring-resolve 'hive-mcp.events.handlers/register-handlers!)]
      (reset-reg!)
      (reset-all!)
      (init!)
      (register!)
      (f))))

;; =============================================================================
;; :kg-conn — Knowledge graph store override (per thread)
;; =============================================================================

(defmethod iso/emit-isolation :kg-conn [_]
  (fn [f]
    (let [kg-conn  (requiring-resolve 'hive-mcp.knowledge-graph.connection/get-conn)
          ds-store (requiring-resolve 'hive-mcp.knowledge-graph.store.datascript/create-store)
          test-store-var (requiring-resolve 'hive-mcp.knowledge-graph.connection/*test-store*)]
      (with-bindings* {test-store-var (ds-store)}
        (fn [] (f))))))
