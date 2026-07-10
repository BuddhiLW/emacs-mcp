(ns hive-mcp.agent.drone.ext-router
  "Dynamic backend discovery and routing for drone execution.

   Probes available backend namespaces and selects the best one.
   Priority: fsm-agentic > sdk-drone > agentic-loop.

   The :agentic-loop backend is the provider-agnostic fallback
   (replaced the legacy :openrouter-loop name in the seam cleanup)."
  (:require [taoensso.timbre :as log]))

(def ^:private backend-nses
  "Ordered list of [backend-key namespace-sym] by preference (highest first)."
  [[:fsm-agentic   'hive-mcp.agent.drone.backend.fsm-agentic]
   [:sdk-drone     'hive-mcp.agent.drone.backend.sdk-drone]
   [:agentic-loop  'hive-mcp.agent.drone.backend.agentic-loop]])

(defonce ^:private available-backends (atom nil))
(defonce ^:private failed-backends (atom #{}))

(defn load-available-backends!
  "Attempt to require each backend namespace. Retries previously failed backends."
  []
  (let [need-retry? (seq @failed-backends)
        need-init?  (nil? @available-backends)]
    (when (or need-init? need-retry?)
      (let [targets (if need-init?
                      backend-nses
                      (filterv (fn [[k _]] (contains? @failed-backends k)) backend-nses))
            {:keys [loaded failed]}
            (reduce
             (fn [acc [k ns-sym]]
               (try
                 (require ns-sym :reload)
                 (update acc :loaded conj k)
                 (catch Exception e
                   (log/debug "ext-router: backend not available" {:backend k :error (ex-message e)})
                   (update acc :failed conj k))))
             {:loaded [] :failed #{}}
             targets)]
        (if need-init?
          (reset! available-backends loaded)
          (swap! available-backends into loaded))
        (reset! failed-backends failed)
        (log/info "ext-router: loaded backends" {:available @available-backends
                                                 :failed   failed})))))

(defn best-backend
  "Return the highest-priority available backend keyword, or :agentic-loop as fallback."
  []
  (or (first @available-backends) :agentic-loop))
