(ns hive-mcp.memory.type-registry
  "Single source of truth for memory types and their properties.

   All consumers derive from this registry — no scattered enums.
   Leaf namespace: zero hive-mcp dependencies (safe to require anywhere).

   Design principle: Knowledge-Layer-First / SST (Single Source of Truth).
   Adding a new memory type = adding one entry here. All downstream
   validation, MCP schemas, catchup, and abstraction levels derive automatically.

   Extension point: addons can register additional types via
   register-memory-type! / register-memory-types! before tool handlers run.")

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

;; Addon-contributed memory types. Merged into registry at query time.
;; Register via register-memory-type! / register-memory-types! at addon init.
(defonce ^:private registry-extensions (atom {}))

(defn register-memory-type!
  "Register an addon-contributed memory type.
   Must be called before tool handlers run (addon init time).
   type-kw: keyword (e.g. :cluster-summary)
   type-def: map with :description, :abstraction, :duration, etc."
  [type-kw type-def]
  (swap! registry-extensions assoc type-kw type-def))

(defn register-memory-types!
  "Register multiple addon-contributed memory types at once.
   types-map: map of {type-kw type-def}."
  [types-map]
  (swap! registry-extensions merge types-map))

(defn registry
  "Returns the full memory type registry (core + addon extensions)."
  []
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

(defn valid-type?
  "Check if type (keyword or string) is valid."
  [t]
  (or (contains? (all-types) (if (keyword? t) t (keyword t)))
      (contains? (all-type-strings) (if (string? t) t (name t)))))

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
