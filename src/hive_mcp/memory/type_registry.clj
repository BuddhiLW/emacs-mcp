(ns hive-mcp.memory.type-registry
  "Single source of truth for memory types and their properties.

   All consumers derive from this registry — no scattered enums.
   Leaf namespace: zero hive-mcp dependencies (safe to require anywhere).

   Design principle: Knowledge-Layer-First / SST (Single Source of Truth).
   Adding a new memory type = adding one entry here. All downstream
   validation, MCP schemas, catchup, and abstraction levels derive automatically.

   Extension point: addons can register additional types via
   register-memory-type! / register-memory-types! before tool handlers run."
  (:require [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Registry (core types + extension injection point)
;; =============================================================================

(def ^:private base-registry
  "Core memory type registry. array-map preserves insertion order (= catchup priority).

   Each type has:
   - :description   Human-readable description
   - :abstraction   Default abstraction level (2-4)
   - :duration      Default duration/TTL category
   - :mcp?          Visible in MCP tool enums (default true)
   - :catchup       Catchup query config, nil = not queried as standalone category
     - :order       Priority ordering (lower = higher priority)
     - :limit       Max entries to query
     - :tags        Tag filter for query (nil = all of type)
     - :meta-trim   Character limit for metadata preview
     - :enrich?     Whether to run KG enrichment on results
     - :piggyback?  Whether to include in memory piggyback delivery
     - :meta-fn     Keyword selecting metadata conversion (:axiom, :priority, :default)"
  (array-map
   ;; === Intent level (abstraction 4) ===
   :axiom      {:description "Foundational, inviolable principles (loaded first by catchup)"
                :abstraction 4 :duration :permanent
                :catchup {:order 1 :limit 100 :meta-trim 80 :enrich? false
                          :piggyback? true :meta-fn :axiom}}

   :principle  {:description "Architectural design principles (intent-level, evolvable)"
                :abstraction 4 :duration :permanent
                :catchup {:order 2 :limit 50 :meta-trim 80 :enrich? false
                          :piggyback? true :meta-fn :default}}

   :decision   {:description "Architectural or design decisions"
                :abstraction 4 :duration :long
                :catchup {:order 4 :limit 50 :meta-trim 80 :enrich? true
                          :piggyback? false :meta-fn :default}}

   :convention {:description "Agreed-upon practices"
                :abstraction 3 :duration :long
                :catchup {:order 5 :limit 50 :meta-trim 80 :enrich? true
                          :piggyback? false :meta-fn :default}}

   :snippet    {:description "Code snippets or examples"
                :abstraction 2 :duration :medium
                :catchup {:order 6 :limit 20 :meta-trim 60 :enrich? false
                          :piggyback? false :meta-fn :default}}

   :note       {:description "General notes and observations"
                :abstraction 2 :duration :short
                :catchup nil}

   :plan       {:description "Large implementation plans (OpenRouter embeddings)"
                :abstraction 4 :duration :long
                :mcp? true
                :catchup nil}

   ;; === Ingestion pipeline (reference material, abstraction 2-3) ===
   :knowledge  {:description "Curated passage from an external source (book, paper, talk). Tag with source:<name>, chapter:<n>."
                :abstraction 3 :duration :long
                :mcp? true
                :catchup nil}

   :ingestion  {:description "Raw, unprocessed dump from the ingestion pipeline. Promotable to :knowledge / :principle / :note."
                :abstraction 2 :duration :medium
                :mcp? true
                :catchup nil}

   ;; === Extended types (Malli-valid, not first-class in MCP) ===
   :doc        {:description "Documentation" :abstraction 2 :duration :medium :mcp? false :catchup nil}
   :todo       {:description "TODO items" :abstraction 2 :duration :short :mcp? false :catchup nil}
   :question   {:description "Questions" :abstraction 2 :duration :short :mcp? false :catchup nil}
   :answer     {:description "Answers" :abstraction 2 :duration :short :mcp? false :catchup nil}
   :warning    {:description "Warnings" :abstraction 2 :duration :short :mcp? false :catchup nil}
   :error      {:description "Errors" :abstraction 2 :duration :short :mcp? false :catchup nil}
   :pattern    {:description "Reusable solution patterns" :abstraction 3 :duration :long :mcp? false :catchup nil}
   :lesson     {:description "Lessons learned" :abstraction 3 :duration :long :mcp? false :catchup nil}
   :rule       {:description "Rules" :abstraction 3 :duration :long :mcp? false :catchup nil}
   :guideline  {:description "Guidelines" :abstraction 3 :duration :long :mcp? false :catchup nil}
   :workflow   {:description "Workflow patterns" :abstraction 3 :duration :medium :mcp? false :catchup nil}
   :recipe     {:description "Recipe patterns" :abstraction 3 :duration :medium :mcp? false :catchup nil}))

;; =============================================================================
;; Type-name sanitization (security boundary)
;; =============================================================================
;;
;; Type names flow into vector-DB metadata + filter expressions, EDN config
;; persistence, and `(keyword ...)` interning. An unbounded / attacker-shaped
;; type string is a real attack surface: filter-expression injection, keyword
;; intern memory growth, EDN key pollution. We never trust a raw type — it
;; must reduce to a SAFE token before it is stored or interned.

(def ^:const max-type-length
  "Upper bound on a sanitized type token (chars). Bounds keyword interning
   and config growth; long enough for any legitimate type name."
  64)

(def ^:private safe-type-re
  "A safe type token: starts with a letter, then letters/digits/_/-.
   Lowercase-only by construction (sanitize-type lowercases first)."
  #"[a-z][a-z0-9_-]*")

(defn sanitize-type
  "Normalize a raw type (string or keyword) to its canonical token form:
   trimmed + lowercased. Returns nil when the input is not a non-blank
   string/keyword. Does NOT enforce the safe charset — see safe-type?."
  [t]
  (when (or (string? t) (keyword? t))
    (let [s (-> (if (keyword? t) (name t) t) str/trim str/lower-case)]
      (when-not (str/blank? s) s))))

(defn safe-type?
  "True when `t` reduces to a safe type token: non-blank, length <=
   max-type-length, matching ^[a-z][a-z0-9_-]*$ after sanitization. Rejects
   whitespace, quotes, EDN/reader chars, path separators, filter-expression
   operators, and oversized input. This is the security gate that replaced
   the old closed-enum membership check."
  [t]
  (boolean
   (when-let [s (sanitize-type t)]
     (and (<= (count s) max-type-length)
          (re-matches safe-type-re s)))))

;; =============================================================================
;; Extension registry (addon- + user-contributed types) + persistence
;; =============================================================================

;; Addon-/user-contributed memory types. Merged into the registry at query
;; time and persisted across restarts (see load-persisted! / persist-extensions!).
(defonce ^:private registry-extensions (atom {}))

(def default-type-def
  "Sane defaults applied to an auto-registered (user-defined) memory type.
   abstraction 2 = Semantic; not surfaced in MCP enums or catchup by default."
  {:description "User-defined extended memory type (auto-registered)."
   :abstraction 2 :duration :medium :mcp? false :catchup nil
   :auto-registered? true})

(def ^:const max-auto-types
  "Cap on distinct auto-registered types. Bounds keyword interning and
   config-file growth against a flood of distinct ad-hoc type names."
  256)

;; --- Persistence (lazy requiring-resolve keeps this ns dependency-free) ---

(def ^:private persist-key
  "Dotted config path the extension registry is persisted under."
  "memory.type-registry.extensions")

(defn- config-get-value []
  (try
    (when-let [f (requiring-resolve 'hive-mcp.config.core/get-config-value)]
      (f persist-key))
    (catch Throwable _ nil)))

(defn- config-set-value! [v]
  (try
    (when-let [f (requiring-resolve 'hive-mcp.config.core/set-config-value!)]
      (f persist-key v))
    (catch Throwable _ nil)))

(defn- persist-extensions!
  "Best-effort write of the current extension map to config. Failures are
   swallowed — persistence is a convenience, never a correctness gate."
  []
  (config-set-value! @registry-extensions))

(defonce ^:private persisted-loaded? (atom false))

(defn load-persisted!
  "Merge persisted extension types from config into registry-extensions.
   Runs at most once (CAS-guarded). Keys are re-sanitized before interning so
   a tampered config cannot smuggle an unsafe type token into the runtime
   registry. Best-effort: any failure leaves the registry as-is."
  []
  (when (compare-and-set! persisted-loaded? false true)
    (try
      (when-let [persisted (config-get-value)]
        (let [safe (into {}
                         (keep (fn [[k v]]
                                 (when (and (map? v) (safe-type? k))
                                   [(keyword (sanitize-type k)) v])))
                         persisted)]
          (when (seq safe)
            (swap! registry-extensions merge safe))))
      (catch Throwable _ nil))))

(defn register-memory-type!
  "Register an addon-/user-contributed memory type and persist it.
   Must be called before tool handlers run for addon types (addon init time);
   user types are auto-registered lazily via ensure-type!.
   type-kw: keyword (e.g. :cluster-summary)
   type-def: map with :description, :abstraction, :duration, etc."
  [type-kw type-def]
  (swap! registry-extensions assoc type-kw type-def)
  (persist-extensions!)
  type-kw)

(defn register-memory-types!
  "Register multiple addon-contributed memory types at once, then persist.
   types-map: map of {type-kw type-def}."
  [types-map]
  (swap! registry-extensions merge types-map)
  (persist-extensions!)
  (keys types-map))

(defn registry
  "Returns the full memory type registry (core + addon + persisted extensions).
   Lazily loads persisted user/addon types on first call (CAS-guarded once)."
  []
  (load-persisted!)
  (merge base-registry @registry-extensions))

;; =============================================================================
;; Derived views (recomputed dynamically to include addon extensions)
;; =============================================================================

(defn all-types
  "Set of all valid memory type keywords."
  []
  (set (keys (registry))))

(defn all-type-strings
  "Set of all valid memory type strings (for Chroma/MCP)."
  []
  (set (map name (all-types))))

(defn mcp-types
  "Ordered vector of type strings visible in MCP tool enums."
  []
  (->> (registry)
       (filter (fn [[_k v]] (get v :mcp? true)))
       (map (comp name key))
       vec))

(defn mcp-types-with-conversation
  "MCP types + 'conversation' for query compatibility."
  []
  (let [types (mcp-types)
        idx (.indexOf ^java.util.List types "plan")]
    (if (pos? idx)
      (vec (concat (subvec types 0 idx) ["conversation"] (subvec types idx)))
      (conj types "conversation"))))

(defn core-type-set
  "Set of core type keywords (mcp-visible)."
  []
  (->> (registry)
       (filter (fn [[_k v]] (get v :mcp? true)))
       (map key)
       set))

(defn type->abstraction
  "Map of type string -> abstraction level."
  []
  (into {} (map (fn [[k v]] [(name k) (:abstraction v)])) (registry)))

(defn catchup-categories
  "Ordered sequence of catchup category configs, sorted by :order.
   Each entry: {:type :keyword, :type-str \"string\", ...catchup-config}."
  []
  (->> (registry)
       (filter (fn [[_k v]] (:catchup v)))
       (map (fn [[k v]] (assoc (:catchup v) :type k :type-str (name k))))
       (sort-by :order)
       vec))

(defn piggyback-types
  "Set of type keywords included in catchup piggyback delivery."
  []
  (->> (catchup-categories)
       (filter :piggyback?)
       (map :type)
       set))

;; =============================================================================
;; Functions
;; =============================================================================

(defn known-type?
  "Strict membership: is `t` one of the registered types (core + extensions)?
   Use this where the *semantics* of a known type matter (catchup categories,
   MCP enum advertising). For accept/reject validation use valid-type?."
  [t]
  (let [s (sanitize-type t)]
    (boolean (and s (contains? (all-type-strings) s)))))

(defn valid-type?
  "Permissive validation gate: a type is *valid* when it is a SAFE token
   (sane charset + bounded length — see safe-type?), NOT when it happens to be
   pre-registered. Unknown-but-safe types are accepted everywhere and treated
   as extended types with sane defaults (see default-type-def / type-def).

   This replaced the old closed-enum membership check so the memory system is
   open to new type names while staying safe against injection / intern abuse.
   Use known-type? for the strict 'is this a registered type?' question."
  [t]
  (or (known-type? t) (safe-type? t)))

(defn type-def
  "Resolve the registry definition for a type (string or keyword). Falls back
   to default-type-def for safe-but-unknown types so every downstream consumer
   (abstraction, duration, catchup) gets a sane shape. Returns nil only for
   unsafe input."
  [t]
  (when-let [s (sanitize-type t)]
    (or (get (registry) (keyword s))
        (when (safe-type? s) default-type-def))))

(defn ensure-type!
  "Ensure a safe type token is registered, auto-registering unknown ones with
   sane defaults (and persisting them) so they become discoverable + consistent
   on subsequent sessions. No-op for already-known types. Honours max-auto-types
   to bound keyword interning. Returns the canonical type string, or nil when
   `t` is not a safe type (caller should reject)."
  [t]
  (when (safe-type? t)
    (let [s  (sanitize-type t)
          kw (keyword s)]
      (when (and (not (contains? (all-types) kw))
                 (< (count @registry-extensions) max-auto-types))
        (register-memory-type! kw (assoc default-type-def
                                         :description
                                         (str "Auto-registered memory type '" s "'."))))
      s)))

(defn abstraction-level
  "Get the abstraction level for a type (string or keyword). Default: 2."
  [t]
  (let [s (if (keyword? t) (name t) t)]
    (get (type->abstraction) s 2)))

(defn mcp-enum
  "Generate MCP JSON schema enum for tool definitions.
   include-conversation? adds 'conversation' for query tools."
  ([] (mcp-types))
  ([{:keys [include-conversation?]}]
   (if include-conversation?
     (mcp-types-with-conversation)
     (mcp-types))))

(defn mcp-type-hint
  "Description fragment for the open `type` MCP param: lists the well-known
   types as guidance and states that any safe custom token is also accepted.
   Used instead of a hard JSON-schema `:enum` so the type field stays OPEN —
   strict MCP clients won't reject novel-but-safe types."
  []
  (str "Well-known types: " (str/join ", " (mcp-types))
       ". Any safe custom type token is also accepted (letters, digits, '_' or "
       "'-', starting with a letter, max " max-type-length " chars) and is "
       "auto-registered with sane defaults."))
