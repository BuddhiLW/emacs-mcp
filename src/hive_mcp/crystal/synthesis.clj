(ns hive-mcp.crystal.synthesis
  "Session synthesis — transforms harvested data into a crystallized summary.

   Pure formatting functions + effectful boundary for Chroma storage
   and lifecycle operations (promotion, decay, xpoll, memory-decay, provenance).

   Input:  harvested map (from hooks/harvest-all)
   Output: SessionSummary map

   SessionSummary shape (content path):
     {:summary-id   string?       ;; Chroma entry ID
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
     {:skipped true :reason \"no-content\" ...lifecycle-stats...}

   Part of CPPB Promote layer (Wave 2, T2).

   DDD: Domain service — pure synthesis logic with effectful boundary."
  (:require [hive-mcp.crystal.core :as crystal]
            [hive-mcp.tools.memory.scope :as scope]
            [hive-mcp.tools.memory.duration :as dur]
            [hive-mcp.extensions.registry :as ext]
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

;; =============================================================================
;; Extension Delegation Helpers
;; =============================================================================

(defn- delegate-or-noop
  "Try to delegate to extension fn, fall back to default value."
  [ext-key default-val args]
  (if-let [f (ext/get-extension ext-key)]
    (apply f args)
    (do
      (log/debug "Extension not available, returning default for" ext-key)
      default-val)))

(defn- surface-rescue-error
  "If rescue/guard attached error metadata, surface :error into map for backward compat.
   rescue and guard (hive-dsl.result) attach {:hive-dsl.result/error {:message ...}}."
  [m]
  (if-let [err (:hive-dsl.result/error (meta m))]
    (assoc m :error (:message err))
    m))

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

   Returns map with keys:
     :promotion-stats :decay-stats :xpoll-stats :memory-decay-stats :file-provenance-stats"
  [project-id directory & {:keys [harvested]}]
  (let [scope-arg [{:scope project-id :created-by "crystallize-session"}]
        run (fn [k noop args]
              (pool/with-io (surface-rescue-error
                             (result/rescue noop (delegate-or-noop k noop args)))))
        fa (run :ch/a noop-a scope-arg)
        fb (run :ch/b noop-b scope-arg)
        fc (run :ch/c noop-c [{:directory directory :limit 100}])
        fd (run :ch/d noop-d [{:directory directory :limit 50}])
        fe (run :ch/e noop-e [{:directory directory
                               :project-id project-id
                               :harvested harvested}])
        [ra rb rc rd re] (mapv timed-deref [fa fb fc fd fe]
                               [noop-a noop-b noop-c noop-d noop-e])]
    {:promotion-stats (select-keys ra [:promoted :skipped :below :evaluated :error])
     :decay-stats (select-keys rb [:decayed :pruned :fresh :evaluated :error])
     :xpoll-stats (select-keys rc [:promoted :candidates :total-scanned :error])
     :memory-decay-stats (select-keys rd [:decayed :expired :total-scanned :error])
     :file-provenance-stats (select-keys re [:files-captured :files-skipped :edges-created :error])}))

;; =============================================================================
;; Session Synthesis (main entry point)
;; =============================================================================

(defn synthesize
  "Synthesize harvested session data into long-term memory.

   Takes the harvested map (from hooks/harvest-all) and:
   1. Builds a text summary from progress notes + tasks + commits
   2. Appends temporal metadata block
   3. Stores to Chroma as short-term note
   4. Runs lifecycle operations in parallel (promotion, decay, xpoll, etc.)

   Returns SessionSummary map.

   Two paths:
   - Content path: summary stored to Chroma, lifecycle runs in parallel
   - No-content path: lifecycle runs for maintenance, result marked :skipped"
  [{:keys [progress-notes completed-tasks git-commits directory _recalls] :as harvested}]
  (log/info "Synthesizing session:" (crystal/session-id) (when directory (str "directory:" directory)))

  (let [project-id (or (when directory (scope/get-current-project-id directory)) "global")
        session-timing (or (:session-timing harvested)
                           (crystal/session-timing-metadata nil (java.time.Instant/now)))
        summary (or (crystal/summarize-session-progress
                     (concat progress-notes completed-tasks)
                     git-commits
                     harvested)
                    (crystal/summarize-memory-activity
                     {:created  (count (or (:memory-ids-created harvested) []))
                      :accessed (count (or (:memory-ids-accessed harvested) []))}
                     harvested))]
    (if (nil? summary)
      ;; No content — still run lifecycle ops for maintenance
      (let [lifecycle (run-lifecycle-ops! project-id directory :harvested harvested)]
        (log/info "No content to synthesize for session:" (crystal/session-id))
        (merge {:skipped true
                :reason "no-content"
                :session (crystal/session-id)
                :project-id project-id
                :session-timing session-timing
                :stats (:summary harvested)}
               lifecycle))
      ;; Content exists — start lifecycle IN PARALLEL with Chroma indexing
      ;; (lifecycle ops don't depend on the Chroma entry-id)
      (let [content (build-summary-content summary session-timing harvested)
            tags (build-summary-tags summary project-id)
            expires (dur/calculate-expires "short")
            ;; Start lifecycle ops on solo executor (avoids IO pool nesting —
            ;; run-lifecycle-ops! internally submits 5 ops to IO pool via pool/with-io)
            lifecycle-fut (pool/with-solo (run-lifecycle-ops! project-id directory :harvested harvested))
            t0 (System/currentTimeMillis)
            store-r (result/try-effect* :crystal/store-failed
                                        (facade/index-memory-entry!
                                         {:type "note"
                                          :content content
                                          :tags tags
                                          :duration "short"
                                          :expires (or expires "")
                                          :project-id project-id
                                          :content-hash (facade/content-hash content)}))
            chroma-ms (- (System/currentTimeMillis) t0)
            ;; Collect lifecycle results (likely already done — they ran during embedding)
            lifecycle (deref lifecycle-fut op-timeout
                             {:error "lifecycle-timeout"})]
        (log/info "synthesize: chroma" chroma-ms "ms, lifecycle overlapped")
        (if (result/ok? store-r)
          (let [entry-id (:ok store-r)]
            (log/info "Created session summary in Chroma:" entry-id "project:" project-id)
            (merge {:summary-id entry-id
                    :session (crystal/session-id)
                    :project-id project-id
                    :session-timing session-timing
                    :stats (:summary harvested)}
                   lifecycle))
          {:error (:message store-r)
           :session (crystal/session-id)})))))

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
