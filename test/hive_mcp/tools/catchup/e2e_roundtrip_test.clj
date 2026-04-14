(ns hive-mcp.tools.catchup.e2e-roundtrip-test
  "E2E roundtrip test proving the wrap->catchup flywheel works.

   Core flywheel:
     Work -> Wrap (crystallize) -> Memory (persist) -> Catchup (recall) -> Context restored

   Strategy:
   - An atom acts as the shared in-memory store (the 'Chroma')
   - Phase 1 (Wrap): harvest + crystallize with deterministic mocks.
     Verifies a session-summary entry lands in the atom.
   - Phase 2 (Catchup): With the same atom, call handle-native-catchup.
     Verifies the catchup response includes the session summary.
   - All external deps (git, Emacs, DataScript, events, extensions, piggyback,
     context-store, scope, pool) are mocked out."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            ;; Wrap path
            [hive-mcp.crystal.hooks :as hooks]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.crystal.synthesis :as synthesis]
            ;; Catchup path
            [hive-mcp.tools.catchup :as catchup]
            [hive-mcp.tools.catchup.scope :as catchup-scope]
            [hive-mcp.tools.catchup.git :as catchup-git]
            [hive-mcp.tools.catchup.format :as fmt]
            ;; Infrastructure mocked
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.channel.memory-piggyback :as memory-piggyback]
            [hive-mcp.channel.context-store :as context-store]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.concurrency.pool :as pool]
            [hive-mcp.knowledge-graph.scope :as kg-scope]
            [hive-mcp.knowledge-graph.edges :as kg-edges]))

;; =============================================================================
;; Deterministic Constants
;; =============================================================================

(def ^:private fixed-now
  "Deterministic 'now' for reproducibility."
  (java.time.Instant/parse "2026-02-20T14:00:00Z"))

(def ^:private fixed-session-start
  (java.time.Instant/parse "2026-02-20T12:00:00Z"))

(def ^:private test-session "e2e-roundtrip-session")
(def ^:private test-project "e2e-test-project")
(def ^:private test-directory "/tmp/e2e-roundtrip-test")
(def ^:private test-agent "e2e-agent")

;; =============================================================================
;; In-Memory Store (shared atom acts as Chroma)
;; =============================================================================

(defn- make-memory-store
  "Create a fresh atom-backed memory store. Returns [atom, reified-store].

   The atom holds a vector of entry maps. Each entry has at least:
     :id :type :content :tags :duration :project-id

   The reified store implements IMemoryStore for catchup's scope queries,
   and the facade functions are redirected to read/write from this atom."
  []
  (let [entries (atom [])
        id-counter (atom 0)
        store (reify mem-proto/IMemoryStore
                (connect! [_ _] nil)
                (disconnect! [_] nil)
                (connected? [_] true)
                (health-check [_] {:healthy? true})

                (add-entry! [_ entry]
                  (let [id (str "e2e-entry-" (swap! id-counter inc))]
                    (swap! entries conj (assoc entry :id id
                                                     :created (.toString fixed-now)))
                    id))

                (get-entry [_ id]
                  (first (filter #(= id (:id %)) @entries)))

                (update-entry! [_ id updates]
                  (swap! entries
                         (fn [es]
                           (mapv (fn [e]
                                   (if (= id (:id e))
                                     (merge e updates)
                                     e))
                                 es)))
                  nil)

                (delete-entry! [_ id]
                  (swap! entries (fn [es] (filterv #(not= id (:id %)) es)))
                  nil)

                (query-entries [_ opts]
                  (let [{:keys [type project-id project-ids tags limit]} opts
                        limit (or limit 100)]
                    (->> @entries
                         (filter (fn [e]
                                   (and (or (nil? type) (= type (:type e)))
                                        (or (nil? project-id) (= project-id (:project-id e)))
                                        (or (nil? project-ids) (some #{(:project-id e)} project-ids))
                                        (or (nil? tags) (every? (set (:tags e)) tags)))))
                         (take limit)
                         vec)))

                (search-similar [_ _query-text _opts] [])
                (supports-semantic-search? [_] false)
                (cleanup-expired! [_] 0)
                (entries-expiring-soon [_ _days _opts] [])
                (find-duplicate [_ _type _hash _opts] nil)
                (store-status [_] {:backend "atom" :count (count @entries)})
                (reset-store! [_] (reset! entries [])))]
    [entries store]))

;; =============================================================================
;; Lifecycle Extension Stubs (required by synthesis/run-lifecycle-ops!)
;; =============================================================================

(defn- register-lifecycle-extensions! []
  (ext/register! :ch/a (fn [_] {:promoted 0 :skipped 0 :below 0 :evaluated 0}))
  (ext/register! :ch/b (fn [_] {:decayed 0 :pruned 0 :fresh 0 :evaluated 0}))
  (ext/register! :ch/c (fn [_] {:promoted 0 :candidates 0 :total-scanned 0}))
  (ext/register! :ch/d (fn [_] {:decayed 0 :expired 0 :total-scanned 0}))
  (ext/register! :ch/e (fn [_] {:files-captured 0})))

(defn- deregister-lifecycle-extensions! []
  (ext/deregister! :ch/a)
  (ext/deregister! :ch/b)
  (ext/deregister! :ch/c)
  (ext/deregister! :ch/d)
  (ext/deregister! :ch/e))

;; =============================================================================
;; Shared with-redefs macro (eliminates duplication across tests)
;; =============================================================================

(defmacro ^:private with-e2e-mocks
  "Bind all external dependencies to deterministic fakes backed by the given store.
   Expects `store` to be a reified IMemoryStore (from make-memory-store)."
  [store & body]
  `(with-redefs
     [;; -- Identity / Context --------------------------------------------------
      ctx/current-directory   (constantly test-directory)
      ctx/current-agent-id   (constantly test-agent)
      scope/get-current-project-id (constantly test-project)

      ;; -- Crystal core (deterministic session + timing) -----------------------
      crystal/session-id     (constantly test-session)
      crystal/get-session-start
      (fn [& _#] fixed-session-start)
      crystal/session-timing-metadata
      (fn [start# _end#]
        (if start#
          {:session-start (.toString start#)
           :session-end   (.toString fixed-now)
           :duration-minutes 120}
          {:session-start nil
           :session-end   (.toString fixed-now)
           :duration-minutes 0}))
      crystal/reset-session-start! (fn [& _#] nil)
      crystal/summarize-memory-activity (fn [& _#] nil)

      ;; -- Memory store (atom-backed) ------------------------------------------
      mem-proto/store-set?   (constantly true)
      mem-proto/get-store    (constantly ~store)

      ;; -- VectorDB facade (delegates to atom store) ---------------------------
      facade/index-memory-entry!
      (fn [entry#]
        (mem-proto/add-entry! ~store entry#))
      facade/query-entries
      (fn [& kv-args#]
        (let [args# (apply hash-map kv-args#)]
          (mem-proto/query-entries ~store {:type       (:type args#)
                                           :project-id (:project-id args#)
                                           :project-ids (:project-ids args#)
                                           :tags       (:tags args#)
                                           :limit      (or (:limit args#) 100)})))
      facade/content-hash
      (fn [c#] (str (hash c#)))

      ;; -- Duration ------------------------------------------------------------
      dur/calculate-expires (constantly "2026-04-20T00:00:00Z")

      ;; -- Scope injection (pass-through) --------------------------------------
      scope/inject-project-scope
      (fn [tags# _pid#] tags#)

      ;; -- KG scope (no file system access) ------------------------------------
      kg-scope/read-direct-project-config (constantly nil)
      kg-scope/visible-scopes (fn [_#] [test-project "global"])
      kg-scope/descendant-scopes (fn [_#] [])
      kg-scope/full-hierarchy-scope-tags
      (fn [_#] #{(str "scope:project:" test-project) "scope:global"})

      ;; -- DataScript (empty) --------------------------------------------------
      ds/get-completed-tasks-this-session (fn [& _#] [])
      ds/get-kanban-movements-this-session (fn [& _#] [])

      ;; -- Git (no real subprocess) --------------------------------------------
      clojure.java.shell/sh (fn [& _#] {:exit 0 :out "" :err ""})

      ;; -- Recall buffer -------------------------------------------------------
      recall/get-buffered-recalls (constantly {})
      recall/flush-created-ids!  (fn [& _#] [])

      ;; -- Piggyback / hivemind (no-op) ----------------------------------------
      piggyback/fetch-history     (fn [& _#] [])
      piggyback/adopt-cursor!     (fn [& _#] nil)
      piggyback/evict-stale-cursors! (fn [& _#] nil)
      memory-piggyback/enqueue!   (fn [& _#] nil)
      memory-piggyback/adopt-buffer! (fn [& _#] nil)

      ;; -- Context store (no-op) -----------------------------------------------
      context-store/context-put-batch! (fn [_#] {})

      ;; -- KG edges (empty) ----------------------------------------------------
      kg-edges/get-edges-since (fn [& _#] [])

      ;; -- Pool macros (run inline, no thread pool) ----------------------------
      pool/submit-io!    (fn [f#] (future (f#)))
      pool/submit-solo!  (fn [f#] (future (f#)))

      ;; -- Catchup git (no shell) ----------------------------------------------
      catchup-git/gather-git-info
      (fn [_#] {:branch "main" :uncommitted false :last-commit "abc1234 - test commit"})

      ;; -- Catchup scope project name ------------------------------------------
      catchup-scope/get-current-project-name
      (fn [_#] "e2e-test-project")]

     ~@body))

;; =============================================================================
;; E2E Roundtrip Test
;; =============================================================================

(deftest e2e-wrap-catchup-roundtrip
  (testing "Session summary crystallized by wrap is retrieved by catchup"
    (let [[entries-atom store] (make-memory-store)]
      (register-lifecycle-extensions!)
      (try
        (with-e2e-mocks store

          ;; ==============================================================
          ;; PHASE 1: WRAP (harvest + crystallize)
          ;; ==============================================================

          (testing "Phase 1 -- Wrap crystallizes session summary into memory"
            (let [;; Simulate some session work: pre-seed the atom with progress notes
                  _ (mem-proto/add-entry!
                     store
                     {:type "note"
                      :content "Implemented the flywheel integration"
                      :tags ["progress" (str "scope:project:" test-project)]
                      :duration "ephemeral"
                      :project-id test-project})
                  _ (mem-proto/add-entry!
                     store
                     {:type "note"
                      :content "Fixed race condition in pool shutdown"
                      :tags ["progress" (str "scope:project:" test-project)]
                      :duration "ephemeral"
                      :project-id test-project})

                  ;; Run harvest-all (delegates to collect/harvest-all)
                  harvested (hooks/harvest-all {:directory test-directory
                                                :agent-id test-agent})

                  ;; Verify harvest captured the progress notes
                  _ (is (>= (count (:progress-notes harvested)) 2)
                        "harvest should find the 2 seeded progress notes")
                  _ (is (= test-session (:session harvested))
                        "harvest session should be our deterministic session ID")
                  _ (is (= test-directory (:directory harvested))
                        "harvest directory should match")

                  ;; Run crystallize-session (synthesis/synthesize)
                  crystal-result (hooks/crystallize-session harvested)

                  ;; Verify crystallization produced a summary
                  _ (is (not (:skipped crystal-result))
                        "crystallize should NOT skip (we have progress notes)")
                  _ (is (some? (:summary-id crystal-result))
                        "crystallize should produce a summary-id")
                  _ (is (= test-session (:session crystal-result))
                        "crystallize session should match")

                  summary-id (:summary-id crystal-result)]

              ;; Verify the atom store now contains the session summary entry
              (let [stored-entries @entries-atom
                    summary-entries (filter
                                    (fn [e]
                                      (and (= "note" (:type e))
                                           (some #{"session-summary"} (:tags e))))
                                    stored-entries)]
                (is (= 1 (count summary-entries))
                    "exactly one session-summary entry should be stored")
                (let [summary-entry (first summary-entries)]
                  (is (= summary-id (:id summary-entry))
                      "stored entry ID should match crystallize result")
                  (is (re-find #"Session Summary" (str (:content summary-entry)))
                      "summary content should contain 'Session Summary'")
                  (is (some #{"session-summary"} (:tags summary-entry))
                      "summary should be tagged 'session-summary'")
                  (is (some #{"wrap-generated"} (:tags summary-entry))
                      "summary should be tagged 'wrap-generated'")))))

          ;; ==============================================================
          ;; PHASE 2: CATCHUP (query memory, restore context)
          ;; ==============================================================

          (testing "Phase 2 -- Catchup retrieves the session summary from memory"
            (let [;; The atom now contains the crystallized session summary from Phase 1.
                  ;; Call the catchup handler -- it queries the store via mem-proto.
                  catchup-result (catchup/handle-native-catchup
                                  {:directory test-directory
                                   :_caller_id "coordinator"})

                  ;; Catchup returns a vector of content blocks (4 blocks)
                  _ (is (vector? catchup-result)
                        "catchup should return a vector of content blocks")
                  _ (is (= 4 (count catchup-result))
                        "catchup should return exactly 4 blocks")

                  ;; Parse the blocks
                  header-block (json/read-str (:text (nth catchup-result 0)) :key-fn keyword)
                  context-block (json/read-str (:text (nth catchup-result 1)) :key-fn keyword)]

              ;; Header block assertions
              (is (:success header-block)
                  "header block should indicate success")
              (is (= "e2e-test-project" (:project header-block))
                  "header should show correct project name")

              ;; Session count in header
              (is (pos? (get-in header-block [:counts :sessions]))
                  "header counts should show at least 1 session")

              ;; Context block should contain our session summary
              (let [sessions (get-in context-block [:context :sessions])]
                (is (seq sessions)
                    "context block should contain session entries")
                ;; At least one session entry should reference our summary
                (let [session-previews (map :P sessions)
                      has-our-summary? (some #(re-find #"Session Summary" (str %))
                                             session-previews)]
                  (is has-our-summary?
                      "catchup sessions should include our crystallized session summary"))))))

        ;; Cleanup
        (finally
          (deregister-lifecycle-extensions!))))))

;; =============================================================================
;; Focused: Round-trip content fidelity
;; =============================================================================

(deftest e2e-wrap-content-appears-in-catchup-sessions
  (testing "Specific content from wrap progress notes appears in catchup session previews"
    (let [[entries-atom store] (make-memory-store)]
      (register-lifecycle-extensions!)
      (try
        (with-e2e-mocks store

          ;; Seed distinctive content
          (mem-proto/add-entry!
           store
           {:type "note"
            :content "Refactored the quantum flux capacitor module"
            :tags ["progress" (str "scope:project:" test-project)]
            :duration "ephemeral"
            :project-id test-project})

          ;; Wrap: harvest + crystallize
          (let [harvested (hooks/harvest-all {:directory test-directory
                                              :agent-id test-agent})
                crystal-result (hooks/crystallize-session harvested)
                summary-id (:summary-id crystal-result)]

            (is (some? summary-id) "wrap should produce a summary-id")

            ;; Verify the stored summary mentions our distinctive content
            (let [stored (first (filter #(= summary-id (:id %)) @entries-atom))]
              (is (re-find #"quantum flux capacitor"
                           (str (:content stored)))
                  "stored summary should contain the distinctive progress note content"))

            ;; Catchup: retrieve and verify
            (let [catchup-result (catchup/handle-native-catchup
                                  {:directory test-directory
                                   :_caller_id "coordinator"})
                  context-block (json/read-str (:text (nth catchup-result 1)) :key-fn keyword)
                  sessions (get-in context-block [:context :sessions])
                  all-previews (apply str (map :P sessions))]
              (is (seq sessions)
                  "catchup should return session entries")
              ;; The preview is truncated (80 chars) but should contain the session header
              (is (re-find #"Session Summary" all-previews)
                  "catchup session preview should reference Session Summary from wrap"))))

        (finally
          (deregister-lifecycle-extensions!))))))

;; =============================================================================
;; Edge case: empty session produces no phantom sessions in catchup
;; =============================================================================

(deftest e2e-empty-wrap-no-phantom-sessions
  (testing "Wrap with no activity skips crystallization; catchup returns no sessions"
    (let [[_entries-atom store] (make-memory-store)]
      (register-lifecycle-extensions!)
      (try
        (with-e2e-mocks store

          ;; Override session-start to nil for the empty-session scenario
          (with-redefs [crystal/get-session-start (fn [& _] nil)]

            ;; Wrap with empty session (no progress notes, no tasks, no commits)
            (let [harvested (hooks/harvest-all {:directory test-directory
                                                :agent-id test-agent})
                  crystal-result (hooks/crystallize-session harvested)]

              ;; No content => skipped
              (is (true? (:skipped crystal-result))
                  "empty session should skip crystallization")
              (is (= "no-content" (:reason crystal-result))
                  "skip reason should be 'no-content'")

              ;; Catchup should find no session summaries
              (let [catchup-result (catchup/handle-native-catchup
                                    {:directory test-directory
                                     :_caller_id "coordinator"})
                    context-block (json/read-str (:text (nth catchup-result 1)) :key-fn keyword)
                    sessions (get-in context-block [:context :sessions])]
                (is (empty? sessions)
                    "catchup should return no sessions when wrap produced nothing")))))

        (finally
          (deregister-lifecycle-extensions!))))))
