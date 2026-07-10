(ns hive-mcp.crystal.golden-test
  "Golden/characterization tests for crystal/hooks.clj — Wave 1, Task 7.

   Pins the current output shape of harvest-all and crystallize-session
   BEFORE the CPPB refactor touches any code. Any refactor that changes
   the output structure will break these tests — that's the point.

   Run with UPDATE_GOLDEN=true to regenerate snapshots after intentional changes.

   Golden files: test/golden/crystal/"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-test.golden :refer [deftest-golden]]
            [hive-mcp.crystal.hooks :as hooks]
            [hive-mcp.crystal.synthesis :as synthesis]
            [hive-mcp.crystal.core :as crystal]
            [hive-mcp.crystal.recall :as recall]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.chroma.core :as chroma]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.agent.context :as ctx]
            [hive-mcp.swarm.datascript :as ds]
            [hive-mcp.channel.piggyback :as piggyback]
            [hive-mcp.concurrency.pool :as pool]))

;; =============================================================================
;; Golden file loader
;; =============================================================================

(defn- load-golden [path]
  (-> (io/resource path)
      slurp
      edn/read-string))

;; =============================================================================
;; Shared mock infrastructure
;; =============================================================================

(def ^:private fixed-now
  "Deterministic 'now' for golden reproducibility."
  (java.time.Instant/parse "2026-01-15T12:00:00Z"))

(def ^:private fixed-session-start
  (java.time.Instant/parse "2026-01-15T10:00:00Z"))

(defmacro ^:private with-harvest-mocks
  "Bind all harvest-all dependencies to deterministic fakes.
   opts keys:
     :progress    — notes returned by Chroma query
     :ds-tasks    — DataScript completed tasks
     :chroma-tasks — Chroma kanban-tagged tasks
     :git-output  — {:exit 0 :out ... :err ...}
     :recalls     — buffered recalls map
     :created-ids — flush-created-ids! return
     :hivemind    — piggyback/fetch-history return
     :ds-kanban   — DataScript completed tasks for kanban
     :session-start — Instant or nil"
  [opts & body]
  `(let [opts# ~opts]
     (with-redefs
       [ctx/current-directory   (fn [] "/tmp/golden-test")
        ctx/current-agent-id    (fn [] "golden-agent")
        scope/get-current-project-id (fn [_#] "golden-project")

        crystal/session-id      (fn [] "test-session-golden")
        crystal/get-session-start
        (fn [& _#] (get opts# :session-start fixed-session-start))
        crystal/session-timing-metadata
        (fn [start# _end#]
          (if start#
            {:session-start (.toString start#)
             :session-end   (.toString fixed-now)
             :duration-minutes (long (/ (.toMinutes (java.time.Duration/between start# fixed-now))
                                        1))}
            {:session-start nil
             :session-end   (.toString fixed-now)
             :duration-minutes 0}))

        ;; Vectordb facade — progress notes / kanban task query
        facade/query-entries
        (fn [& {:as args#}]
          (cond
            (= "note" (:type args#))
            (if (some #{"kanban"} (:tags args#))
              (get opts# :chroma-tasks [])
              (get opts# :progress []))
            :else []))

        ;; DataScript — completed tasks
        ds/get-completed-tasks-this-session
        (fn [& {:as _args#}]
          (get opts# :ds-tasks []))

        ;; Git — shell subprocess
        clojure.java.shell/sh
        (fn [& _args#]
          (get opts# :git-output {:exit 0 :out "" :err ""}))

        ;; Piggyback — hivemind messages
        piggyback/fetch-history
        (fn [& {:as _args#}]
          (get opts# :hivemind []))

        ;; Recall buffer
        recall/get-buffered-recalls
        (fn [] (get opts# :recalls {}))

        recall/flush-created-ids!
        (fn [& _#] (get opts# :created-ids []))]

       ~@body)))

(defmacro ^:private with-synthesis-mocks
  "Bind crystallize-session dependencies for deterministic output.
   Registers lifecycle extension noops. Uses with-crystallize-mocks pattern.
   opts keys:
     :summary     — return from summarize-session-progress (nil = no-content)
     :entry-id    — return from chroma/index-memory-entry!"
  [opts & body]
  `(let [opts# ~opts]
     (ext/register! :ch/a (fn [_#] {:promoted 0 :skipped 0 :below 0 :evaluated 0}))
     (ext/register! :ch/b (fn [_#] {:decayed 0 :pruned 0 :fresh 0 :evaluated 0}))
     (ext/register! :ch/c (fn [_#] {:promoted 0 :candidates 0 :total-scanned 0}))
     (ext/register! :ch/d (fn [_#] {:decayed 0 :expired 0 :total-scanned 0}))
     (ext/register! :ch/e (fn [_#] {:files-captured 0}))
     (try
       (with-redefs
         [crystal/summarize-session-progress
          (fn [& _#] (:summary opts#))

          crystal/summarize-memory-activity
          (fn [& _#] nil)

          crystal/session-id
          (fn [] "test-session-golden")

          scope/get-current-project-id
          (fn [_#] "golden-project")

          scope/inject-project-scope
          (fn [tags# _pid#] tags#)

          dur/calculate-expires
          (fn [_#] "2026-03-15T00:00:00Z")

          ctx/current-directory
          (fn [] "/tmp/golden-test")

          facade/index-memory-entry!
          (fn [_#] (get opts# :entry-id "entry-golden-001"))

          facade/content-hash
          (fn [c#] (str (hash c#)))]

         (let [result# (do ~@body)]
           result#))
       (finally
         (ext/deregister! :ch/a)
         (ext/deregister! :ch/b)
         (ext/deregister! :ch/c)
         (ext/deregister! :ch/d)
         (ext/deregister! :ch/e)))))

;; =============================================================================
;; Golden: harvest-all with no session activity
;; =============================================================================

(deftest golden-harvest-empty-keys
  (testing "harvest-all with empty session returns all expected keys"
    (with-harvest-mocks
      {:progress     []
       :ds-tasks     []
       :chroma-tasks []
       :git-output   {:exit 0 :out "" :err ""}
       :recalls      {}
       :created-ids  []
       :hivemind     []
       :session-start nil}
      (let [result (hooks/harvest-all {:directory "/tmp/golden-test"
                                       :agent-id "golden-agent"})
            golden (load-golden "golden/crystal/crystal-harvest-empty.edn")]
        ;; Key set must match exactly
        (is (= (set (keys golden)) (set (keys result)))
            "harvest-all empty: key set must match golden baseline")
        ;; Structural assertions on value types
        (is (vector? (:progress-notes result)))
        (is (sequential? (:completed-tasks result)))
        (is (vector? (:git-commits result)))
        (is (map? (:recalls result)))
        (is (vector? (:hivemind-messages result)))
        (is (map? (:kanban-activity result)))
        (is (map? (:session-timing result)))
        (is (map? (:session-temporal result)))
        (is (vector? (:memory-ids-created result)))
        (is (vector? (:memory-ids-accessed result)))
        (is (string? (:session result)))
        (is (string? (:directory result)))
        (is (string? (:agent-id result)))
        (is (map? (:summary result)))
        ;; Summary sub-keys must match
        (is (= (set (keys (:summary golden)))
               (set (keys (:summary result))))
            "summary sub-keys must match golden baseline")
        ;; All numeric counts zero for empty harvest (:created-by-type is a
        ;; map breakdown, not a count — excluded from the zero check)
        (is (every? zero? (filter number? (vals (:summary result))))
            "all summary counts should be 0 for empty harvest")
        ;; session-temporal should alias session-timing
        (is (= (:session-timing result) (:session-temporal result))
            ":session-temporal must alias :session-timing")))))

;; =============================================================================
;; Golden: harvest-all with rich session activity
;; =============================================================================

(deftest golden-harvest-rich-keys
  (testing "harvest-all with active session returns correct structure and counts"
    (with-harvest-mocks
      {:progress     [{:content "Implemented feature X" :tags ["progress"] :duration "ephemeral"}
                      {:content "Fixed bug in module Y" :tags ["progress"] :duration "ephemeral"}]
       :ds-tasks     [{:completed-task/id "task-1"
                       :completed-task/title "Deploy service"
                       :completed-task/completed-at "2026-01-15T11:20:00Z"
                       :completed-task/agent-id "golden-agent"}]
       :chroma-tasks []
       :git-output   {:exit 0
                      :out "abc1234 feat: add feature X\ndef5678 fix: resolve bug Y\n"
                      :err ""}
       :recalls      {"mem-001" {:id "mem-001" :content "API design notes" :access-count 3}
                      "mem-002" {:id "mem-002" :content "Architecture doc" :access-count 1}}
       :created-ids  [{:id "created-001"} {:id "created-002"}]
       :hivemind     [{:from "agent-a" :content "Starting deploy" :timestamp "2026-01-15T11:00:00Z"}
                      {:from "agent-b" :content "Tests passing" :timestamp "2026-01-15T11:30:00Z"}
                      {:from "golden-agent" :content "Feature complete" :timestamp "2026-01-15T11:45:00Z"}]}
      (let [result (hooks/harvest-all {:directory "/tmp/golden-test"
                                       :agent-id "golden-agent"})
            golden (load-golden "golden/crystal/crystal-harvest-rich.edn")]
        ;; Key set must match
        (is (= (set (keys golden)) (set (keys result)))
            "harvest-all rich: key set must match golden baseline")
        ;; Counts from summary
        (is (= 2 (get-in result [:summary :progress-count]))
            "progress-count should be 2")
        (is (>= (get-in result [:summary :task-count]) 1)
            "task-count should be >= 1 (datascript tasks)")
        (is (= 2 (get-in result [:summary :commit-count]))
            "commit-count should be 2")
        (is (= 2 (get-in result [:summary :recall-count]))
            "recall-count should be 2")
        (is (= 3 (get-in result [:summary :hivemind-shout-count]))
            "hivemind-shout-count should be 3")
        (is (= 2 (get-in result [:summary :created-count]))
            "created-count should be 2")
        (is (= 2 (get-in result [:summary :accessed-count]))
            "accessed-count should be 2")
        ;; Memory IDs
        (is (= 2 (count (:memory-ids-created result)))
            "Should have 2 created memory IDs")
        (is (= #{"mem-001" "mem-002"} (set (:memory-ids-accessed result)))
            "Accessed IDs should match recall buffer keys")
        ;; Hivemind messages
        (is (= 3 (count (:hivemind-messages result)))
            "Should have 3 hivemind messages")
        ;; Git commits
        (is (= 2 (count (:git-commits result)))
            "Should have 2 git commits")
        ;; Session timing populated
        (is (some? (get-in result [:session-timing :session-start]))
            "session-start should be populated")
        (is (some? (get-in result [:session-timing :duration-minutes]))
            "duration-minutes should be populated")))))

;; =============================================================================
;; Golden: crystallize-session output shape — no-content path
;; =============================================================================

(deftest-golden crystal-synthesis-no-content-shape
  "test/golden/crystal/crystal-synthesis-no-content.edn"
  (with-synthesis-mocks
    {:summary nil}
    (let [harvested {:progress-notes []
                     :completed-tasks []
                     :git-commits []
                     :directory "/tmp/golden-test"
                     :recalls {}
                     :memory-ids-created []
                     :memory-ids-accessed []
                     :session-timing {:session-start "2026-01-15T10:00:00Z"
                                      :session-end "2026-01-15T12:00:00Z"
                                      :duration-minutes 120}
                     :summary {:progress-count 0
                               :task-count 0
                               :commit-count 0
                               :recall-count 0
                               :hivemind-shout-count 0
                               :kanban-completed 0
                               :created-count 0
                               :accessed-count 0}}]
      (hooks/crystallize-session harvested))))

;; =============================================================================
;; Golden: crystallize-session output shape — content path
;; =============================================================================

(deftest-golden crystal-synthesis-content-shape
  "test/golden/crystal/crystal-synthesis-content.edn"
  (with-synthesis-mocks
    {:summary {:content "## Session Summary\n\nImplemented feature X and fixed bug Y."
               :tags ["wrap"]}
     :entry-id "entry-golden-001"}
    (let [harvested {:progress-notes [{:content "Implemented feature X"}]
                     :completed-tasks []
                     :git-commits ["abc1234 feat: add feature X"]
                     :directory "/tmp/golden-test"
                     :recalls {}
                     :memory-ids-created [{:id "created-001"}]
                     :memory-ids-accessed ["mem-001"]
                     :session-timing {:session-start "2026-01-15T10:00:00Z"
                                      :session-end "2026-01-15T12:00:00Z"
                                      :duration-minutes 120}
                     :summary {:progress-count 1
                               :task-count 0
                               :commit-count 1
                               :recall-count 0
                               :hivemind-shout-count 0
                               :kanban-completed 0
                               :created-count 1
                               :accessed-count 1}}]
      (hooks/crystallize-session harvested))))

;; =============================================================================
;; Golden: synthesis shape structural contract (keys + types)
;; =============================================================================

(deftest golden-synthesis-shape-contract
  (testing "Both crystallize-session paths return expected key sets from golden file"
    (let [golden (load-golden "golden/crystal/crystal-synthesis-shape.edn")]
      ;; No-content path — always-store contract (no :skipped branch)
      (with-synthesis-mocks
        {:summary nil}
        (let [result (hooks/crystallize-session
                      {:progress-notes []
                       :completed-tasks []
                       :git-commits []
                       :directory "/tmp/golden-test"
                       :recalls {}
                       :memory-ids-created []
                       :memory-ids-accessed []
                       :session-timing {:session-start "2026-01-15T10:00:00Z"
                                        :session-end "2026-01-15T12:00:00Z"
                                        :duration-minutes 120}
                       :summary {:progress-count 0 :task-count 0 :commit-count 0
                                 :recall-count 0}})]
          (is (= (:keys (:no-content-path golden))
                 (set (keys result)))
              "No-content path key set must match golden baseline")
          (is (string? (:summary-id result))
              "No-content path always stores a minimal-wrap breadcrumb")
          (is (not (contains? result :skipped))
              "always-store contract: no :skipped key")
          (is (not (contains? result :reason))
              "always-store contract: no :reason key")
          ;; Lifecycle stats sub-keys
          (is (= #{:promoted :skipped :below :evaluated}
                 (set (keys (:promotion-stats result))))
              "promotion-stats keys")
          (is (= #{:decayed :pruned :fresh :evaluated}
                 (set (keys (:decay-stats result))))
              "decay-stats keys")
          (is (= #{:promoted :candidates :total-scanned}
                 (set (keys (:xpoll-stats result))))
              "xpoll-stats keys")
          (is (= #{:decayed :expired :total-scanned}
                 (set (keys (:memory-decay-stats result))))
              "memory-decay-stats keys")
          (is (contains? result :file-provenance-stats)
              "file-provenance-stats present")))

      ;; Content path
      (with-synthesis-mocks
        {:summary {:content "Summary content" :tags ["wrap"]}
         :entry-id "entry-golden-001"}
        (let [result (hooks/crystallize-session
                      {:progress-notes [{:content "work"}]
                       :completed-tasks []
                       :git-commits []
                       :directory "/tmp/golden-test"
                       :recalls {}
                       :memory-ids-created []
                       :memory-ids-accessed []
                       :session-timing {:session-start "2026-01-15T10:00:00Z"
                                        :session-end "2026-01-15T12:00:00Z"
                                        :duration-minutes 120}
                       :summary {:progress-count 1 :task-count 0 :commit-count 0
                                 :recall-count 0}})]
          (is (= (:keys (:content-path golden))
                 (set (keys result)))
              "Content path key set must match golden baseline")
          (is (= "entry-golden-001" (:summary-id result)))
          (is (not (contains? result :skipped)))
          ;; Same lifecycle stats sub-keys
          (is (= #{:promoted :skipped :below :evaluated}
                 (set (keys (:promotion-stats result)))))
          (is (= #{:decayed :pruned :fresh :evaluated}
                 (set (keys (:decay-stats result)))))
          (is (= #{:promoted :candidates :total-scanned}
                 (set (keys (:xpoll-stats result)))))
          (is (= #{:decayed :expired :total-scanned}
                 (set (keys (:memory-decay-stats result))))))))))

;; =============================================================================
;; Golden: format-temporal-block output (pure function, exact match)
;; =============================================================================

(deftest-golden crystal-temporal-block-full
  "test/golden/crystal/crystal-temporal-block-full.edn"
  (synthesis/format-temporal-block
   {:session-start "2026-01-15T10:00:00Z"
    :session-end "2026-01-15T12:00:00Z"
    :duration-minutes 120}
   {:memory-ids-created ["id-1" "id-2" "id-3"]
    :memory-ids-accessed ["id-4" "id-5"]}))

(deftest-golden crystal-temporal-block-no-ids
  "test/golden/crystal/crystal-temporal-block-no-ids.edn"
  (synthesis/format-temporal-block
   {:session-start "2026-01-15T10:00:00Z"
    :session-end "2026-01-15T12:00:00Z"
    :duration-minutes 120}
   {}))

(deftest-golden crystal-temporal-block-nil-start
  "test/golden/crystal/crystal-temporal-block-nil-start.edn"
  (synthesis/format-temporal-block
   {:session-start nil
    :session-end "2026-01-15T12:00:00Z"
    :duration-minutes 0}
   {:memory-ids-created []
    :memory-ids-accessed []}))
