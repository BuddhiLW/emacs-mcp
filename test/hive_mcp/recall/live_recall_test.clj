(ns ^:integration hive-mcp.recall.live-recall-test
  "GOLDEN RECALL — THE LIVE TIER. Requires Milvus + a reachable embedder.

   Excluded from `:test-unit` by the `^:integration` metadata; run with
   `clojure -M:test-integration`.

   WHY A SECOND TIER EXISTS
   -----------------------
   Convention 20260709234832-4a5fa9c1: the golden in-memory store is NOT a
   substitute for a real trifecta. `hive-mcp.recall.lexical-anchor-test` proves
   the PIPELINE is correct given a store that keeps its contract. It cannot
   prove the store keeps it. Every single defect of 2026-07-12 lived exactly
   there — at the wire:

     • the Milvus boundary handed a COSINE SIMILARITY through as a :distance
     • the query encoder's width did not match the collection's
     • Qdrant's scored points escaped undecoded

   None of those is reachable from a fixture. They are only reachable from the
   wire, and the wire is what this tier reads.

   HONESTY CONTRACT
   ----------------
   A backend that is ABSENT is a FAILURE here, never a skip. You opted into the
   integration tier; if the store is not there, that is the finding. A test that
   silently passes when its subject is missing is precisely the pathology this
   whole suite exists to eliminate — it is the same lie as an empty result set
   from a populated store, told in the test harness instead of the product."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [hive-mcp.embeddings.service :as embed-svc]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.recall.golden :as g]
            [hive-mcp.server.init :as init]
            [hive-mcp.tools.memory.search :as search]
            [hive-mcp.recall.canary :as canary]
            [hive-mcp.recall.canary.live :as canary-live]))

;; =============================================================================
;; Bootstrap — the real config, the real providers. Read-only.
;; =============================================================================

(defn- live-embeddings!
  "Bring up the embedding service exactly as the server does: from
   ~/.config/hive-mcp/config.edn. Read-only — configures in-process providers,
   touches no collection."
  []
  (init/init-embedding-provider!))

(defn- live-store
  "The registered :default IMemoryStore, or nil. In a bare test JVM no addon has
   run, so this is nil unless the operator started the tier in a JVM where
   hive-milvus initialized."
  []
  (when (mem-proto/store-set?)
    (mem-proto/get-store)))

(defn- backend-absent-fault []
  {:fault     :recall/backend-absent
   :diagnosis (str "no :default IMemoryStore is registered. The live recall tier "
                   "cannot verify a backend that is not there — and MUST NOT pass "
                   "as though it had. Start this tier in a JVM where the memory "
                   "addon has initialized (hive-milvus registers the store in "
                   "load-extensions!, Phase 4.5).")})

;; =============================================================================
;; CASE 5 (LIVE) — the dimension invariant, on the real configured providers
;; =============================================================================

(deftest ^:integration live-dimension-invariant
  (testing "for every configured memory collection, the width the EMBEDDER emits
            must equal the width the COLLECTION holds. This needs no Milvus
            connection — only the live config — and it is the single check that
            would have caught the encoder/index drift at boot."
    (live-embeddings!)
    (let [dim-of    (requiring-resolve 'hive-mcp.embeddings.service/dimension-for-collection)
          colls     (keys (embed-svc/list-configured-collections))
          readings  (for [c colls]
                      {:collection c
                       :expected   (dim-of c)
                       :actual     (try (embed-svc/get-dimension-for c)
                                        (catch Throwable _ nil))})
          memory    (filter :expected readings)     ;; name-derivable width only
          mismatched (remove #(= (:expected %) (:actual %)) memory)]
      (is (seq colls)
          "no collections are configured — the embedding service did not come up")
      (is (empty? mismatched)
          (str "DIMENSION INVARIANT VIOLATED. A query embedded at one width and "
               "searched against an index of another returns confident neighbours "
               "from a space the query was never in: " (vec mismatched))))))

;; =============================================================================
;; CASE 1 (LIVE) — the anchor, from the real store
;; =============================================================================

(deftest ^:integration live-lexical-anchor
  (testing "the canary's OWN anchor must come back from the live store.

            This tier used to assert a hand-picked production id. That id was
            deleted out from under it and the assertion became unfalsifiable —
            a canary whose anchor someone else owns is not a canary. It now
            reads the fixture the canary itself writes and re-finds by tag."
    (live-embeddings!)
    (if-let [store (live-store)]
      (let [ids (canary-live/ensure-fixtures! store)]
        (is (:anchor ids)
            "the canary could not find OR create its anchor fixture — the store
             refused a write, so recall cannot be measured against it")
        (when (:anchor ids)
          (let [resp (search/handle-search-semantic
                      {:query canary/anchor-query :limit 10 :scope "global"})
                body (when-not (:isError resp)
                       (json/read-str (:text resp) :key-fn keyword))]
            (is (not (:isError resp))
                (str "live memory search errored: " (:text resp)))
            (is (nil? (canary/recall-fault {:label        "case-1/LIVE"
                                            :populated?   true
                                            :results      (:results body)
                                            :must-contain [(:anchor ids)]}))))))
      (is (nil? (backend-absent-fault))))))

(deftest ^:integration live-results-are-ordered-nearest-first
  (testing "THE WIRE TEST. `:distance` must ascend. If the Milvus boundary ever
            regresses to passing a COSINE similarity through under the :distance
            key (MEM-P0-EMBED-LANE), the numbers still look plausible and the
            rows still look confident — but they descend. Nothing else in the
            system can see this. This assertion can."
    (live-embeddings!)
    (if-let [_store (live-store)]
      (let [resp (search/handle-search-semantic
                  {:query "memory search ranking" :limit 10 :scope "global"})
            body (when-not (:isError resp)
                   (json/read-str (:text resp) :key-fn keyword))]
        (is (seq (:results body))
            "the live store returned nothing at all for a broad query — it is
             either empty (it is not) or the retrieval lane is down")
        (is (nil? (g/rank-fault {:label "case-1/LIVE-ordering"
                                 :results (:results body)}))))
      (is (nil? (backend-absent-fault))))))

(deftest ^:integration live-store-is-actually-populated
  (testing "the premise every other assertion rests on. If the store is empty,
            an empty result IS honest, and this whole suite is measuring nothing
            — so state the premise as an assertion rather than assuming it."
    (if-let [store (live-store)]
      (let [entries (mem-proto/query-entries store {:type "note" :limit 5})]
        (is (seq entries)
            "the live store holds no notes — the recall canary has no corpus to
             recall from, and every 'pass' below it would be vacuous"))
      (is (nil? (backend-absent-fault))))))
