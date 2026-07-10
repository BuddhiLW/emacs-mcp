(ns hive-mcp.channel.instruction-store
  "Cross-process instruction queue for hivemind → ling piggyback delivery.

   Problem solved
   ==============
   The original instruction queue in `channel/piggyback.clj` was a process-local
   atom. In a distributed layout — coordinator on one host, ling spawned in a
   sidecar process on another — instructions pushed by the coordinator never
   reached the ling because the two JVMs don't share the atom.

   Design (SOLID / DDD / FP)
   =========================
   - DIP: depends on `protocols.event-backbone/IEventBackbone`, never on NATS
     directly. A local `IInstructionStore` protocol defines the domain surface;
     concrete records (`LocalInstructionStore`, `NatsBackedInstructionStore`)
     implement it. The facade in `channel/piggyback.clj` talks only to this ns.
   - SRP: this ns owns exactly one concept — the cross-process instruction
     queue. It does not know about cursors, backbone buffers, or shouts.
   - OCP: adding a new transport means adding a new record. The facade and
     its call sites stay unchanged.
   - FP: dedup, envelope, and subject derivation are pure helpers. Side
     effects (atom swaps, publishes, subscribes) are confined to record
     methods.

   Delivery semantics
   ==================
   - Each pushed instruction is wrapped in an envelope
     `{:instr/id <uuid>  :instr/payload <original-instruction>}`. The UUID
     is assigned once at push time.
   - `push-instruction!` atomically appends the envelope to the local atom
     AND (when connected) publishes it on `hive.v1.instruction.{agent-id}`.
   - A single wildcard subscription on `hive.v1.instruction.>` receives
     remote envelopes and swaps them into the same local atom. This is the
     mechanism by which a ling waking up in another process sees remote
     instructions — from its perspective, they look identical to local
     pushes because both land in the atom before drain.
   - `drain-instructions!` reads+clears the per-agent vector in the atom
     and returns the unwrapped payloads, preserving insertion order.
   - A bounded LRU `seen-ids` set dedups by `:instr/id`, so the local
     push path and the subscription callback can never double-deliver
     (and so two remote observers ignoring their own broadcast loop back
     is safe). The set is capped at `max-seen-ids` to bound memory.

   Fallback
   ========
   When `(eb/connected? backbone)` is false, push/drain degrade to pure atom
   operations — identical to the pre-NATS behavior. There is no connection
   retry loop here; if the backbone comes online later, an explicit
   `rewire!` call (from server/init) rebuilds the subscription."

  (:require [hive-mcp.protocols.event-backbone :as eb]
            [taoensso.timbre :as log]
            [hive-mcp.protocols.registry :as reg])
  (:import [java.util UUID]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Subject convention
;; =============================================================================

(def ^:private instruction-prefix "hive.v1.instruction")

(defn instruction-subject
  "Pure: build the NATS subject for an agent's instruction stream.
   E.g. (instruction-subject \"ling-1\") => \"hive.v1.instruction.ling-1\""
  [agent-id]
  (str instruction-prefix "." (or agent-id "unknown")))

(def instruction-wildcard
  "Wildcard subject covering every agent's instruction stream."
  (str instruction-prefix ".>"))

(defn agent-id-from-subject
  "Pure: extract the agent-id suffix from an instruction subject, or nil
   if the subject does not match the expected shape."
  [subject]
  (when (and (string? subject)
             (.startsWith ^String subject (str instruction-prefix ".")))
    (subs subject (inc (count instruction-prefix)))))

;; =============================================================================
;; Envelope helpers (pure)
;; =============================================================================

(defn new-instruction-id
  "Generate a fresh instruction id. Exposed for injection/testing."
  []
  (str (UUID/randomUUID)))

(defn wrap-envelope
  "Pure: wrap a raw instruction into a transport envelope with id + agent-id.
   If the caller already supplied `:instr/id` (rare, mostly tests), reuse it."
  [agent-id instruction]
  {:instr/id      (or (:instr/id instruction) (new-instruction-id))
   :instr/agent   agent-id
   :instr/payload (dissoc instruction :instr/id)})

(defn unwrap-envelope
  "Pure: return the raw instruction payload from an envelope.
   Tolerant: if given a non-envelope (legacy shape), returns it as-is."
  [envelope]
  (if (and (map? envelope) (contains? envelope :instr/payload))
    (:instr/payload envelope)
    envelope))

(defn envelope-id
  "Pure: return `:instr/id` of an envelope, or nil for legacy shapes."
  [envelope]
  (when (map? envelope) (:instr/id envelope)))

;; =============================================================================
;; Seen-id dedup (bounded LRU-ish set)
;; =============================================================================

(def ^:private max-seen-ids
  "Upper bound on the dedup set. When exceeded we drop half the oldest entries.
   This protects against unbounded growth in long-lived coordinators."
  4096)

(defn- prune-seen
  "Pure: if `seen` has grown past the cap, drop roughly the oldest half.
   `seen` is an ordered set (clojure.lang.PersistentTreeSet) keyed by
   insertion counter — but for simplicity we use a plain vector + set pair."
  [{:keys [order set*] :as s}]
  (if (> (count order) max-seen-ids)
    (let [keep-n   (quot max-seen-ids 2)
          dropped  (subvec order 0 (- (count order) keep-n))
          kept     (subvec order (- (count order) keep-n))
          drop-set (into #{} dropped)]
      {:order kept
       :set*  (into #{} (remove drop-set) set*)})
    s))

(defn- seen?
  "Pure: has this id been seen before?"
  [seen id]
  (contains? (:set* seen) id))

(defn- mark-seen
  "Pure: record an id as seen. No-op if already present. Prunes if oversized."
  [seen id]
  (if (or (nil? id) (seen? seen id))
    seen
    (prune-seen {:order (conj (:order seen) id)
                 :set*  (conj (:set* seen) id)})))

(def ^:private empty-seen {:order [] :set* #{}})

;; =============================================================================
;; Pure queue helpers
;; =============================================================================

(defn append-envelope
  "Pure: append an envelope to the queue map for `agent-id`."
  [queues agent-id envelope]
  (update queues agent-id (fnil conj []) envelope))

(defn take-queue
  "Pure: return [envelopes new-queues-map] after removing `agent-id`."
  [queues agent-id]
  [(get queues agent-id []) (dissoc queues agent-id)])

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol IInstructionStore
  "Domain protocol for the cross-process instruction queue.

   Backwards compatible with the pre-NATS single-atom design: both `push!`
   and `drain!` may be called in single-process mode with no backbone; the
   `LocalInstructionStore` implementation services that case without any
   network I/O."

  (push! [this agent-id instruction]
    "Enqueue `instruction` for `agent-id`. Returns the envelope id (string).")

  (drain! [this agent-id]
    "Return and remove all pending instruction payloads (raw, unwrapped) for
     `agent-id`, preserving insertion order.")

  (peek* [this agent-id]
    "Return pending instruction payloads for `agent-id` without removing them.")

  (clear! [this]
    "Drop every queued instruction for every agent. Diagnostic / guarded.")

  (start! [this]
    "Start any background subscriptions this store needs. Idempotent.")

  (stop! [this]
    "Tear down background subscriptions. Idempotent."))

;; =============================================================================
;; LocalInstructionStore — atom-only, no backbone
;; =============================================================================

(defrecord LocalInstructionStore [queues-atom]
  IInstructionStore
  (push! [_ agent-id instruction]
    (let [env (wrap-envelope agent-id instruction)]
      (swap! queues-atom append-envelope agent-id env)
      (:instr/id env)))

  (drain! [_ agent-id]
    (let [[envs _] (take-queue @queues-atom agent-id)]
      (swap! queues-atom (fn [q] (second (take-queue q agent-id))))
      (mapv unwrap-envelope envs)))

  (peek* [_ agent-id]
    (mapv unwrap-envelope (get @queues-atom agent-id [])))

  (clear! [_]
    (reset! queues-atom {}))

  (start! [_] nil)
  (stop!  [_] nil))

(defn local-store
  "Create a LocalInstructionStore sharing an externally-held queues atom.
   Sharing the atom lets the facade expose the same `defonce` to callers
   that historically reached in."
  [queues-atom]
  (->LocalInstructionStore queues-atom))

;; =============================================================================
;; NatsBackedInstructionStore — atom fast-path + wildcard subscription
;; =============================================================================

(defn- on-remote-envelope
  "Receive an envelope on the wildcard subscription.
   Idempotent via the seen-ids set: if we've already observed this envelope
   (e.g. because we published it ourselves and NATS echoed it back), skip.
   Otherwise append to the local queue."
  [{:keys [queues-atom seen-atom]} payload]
  (try
    (let [id       (envelope-id payload)
          agent-id (:instr/agent payload)]
      (cond
        (nil? id)
        (log/debug "[instruction-store] dropping envelope without id")

        (nil? agent-id)
        (log/debug "[instruction-store] dropping envelope without agent-id" id)

        (seen? @seen-atom id)
        (log/debug "[instruction-store] dedup: ignoring" id "for" agent-id)

        :else
        (do
          (swap! seen-atom mark-seen id)
          (swap! queues-atom append-envelope agent-id payload)
          (log/debug "[instruction-store] accepted remote envelope"
                     id "for" agent-id))))
    (catch Exception e
      (log/warn "[instruction-store] on-remote-envelope failed:"
                (.getMessage e)))))

(defrecord NatsBackedInstructionStore [queues-atom seen-atom backbone running?]
  IInstructionStore
  (push! [this agent-id instruction]
    (let [env (wrap-envelope agent-id instruction)
          id  (:instr/id env)]
      ;; Mark as seen BEFORE publish so the echo from our own subscription
      ;; is ignored even if it arrives on a different thread.
      (swap! seen-atom mark-seen id)
      (swap! queues-atom append-envelope agent-id env)
      (when (and backbone (eb/connected? backbone))
        (try
          (eb/publish! backbone (instruction-subject agent-id) env)
          (catch Exception e
            (log/warn "[instruction-store] publish failed for" agent-id
                      ":" (.getMessage e)))))
      id))

  (drain! [_ agent-id]
    (let [[envs _] (take-queue @queues-atom agent-id)]
      (swap! queues-atom (fn [q] (second (take-queue q agent-id))))
      (mapv unwrap-envelope envs)))

  (peek* [_ agent-id]
    (mapv unwrap-envelope (get @queues-atom agent-id [])))

  (clear! [_]
    (reset! queues-atom {})
    (reset! seen-atom empty-seen))

  (start! [this]
    (when (and backbone
               (eb/connected? backbone)
               (not @running?))
      (try
        (eb/subscribe! backbone
                       instruction-wildcard
                       (partial on-remote-envelope
                                {:queues-atom queues-atom
                                 :seen-atom   seen-atom}))
        (reset! running? true)
        (log/info "[instruction-store] subscribed to" instruction-wildcard
                  "on" (eb/backbone-id backbone))
        (catch Exception e
          (log/warn "[instruction-store] subscribe failed:" (.getMessage e))))))

  (stop! [_]
    (when @running?
      (try
        (eb/unsubscribe! backbone instruction-wildcard)
        (catch Exception e
          (log/warn "[instruction-store] unsubscribe failed:" (.getMessage e))))
      (reset! running? false)
      (log/info "[instruction-store] unsubscribed from" instruction-wildcard))))

(defn nats-backed-store
  "Create a NatsBackedInstructionStore. The store still works if `backbone`
   is nil or disconnected — in that case it degrades to the local-only path.
   Call `start!` to attach the wildcard subscription once the backbone is up."
  [queues-atom backbone]
  (->NatsBackedInstructionStore queues-atom
                                (atom empty-seen)
                                backbone
                                (atom false)))

;; =============================================================================
;; Active store management (defonce — persists across reloads)
;; =============================================================================

(defonce ^:private slot
  (reg/single-slot {:validate #(satisfies? IInstructionStore %)
                    :teardown stop!}))

(defn set-store!
  "Install `store` as the active instruction store. Replaces any previous one
   (stopping it first). Returns the installed store."
  [store]
  {:pre [(satisfies? IInstructionStore store)]}
  (when-let [prev (reg/current slot)]
    (try (stop! prev) (catch Exception _)))
  (reg/install! slot store))

(defn get-store
  "Return the active instruction store, or nil if none has been set.
   The facade uses this with a lazy default."
  []
  (reg/current slot))

(defn clear-store!
  "Remove the active store (stopping it first). Primarily for tests."
  []
  (reg/clear! slot))

;; =============================================================================
;; Bootstrap helper — invoked from server/init once the backbone is ready
;; =============================================================================

(defn rewire!
  "Replace the active store with a NATS-backed one bound to `backbone`,
   reusing the existing queues atom so in-flight instructions survive.
   Safe to call multiple times. Returns the new store."
  [queues-atom backbone]
  (let [store (nats-backed-store queues-atom backbone)]
    (set-store! store)
    (start! store)
    store))
