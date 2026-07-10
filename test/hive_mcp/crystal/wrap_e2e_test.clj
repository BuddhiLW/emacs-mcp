(ns hive-mcp.crystal.wrap-e2e-test
  "Step 12 verification gates (kanban `20260504173212-6fecb0fb`,
   wave-10 wrap of plan `20260504173159-46dc47f1`).

   Two end-to-end gates not covered by the unit tests:

   ### Gate 2 — Multi-scope wrap E2E
   A fixture session touches three real project-ids (hive-mcp + funeraria
   + sisf-crm) plus a cross-cutting umbrella datum. Drives the full
   pipeline `harvest-all-by-scope -> synthesize-wraps -> persist-wraps!`
   (synthesiser + IMemoryStore mocked) and asserts the boundary
   contract:
     - 3 child wraps + 1 umbrella  (= 4 total persisted entries)
     - each child carries the right `scope:project:<pid>` tag
     - the umbrella carries `scope:multi-project`
     - each persist call honours the explicit per-wrap `:project-id`
     - per-scope content stays partitioned (no cross-pid leakage)

   ### Gate 3 — Bleed regression
   Feeds the persisted wraps through the catchup scope-filter pipeline
   at funeraria scope and asserts NO `scope:project:hive*` wrap surfaces
   in `recent-wraps`. This is the original bleed report from the wrap
   plan: wraps from sibling projects leaking into a sibling's catchup.

   Both gates run against mocks; no Chroma/Milvus/DataScript/git IO."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-mcp.crystal.core :as core]
            [hive-mcp.crystal.fanout :as fan]
            [hive-mcp.crystal.harvest.by-scope :as bs]
            [hive-mcp.crystal.harvest.collect :as coll]
            [hive-mcp.crystal.persist :as persist]
            [hive-mcp.protocols.memory :as mem-proto]
            [hive-mcp.tools.catchup.scope-filter :as sf]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Mock synthesiser — emits a deterministic stub that surfaces the inputs so we
;; can later assert the per-scope partitioning.
;; =============================================================================

(defn- stub-synth-fn
  "Return a synth fn that records each call into `calls-atom` and emits a
   per-scope stub entry tagged with `wrap-generated` + `session-summary`.
   Real shape mirrors `summarize-session-progress`'s `[notes git-commits
   harvested]` arity so the boundary contract holds."
  [calls-atom]
  (fn [notes git-commits harvested]
    (let [pid (:project-id harvested)]
      (swap! calls-atom conj
             {:project-id   pid
              :scope        (:scope harvested)
              :note-count   (count notes)
              :commit-count (count git-commits)
              :kg-edges     (get-in harvested [:kg-edges-created :edges] [])})
      {:type     :note
       :content  (str "Wrap synth for " pid)
       :tags     ["session-summary" "wrap-generated"]
       :duration :short})))

;; =============================================================================
;; Mock IMemoryStore — captures every add-entry! payload so we can assert the
;; explicit-pid + tag contract without a real backend.
;; =============================================================================

(defn- with-stub-store
  [calls-atom f]
  (let [stub-store (reify Object)]
    (with-redefs [mem-proto/store-set? (fn [] true)
                  mem-proto/get-store  (fn ([] stub-store) ([_k] stub-store))
                  mem-proto/add-entry! (fn [_store entry]
                                         (swap! calls-atom conj entry)
                                         (str "mem-" (count @calls-atom)))]
      (f))))

;; =============================================================================
;; Fixture — three real scopes + a cross-cutting umbrella datum
;; =============================================================================

(defn- multi-scope-legacy-result
  "Mirrors `harvest-all`'s legacy single-scope shape but seeds datums whose
   per-row attribution will route to three distinct project-ids and one
   umbrella bucket. The `:directory` is the harvest's caller scope; per-datum
   attribution overrides it where present."
  []
  {:progress-notes      [{:project-id "hive-mcp"  :n "fix null"}
                         {:tags ["scope:project:funeraria"] :n "wip"}
                         {:project-id "sisf-crm"  :n "import"}]
   :completed-tasks     [{:completed-task/project-id "hive-mcp" :title "T1"}
                         {:completed-task/project-id "funeraria" :title "T2"}]
   :git-commits         ["abc1 fix: hive-mcp" "def2 feat: funeraria"]
   :recalls             {"id-a" {:project-id "hive-mcp"}
                         "id-b" {:project-id "sisf-crm"}}
   :hivemind-messages   [{:project-id "hive-mcp" :m "ping"}]
   :kanban-activity     {:tasks-completed []}
   :kg-edges-created    {:edges [{:kg-edge/scope "hive-mcp"}
                                 {:kg-edge/scope "funeraria"}
                                 ;; Cross-pid edge — no scope → umbrella
                                 {:kg-edge/scope nil}]}
   :kanban-movements    {:movements []}
   :memory-ids-created  []
   :memory-ids-accessed []
   :session             "20260506-e2e"
   :directory           "/home/leibniz/PP/hive/hive-mcp"
   :agent-id            "coordinator"
   :session-timing      {:session-start "x" :session-end "y" :duration-minutes 1}
   :summary             {:progress-count 3 :task-count 2 :commit-count 2
                         :recall-count 2 :hivemind-shout-count 1
                         :kanban-completed 0 :kg-edge-count 3 :kanban-movement-count 0
                         :created-count 0 :accessed-count 0}})

(defn- run-pipeline!
  "Drive `harvest-all-by-scope -> synthesize-wraps -> persist-wraps!` end-to-end
   with the synthesiser and store stubbed. Returns
   `{:synth-calls [...] :persist-calls [...] :result <persist-summary>}`."
  []
  (let [synth-calls   (atom [])
        persist-calls (atom [])
        result        (with-redefs [coll/harvest-all              (fn [_] (multi-scope-legacy-result))
                                    core/summarize-session-progress (stub-synth-fn synth-calls)]
                        (with-stub-store persist-calls
                          (fn []
                            (let [hbs   (coll/harvest-all-by-scope
                                         {:directory "/home/leibniz/PP/hive/hive-mcp"})
                                  wraps (fan/synthesize-wraps hbs)]
                              (persist/persist-wraps! wraps)))))]
    {:synth-calls   @synth-calls
     :persist-calls @persist-calls
     :result        result}))

;; =============================================================================
;; Gate 2 — multi-scope wrap E2E
;; =============================================================================

(deftest gate-2--three-children-plus-umbrella
  (testing "harvest -> synthesize -> persist emits 3 child wraps + 1 umbrella"
    (let [{:keys [synth-calls persist-calls result]} (run-pipeline!)]
      (is (= 4 (count persist-calls))
          "3 real-pid wraps + 1 umbrella = 4 persisted entries")
      (is (= 4 (:total result))    "result.total reflects 4 wraps")
      (is (= 4 (:persisted result)) "all 4 wraps persisted successfully")
      (is (= 0 (:failed result))    "no persist failures")
      (testing "every real pid present + umbrella aliased to multi-project"
        (let [pids (set (map :project-id persist-calls))]
          (is (= #{"hive-mcp" "funeraria" "sisf-crm" "multi-project"} pids)
              "exact 3-child + 1-umbrella project-id set")))
      (testing "synthesiser saw each scope as an isolated input"
        (is (= 4 (count synth-calls)) "synth fn called once per scope")))))

(deftest gate-2--each-wrap-carries-correct-scope-tag
  (testing "every persisted wrap has the right scope:project:* / scope:multi-project tag"
    (let [{:keys [persist-calls]} (run-pipeline!)
          tag-of (fn [pid] (->> persist-calls
                                (filter #(= pid (:project-id %)))
                                first :tags first))]
      (is (= "scope:project:hive-mcp"  (tag-of "hive-mcp")))
      (is (= "scope:project:funeraria" (tag-of "funeraria")))
      (is (= "scope:project:sisf-crm"  (tag-of "sisf-crm")))
      (is (= "scope:multi-project"     (tag-of "multi-project"))))))

(deftest gate-2--content-is-partitioned-no-cross-pid-leakage
  (testing "each child wrap was synthesised from only its own slice"
    (let [{:keys [synth-calls]} (run-pipeline!)
          by-pid (group-by :project-id synth-calls)]
      (testing "hive-mcp child sees only hive-mcp datums"
        (let [c (first (get by-pid "hive-mcp"))]
          (is (some? c))
          (is (every? #(= "hive-mcp" (:kg-edge/scope %)) (:kg-edges c))
              "kg-edges in hive-mcp slice are all scope=hive-mcp")))
      (testing "funeraria child sees only funeraria datums"
        (let [c (first (get by-pid "funeraria"))]
          (is (some? c))
          (is (every? #(= "funeraria" (:kg-edge/scope %)) (:kg-edges c))
              "kg-edges in funeraria slice are all scope=funeraria")))
      (testing "sisf-crm child sees no kg-edges (none routed there)"
        (let [c (first (get by-pid "sisf-crm"))]
          (is (some? c))
          (is (= 0 (count (:kg-edges c))))))
      (testing "umbrella sees the cross-pid edge (nil scope)"
        (let [c (first (get by-pid "multi-project"))]
          (is (some? c))
          (is (= :umbrella (:scope c))
              "umbrella synth gets :scope :umbrella marker"))))))

;; =============================================================================
;; Gate 3 — bleed regression
;;
;; Runs the persisted wraps through the same scope-filter pipeline catchup uses.
;; Asserts NO scope:project:hive* wrap surfaces when caller is at funeraria.
;; =============================================================================

(defn- as-catchup-entry
  "Coerce a persisted add-entry! payload into the catchup-bundle entry shape
   (the bundle stores `:tags` + `:project-id` and queries by tag/type)."
  [entry idx]
  {:id         (str "wrap-" idx)
   :type       "note"
   :tags       (vec (:tags entry))
   :project-id (:project-id entry)
   :created    (str "2026-05-06T12:00:0" idx "Z")})

(deftest gate-3--funeraria-catchup-shows-no-hive-wraps
  (testing "scope-filter-entries at funeraria scope drops scope:project:hive* wraps"
    (let [{:keys [persist-calls]} (run-pipeline!)
          recent-wraps (map-indexed #(as-catchup-entry %2 %1) persist-calls)
          ;; Funeraria-scope catchup view: scope:project:funeraria + scope:global
          ;; (the umbrella's scope:multi-project is intentionally cross-cutting)
          funeraria-scope-tags #{"scope:project:funeraria" "scope:global"}
          visible-ids #{"funeraria"}
          filtered (sf/scope-filter-entries recent-wraps
                                            funeraria-scope-tags
                                            visible-ids)
          surfacing-tags (set (mapcat :tags filtered))]
      (is (not (contains? surfacing-tags "scope:project:hive-mcp"))
          "hive-mcp wrap MUST NOT surface in funeraria catchup")
      (is (not (contains? surfacing-tags "scope:project:hive"))
          "any scope:project:hive* wrap MUST NOT surface in funeraria catchup")
      (is (not (contains? surfacing-tags "scope:project:sisf-crm"))
          "sibling sisf-crm wrap MUST NOT surface either")
      (is (contains? surfacing-tags "scope:project:funeraria")
          "the funeraria wrap itself MUST surface")
      (is (= 1 (count filtered))
          "only the funeraria wrap surfaces — no bleed"))))

(deftest gate-3--strict-filter-also-rejects-hive-bleed
  (testing "the strict-mode tripwire (step-10) honours the same boundary"
    (let [{:keys [persist-calls]} (run-pipeline!)
          recent-wraps (map-indexed #(as-catchup-entry %2 %1) persist-calls)
          funeraria-scope-tags #{"scope:project:funeraria" "scope:global"}
          strict   (sf/scope-filter-entries-strict recent-wraps funeraria-scope-tags)
          tags-out (set (mapcat :tags strict))]
      (is (= 1 (count strict)))
      (is (contains? tags-out "scope:project:funeraria"))
      (is (not (contains? tags-out "scope:project:hive-mcp")))
      (is (not (contains? tags-out "scope:multi-project"))
          "umbrella's scope:multi-project tag does NOT match funeraria scope"))))
