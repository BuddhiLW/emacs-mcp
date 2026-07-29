(ns hive-mcp.test-fixtures
  "Test fixtures and doubles for hive-mcp tests.

   Contains test infrastructure that should not pollute production code.
   Pattern: Protocol test doubles in test scope only.

   For isolation fixtures (DataScript swarm, agent registry, events, …)
   see `hive-mcp.isolation-methods` and `hive-test.isolation`."
  (:require [hive-mcp.swarm.datascript.connection :as ds-conn]
            [hive-spi.embeddings.ports :as emb]))

;;; ============================================================
;;; Swarm DataScript reset helper
;;; ============================================================

(defn reset-isolated-swarm!
  "Clear the per-test DataScript conn in place.

   Only valid inside a body where `ds-conn/*test-conn*` is bound (e.g.
   under `(iso/with-isolations :swarm-ds)`). Mutates the bound atom
   to an empty db value, preserving references the test holds to it.
   Use sparingly — prefer one deftest per scenario over mid-test resets."
  []
  (when-let [tc ds-conn/*test-conn*]
    (reset! tc @(ds-conn/create-conn))))

;;; ============================================================
;;; Mock Embedding Provider (for testing)
;;; ============================================================

(defrecord MockEmbedder [dimension]
  emb/EmbeddingProvider
  (embed-text [_ text]
    (let [h (hash text)]
      (vec (for [i (range dimension)]
             (-> (bit-xor h i)
                 (mod 1000)
                 (/ 1000.0)
                 (* 2)
                 (- 1))))))
  (embed-batch [this texts]
    (mapv #(emb/embed-text this %) texts))
  (embedding-dimension [_] dimension))

(defn ->MockEmbedder
  "Create a mock embedder for testing (not for production use).
   Generates deterministic embeddings based on text hash."
  ([] (->MockEmbedder 384))
  ([dimension] (MockEmbedder. dimension)))
