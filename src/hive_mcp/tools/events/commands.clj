(ns hive-mcp.tools.events.commands
  "Per-subcommand handlers for the `events` MCP tool.

   All handlers dispatch lazily via `requiring-resolve` against
   `hive-agent.events.*` so this ns loads cleanly even if hive-agent is
   not on the classpath. When a target var is unresolvable we return a
   structured error response shaped like `hive-mcp.tools.core/mcp-error`.

   Returned shapes:
   - Success:  {:ok true  :command <kw> :result <data>}
   - Error:    {:ok false :command <kw> :error <msg>}

   The router (`core.clj`) wraps these with `mcp-json` for the MCP boundary."
  (:require [clojure.string  :as str]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ok
  [command data]
  {:ok true :command command :result data})

(defn- err
  [command msg]
  {:ok false :command command :error msg})

(defn- resolve-or-throw
  "Resolve a fully-qualified symbol or throw with a structured error."
  [sym-str]
  (let [sym (cond
              (symbol? sym-str)  sym-str
              (string? sym-str)  (try (symbol sym-str)
                                   (catch Exception _ nil))
              :else nil)]
    (when (or (nil? sym) (nil? (namespace sym)))
      (throw (ex-info "Symbol must be fully-qualified ns/name" {:symbol sym-str})))
    (try
      (or (requiring-resolve sym)
          (throw (ex-info "Could not resolve symbol" {:symbol sym})))
      (catch Throwable t
        (throw (ex-info (str "Could not resolve symbol: " (ex-message t))
                        {:symbol sym}))))))

(defn- normalize-agent-ids
  "Coerce :agent-ids/:agent_ids/:all into the shape `hive-agent.events.observers/enable!`
   expects: either `:all` keyword or a set of agent-id strings."
  [{:keys [agent-ids agent_ids all]}]
  (cond
    (true? all)              :all
    (= "all" agent-ids)      :all
    (= :all agent-ids)       :all
    (set? agent-ids)         agent-ids
    (sequential? agent-ids)  (set agent-ids)
    (set? agent_ids)         agent_ids
    (sequential? agent_ids)  (set agent_ids)
    :else                    nil))

(defn- agent-id*
  [{:keys [agent-id agent_id id]}]
  (or agent-id agent_id id))

;; =============================================================================
;; enable / disable
;; =============================================================================

(defn handle-enable
  [params]
  (r/rescue (err :enable "Internal error in events.enable")
    (let [aids (normalize-agent-ids params)]
      (if (nil? aids)
        (err :enable "Provide :agent-ids ([...]) or :all true")
        (let [enable! (resolve-or-throw 'hive-agent.events.observers/enable!)
              flt (enable! aids)]
          (ok :enable {:filter (cond
                                 (= flt :all) "all"
                                 (set? flt)   (vec flt)
                                 :else        flt)
                       :enabled? true}))))))

(defn handle-disable
  [params]
  (r/rescue (err :disable "Internal error in events.disable")
    (let [aids (or (normalize-agent-ids params) :all)
          disable! (resolve-or-throw 'hive-agent.events.observers/disable!)
          flt (disable! aids)]
      (ok :disable {:filter (cond
                              (= flt :all) "all"
                              (set? flt)   (vec flt)
                              :else        flt)
                    :enabled? (boolean flt)}))))

;; =============================================================================
;; tail / dump / stats
;; =============================================================================

(defn- entry->edn
  "Serialize an entry for transport. PersistentQueue-style entries are
   `[event-id payload]` with `Instant` recorded-at; we render the Instant as
   ISO string so the JSON layer doesn't choke."
  [[id payload]]
  [id (cond-> payload
        (and (map? payload)
             (instance? java.time.Instant (:event/recorded-at payload)))
        (update :event/recorded-at str))])

(defn handle-tail
  [params]
  (r/rescue (err :tail "Internal error in events.tail")
    (let [aid (agent-id* params)
          n   (or (:n params) 50)]
      (if (str/blank? (str aid))
        (err :tail "Missing :agent-id")
        (let [tail (resolve-or-throw 'hive-agent.events.ring-buffer/tail)
              entries (tail aid (long n))]
          (ok :tail {:agent-id aid
                     :count    (count entries)
                     :entries  (mapv entry->edn entries)}))))))

(defn handle-dump
  [params]
  (r/rescue (err :dump "Internal error in events.dump")
    (let [aid (agent-id* params)]
      (if (str/blank? (str aid))
        (err :dump "Missing :agent-id")
        (let [dump (resolve-or-throw 'hive-agent.events.ring-buffer/dump)
              entries (dump aid)]
          (ok :dump {:agent-id aid
                     :count    (count entries)
                     :entries  (mapv entry->edn entries)}))))))

(defn- handler-registered?-fn
  []
  (try
    (requiring-resolve 'hive.events/handler-registered?)
    (catch Throwable _ nil)))

(defn- observed-events-set
  []
  (try
    (when-let [v (requiring-resolve 'hive-agent.events.observers/observed-events)]
      (set (keys @v)))
    (catch Throwable _ #{})))

(defn handle-stats
  [params]
  (r/rescue (err :stats "Internal error in events.stats")
    (let [aid (agent-id* params)]
      (if (str/blank? (str aid))
        (err :stats "Missing :agent-id")
        (let [stats-fn (resolve-or-throw 'hive-agent.events.ring-buffer/stats)
              s        (stats-fn aid)
              hreg     (handler-registered?-fn)
              decl     (observed-events-set)
              registered (when hreg
                           (into {} (for [eid decl]
                                      [eid (boolean (hreg eid))])))]
          (ok :stats {:agent-id aid
                      :buffer   (when s
                                  (-> s
                                      (update :first-ts #(some-> % str))
                                      (update :last-ts  #(some-> % str))))
                      :handlers registered}))))))

;; =============================================================================
;; register-handler / unreg-fx
;; =============================================================================

(defn handle-register-handler
  [{:keys [event-id event_id symbol sym]}]
  (r/rescue (err :register-handler "Internal error in events.register-handler")
    (let [eid (or event-id event_id)
          fq  (or symbol sym)]
      (cond
        (str/blank? (str eid))
        (err :register-handler "Missing :event-id")

        (str/blank? (str fq))
        (err :register-handler "Missing :symbol (fully-qualified ns/name)")

        :else
        (let [eid-kw (if (keyword? eid) eid (keyword (str/replace (str eid) #"^:" "")))
              v      (resolve-or-throw fq)
              reg!   (resolve-or-throw 'hive.events/register-handler!)]
          (reg! eid-kw @v)
          (ok :register-handler {:event-id eid-kw
                                 :symbol   (str fq)}))))))

(defn handle-unreg-fx
  [{:keys [fx-id fx_id]}]
  (r/rescue (err :unreg-fx "Internal error in events.unreg-fx")
    (let [fxid (or fx-id fx_id)]
      (if (str/blank? (str fxid))
        (err :unreg-fx "Missing :fx-id")
        (let [fxkw (if (keyword? fxid) fxid (keyword (str/replace (str fxid) #"^:" "")))
              unreg (resolve-or-throw 'hive.events.fx/unreg-fx)]
          (unreg fxkw)
          (ok :unreg-fx {:fx-id fxkw}))))))

;; =============================================================================
;; help
;; =============================================================================

(def help-text
  (str "events tool — hive-agent observability surface\n"
       "\n"
       "Subcommands:\n"
       "  enable {:agent-ids [...] | :all true}\n"
       "      Register opt-in observer that pushes events to the ring buffer.\n"
       "  disable {:agent-ids [...] | :all true}\n"
       "      Remove observer (or narrow filter).\n"
       "  tail {:agent-id ID :n 50}\n"
       "      Return last n entries from the ring buffer.\n"
       "  dump {:agent-id ID}\n"
       "      Full buffer copy.\n"
       "  stats {:agent-id ID}\n"
       "      Ring-buffer stats + handler-registered? for each observed event.\n"
       "  register-handler {:event-id KW :symbol \"ns/name\"}\n"
       "      Register a handler at runtime by fully-qualified symbol.\n"
       "  unreg-fx {:fx-id KW}\n"
       "      Drop a registered fx handler from hive.events.fx.\n"
       "  help\n"
       "      Show this text."))

(defn handle-help
  [_params]
  (ok :help help-text))
