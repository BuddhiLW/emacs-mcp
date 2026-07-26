(ns hive-mcp.crystal.synthesis
  "Session synthesis — transforms harvested data into a crystallized summary.

   Pure formatting functions + effectful boundary for memory store storage
   and lifecycle operations (promotion, decay, xpoll, memory-decay, provenance).

   Input:  harvested map (from hooks/harvest-all)
   Output: SessionSummary map

   SessionSummary shape (content path):
     {:summary-id   string?       ;; memory store entry ID
      :session      string?       ;; session identifier
      :project-id   string?       ;; project scope
      :session-timing map?        ;; {:session-start :session-end :duration-minutes}
      :stats         map?         ;; harvest summary counts
      :promotion-stats map?       ;; from lifecycle :ch/a
      :decay-stats     map?       ;; from lifecycle :ch/b
      :xpoll-stats     map?       ;; from lifecycle :ch/c
      :memory-decay-stats map?    ;; from lifecycle :ch/d
      :file-provenance-stats map?};; from lifecycle :ch/e

   SessionSummary shape (no-content path):
     same keys as the content path — a minimal wrap summary is synthesized
     and persisted, tagged \"wrap-minimal\". Nothing is skipped.

   Part of CPPB Promote layer (Wave 2, T2).

   DDD: Domain service — pure synthesis logic with effectful boundary."
  (:require [hive-mcp.crystal.core :as crystal]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.extensions.registry :as ext]
            [hive-mcp.extensions.delegate :refer [delegate-or-noop]]
            [hive-mcp.vectordb.facade :as facade]
            [hive-mcp.dns.result :as result]
            [hive-mcp.concurrency.pool :as pool]
            [clojure.string :as str]
            [taoensso.timbre :as log]))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Pure Formatting
;; =============================================================================

(defn format-temporal-block
  "Format session timing and memory-ID counts as a markdown block.
   Appended to session summary content for temporal traceability.
   Nil-safe: handles missing memory-ids gracefully."
  [{:keys [session-start session-end duration-minutes]}
   {:keys [memory-ids-created memory-ids-accessed]}]
  (let [lines (cond-> ["\n\n### Temporal Metadata"
                       (str "- Session start: " (or session-start "unknown"))
                       (str "- Session end: " (or session-end "unknown"))
                       (str "- Duration: " (or duration-minutes 0) " minutes")]
                (seq memory-ids-created)
                (conj (str "- Memory entries created: " (count memory-ids-created)))
                (seq memory-ids-accessed)
                (conj (str "- Memory entries accessed: " (count memory-ids-accessed))))]
    (str/join "\n" lines)))

(defn build-summary-content
  "Build the session summary content string from summary + temporal metadata.
   Pure function — no IO."
  [summary session-timing harvested]
  (let [temporal-block (format-temporal-block session-timing harvested)]
    (str (:content summary) temporal-block)))

(defn build-summary-tags
  "Build tags vector with project scope injection.
   Pure function — no IO."
  [summary project-id]
  (let [base-tags (into (or (:tags summary) []) ["auto-kg" "session-wrap" "temporal"])]
    (scope/inject-project-scope base-tags project-id)))

(defn minimal-wrap-summary
  "Build a minimal session-wrap summary when no progress/activity was harvested.
   Guarantees every wrap leaves a breadcrumb in the memory store so catchup can
   reconstruct session boundaries even for quiet sessions (read-only browsing,
   pure Q&A, etc.).
   Pure function — no IO."
  [session-id harvested]
  (let [stats (or (:summary harvested) {})]
    {:type :note
     :content (str "## Session Summary: " session-id "\n\n"
                   "### Activity\n"
                   "- Progress notes: " (or (:progress-count stats) 0) "\n"
                   "- Completed tasks: " (or (:task-count stats) 0) "\n"
                   "- Commits: " (or (:commit-count stats) 0) "\n"
                   "- Memories created: " (or (:created-count stats) 0) "\n"
                   "- Memories accessed: " (or (:accessed-count stats) 0) "\n"
                   "\n(Quiet session — no synthesized progress; breadcrumb persisted for catchup.)")
     :tags ["session-summary" "wrap-generated" "wrap-minimal"]
     :duration :short}))

;; =============================================================================
;; Extension Delegation Helpers
;; =============================================================================

(defn- surface-rescue-error
  "If rescue/guard attached error metadata, surface :error into map for backward compat.
   rescue and guard (hive-dsl.result) attach {:hive-dsl.result/error {:message ...}}.

   Also surfaces :latency-ms and :cause when present in the metadata (set by
   timed-rescue below) per HTTP-error convention 20260420172519-6dd91cc7 —
   downstream observability needs both to distinguish slow/transient failures
   from fatal ones."
  [m]
  (if-let [err (:hive-dsl.result/error (meta m))]
    (cond-> (assoc m :error (:message err))
      (:latency-ms err) (assoc :latency-ms (:latency-ms err))
      (:cause err)      (assoc :cause      (:cause err)))
    m))

(defn- root-cause-message
  "Walk Throwable getCause chain, return last cause's message (or class name
   when message is null). Stops on nil or self-cycle."
  [^Throwable t]
  (loop [cur t prev nil]
    (if (or (nil? cur) (identical? cur prev))
      (when prev (or (.getMessage ^Throwable prev) (.getName (class prev))))
      (recur (.getCause cur) cur))))

(defn- timed-rescue*
  "Run thunk; on Throwable, return `noop` with `:hive-dsl.result/error`
   metadata carrying {:message :latency-ms :cause :label} so
   `surface-rescue-error` can project it onto the result map. Logs at
   WARN with `label` so HTTP/transport errors during wrap lifecycle are
   observable rather than silently noop'd.

   Pure-stratum logic does NOT catch HTTP — this boundary helper does
   (Stratification axiom 20260415135102-1d300fdc). Convention
   20260420172519-6dd91cc7: HTTP error ex-info carries :latency-ms +
   :cause so observability can distinguish upstream slowness from
   network/DNS flakes."
  [label noop thunk]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [r (thunk)]
        (if (instance? clojure.lang.IObj r) r noop))
      (catch Throwable t
        (let [latency (- (System/currentTimeMillis) t0)
              msg     (or (.getMessage t) (.getName (class t)))
              cause   (root-cause-message t)]
          (log/warn t (str "lifecycle " label " failed after " latency
                           "ms: " msg
                           (when (and cause (not= cause msg))
                             (str " (cause=" cause ")"))))
          (with-meta noop
            {:hive-dsl.result/error {:message    msg
                                     :label      label
                                     :latency-ms latency
                                     :cause      cause}}))))))

;; =============================================================================
;; Lifecycle Noop Defaults
;; =============================================================================

(def ^:private noop-a {:promoted 0 :skipped 0 :below 0 :evaluated 0})
(def ^:private noop-b {:decayed 0 :pruned 0 :fresh 0 :evaluated 0})
(def ^:private noop-c {:promoted 0 :candidates 0 :total-scanned 0})
(def ^:private noop-d {:decayed 0 :expired 0 :total-scanned 0})
(def ^:private noop-e {:files-captured 0})

(def ^:private ^:const op-timeout 15000)

(defn- timed-deref [fut default]
  (let [r (result/guard Exception default
                        (deref fut op-timeout ::timeout))]
    (if (= r ::timeout)
      (do (future-cancel fut) (assoc default :error "timed-out"))
      (surface-rescue-error r))))

;; =============================================================================
;; Lifecycle Operations (effectful — runs at boundary)
;; =============================================================================

(defn run-lifecycle-ops!
  "Run post-crystallization lifecycle operations in parallel with timeout guard.
   Optional harvested map is passed through to :ch/e.

   Each branch is wrapped in `timed-rescue*`: on HTTP/transport failure
   it logs once at WARN with a labelled message and surfaces
   {:error :latency-ms :cause} into the per-branch result. This stops
   raw `HTTP Error` exceptions from `:ch/c` (xpoll) and `:ch/d`
   (memory-decay) from leaking out as bare ex-info — the wrap boundary
   translates them, the pure decay/xpoll logic stays effect-free.

   Returns map with keys:
     :promotion-stats :decay-stats :xpoll-stats :memory-decay-stats :file-provenance-stats"
  [project-id directory & {:keys [harvested]}]
  (let [scope-arg [{:scope project-id :created-by "crystallize-session"}]
        run (fn [k label noop args]
              (pool/with-io
                (surface-rescue-error
                 (timed-rescue* label noop
                                #(delegate-or-noop k noop args)))))
        fa (run :ch/a "promotions"   noop-a scope-arg)
        fb (run :ch/b "decay"        noop-b scope-arg)
        fc (run :ch/c "xpoll"        noop-c [{:directory directory :limit 100}])
        fd (run :ch/d "memory-decay" noop-d [{:directory directory :limit 50}])
        fe (run :ch/e "file-provenance" noop-e [{:directory directory
                                                 :project-id project-id
                                                 :harvested harvested}])
        [ra rb rc rd re] (mapv timed-deref [fa fb fc fd fe]
                               [noop-a noop-b noop-c noop-d noop-e])
        ;; Per convention 20260420172519-6dd91cc7: surface :latency-ms and
        ;; :cause alongside :error so observability can distinguish slow
        ;; upstream from network/DNS flakes. Keys are present only on
        ;; failure paths (select-keys drops missing keys), preserving the
        ;; success-path key set asserted by golden tests.
        err-keys [:error :latency-ms :cause]]
    {:promotion-stats       (select-keys ra (into [:promoted :skipped :below :evaluated] err-keys))
     :decay-stats           (select-keys rb (into [:decayed :pruned :fresh :evaluated] err-keys))
     :xpoll-stats           (select-keys rc (into [:promoted :candidates :total-scanned] err-keys))
     :memory-decay-stats    (select-keys rd (into [:decayed :expired :total-scanned] err-keys))
     :file-provenance-stats (select-keys re (into [:files-captured :files-skipped :edges-created] err-keys))}))

;; =============================================================================
;; Scope grouping (pure — no IO)
;; =============================================================================

(defn- entry-project-id
  "Extract the project-id from an entry's scope:project:* tag, or nil."
  [entry]
  (some (fn [tag]
          (when (and (string? tag) (str/starts-with? tag "scope:project:"))
            (subs tag (count "scope:project:"))))
        (:tags entry)))

(defn extract-project-scopes
  "Return the set of distinct project-ids appearing in harvested entries'
   scope:project:* tags. Untagged entries do not contribute.

   Pure — no IO. Used to decide whether synthesis needs per-project
   summaries plus a global umbrella (multi-project session) or a single
   per-project summary (single-project session)."
  [harvested]
  (let [collect (fn [coll]
                  (->> (or coll [])
                       (filter map?)
                       (keep entry-project-id)))
        all (concat (collect (:progress-notes harvested))
                    (collect (:completed-tasks harvested))
                    (collect (:memory-ids-created harvested))
                    (collect (:memory-ids-accessed harvested)))]
    (set all)))

(defn group-harvested-by-scope
  "Partition harvested data into per-project sub-harvests keyed by project-id.
   Entries without a scope:project:* tag land in the default `fallback-pid`
   bucket. Git commits and session-timing are attached to every sub-harvest
   (they are session-global, not per-project).

   Pure — no IO."
  [harvested fallback-pid]
  (let [bucketize (fn [coll]
                    (->> (or coll [])
                         (filter map?)
                         (group-by #(or (entry-project-id %) fallback-pid))))
        notes    (bucketize (:progress-notes harvested))
        tasks    (bucketize (:completed-tasks harvested))
        created  (bucketize (:memory-ids-created harvested))
        accessed (bucketize (:memory-ids-accessed harvested))
        pids     (set (concat (keys notes) (keys tasks)
                              (keys created) (keys accessed)))]
    (into {}
          (for [pid pids]
            [pid (assoc harvested
                        :progress-notes      (get notes pid [])
                        :completed-tasks     (get tasks pid [])
                        :memory-ids-created  (get created pid [])
                        :memory-ids-accessed (get accessed pid []))]))))

;; =============================================================================
;; Session Synthesis (main entry point)
;; =============================================================================

(defn- store-one-summary!
  "Effectful: persist a single scoped summary to the memory store and run
   lifecycle ops for that scope. Returns a SessionSummary-shaped map with
   :summary-id (or :error) merged with lifecycle stats."
  [harvested project-id directory scope-tag umbrella?]
  (let [session-timing (or (:session-timing harvested)
                           (crystal/session-timing-metadata nil (java.time.Instant/now)))
        content-summary (or (crystal/summarize-session-progress
                             (concat (:progress-notes harvested)
                                     (:completed-tasks harvested))
                             (:git-commits harvested)
                             harvested)
                            (crystal/summarize-memory-activity
                             {:created  (count (or (:memory-ids-created harvested) []))
                              :accessed (count (or (:memory-ids-accessed harvested) []))}
                             harvested))
        minimal? (nil? content-summary)
        summary (or content-summary
                    (minimal-wrap-summary (crystal/session-id) harvested))
        content (build-summary-content summary session-timing harvested)
        base-tags (into (or (:tags summary) []) ["auto-kg" "session-wrap" "temporal"])
        ;; Force scope tag explicitly (do not rely on cwd-derived project-id).
        tags (cond-> (conj (vec base-tags) scope-tag)
               minimal? (conj "wrap-minimal")
               umbrella? (conj "umbrella"))
        expires (dur/calculate-expires "short")
        lifecycle-fut (pool/with-solo
                        (run-lifecycle-ops! project-id directory :harvested harvested))
        store-r (result/try-effect* :crystal/store-failed
                                    (facade/index-memory-entry!
                                     {:type "note"
                                      :content content
                                      :tags tags
                                      :duration "short"
                                      :expires (or expires "")
                                      :project-id project-id
                                      :content-hash (facade/content-hash content)}))
        lifecycle (deref lifecycle-fut op-timeout
                         {:lifecycle-error "timeout"})]
    (when (result/ok? store-r)
      ;; Re-anchor any placeholder claim-sets persisted by the wrap-time
      ;; claim-extract pipeline (which only knew the session-id) to this
      ;; canonical wrap memory id. Fire-and-forget — addons attach a
      ;; rewrite hook under :crystal/claim-rewrite via the extension
      ;; registry; misses are surfaced at catchup-time.
      (try
        (when-let [rewrite-fn (ext/get-extension :crystal/claim-rewrite)]
          (future
            (try (rewrite-fn (crystal/session-id) (:ok store-r) project-id)
                 (catch Throwable t
                   (log/warn "claim-set rewrite hook failed:" (ex-message t))))))
        (catch Throwable t
          (log/warn "claim-set rewrite hook launch failed:" (ex-message t)))))
    (if (result/ok? store-r)
      (merge {:summary-id (:ok store-r)
              :session (crystal/session-id)
              :project-id project-id
              :scope-tag scope-tag
              :umbrella? (boolean umbrella?)
              :session-timing session-timing
              :stats (:summary harvested)}
             lifecycle)
      {:error (:message store-r)
       :project-id project-id
       :scope-tag scope-tag
       :session (crystal/session-id)})))

(defn synthesize
  "Synthesize harvested session data into long-term memory.

   Scoping rules (axiom 20260211011330 — HCR is top-down):
   - If harvested entries reference multiple `scope:project:*` scopes,
     emit one per-project summary (tagged `scope:project:<pid>`) plus
     a global umbrella summary (tagged `scope:global` and `umbrella`)
     so parent scopes can see the aggregate view.
   - If harvested entries reference at most one project scope, emit a
     single summary tagged for that scope (single-project sessions keep
     their prior behavior — no umbrella noise).

   Returns the umbrella summary (multi-project) or the single summary
   (single-project), with a :sub-summaries vector of all emitted summaries
   (including the umbrella itself). Back-compat: callers that read only
   :summary-id / :project-id / :stats / lifecycle stats continue to work."
  [{:keys [directory] :as harvested}]
  (log/info "Synthesizing session:" (crystal/session-id)
            (when directory (str "directory:" directory)))
  (let [cwd-project-id (or (when directory (scope/get-current-project-id directory))
                           "global")
        project-scopes (extract-project-scopes harvested)
        multi? (> (count project-scopes) 1)]
    (if multi?
      ;; Per-project summaries + global umbrella.
      (let [groups (group-harvested-by-scope harvested cwd-project-id)
            sub-summaries (vec
                           (for [[pid sub-harvested] groups]
                             (store-one-summary! sub-harvested
                                                 pid
                                                 directory
                                                 (str "scope:project:" pid)
                                                 false)))
            umbrella (store-one-summary! harvested
                                         "global"
                                         directory
                                         "scope:global"
                                         true)]
        (log/info "synthesize: emitted" (inc (count sub-summaries))
                  "summaries (per-project + umbrella) for projects:" project-scopes)
        (assoc umbrella :sub-summaries (conj sub-summaries umbrella)))
      ;; Single-project (or no project) path — back-compat.
      (let [effective-pid (or (first project-scopes) cwd-project-id)
            scope-tag (if (= effective-pid "global")
                        "scope:global"
                        (str "scope:project:" effective-pid))
            r (store-one-summary! harvested effective-pid directory scope-tag false)]
        (when (:summary-id r)
          (log/info "Created session summary in memory store:"
                    (:summary-id r) "project:" effective-pid))
        ;; Strip internal helper keys to preserve back-compat key set.
        (dissoc r :scope-tag :umbrella?)))))

(comment
  ;; Example: synthesize with minimal harvested data
  (synthesize {:progress-notes [{:content "Implemented feature X" :tags ["progress"]}]
               :completed-tasks []
               :git-commits ["abc1234 feat: add feature X"]
               :directory "/tmp/project"
               :recalls {}
               :memory-ids-created []
               :memory-ids-accessed []
               :session-timing {:session-start "2026-01-15T10:00:00Z"
                                :session-end "2026-01-15T12:00:00Z"
                                :duration-minutes 120}
               :summary {:progress-count 1 :task-count 0
                         :commit-count 1 :recall-count 0}}))
