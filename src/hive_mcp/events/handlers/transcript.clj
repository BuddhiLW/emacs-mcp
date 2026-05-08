(ns hive-mcp.events.handlers.transcript
  "Transcript event handlers — event-driven dual-write to ITranscriptStore.

   Events:
   - :transcript/entry-recorded  — New transcript entry from agent turn
   - :transcript/session-started — Agent session began (create store)
   - :transcript/session-ended   — Agent session ended (close store)

   Architecture:
     loop/core.clj record-turn! → ev/dispatch [:transcript/entry-recorded ...]
       → this handler → :transcript-append! effect
       → effect fn → ITranscriptStore/append-entry! (Datahike for time-travel)

   Why Datahike over Datalevin for transcripts:
   - Bitemporal queries: 'what did agent see at turn N' vs 'what we know now'
   - Replay: fork-from-cursor creates point-in-time branch
   - Model scoring: same context, different model → compare via as-of
   - A/B testing: same task, different presets → temporal diff
   - Tool usage analytics: history(db) shows all retractions too"
  (:require [hive-mcp.events.core :as ev]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Store Registry (agent-id → ITranscriptStore atom)
;; =============================================================================

(defonce ^:private store-registry (atom {}))

(defn get-store
  "Look up active transcript store for agent-id. Returns nil if none."
  [agent-id]
  (get @store-registry agent-id))

(defn register-store!
  "Register an ITranscriptStore for an agent-id."
  [agent-id store]
  (swap! store-registry assoc agent-id store)
  store)

(defn deregister-store!
  "Remove and return the store for agent-id."
  [agent-id]
  (let [store (get @store-registry agent-id)]
    (swap! store-registry dissoc agent-id)
    store))

(defn store-registry-snapshot
  "Return [[agent-id store] ...] snapshot of currently registered transcript
   stores. Used by the SplitTranscriptStore sweeper to enumerate stores
   without coupling to the private registry atom."
  []
  (vec @store-registry))

;; =============================================================================
;; Handler: :transcript/entry-recorded
;; =============================================================================

(defn handle-entry-recorded
  "Handler for :transcript/entry-recorded events.

   Fired by loop/core.clj record-turn! after each LLM response.
   Appends normalized entry to the agent's ITranscriptStore (Datahike).

   Event data:
   {:agent-id   string
    :turn       int
    :role       keyword (:assistant | :tool | :user | :system)
    :content    string
    :cost-usd   double
    :tool-calls vec (optional)}

   Produces effects:
   - :transcript-append! — Write to ITranscriptStore"
  [_coeffects [_ {:keys [agent-id] :as entry-data}]]
  (if (get-store agent-id)
    {:transcript-append! entry-data
     :log {:level :debug
           :message (format "[transcript] Recording turn %s for %s"
                            (:turn entry-data) agent-id)}}
    ;; No store registered — create one lazily
    {:transcript-ensure-store! {:agent-id agent-id :backend :datahike}
     :dispatch [:transcript/entry-recorded entry-data]
     :log {:level :info
           :message (format "[transcript] Lazy-creating Datahike store for %s" agent-id)}}))

;; =============================================================================
;; Handler: :transcript/session-started
;; =============================================================================

(defn handle-session-started
  "Create and register Datahike transcript store for new agent session.

   Event data:
   {:agent-id string
    :model    string
    :task     string (first 200 chars)}

   Produces effects:
   - :transcript-ensure-store! — Create/connect Datahike store"
  [_coeffects [_ {:keys [agent-id] :as session-data}]]
  {:transcript-ensure-store! (assoc session-data :backend :datahike)
   :log {:level :info
         :message (format "[transcript] Session started for %s (model=%s)"
                          agent-id (:model session-data))}})

;; =============================================================================
;; Handler: :transcript/session-ended
;; =============================================================================

(defn handle-session-ended
  "Close and deregister transcript store for ended agent session.

   Event data:
   {:agent-id string
    :turns    int
    :outcome  keyword (:outcome/completed etc)}

   Produces effects:
   - :transcript-close! — Close ITranscriptStore, release resources"
  [_coeffects [_ {:keys [agent-id turns outcome]}]]
  {:transcript-close! {:agent-id agent-id}
   :log {:level :info
         :message (format "[transcript] Session ended for %s (%d turns, %s)"
                          agent-id (or turns 0) (or outcome :unknown))}})

;; =============================================================================
;; Effects
;; =============================================================================

(defn- make-datahike-store [agent-id]
  (let [result (r/rescue nil
                 (let [make-fn (requiring-resolve 'hive-agent.loop.transcript.datahike-store/make-store)
                       res     (make-fn {:agent-id agent-id})]
                   (if (and (map? res) (contains? res :ok))
                     (:ok res)
                     (do (log/warn "[transcript] Datahike store creation returned non-ok" {:result res})
                         nil))))]
    (or result
        ;; Fallback to datalevin
        (do (log/warn "[transcript] Datahike failed, falling back to datalevin")
            (r/rescue nil
              (let [make-fn (requiring-resolve 'hive-agent.loop.transcript.datalevin-store/make-store)
                    res     (make-fn {:agent-id agent-id})]
                (if (and (map? res) (contains? res :ok)) (:ok res) nil)))))))

(defn handle-transcript-append!
  "Effect: append entry to ITranscriptStore."
  [{:keys [agent-id turn role content cost-usd tool-calls]}]
  (when-let [store (get-store agent-id)]
    (r/rescue nil
      (let [normalize-fn (requiring-resolve 'hive-agent.loop.transcript.store/normalize-entry)
            append-fn    (requiring-resolve 'hive-agent.loop.transcript.store/append-entry!)
            entry        (normalize-fn {:role       (name (or role :unknown))
                                        :content    (or content "")
                                        :tool_calls (or tool-calls [])}
                                       {:agent-id agent-id
                                        :turn     (or turn 0)
                                        :cost-usd (or cost-usd 0.0)})]
        (append-fn store entry)))))

(defn- transcript-embed-fn
  "Build the `:embed-fn` callback fed to QdrantTranscriptStore.
   Soft-deps `hive-mcp.embeddings.service` so this ns stays importable
   without it (returns nil → SplitTranscriptStore never picks split path)."
  []
  (when-let [emb (try (requiring-resolve 'hive-mcp.embeddings.service/embed-for-collection)
                       (catch Throwable _ nil))]
    (let [coll-fn (try (requiring-resolve 'hive-agent.config/resolve-transcript-embedding-collection)
                       (catch Throwable _ nil))
          coll    (or (when coll-fn (coll-fn)) "hive_mcp_memory")]
      (fn embed [text] (emb coll (str text))))))

(defn- make-store-from-config
  "Resolve the backend per config.edn / env, construct via the canonical
   tstore/make-store factory, and inject :embed-fn for the split path.

   Falls back to the old datahike→datalevin chain on any Result/err so
   existing deployments don't lose persistence."
  [agent-id]
  (let [resolve-backend! (try (requiring-resolve 'hive-agent.config/resolve-transcript-backend)
                              (catch Throwable _ nil))
        make-fn          (try (requiring-resolve 'hive-agent.loop.transcript.store/make-store)
                              (catch Throwable _ nil))
        resolve-coll!    (try (requiring-resolve 'hive-agent.config/resolve-transcript-qdrant-collection)
                              (catch Throwable _ nil))
        backend          (when resolve-backend! (resolve-backend! :datahike))
        opts             (cond-> {:agent-id agent-id}
                           (= backend :split-qdrant-datalevin)
                           (assoc :embed-fn        (transcript-embed-fn)
                                  :collection-name (when resolve-coll! (resolve-coll!))))
        attempt          (when (and make-fn backend) (make-fn backend opts))]
    (cond
      (and (map? attempt) (contains? attempt :ok)) (:ok attempt)
      :else
      (do (log/warn "[transcript] split/preferred backend unavailable — falling back to datahike→datalevin"
                    {:backend backend
                     :error   (when (map? attempt) (:error attempt))})
          (make-datahike-store agent-id)))))

(defn handle-transcript-ensure-store!
  "Effect: construct the configured ITranscriptStore (per
   `hive-agent.config/resolve-transcript-backend`) and register it.

   Honors :split-qdrant-datalevin (injects :embed-fn from hive-mcp's
   embedding service). Falls back to legacy datahike→datalevin on any
   Result/err so existing deployments stay working."
  [{:keys [agent-id]}]
  (when (and agent-id (not (get-store agent-id)))
    (when-let [store (make-store-from-config agent-id)]
      (register-store! agent-id store)
      (log/info "[transcript] store registered" {:agent-id agent-id
                                                 :store    (some-> store class .getName)}))))

(defn handle-transcript-close!
  "Effect: close and deregister store."
  [{:keys [agent-id]}]
  (when-let [store (deregister-store! agent-id)]
    (r/rescue nil
      (let [close-fn (requiring-resolve 'hive-agent.loop.transcript.store/close!)]
        (close-fn store)))))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-handlers!
  "Register transcript event handlers and effects.
   Called from hive-mcp init or event system bootstrap."
  []
  (ev/reg-event :transcript/entry-recorded  [] handle-entry-recorded)
  (ev/reg-event :transcript/session-started [] handle-session-started)
  (ev/reg-event :transcript/session-ended   [] handle-session-ended)

  (ev/reg-fx :transcript-append!       handle-transcript-append!)
  (ev/reg-fx :transcript-ensure-store! handle-transcript-ensure-store!)
  (ev/reg-fx :transcript-close!        handle-transcript-close!))