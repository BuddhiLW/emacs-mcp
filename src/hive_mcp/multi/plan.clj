(ns hive-mcp.multi.plan
  "Persistent compile-then-run for the multi tool. Two modes:

   1. `(compile-and-persist! ops opts)` — runs the pure compile pipeline,
      persists the resulting wave plan as a `:plan` memory entry, returns
      the plan-id + a snapshot of waves.

   2. `(run! plan-id opts)` — fetches the persisted plan, optionally compares
      the stored `:registry/version` against the current registry snapshot
      (raises `:multi/registry-stale` warn-log on mismatch), then dispatches
      via the `tools.multi/run-multi` executor.

   Plans are written as memory entries with type=plan, content=EDN form of
   the plan map, tagged `multi-plan`. They decay along normal memory rules.

   Decision: 20260429230453-7e7627cc"
  (:require [hive.events.multi :as ev-multi]
            [hive-mcp.multi.registry :as registry]
            [hive-dsl.result :as r :refer [rescue]]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [hive-mcp.multi.util :as util]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Lazy resolvers (avoid hard load-order coupling with memory + tools.multi)
;; =============================================================================

(defn- memory-add []
  (rescue nil
    (some-> (requiring-resolve 'hive-mcp.tools.memory.crud/handle-add) deref)))

(defn- memory-get []
  (rescue nil
    (some-> (requiring-resolve 'hive-mcp.tools.memory.crud/handle-get-full) deref)))

(defn- run-multi []
  (rescue nil
    (some-> (requiring-resolve 'hive-mcp.tools.multi/run-multi) deref)))

;; =============================================================================
;; Compile + persist
;; =============================================================================

(defn- extract-id [add-result]
  (let [decoded (util/decode-mcp-text add-result)]
    (or (:id decoded)
        (some-> decoded :entry :id))))

(defn compile-and-persist!
  "Pure-compile the ops, persist the plan, return summary + plan-id.

   Returns a Result:
     ok  → {:plan-id \"<id>\" :wave-count N :registry/version hash :ops [...]}
     err → :multi/plan-compile-failed | :multi/plan-persist-failed

   `opts` keys:
     :reason    — optional human note stored on the plan entry
     :directory — project scope for the memory entry"
  [ops {:keys [reason directory] :as _opts}]
  (let [snap (registry/snapshot)
        compiled (ev-multi/compile-multi-spec ops)]
    (cond
      (not (:valid compiled))
      (r/err :multi/plan-compile-failed
             {:errors (:errors compiled)
              :message (str "compile-multi-spec rejected ops: "
                            (clojure.string/join "; " (:errors compiled)))})

      :else
      (if-let [add-fn (memory-add)]
        (let [plan-payload {:ops        (:ops compiled)
                            :waves      (:waves compiled)
                            :wave-count (:wave-count compiled)
                            :registry/version (:version snap)
                            :reason     reason
                            :compiled-at (str (java.time.Instant/now))}
              ;; type "note" (not "plan") — type=plan is intercepted by the
              ;; kanban plan-to-kanban parser and would reject our :ops shape.
              ;; The "multi-plan" tag distinguishes us from generic notes.
              add-result (add-fn (cond-> {:type "note"
                                          :content (pr-str plan-payload)
                                          :tags ["multi-plan" "ephemeral"]
                                          :duration "short"
                                          :async? false}
                                   directory (assoc :directory directory)))
              plan-id (extract-id add-result)]
          (if plan-id
            (r/ok {:plan-id plan-id
                   :wave-count (:wave-count compiled)
                   :registry/version (:version snap)
                   :ops (:ops compiled)})
            (r/err :multi/plan-persist-failed
                   {:add-result add-result
                    :message "memory.add did not return a plan id"})))
        (r/err :multi/plan-persist-failed
               {:message "hive-mcp.tools.memory.crud/handle-add not resolvable"})))))

;; =============================================================================
;; Fetch
;; =============================================================================

(defn fetch
  "Retrieve a persisted plan by id.

   Returns a Result:
     ok  → plan-payload-map
     err → :multi/plan-not-found | :multi/plan-malformed"
  [plan-id]
  (if-let [get-fn (memory-get)]
    (let [result (rescue nil (get-fn {:id plan-id}))
          decoded (util/decode-mcp-text result)
          content (or (:content decoded)
                      (some-> decoded :entry :content))]
      (cond
        (or (nil? result) (nil? content))
        (r/err :multi/plan-not-found
               {:plan-id plan-id
                :message (str "no plan with id: " plan-id)})

        :else
        (let [parsed (rescue nil (edn/read-string content))]
          (cond
            (nil? parsed)
            (r/err :multi/plan-malformed
                   {:plan-id plan-id
                    :message "plan content is not valid EDN"})

            (not (and (map? parsed) (sequential? (:ops parsed))))
            (r/err :multi/plan-malformed
                   {:plan-id plan-id
                    :message "plan content missing :ops vector"})

            :else (r/ok parsed)))))
    (r/err :multi/plan-not-found
           {:plan-id plan-id
            :message "memory.get-full not resolvable"})))

;; =============================================================================
;; Run
;; =============================================================================

(defn- registry-stale? [stored-version]
  (let [current (:version (registry/snapshot))]
    (and stored-version current (not= stored-version current))))

(defn run!
  "Execute a persisted plan by id.

   Returns a Result:
     ok  → run-result map (the standard {:success :waves :summary} shape)
     err → :multi/plan-not-found | :multi/plan-malformed | :multi/* runtime errs

   On registry-version mismatch a warn is logged but execution proceeds —
   replays remain best-effort."
  [plan-id _opts]
  (let [fetched (fetch plan-id)]
    (if (r/err? fetched)
      fetched
      (let [plan (:ok fetched)
            stored-ver (get plan :registry/version)]
        (when (registry-stale? stored-ver)
          (log/warn "[multi.plan] :multi/registry-stale — replaying plan"
                    plan-id "against a registry whose version differs"
                    {:stored-version stored-ver
                     :current-version (:version (registry/snapshot))}))
        (if-let [run-fn (run-multi)]
          (let [result (rescue nil (run-fn (:ops plan)))]
            (if result
              (r/ok (assoc result :plan-id plan-id
                                  :registry/version stored-ver
                                  :registry/stale? (registry-stale? stored-ver)))
              (r/err :multi/plan-compile-failed
                     {:plan-id plan-id
                      :message "run-multi returned nil"})))
          (r/err :multi/plan-compile-failed
                 {:message "tools.multi/run-multi not resolvable"}))))))
