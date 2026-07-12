(ns hive-mcp.embeddings.model-spec
  "What an embedding model needs in order to be loaded correctly: the width of
   the vectors it emits, the context window to load it with, and the VRAM to
   budget for it.

   Strata (each calls only downward):

     composition  catalog / layered      wiring: config over defaults
     port         IModelCatalog          who may answer 'what is this model?'
     value        ModelSpec              a resolved answer
     floor        floor-spec             what we assume when nobody says

   Nothing here performs I/O or reads config. A caller hands in a catalog; the
   catalog decides. Declared values always win over the built-in table."
  (:require [malli.core :as m]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Value Objects (malli)
;; =============================================================================

(def Dimension [:int {:min 1 :max 16384}])
(def NumCtx    [:int {:min 256 :max 131072}])
(def VramMb    [:int {:min 1 :max 100000}])

(def PartialSpec
  "What a catalog may know about a model. Every key optional, and a nil means
   'not declared' — it must not erase the layer beneath it."
  [:map
   [:dimension {:optional true} [:maybe Dimension]]
   [:num-ctx   {:optional true} [:maybe NumCtx]]
   [:vram-mb   {:optional true} [:maybe VramMb]]])

(def Spec
  "A resolved spec. :dimension may still be absent: an unknown, undeclared model
   has no vector width we are entitled to invent — the caller must refuse it."
  [:map
   [:num-ctx   NumCtx]
   [:vram-mb   VramMb]
   [:dimension {:optional true} [:maybe Dimension]]])

(def Layers
  "Partial specs, floor first. Later layers win."
  [:sequential [:maybe PartialSpec]])

;; =============================================================================
;; Floor — what we assume when nobody says
;; =============================================================================

(def default-num-ctx
  "Context window for a model nobody declared one for. Ollama sizes the KV cache
   from it at load time, so guessing high is paid for in VRAM."
  8192)

(def default-vram-mb 1000)

(def floor-spec {:num-ctx default-num-ctx :vram-mb default-vram-mb})

(def built-in
  "Specs for the models we ship knowledge of. A floor, never a policy —
   any layer above overrides it."
  {"nomic-embed-text"       {:dimension 768  :num-ctx 2048  :vram-mb 700}
   "mxbai-embed-large"      {:dimension 1024 :num-ctx 512   :vram-mb 1500}
   "all-minilm"             {:dimension 384  :num-ctx 256   :vram-mb 400}
   "snowflake-arctic-embed" {:dimension 1024 :num-ctx 512   :vram-mb 1500}
   "qwen3-embedding:0.6b"   {:dimension 1024 :num-ctx 8192  :vram-mb 1200}
   "qwen3-embedding:4b"     {:dimension 2560 :num-ctx 8192  :vram-mb 4000}
   "qwen3-embedding:8b"     {:dimension 4096 :num-ctx 8192  :vram-mb 7300}})

;; =============================================================================
;; The one calculation — plain data in, plain data out
;; =============================================================================

(defn- declared
  "Keys that actually carry a value. nil means 'not declared'."
  [partial-spec]
  (into {} (remove (comp nil? val) partial-spec)))

(defn merge-layers
  "Collapse layers, later winning key by key. The floor is a layer like any
   other — pass it first."
  [layers]
  (reduce (fn [acc layer] (merge acc (declared layer))) {} layers))

(m/=> merge-layers [:=> [:cat Layers] [:map]])

;; =============================================================================
;; Value Object
;; =============================================================================

(defrecord ModelSpec [dimension num-ctx vram-mb])

(defn ->spec
  "Build a ModelSpec by collapsing `layers` onto the floor."
  [layers]
  (map->ModelSpec (merge-layers (cons floor-spec layers))))

;; =============================================================================
;; Port (DIP) — who may answer 'what is this model?'
;; =============================================================================

(defprotocol IModelCatalog
  (-lookup [this model]
    "Partial spec for `model`, or nil when this catalog knows nothing of it."))

(defrecord TableCatalog [table]
  IModelCatalog
  (-lookup [_ model] (get table (str model))))

(defrecord LayeredCatalog [catalogs]
  IModelCatalog
  (-lookup [_ model]
    (merge-layers (map #(-lookup % model) catalogs))))

(defrecord EmptyCatalog []
  IModelCatalog
  (-lookup [_ _] nil))

;; A deftype, not a defrecord: this holds a mutable cell, so it is emphatically
;; NOT a value and must not pretend to be one by supporting map equality.
(deftype CachingCatalog [inner ^:volatile-mutable cache]
  IModelCatalog
  (-lookup [_ model]
    (let [k (str model)]
      (if (contains? cache k)
        (get cache k)
        (let [v (-lookup inner model)]
          (set! cache (assoc cache k v))
          v)))))

;; =============================================================================
;; Composition
;; =============================================================================

(defn table-catalog
  "A catalog backed by a model -> partial-spec map."
  [table]
  (->TableCatalog (or table {})))

(defn built-in-catalog
  "The models we ship knowledge of."
  []
  (table-catalog built-in))

(defn empty-catalog
  "Knows nothing. The no-op default."
  []
  (->EmptyCatalog))

(defn layered
  "Compose catalogs; later ones override earlier ones, key by key."
  [& catalogs]
  (->LayeredCatalog (vec (remove nil? catalogs))))

(defn caching
  "Memoize a catalog's lookups."
  [inner]
  (->CachingCatalog inner {}))

(defn default-catalog
  "The built-in table, with `declared` (config) layered over it."
  [declared-table]
  (layered (built-in-catalog) (table-catalog declared-table)))

(defn spec-for
  "The resolved ModelSpec for `model` according to `catalog`."
  [catalog model]
  (->spec [(-lookup catalog model)]))

(defn known-model?
  "Does `catalog` know a vector width for `model`?"
  [catalog model]
  (some? (:dimension (spec-for catalog model))))
