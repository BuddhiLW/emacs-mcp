(ns hive-mcp.knowledge-graph.schema
  "Knowledge Graph schema for DataScript edge storage.

   Defines the schema for knowledge edges that connect memory entries,
   enabling graph traversal, impact analysis, and knowledge promotion."
  (:require [hive-mcp.memory.type-registry :as type-registry]
            [clojure.string :as str]
            [malli.core :as m]))

;; =============================================================================
;; Relation Type Registry (OCP — extensions inject their own relation types)
;; =============================================================================

(def ^:private core-relation-types
  "Core relation types shipped with hive-mcp.
   Addons may register additional types via register-relation-type!.

   :relates is the OPEN semantic relation — a `:relates` edge carries a
   free-text `:kg-edge/predicate` (e.g. \"causes\", \"motivates\", \"part-of\")
   so agents can express arbitrary semantics without expanding the closed
   structural vocabulary. Structural algos treat it as low-weight (see
   DefaultEdgeWeights default 0.3); the other relations remain the
   first-class structural lane."
  #{:implements :supersedes :refines :contradicts
    :depends-on :derived-from :applies-to :relates})

;; Registry atom for addon-contributed relation types.
;; Merged into relation-types at call time.
(defonce ^:private relation-type-extensions (atom #{}))

(defn register-relation-type!
  "Register an additional relation type from an addon.
   Must be called before any validation that needs the type."
  [rel]
  (swap! relation-type-extensions conj rel))

(defn register-relation-types!
  "Register multiple additional relation types from an addon."
  [rels]
  (swap! relation-type-extensions into rels))

(defn relation-types
  "Valid relation types for knowledge graph edges.
   Returns core types merged with any addon-registered extensions.

   Core types:
   - :implements   - Realizes a principle/pattern
   - :supersedes   - Replaces previous knowledge
   - :refines      - Improves without replacing
   - :contradicts  - Conflicts with
   - :depends-on   - Requires for correctness
   - :derived-from - Synthesized from sources
   - :applies-to   - Scope applicability"
  []
  (into core-relation-types @relation-type-extensions))

(def kg-schema
  "DataScript schema for Knowledge Graph edges.

   Bounded context pattern: separate from Chroma memory storage.
   Edges connect memory entry IDs without duplicating content."
  {:kg-edge/id            {:db/unique :db.unique/identity
                           :db/noHistory true
                           :db/doc "Unique edge identifier (UUID string)"}
   :kg-edge/from          {:db/noHistory true
                           :db/doc "Source node ID (memory entry ID)"}
   :kg-edge/to            {:db/noHistory true
                           :db/doc "Target node ID (memory entry ID)"}
   :kg-edge/relation      {:db/noHistory true
                           :db/doc "Relation type keyword from relation-types"}
   :kg-edge/scope         {:db/noHistory true
                           :db/doc "Scope where edge was discovered (e.g., project-id)"}
   :kg-edge/confidence    {:db/noHistory true
                           :db/doc "Confidence score 0.0-1.0"}
   :kg-edge/created-by    {:db/noHistory true
                           :db/doc "Agent ID that created this edge"}
   :kg-edge/created-at    {:db/noHistory true
                           :db/doc "Creation timestamp (inst)"}
   :kg-edge/last-verified {:db/noHistory true
                           :db/doc "Timestamp of last verification that this edge is still valid (inst)"}
   :kg-edge/source-type   {:db/noHistory true
                           :db/doc "How this edge was established: :manual, :automated, :inferred, :co-access"}
   :kg-edge/schema-text    {:db/noHistory true
                            :db/doc "Attached-schema satellite payload: full source text of the (m/=> ..)/(s/fdef ..) form (a :has-schema edge)"}
   :kg-edge/schema-head    {:db/noHistory true
                            :db/doc "Attached-schema satellite head name: \"=>\" (malli) or \"fdef\" (spec)"}
   :kg-edge/schema-subject {:db/noHistory true
                            :db/doc "Attached-schema satellite subject qn (the fn the schema constrains); mirrors the :from node's qn for direct datalog"}})

;; =============================================================================
;; =============================================================================
;;
;; Abstraction Levels:
;;   L0: Parabola (Runtime) - Not stored, inferred from live system
;;
;; Knowledge degrades as it rises through abstraction levels. These fields
;; track the abstraction level and grounding status of knowledge entries.

(def abstraction-levels
  "Valid abstraction levels for knowledge entries.
   L0 (runtime) is not stored - it's inferred from live system state."
  {:level-1 {:level 1 :name "Disc"     :description "Files, kondo analysis, git state"}
   :level-2 {:level 2 :name "Semantic" :description "What functions DO"}
   :level-3 {:level 3 :name "Pattern"  :description "Conventions, idioms"}
   :level-4 {:level 4 :name "Intent"   :description "ADRs, decisions, axioms"}})

(def knowledge-schema
  "DataScript schema for knowledge abstraction tracking.

   Tracks the abstraction level and grounding status of knowledge entries,
   enabling drift detection and re-grounding workflows."
  {:knowledge/abstraction-level {:db/doc "Abstraction level 1-4 (1=File, 2=Semantic, 3=Pattern, 4=Intent)"}
   :knowledge/grounded-at       {:db/doc "Timestamp of last verification against lower level (inst)"}
   :knowledge/grounded-from     {:db/doc "Ref to disc entity (file/commit) verified against"}
   :knowledge/gaps              {:db/cardinality :db.cardinality/many
                                 :db/doc "Set of known abstraction gaps (keywords)"}
   :knowledge/source-hash       {:db/doc "Content hash of source when abstracted (for drift detection)"}
   :knowledge/source-type       {:db/doc "How this knowledge was created: :manual, :automated, :inferred, :co-access"}})

(def source-types
  "Valid source types for edge and knowledge provenance tracking.

   - :manual     - Explicitly created by a human or agent
   - :automated  - Created by automated analysis (kondo, git, etc.)
   - :inferred   - Derived from pattern detection or heuristics
   - :co-access  - Created from co-access pattern (batch recall)"
  #{:manual :automated :inferred :co-access})

(defn valid-relation?
  "Check if a relation type is valid."
  [relation]
  (contains? (relation-types) relation))

(defn normalize-predicate
  "Normalize a free-text `:relates` predicate to a stable kebab-case token so
   semantically-equal predicates converge instead of fragmenting the graph.
   Lowercases, trims, and collapses runs of whitespace/underscores/hyphens to a
   single hyphen. Returns nil for nil/blank/non-string input.

   v1 is purely syntactic; semantic canonicalization (synonym / embedding
   clustering of predicates) is a deferred v2."
  [predicate]
  (when (string? predicate)
    (let [norm (-> predicate
                   str/trim
                   str/lower-case
                   (str/replace #"[\s_]+" "-")
                   (str/replace #"-{2,}" "-")
                   (str/replace #"^-+|-+$" ""))]
      (when (seq norm) norm))))

(defn valid-confidence?
  "Check if confidence score is in valid range [0.0, 1.0]."
  [confidence]
  (and (number? confidence)
       (<= 0.0 confidence 1.0)))

(defn valid-source-type?
  "Check if source type is valid."
  [source-type]
  (contains? source-types source-type))

(def NodeId
  "The canonical KG node id: a non-blank string that is not an unsubstituted
   placeholder.

   A leading `$` means a batch/DSL reference ($0, $ref:$0.id, $mem) reached the
   write path unresolved. Persisting one mints a phantom node that accumulates
   edges and skews every structural algorithm that reads degree — PPR/recall,
   PageRank/impact, community, bridges, link-predict — so it is not a node id."
  [:and
   :string
   [:re #"\S"]
   [:not [:re #"^\$"]]])

(defn valid-node-id?
  "Check if a value is usable as a KG node id — see NodeId."
  [node-id]
  (m/validate NodeId node-id))

(m/=> valid-node-id? [:=> [:cat :any] :boolean])

(defn valid-abstraction-level?
  "Check if abstraction level is valid (1-4).
   L0 (runtime) is not stored, so 0 is not valid for persistence."
  [level]
  (and (integer? level)
       (<= 1 level 4)))

(defn abstraction-level-keyword
  "Convert integer level to keyword (:level-1, :level-2, :level-3, :level-4)."
  [level]
  (when (valid-abstraction-level? level)
    (keyword (str "L" level))))

(defn abstraction-level-info
  "Get full info for an abstraction level.
   Returns {:level n :name \"Name\" :description \"...\"} or nil."
  [level]
  (when (valid-abstraction-level? level)
    (get abstraction-levels (keyword (str "level-" level)))))

;; =============================================================================
;; Disc Entity Schema (File State Tracking)
;; =============================================================================
;;
;; Disc entities track the actual state of files on disk, enabling grounding
;; verification without re-reading files. When a memory entry is grounded,
;; it references a disc entity as proof of verification.

;; Volatility classes for Bayesian certainty decay
(def volatility-classes
  "Valid volatility classes for disc certainty tracking.
   Affects how quickly certainty decays over time.

   - :stable    - Rarely changes (config, deps, infrastructure)
   - :moderate  - Changes occasionally (business logic, handlers)
   - :volatile  - Changes frequently (tests, UI, hot paths)"
  #{:stable :moderate :volatile})

(def disc-schema
  "DataScript schema for disc (file) state tracking.

   Disc entities represent the file abstraction level - actual files on disk.
   Used as grounding targets for higher-level knowledge entries.

   Bayesian Certainty Fields:
   - certainty-alpha/beta form a Beta distribution for probabilistic staleness
   - Mean certainty = alpha / (alpha + beta)
   - Higher alpha = more confident the knowledge is fresh
   - Higher beta = more observations of staleness
   - volatility-class affects decay rate between observations"
  {:disc/path              {:db/unique :db.unique/identity
                            :db/doc "File path (unique identity for the disc entity)"}
   :disc/content-hash      {:db/doc "SHA256 hash of file content"}
   :disc/analyzed-at       {:db/doc "Timestamp of last kondo/analysis (inst)"}
   :disc/git-commit        {:db/doc "Git commit hash when analyzed"}
   :disc/project-id        {:db/doc "Project scope (for multi-project support)"}
   :disc/last-read-at      {:db/doc "Timestamp of last file read by any agent (inst)"}
   :disc/read-count        {:db/doc "Number of times this file has been read by agents"}
   ;; Bayesian certainty fields
   :disc/certainty-alpha   {:db/doc "Beta distribution alpha parameter (float, default 5.0)"}
   :disc/certainty-beta    {:db/doc "Beta distribution beta parameter (float, default 2.0)"}
   :disc/volatility-class  {:db/doc "Volatility class: :stable, :moderate, or :volatile"}
   :disc/last-observation  {:db/doc "Timestamp when certainty was last updated (inst)"}})

(defn valid-volatility-class?
  "Check if volatility class is valid."
  [volatility-class]
  (contains? volatility-classes volatility-class))

(defn valid-certainty-alpha?
  "Check if certainty alpha is valid (positive number)."
  [alpha]
  (and (number? alpha) (pos? alpha)))

(defn valid-certainty-beta?
  "Check if certainty beta is valid (positive number)."
  [beta]
  (and (number? beta) (pos? beta)))

;; =============================================================================
;; Malli Specs for Disc Certainty Fields
;; =============================================================================

(def DiscCertaintyAlpha
  "Malli spec for Beta distribution alpha parameter."
  [:and :double [:> 0]])

(def DiscCertaintyBeta
  "Malli spec for Beta distribution beta parameter."
  [:and :double [:> 0]])

(def DiscVolatilityClass
  "Malli spec for volatility class enum."
  [:enum :stable :moderate :volatile])

(def DiscLastObservation
  "Malli spec for last observation timestamp."
  inst?)

(def DiscCertaintyFields
  "Malli spec for the complete set of Bayesian certainty fields."
  [:map
   [:disc/certainty-alpha {:optional true} DiscCertaintyAlpha]
   [:disc/certainty-beta {:optional true} DiscCertaintyBeta]
   [:disc/volatility-class {:optional true} DiscVolatilityClass]
   [:disc/last-observation {:optional true} DiscLastObservation]])

;; =============================================================================
;; Default Values for New Disc Entities
;; =============================================================================

(def disc-certainty-defaults
  "Default values for Bayesian certainty fields on new disc entities.

   Alpha=5, Beta=2 gives:
   - Mean certainty: 5/(5+2) = 0.714 (moderately confident)
   - Variance: relatively low, reflecting prior belief
   - Represents 'reasonably fresh but not certain' starting state"
  {:disc/certainty-alpha  5.0
   :disc/certainty-beta   2.0
   :disc/volatility-class :moderate})

;; =============================================================================
;; Synth Schema — derived KG metrics produced by graph-algos synth loops
;; =============================================================================
;; Written by hive-knowledge.graph-algos via IKgWriter (DatahikeKgWriter).
;; Read by ranking/fuse, catchup filter, mcp/* surfaces, carto detectors.
;; All values are derived (recomputable) — losing them is recoverable.

(def synth-schema
  "DataScript schema for synth-loop-derived metrics on KG nodes.

   Identity:
   - :synth/node-id  unique identity anchor. Value = KG node id (same string
     as :kg-edge/from / :kg-edge/to). Datahike upserts by identity, so writers
     transact `[{:synth/node-id id, :synth/<attr> value}]` idempotently.

   Population:
   - :synth/community-id  ← Louvain/Leiden (graph_algos.synth.loop_communities)
   - :synth/betweenness   ← Brandes (loop_betweenness, GAV2-1.2)
   - :synth/k-core        ← KCoreDecomposition (loop_kcore, GAV2-1.4)
   - :synth/hits-hub      ← HITS hub score (loop_hits, GAV2-1.5)
   - :synth/hits-auth     ← HITS authority score (loop_hits)
   - :synth/conductance   ← per-cluster (GAV2-2.4)
   - :synth/modularity-q  ← per-community (GAV2-2.4)
   - :synth/katz          ← IKatzCentralityBackend (GAV2-2.1)
   - :synth/eigenvector   ← IEigenvectorCentralityBackend (GAV2-2.2)
   - :synth/triangle-count    ← jgrapht triangle counting (GAV2-1.7)
   - :synth/clustering-coef   ← local clustering coefficient (GAV2-1.7)

   Cardinality :db.cardinality/one for scalar metrics. :synth/community-id is
   one (each node belongs to a single community per detection run); switch to
   :db.cardinality/many later if multi-cluster membership is wanted."
  {:synth/node-id         {:db/unique :db.unique/identity
                           :db/noHistory true
                           :db/doc "Identity anchor for synth-derived attrs (KG node id)"}
   :synth/community-id    {:db/index true
                           :db/noHistory true
                           :db/doc "Community label assigned by latest ICommunities run"}
   :synth/betweenness     {:db/noHistory true
                           :db/doc "Brandes betweenness centrality score (double)"}
   :synth/k-core          {:db/noHistory true
                           :db/doc "k-core decomposition coreness (long)"}
   :synth/hits-hub        {:db/noHistory true
                           :db/doc "HITS hub score (double)"}
   :synth/hits-auth       {:db/noHistory true
                           :db/doc "HITS authority score (double)"}
   :synth/conductance     {:db/noHistory true
                           :db/doc "Per-cluster conductance edges-out/(in+out) (double)"}
   :synth/modularity-q    {:db/noHistory true
                           :db/doc "Per-community modularity Q contribution (double)"}
   :synth/katz            {:db/noHistory true
                           :db/doc "Katz centrality (double)"}
   :synth/eigenvector     {:db/noHistory true
                           :db/doc "Eigenvector centrality (double)"}
   :synth/triangle-count  {:db/noHistory true
                           :db/doc "Triangles incident at node (long)"}
   :synth/clustering-coef {:db/noHistory true
                           :db/doc "Local clustering coefficient (double)"}})

(defn synth-attr?
  "True iff attr is in the :synth/* namespace (excludes :synth/node-id).
   Used by DatahikeKgWriter destructive-guard."
  [attr]
  (and (keyword? attr)
       (= "synth" (namespace attr))
       (not= :synth/node-id attr)))

(defn disc-certainty-defaults-with-timestamp
  "Returns disc certainty defaults with current timestamp for last-observation."
  []
  (assoc disc-certainty-defaults
         :disc/last-observation (java.util.Date.)))

(defn apply-disc-certainty-defaults
  "Apply default certainty values to a disc entity map.
   Only sets values for keys not already present."
  [disc-entity]
  (merge (disc-certainty-defaults-with-timestamp) disc-entity))

;; =============================================================================
;; KG Schema Extension Registry (IAddon injection point)
;; =============================================================================
;;
;; Addons register additional DataScript schema attributes here BEFORE the
;; KG store is first accessed (ensure-conn! calls full-schema). Addon
;; initialization happens at startup before tool handlers run, so timing
;; is safe. DataScript schema is immutable after d/create-conn.

;; Registry atom for addon-contributed KG schema attributes.
;; Merged into full-schema at connection creation time.
(defonce ^:private kg-schema-extensions (atom {}))

(defn register-kg-schema!
  "Register additional DataScript schema attributes from an addon.
   Must be called before the KG store is first accessed.
   schema-map: a DataScript schema map, e.g. {:my/attr {:db/doc \"...\"}}"
  [schema-map]
  (swap! kg-schema-extensions merge schema-map))

(defn get-kg-schema-extensions
  "Returns the current KG schema extensions registered by addons."
  []
  @kg-schema-extensions)

;; =============================================================================
;; Abstraction Level Helpers
;; =============================================================================

(defn type->abstraction-level
  "Maps memory entry types to their default abstraction levels.
   Derived from type-registry (SST)."
  []
  (type-registry/type->abstraction))

(defn derive-abstraction-level
  "Derive the default abstraction level for a memory entry type.
   Returns integer 2-4, defaulting to 2 (Semantic) for unknown types."
  [entry-type]
  (type-registry/abstraction-level entry-type))

(defn full-schema
  "Returns the combined KG schema (edges + knowledge abstraction + disc + synth + addon extensions)."
  []
  (merge kg-schema knowledge-schema disc-schema synth-schema @kg-schema-extensions))